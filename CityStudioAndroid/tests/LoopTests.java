package ug.audiocity.studio;

import org.json.*;
import java.io.*;
import java.nio.file.*;

public class LoopTests {
    static int passed = 0;
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); passed++; }
    public static void main(String[] args) throws Exception {
        check(ProLoops.bpm("Afrobeat drum loop 102 BPM", "", "") == 102, "tempo from the name");
        check(ProLoops.bpm("groove", "loop bpm120 drums", "") == 120, "tempo from tags");
        check(ProLoops.bpm("groove", "", "Made at BPM: 95 in my DAW") == 95, "tempo from description");
        check(ProLoops.bpm("Loop 2024 take 3", "808", "") == 0, "years and numbers are not tempos");
        check(ProLoops.bars(9.6, 100) == 4 && ProLoops.bars(4.8, 100) == 2 && ProLoops.bars(7.0, 100) == 0, "bar count from duration");
        check(ProLoops.fixTempo(2.4, 50) == 100 && ProLoops.fixTempo(4.8, 50) == 50, "half-time tempo tags are corrected");
        check(ProLoops.cc0("http://creativecommons.org/publicdomain/zero/1.0/") && ProLoops.cc0("https://creativecommons.org/publicdomain/zero/1.0/") && !ProLoops.cc0("http://creativecommons.org/licenses/by/4.0/") && !ProLoops.cc0("http://creativecommons.org/licenses/by-nc/4.0/"), "only CC0 passes");
        String u = ProLoops.url("", "Amapiano", "Drums", 2, " KEY123 ");
        check(u.startsWith(ProLoops.API + "?query=Amapiano%20Drums") && u.contains("license%3A%22Creative%20Commons%200%22") && u.contains("tag%3Aloop") && u.endsWith("token=KEY123") && u.contains("page=2"), "search URL asks for CC0 loops only");
        String body = "{\"count\":3,\"next\":\"https://freesound.org/apiv2/search/text/?page=2\",\"results\":["
            + "{\"id\":11,\"name\":\"afro_groove_100bpm.wav\",\"username\":\"maker\",\"license\":\"http://creativecommons.org/publicdomain/zero/1.0/\",\"url\":\"https://freesound.org/people/maker/sounds/11/\",\"previews\":{\"preview-hq-mp3\":\"https://cdn.freesound.org/previews/0/11_1-hq.mp3\"},\"duration\":9.6,\"tags\":[\"loop\",\"afrobeat\"],\"description\":\"\"},"
            + "{\"id\":12,\"name\":\"attribution loop\",\"username\":\"x\",\"license\":\"http://creativecommons.org/licenses/by/4.0/\",\"url\":\"u\",\"previews\":{\"preview-hq-mp3\":\"https://cdn.freesound.org/p.mp3\"},\"duration\":4,\"tags\":[],\"description\":\"\"},"
            + "{\"id\":13,\"name\":\"pad\",\"username\":\"y\",\"license\":\"http://creativecommons.org/publicdomain/zero/1.0/\",\"url\":\"https://freesound.org/s/13/\",\"previews\":{\"preview-hq-mp3\":\"https://cdn.freesound.org/previews/0/13-hq.mp3\"},\"duration\":12.3,\"tags\":[\"loop\"],\"description\":\"\"}]}";
        ProLoops.Page p = ProLoops.parse(body);
        check(p.loops.size() == 2 && p.rejected == 1 && p.more && p.total == 3, "non-CC0 results are dropped");
        ProLoops.Loop a = p.loops.get(0); check(a.name.equals("afro groove 100bpm") && a.bpm == 100 && a.bars == 4 && a.timed(), "first loop parsed with tempo and bars");
        check(!p.loops.get(1).timed() && p.loops.get(1).info().startsWith("Tempo unknown"), "a loop without a stated tempo is marked");
        check(a.credit().contains("maker") && a.credit().contains("CC0") && a.credit().contains("freesound.org/people/maker/sounds/11"), "credit names creator, licence and page");
        check(ProLoops.problem(401, "{}").contains("API key") && ProLoops.problem(500, "{\"detail\":\"boom\"}").contains("boom"), "errors in plain words");
        File dir = Files.createTempDirectory("loops").toFile();
        try (Writer w = new FileWriter(new File(dir, "11.json"))) { w.write(a.json().toString()); } Files.write(new File(dir, "11.mp3").toPath(), new byte[]{'I', 'D', '3', 0});
        try (Writer w = new FileWriter(new File(dir, "99.json"))) { w.write(a.json().put("id", 99).toString()); } // metadata without audio
        java.util.List<ProLoops.Loop> lib = ProLoops.library(dir); check(lib.size() == 1 && lib.get(0).bpm == 100 && lib.get(0).bars == 4, "downloaded library lists complete loops only");
        check(ProLoops.mp3(new byte[]{'I', 'D', '3'}, 3) && ProLoops.mp3(new byte[]{(byte) 0xff, (byte) 0xfb, 0}, 3) && !ProLoops.mp3("<html".getBytes(), 5), "downloads must really be MP3");
        int bar = Core.RATE * 240 / 120; float[] raw = new float[(bar * 2 + 1105) * 2]; for (int i = 1105; i < raw.length / 2; i++) raw[2 * i] = raw[2 * i + 1] = (i - 1105) % 4800 == 0 ? .9f : .05f;
        float[] cut = ProLoops.trimToBars(raw, 2, 120); check(cut.length == bar * 2 * 2 && cut[0] == .9f, "decoder padding is removed and the loop lands on the beat");
        float[] tight = new float[bar * 2]; tight[0] = tight[1] = .5f; float[] same = ProLoops.trimToBars(tight, 1, 120); check(same.length == tight.length && same[0] == .5f, "an exact loop is left alone");
        float[] shortLoop = ProLoops.trimToBars(new float[bar], 1, 120); check(shortLoop.length == bar * 2, "a slightly short loop is padded to the bar");
        System.out.println("LoopTests passed " + passed + " checks");
    }
}
