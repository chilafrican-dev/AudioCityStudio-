package ug.audiocity.studio;
import java.io.File;
import java.util.List;
import java.util.Map;

public class StockSoundCatalogTests {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    public static void main(String[] args) {
        String[] files = new File("assets/samples/builtin/soundza").list();
        check(files != null && files.length > 100, "stock samples present");
        Map<String, List<String>> g = StockSoundCatalog.group(files);
        int total = 0; for (List<String> l : g.values()) total += l.size();
        int wavs = 0; for (String f : files) if (f.endsWith(".wav")) wavs++;
        check(total == wavs, "every stock sample is in a section");
        check(g.get("Djembe & African drums").size() >= 8, "djembe section filled");
        check(g.get("Kicks").size() >= 10, "kicks section filled");
        check(StockSoundCatalog.title("042-soundza-crisp-block-click.wav").equals("Crisp block click"), "title");
        System.out.println(total + " stock sounds in " + g.size() + " sections; stock catalog checks passed");
    }
}
