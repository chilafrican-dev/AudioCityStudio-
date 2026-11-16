package ug.audiocity.studio;

import android.app.AlertDialog;
import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** Browse, preview and add the bundled afro / percussion stock sounds to Arrange. */
final class StockSoundsUi {
    static final String DIR = "samples/builtin/soundza";
    private final MainActivity a;
    StockSoundsUi(MainActivity a) { this.a = a; }

    void open() {
        final Map<String, List<String>> groups;
        try { groups = StockSoundCatalog.group(a.getAssets().list(DIR)); }
        catch (Exception e) { a.error("Stock sounds", "Could not read the built-in samples."); return; }
        final String[] names = groups.keySet().toArray(new String[0]);
        String[] rows = new String[names.length];
        for (int i = 0; i < names.length; i++) rows[i] = names[i] + "  (" + groups.get(names[i]).size() + ")";
        new AlertDialog.Builder(a).setTitle("Afro stock sounds").setItems(rows, (d, i) -> section(names[i], groups.get(names[i]), groups))
            .setNegativeButton("Close", null).show();
    }

    private void section(final String name, final List<String> files, final Map<String, List<String>> groups) {
        String[] titles = new String[files.size()];
        for (int i = 0; i < titles.length; i++) titles[i] = StockSoundCatalog.title(files.get(i));
        final int[] chosen = {-1};
        final AlertDialog dialog = new AlertDialog.Builder(a).setTitle(name + " · tap to hear")
            .setSingleChoiceItems(titles, -1, (d, i) -> { chosen[0] = i; preview(files.get(i)); ((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); })
            .setPositiveButton("Add to Arrange", (d, w) -> { if (chosen[0] >= 0) add(files.get(chosen[0])); })
            .setNeutralButton("‹ Sections", (d, w) -> open())
            .setNegativeButton("Close", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false));
        dialog.show();
    }

    private void preview(final String file) {
        new Thread(() -> {
            try (InputStream in = a.getAssets().open(DIR + "/" + file)) {
                final float[] wave = SampleAudio.readWav(in);
                a.runOnUiThread(() -> a.preview(wave));
            } catch (Exception e) { a.runOnUiThread(() -> a.toast("Could not play this sound.")); }
        }).start();
    }

    private void add(final String file) {
        if (!a.canEdit()) return;
        final Core.Session project = a.session;
        final long at = Math.max(0, a.engine.position);
        a.checkpoint();
        a.busy("Adding sound…", () -> {
            float[] wave;
            try (InputStream in = a.getAssets().open(DIR + "/" + file)) { wave = SampleAudio.readWav(in); }
            final File dir = new File(project.directory);
            final Core.Clip clip = AudioIO.store(dir, wave, StockSoundCatalog.title(file), at);
            a.runOnUiThread(() -> {
                if (a.session != project) { new File(dir, clip.file).delete(); a.toast("Project changed; sound not added."); return; }
                Core.Track track = new Core.Track("Afro · " + StockSoundCatalog.title(file), 0xffd98a3a);
                track.clips.add(clip); project.tracks.add(track); a.selected = track; a.clip = clip;
                a.save(false); a.refresh(); a.toast(StockSoundCatalog.title(file) + " added at the playhead.");
            });
        });
    }
}
