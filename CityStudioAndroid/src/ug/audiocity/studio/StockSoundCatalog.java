package ug.audiocity.studio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Groups the bundled Soundza afro / percussion one-shots and loops into browsable sections. Host-testable. */
public final class StockSoundCatalog {
    private StockSoundCatalog() {}

    public static final String[] SECTIONS = {
        "Kicks", "Snares & rims", "Claps & snaps", "Hi-hats", "Shakers & tambourine",
        "Djembe & African drums", "Congas, bongos & udu", "Toms", "Wood & clicks", "Loops", "FX & other"
    };

    public static String section(String file) {
        String n = file.toLowerCase(Locale.US);
        if (n.contains("loop") || n.contains("fill")) return "Loops";
        if (n.contains("kick") || n.contains("bass-drum")) return "Kicks";
        if (n.contains("clap") || n.contains("snap")) return "Claps & snaps";
        if (n.contains("hi-hat")) return "Hi-hats";
        if (n.contains("snare") || n.contains("rimshot") || n.contains("side-stick")) return "Snares & rims";
        if (n.contains("shaker") || n.contains("maraca") || n.contains("cabasa") || n.contains("shekere") || n.contains("tambourine")) return "Shakers & tambourine";
        if (n.contains("djembe") || n.contains("dundun") || n.contains("kenkeni") || n.contains("sangban") || n.contains("african")) return "Djembe & African drums";
        if (n.contains("conga") || n.contains("bongo") || n.contains("udu") || n.contains("cajon")) return "Congas, bongos & udu";
        if (n.contains("tom")) return "Toms";
        if (n.contains("wood") || n.contains("block") || n.contains("click") || n.contains("clave") || n.contains("stick")) return "Wood & clicks";
        return "FX & other";
    }

    /** "042-soundza-crisp-block-click.wav" becomes "Crisp block click". */
    public static String title(String file) {
        String n = file.replaceFirst("^\\d+-soundza-", "").replaceFirst("\\.wav$", "").replace('-', ' ');
        return n.isEmpty() ? file : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    public static Map<String, List<String>> group(String[] files) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String s : SECTIONS) out.put(s, new ArrayList<String>());
        for (String f : files) if (f.toLowerCase(Locale.US).endsWith(".wav")) out.get(section(f)).add(f);
        for (List<String> list : out.values()) java.util.Collections.sort(list);
        out.values().removeIf(List::isEmpty);
        return out;
    }
}
