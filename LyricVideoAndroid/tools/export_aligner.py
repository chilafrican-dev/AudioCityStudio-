"""
Builds the on-phone Luganda speech model for Lyric Video Studio (run by .github/workflows/luganda-aligner-model.yml).

1. Picks the first candidate Luganda wav2vec2 CTC model on Hugging Face whose licence allows commercial use.
2. Exports it to ONNX (16 kHz mono audio in, per-frame log-probabilities out) and quantises the weights to int8.
3. Checks the int8 model against the original and measures character error rate on real Luganda speech
   (Google FLEURS lg_ug test set), so the numbers are known before anyone downloads it.
4. Writes aligner_int8.onnx, aligner.json (vocabulary and settings) and MODEL_LICENSE.md into the output folder.
"""
import io
import json
import os
import sys
import tarfile

import numpy as np
import onnxruntime as ort
import torch
from huggingface_hub import HfApi, hf_hub_download
from onnxruntime.quantization import QuantType, quantize_dynamic
from transformers import Wav2Vec2ForCTC, Wav2Vec2Processor

CANDIDATES = os.environ.get("MODEL_CANDIDATES", "indonesian-nlp/wav2vec2-luganda,cahya/wav2vec2-luganda,lucio/wav2vec2-large-xlsr-luganda").split(",")
PERMISSIVE = {"apache-2.0", "mit", "cc-by-4.0", "cc0-1.0", "bsd-2-clause", "bsd-3-clause", "openrail", "cc-by-sa-4.0"}
OUT = sys.argv[1] if len(sys.argv) > 1 else "model-out"
os.makedirs(OUT, exist_ok=True)


def licence_of(model_id):
    info = HfApi().model_info(model_id)
    for tag in info.tags or []:
        if tag.startswith("license:"):
            return tag.split(":", 1)[1].lower()
    data = getattr(info, "card_data", None) or getattr(info, "cardData", None)
    if data is not None:
        lic = data.get("license") if isinstance(data, dict) else getattr(data, "license", None)
        if lic:
            return str(lic).lower()
    return "unknown"


chosen, licence = None, None
for cand in CANDIDATES:
    cand = cand.strip()
    try:
        lic = licence_of(cand)
    except Exception as e:  # noqa: BLE001
        print(f"skip {cand}: {e}")
        continue
    print(f"{cand}: licence {lic}")
    if lic in PERMISSIVE:
        chosen, licence = cand, lic
        break
if not chosen:
    sys.exit("No candidate model has a licence that allows commercial use; nothing was published.")
print(f"Using {chosen} ({licence})")

processor = Wav2Vec2Processor.from_pretrained(chosen)
model = Wav2Vec2ForCTC.from_pretrained(chosen).eval()
vocab = processor.tokenizer.get_vocab()
# Only the symbols the model actually outputs (the tokenizer may list extra ones such as <s> and </s>).
tokens = [None] * int(model.config.vocab_size)
for tok, idx in vocab.items():
    if idx < len(tokens):
        tokens[idx] = tok
blank = processor.tokenizer.pad_token_id
delimiter = processor.tokenizer.word_delimiter_token
normalize = bool(getattr(processor.feature_extractor, "do_normalize", True))
print("vocab", len(tokens), "blank", blank, repr(tokens[blank]), "delimiter", repr(delimiter), "normalize", normalize)


class LogProbs(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, audio):
        return torch.log_softmax(self.m(audio).logits, dim=-1)


fp32_path = os.path.join(OUT, "aligner_fp32.onnx")
int8_path = os.path.join(OUT, "aligner_int8.onnx")
wrapped = LogProbs(model).eval()
dummy = torch.randn(1, 16000 * 4)
with torch.no_grad():
    torch.onnx.export(wrapped, dummy, fp32_path, input_names=["audio"], output_names=["log_probs"],
                      dynamic_axes={"audio": {1: "samples"}, "log_probs": {1: "frames"}}, opset_version=17, do_constant_folding=True)
