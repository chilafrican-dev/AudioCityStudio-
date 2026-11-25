package ug.audiocity.lyrics;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CancellationException;

/**
 * The on-phone Luganda speech model (wav2vec2 CTC, int8 ONNX) run with ONNX Runtime. Turns 16 kHz audio into
 * per-frame character log-probabilities (one frame every 20 ms), in overlapping chunks so long songs fit in memory.
 */
final class SpeechModel implements AutoCloseable {
    static final int FRAME = 320;                 // samples per output frame at 16 kHz = 20 ms
    static final int FRAME_MS = 20;
    static final int CORE = 15 * Audio16k.RATE;   // 15 s per chunk…
    static final int CONTEXT = 24000;             // …with 1.5 s of context on each side (multiples of FRAME)

    Ctc.Vocab vocab;
    final boolean normalize;
    final String name, licence;
    private final OrtEnvironment env;
    private final OrtSession session;
    private final String input;

    SpeechModel(File dir) throws IOException {
        try {
            JSONObject j = new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(dir, ModelStore.CONFIG).toPath()), StandardCharsets.UTF_8));
            JSONArray t = j.getJSONArray("tokens");
            String[] tokens = new String[t.length()];
            for (int i = 0; i < tokens.length; i++) tokens[i] = t.isNull(i) ? null : t.getString(i);
            vocab = new Ctc.Vocab(tokens, j.getInt("blank"), j.optString("delimiter", "|"));
            normalize = j.optBoolean("normalize", true);
            name = j.optString("model", "Luganda speech model");
            licence = j.optString("licence", "");
        } catch (org.json.JSONException e) { throw new IOException("The speech model's settings are damaged: " + e.getMessage()); }
        try {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions o = new OrtSession.SessionOptions();
            o.setIntraOpNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = env.createSession(new File(dir, ModelStore.MODEL).getAbsolutePath(), o);
            input = session.getInputNames().iterator().next();
            // The tokenizer can list more symbols than the model outputs (e.g. <s>, </s>): use the model's size.
            long[] shape = ((ai.onnxruntime.TensorInfo) session.getOutputInfo().values().iterator().next().getInfo()).getShape();
            int outputs = shape.length == 3 ? (int) shape[2] : -1;
            if (outputs > 0 && outputs != vocab.size()) {
                if (outputs < vocab.size() && vocab.blank < outputs) vocab = new Ctc.Vocab(java.util.Arrays.copyOf(vocab.tokens, outputs), vocab.blank, vocab.delimiter >= 0 ? vocab.tokens[vocab.delimiter] : null);
                else throw new IOException("The speech model does not match its settings file.");
            }
        } catch (OrtException e) { throw new IOException("Cannot start the speech model: " + e.getMessage()); }
    }

    /** Log-probabilities lp[t * V + k] for every 20 ms frame of the audio; frames returned in framesOut[0]. */
    float[] listen(float[] audio, int[] framesOut, VideoExporter.Listener progress) throws IOException {
        int V = vocab.size(), total = audio.length / FRAME;
        float[] lp = new float[Math.max(1, total) * V];
        for (int core = 0; core < audio.length; core += CORE) {
            if (progress.cancelled()) throw new CancellationException();
            int from = Math.max(0, core - CONTEXT), to = Math.min(audio.length, core + CORE + CONTEXT);
            if (to - from < 800) break;
            float[] x = java.util.Arrays.copyOfRange(audio, from, to);
            if (normalize) {
                double mean = 0, var = 0;
                for (float v : x) mean += v;
                mean /= x.length;
                for (float v : x) var += (v - mean) * (v - mean);
                float scale = (float) (1 / Math.sqrt(var / x.length + 1e-7));
                for (int i = 0; i < x.length; i++) x[i] = (float) ((x[i] - mean) * scale);
            }
            float[][] out;
            try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), new long[]{1, x.length});
                 OrtSession.Result r = session.run(Collections.singletonMap(input, tensor))) {
                out = ((float[][][]) r.get(0).getValue())[0];
            } catch (OrtException e) { throw new IOException("The speech model failed: " + e.getMessage()); }
            if (out.length == 0 || out[0].length != V) throw new IOException("The speech model returned an unexpected shape.");
            int first = core / FRAME, last = Math.min(total, (core + CORE) / FRAME);
            for (int g = first; g < last; g++) {
                int local = Math.min(out.length - 1, g - from / FRAME);
                System.arraycopy(out[local], 0, lp, g * V, V);
            }
            progress.progress(Math.min(1f, (core + CORE) / (float) audio.length), "Listening… " + Math.min(100, Math.round(100f * (core + CORE) / audio.length)) + " %");
        }
        framesOut[0] = total;
        return lp;
    }

    @Override public void close() {
        try { session.close(); } catch (OrtException ignored) {}
    }
}