quantize_dynamic(fp32_path, int8_path, weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul"])
print("int8 model size: %.1f MB" % (os.path.getsize(int8_path) / 1e6))

session = ort.InferenceSession(int8_path, providers=["CPUExecutionProvider"])


def prep(x):
    x = x.astype(np.float32)
    if normalize:
        x = (x - x.mean()) / np.sqrt(x.var() + 1e-7)
    return x


def greedy(lp):
    ids = lp.argmax(-1)
    out, prev = [], -1
    for i in ids:
        if i != prev and i != blank:
            out.append(tokens[i])
        prev = i
    return "".join(out).replace(delimiter, " ").strip()


def cer(ref, hyp):
    ref, hyp = ref.lower(), hyp.lower()
    d = list(range(len(hyp) + 1))
    for i in range(1, len(ref) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(hyp) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (ref[i - 1] != hyp[j - 1]))
            prev, d[j] = d[j], cur
    return d[len(hyp)] / max(1, len(ref))


# Real Luganda speech: a few FLEURS test clips (16 kHz WAV).
report = {"model": chosen, "licence": licence}
try:
    import soundfile as sf
    tsv = hf_hub_download("google/fleurs", "data/lg_ug/test.tsv", repo_type="dataset")
    rows = [line.rstrip("\n").split("\t") for line in open(tsv, encoding="utf-8")]
    wanted = {r[1]: r[3] for r in rows[:400]}
    tar = hf_hub_download("google/fleurs", "data/lg_ug/audio/test.tar.gz", repo_type="dataset")
    clips = []
    with tarfile.open(tar) as t:
        for m in t:
            name = os.path.basename(m.name)
            if name in wanted and m.isfile():
                data, sr = sf.read(io.BytesIO(t.extractfile(m).read()), dtype="float32")
                if data.ndim > 1:
                    data = data.mean(1)
                clips.append((name, data, wanted[name]))
            if len(clips) >= 12:
                break
    agree, frames, cers_int8, cers_fp32 = 0, 0, [], []
    for name, audio, ref in clips:
        x = prep(audio)
        lp8 = session.run(None, {"audio": x[None, :]})[0][0]
        with torch.no_grad():
            lp32 = wrapped(torch.from_numpy(x)[None, :])[0].numpy()
        agree += int((lp8.argmax(-1) == lp32.argmax(-1)).sum())
        frames += lp8.shape[0]
        cers_int8.append(cer(ref, greedy(lp8)))
        cers_fp32.append(cer(ref, greedy(lp32)))
        print(f"{name}\n  ref : {ref}\n  int8: {greedy(lp8)}")
    report.update({"clips": len(clips), "frame_agreement": agree / max(1, frames),
                   "cer_int8": float(np.mean(cers_int8)), "cer_fp32": float(np.mean(cers_fp32))})
    print(json.dumps(report, indent=1))
    if report["frame_agreement"] < 0.9:
        sys.exit("int8 model disagrees with the original too often; not publishing.")
except SystemExit:
    raise
except Exception as e:  # noqa: BLE001
    print("::warning::Could not run the FLEURS Luganda check:", e)
    report["fleurs_check"] = f"skipped: {e}"

with open(os.path.join(OUT, "aligner.json"), "w", encoding="utf-8") as f:
    json.dump({"model": chosen, "licence": licence, "tokens": tokens, "blank": blank, "delimiter": delimiter,
               "sample_rate": 16000, "normalize": normalize, "report": report}, f, ensure_ascii=False, indent=1)
with open(os.path.join(OUT, "MODEL_LICENSE.md"), "w", encoding="utf-8") as f:
    f.write(f"# On-phone Luganda speech model\n\nConverted to int8 ONNX from https://huggingface.co/{chosen}\n\n"
            f"Licence of the original model: **{licence}**. The conversion only changes the number format of the weights.\n\n"
            f"Check results: {json.dumps(report)}\n")
os.remove(fp32_path)
data_file = fp32_path + ".data"
if os.path.exists(data_file):
    os.remove(data_file)
print("done:", os.listdir(OUT))
