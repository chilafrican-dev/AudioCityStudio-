package ug.audiocity.studio;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * "Auto parts" for beginners: pick a part (melody, bass, rhythm guitar, solo guitar) and a mood, press one button,
 * hear five options that already fit the chords in the project, and keep the one you like as an editable MIDI clip.
 */
final class AutoPartsUi {
    static final int SHEET = AiGenerateUi.SHEET, CHIP = AiGenerateUi.CHIP, EDGE = AiGenerateUi.EDGE, TXT = AiGenerateUi.TXT, MUTED = AiGenerateUi.MUTED, VIOLET = AiGenerateUi.VIOLET, INK = AiGenerateUi.INK;
    static final int[] COLORS = {0xffff7aa8, 0xff9d8cff, 0xfff0b34a, 0xff3ec6e0};
    static final int[] BASS_PATCH = {12, 13, 15, 15, 14, 15, 16, 15, 13};

    final MainActivity a; final ProUi ui;
    int part = 0, style = -1, mood = 1, bars = 8, key = 9; boolean minor = true, keyChosen = false;
    boolean chordsAdded = false;
    final List<Button> partButtons = new ArrayList<>(), moodButtons = new ArrayList<>(), styleButtons = new ArrayList<>();
    LinearLayout styleRow, results, box; TextView barsValue, chordInfo, keyInfo;
    AlertDialog dialog; AutoParts.Analysis analysis; double[] kicks; double startBeat; long startFrame; int lengthFrames;
    SampledInstruments.Pack pack; Synth.Config config; String packId;
    final java.util.HashMap<String, SampledInstruments.Pack> cache = new java.util.HashMap<>();

    AutoPartsUi(MainActivity activity, ProUi proUi) { a = activity; ui = proUi; }

    GradientDrawable pill(int fill, int stroke, float r) { GradientDrawable g = new GradientDrawable(); g.setColor(fill); g.setCornerRadius(a.dp(r)); g.setStroke(a.dp(1), stroke); return g; }
    Button chip(String text, final Runnable action) {
        Button b = new Button(a); b.setText(text); b.setAllCaps(false); b.setTextSize(13); b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(a.dp(10), 0, a.dp(10), 0);
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { action.run(); refreshChips(); } }); return b;
    }
    void style(Button b, boolean on) { b.setBackground(pill(on ? VIOLET : CHIP, on ? VIOLET : EDGE, 10)); b.setTextColor(on ? INK : TXT); b.setTypeface(Typeface.create("sans-serif-medium", on ? Typeface.BOLD : Typeface.NORMAL)); }
    TextView heading(String s) { TextView t = a.label(s, 11, MUTED, true); t.setLetterSpacing(.08f); t.setPadding(0, a.dp(12), 0, a.dp(5)); return t; }

    void refreshChips() {
        for (int i = 0; i < partButtons.size(); i++) style(partButtons.get(i), i == part);
        for (int i = 0; i < moodButtons.size(); i++) style(moodButtons.get(i), i == mood);
        for (int i = 0; i < styleButtons.size(); i++) style(styleButtons.get(i), i - 1 == style);
        barsValue.setText(String.valueOf(bars));
    }

    void buildStyles() {
        styleRow.removeAllViews(); styleButtons.clear(); String[] names = AutoParts.STYLES[part];
        for (int i = -1; i < names.length; i++) {
            final int s = i; Button b = chip(i < 0 ? "Surprise me" : names[i], new Runnable() { public void run() { style = s; } }); styleButtons.add(b);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, a.dp(44)); p.rightMargin = a.dp(6); styleRow.addView(b, p);
        }
    }

    void show() {
        if (!a.canEdit()) return;
        a.engine.pause();
        box = a.column(); box.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8)); box.setBackgroundColor(SHEET);
        box.addView(a.label("Auto parts", 18, VIOLET, true));
        TextView sub = a.label("Pick a part and a mood. You get 5 ready-made options that already fit your chords. Hear them, keep the one you like.", 12, MUTED, false); sub.setPadding(0, a.dp(2), 0, 0); box.addView(sub);

        box.addView(heading("WHAT DO YOU WANT?"));
        LinearLayout partRow = new LinearLayout(a);
        for (int i = 0; i < AutoParts.PART_NAMES.length; i++) {
            final int p = i; Button b = chip(AutoParts.PART_NAMES[i], new Runnable() { public void run() { part = p; style = -1; buildStyles(); results.removeAllViews(); } }); partButtons.add(b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, a.dp(48), 1); lp.rightMargin = a.dp(5); partRow.addView(b, lp);
        }
        box.addView(partRow);

        box.addView(heading("MOOD"));
        LinearLayout moodRow = new LinearLayout(a);
        for (int i = 0; i < AutoParts.MOODS.length; i++) {
            final int m = i; Button b = chip(AutoParts.MOODS[i], new Runnable() { public void run() { mood = m; } }); moodButtons.add(b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, a.dp(44), 1); lp.rightMargin = a.dp(5); moodRow.addView(b, lp);
        }
        box.addView(moodRow);

        box.addView(heading("STYLE"));
        HorizontalScrollView sv = new HorizontalScrollView(a); sv.setHorizontalScrollBarEnabled(false); styleRow = new LinearLayout(a); sv.addView(styleRow); box.addView(sv); buildStyles();

        box.addView(heading("LENGTH (BARS)"));
        LinearLayout barsRow = new LinearLayout(a); barsRow.setGravity(Gravity.CENTER_VERTICAL);
        Button minus = chip("−", new Runnable() { public void run() { bars = Math.max(2, bars - (bars > 8 ? 4 : 1)); } }), plus = chip("+", new Runnable() { public void run() { bars = Math.min(32, bars + (bars >= 8 ? 4 : 1)); } });
        barsValue = a.label("8", 18, TXT, true); barsValue.setGravity(Gravity.CENTER); barsValue.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        barsRow.addView(minus, new LinearLayout.LayoutParams(a.dp(56), a.dp(44))); barsRow.addView(barsValue, new LinearLayout.LayoutParams(0, -2, 1)); barsRow.addView(plus, new LinearLayout.LayoutParams(a.dp(56), a.dp(44))); box.addView(barsRow);

        chordInfo = a.label("", 12, TXT, false); chordInfo.setPadding(0, a.dp(12), 0, 0); box.addView(chordInfo);
        LinearLayout keyRow = new LinearLayout(a); keyRow.setPadding(0, a.dp(4), 0, 0);
        keyInfo = a.label("", 12, MUTED, false); keyRow.addView(keyInfo, new LinearLayout.LayoutParams(0, -2, 1));
        Button keyButton = chip("Change key", new Runnable() { public void run() { chooseKey(); } }); keyRow.addView(keyButton, new LinearLayout.LayoutParams(-2, a.dp(38))); box.addView(keyRow);

        Button go = new Button(a); go.setText("Make 5 options"); go.setAllCaps(false); go.setTextSize(16); go.setTextColor(INK); go.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD)); go.setBackground(pill(VIOLET, VIOLET, 12));
        go.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { make(); } });
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, a.dp(54)); gp.topMargin = a.dp(14); box.addView(go, gp);
        results = a.column(); box.addView(results);

        ScrollView scroll = new ScrollView(a); scroll.addView(box); refreshChips(); readProject(); describe();
        dialog = new AlertDialog.Builder(a).setView(scroll).setNegativeButton("Close", null).create();
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() { public void onDismiss(android.content.DialogInterface d) { a.liveMidi.voices.cancelScheduled(15); } });
        dialog.show();
    }

    void chooseKey() {
        final String[] names = new String[24]; for (int i = 0; i < 12; i++) { names[i] = AiGenerateUi.KEYS[i] + " major"; names[12 + i] = AiGenerateUi.KEYS[i] + " minor"; }
        new AlertDialog.Builder(a).setTitle("Key for the new chords").setItems(names, new android.content.DialogInterface.OnClickListener() { public void onClick(android.content.DialogInterface d, int i) { key = i % 12; minor = i >= 12; keyChosen = true; readProject(); describe(); } }).show();
    }

    /** Reads the chords and kick drum from the project, at the bar of the playhead. */
    void readProject() {
        Core.Session s = a.session; Pro.TempoMap m = ui.map(); int bpb = m.beatsPerBar();
        startBeat = Math.floor(m.beatAt(a.engine.position) / bpb) * bpb; startFrame = m.frameAt(startBeat); lengthFrames = (int) (m.frameAt(startBeat + bars * (double) bpb) - startFrame);
        List<AutoParts.In> notes = new ArrayList<>(); List<Double> kickList = new ArrayList<>(); double endBeat = startBeat + bars * (double) bpb;
        for (Core.Track t : s.tracks) for (Core.Clip c : t.clips) {
            if (c.notes == null) continue;
            boolean drums = c.drumPattern != null; for (Pro.Note n : c.notes) if (n.channel == 9) { drums = true; break; }
            boolean ours = t.name != null && t.name.startsWith("Auto · ") && !t.name.contains("Chords");
            for (Pro.Note n : c.notes) {
                double b0 = m.beatAt(c.start + n.start), b1 = m.beatAt(c.start + n.start + n.length); if (b1 <= startBeat || b0 >= endBeat) continue;
                if (drums || n.channel == 9) { if (n.pitch == 36 || n.pitch == 35) kickList.add(b0 - startBeat); }
                else if (!ours) notes.add(new AutoParts.In(n.pitch, b0 - startBeat, b1 - b0));
            }
        }
        if (!keyChosen) {
            if (s.tuneConfigured) { key = s.tuneKey; minor = s.tuneScale == 2; } else { key = mood <= 0 || mood == 2 ? 9 : 0; minor = mood <= 0 || mood == 2; }
        }
        analysis = AutoParts.analyse(notes, bars, bpb, key, minor, 1);
        kicks = new double[kickList.size()]; for (int i = 0; i < kicks.length; i++) kicks[i] = kickList.get(i); java.util.Arrays.sort(kicks);
    }

    void describe() {
        if (analysis.found) { chordInfo.setText("Chords found in your project: " + analysis.describe()); keyInfo.setText("Key: " + AiGenerateUi.KEYS[analysis.key] + (analysis.minor ? " minor" : " major") + (kicks.length > 0 ? " · kick drum found" : "")); }
        else { chordInfo.setText("No chords in your project yet. I'll make a chord track that matches your first option: " + analysis.describe()); keyInfo.setText("Key: " + AiGenerateUi.KEYS[analysis.key] + (analysis.minor ? " minor" : " major")); }
    }

    void instrumentFor(int partNo, int moodNo, int styleNo) {
        config = null; pack = null; packId = null; String id = null; Synth.Config patch;
        switch (partNo) {
            case AutoParts.BASS: patch = Synth.factory(BASS_PATCH[Math.max(0, Math.min(BASS_PATCH.length - 1, styleNo))]); break;
            case AutoParts.RHYTHM: patch = Synth.factory(10); id = "freepats-electric-guitar-clean"; break;
            case AutoParts.SOLO: patch = Synth.factory(10); id = styleNo == 1 ? "freepats-electric-guitar-jazz" : "freepats-electric-guitar-direct"; break;
            default: patch = Synth.factory(6); id = moodNo == 0 || moodNo == 2 ? "freepats-ydp-grand-compact" : "freepats-fm-piano2";
        }
        config = patch;
        if (id != null) try { for (SampledInstruments.Info info : a.instrumentLibrary.list()) if (info.filename.startsWith("factory-" + id + "/")) { SampledInstruments.Pack hit; synchronized (cache) { hit = cache.get(info.filename); } if (hit == null) { hit = a.instrumentLibrary.load(info); synchronized (cache) { cache.put(info.filename, hit); } } pack = hit; packId = info.filename; break; } } catch (Exception ignored) { pack = null; packId = null; }
    }

    void make() {
        if (!a.canEdit()) return;
        readProject(); describe(); results.removeAllViews();
        final int p = part, st = style, md = mood; final AutoParts.Analysis an = analysis; final int bpb = ui.map().beatsPerBar();
        a.busy("Writing 5 " + AutoParts.PART_NAMES[p].toLowerCase(java.util.Locale.US) + " options…", new MainActivity.Task() { public void run() throws Exception {
            final List<AutoParts.Option> options = AutoParts.options(p, st, md, an, bpb, kicks, System.nanoTime());
            // Warm up the instrument off the UI thread so Hear is instant.
            instrumentFor(p, md, st < 0 ? 0 : st);
            a.runOnUiThread(new Runnable() { public void run() { showOptions(options, p, md, an, bpb); } });
        } });
    }

    List<Pro.Note> proNotes(List<AiMidiGenerator.Note> notes) { return AiMidiGenerator.toNotes(notes, ui.map(), startBeat, 0); }

    void showOptions(final List<AutoParts.Option> options, final int p, final int md, final AutoParts.Analysis an, final int bpb) {
        results.removeAllViews(); results.addView(heading("YOUR 5 OPTIONS"));
        for (final AutoParts.Option o : options) {
            LinearLayout card = new LinearLayout(a); card.setGravity(Gravity.CENTER_VERTICAL); card.setPadding(a.dp(12), a.dp(6), a.dp(8), a.dp(6)); card.setBackground(pill(CHIP, EDGE, 12));
            TextView title = a.label(o.title, 14, TXT, true); card.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            Button hear = chip("▶ Hear", new Runnable() { public void run() { hear(o, p, md, an, bpb); } });
            final Button keep = chip("Keep", new Runnable() { public void run() { keep(o, p, md, an, bpb); } });
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(a.dp(86), a.dp(44)); hp.rightMargin = a.dp(6);
            card.addView(hear, hp); card.addView(keep, new LinearLayout.LayoutParams(a.dp(76), a.dp(44)));
            style(hear, false); style(keep, true);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.topMargin = a.dp(8); results.addView(card, cp);
        }
        Button more = chip("Make 5 different ones", new Runnable() { public void run() { make(); } }); style(more, false);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, a.dp(46)); mp.topMargin = a.dp(10); results.addView(more, mp);
    }

    void hear(AutoParts.Option o, int p, int md, AutoParts.Analysis an, int bpb) {
        instrumentFor(p, md, o.style);
        List<Pro.Note> notes = proNotes(o.notes);
        if (p == AutoParts.MELODY || p == AutoParts.SOLO) notes.addAll(proNotes(AutoParts.chordBlocks(an.chords, bpb, 38)));
        ui.auditionMidiPhrase(notes, config, pack, lengthFrames);
    }

    void keep(AutoParts.Option o, int p, int md, AutoParts.Analysis an, int bpb) {
        final Core.Session project = a.session; boolean addChords = !an.found && !chordsAdded; int need = addChords ? 2 : 1;
        if (project.tracks.size() + need > Core.MAX_TRACKS) { a.toast("Not enough free tracks."); return; }
        if (startFrame + lengthFrames > Core.MAX_FRAMES) { a.toast("Move the playhead earlier; this would run past the end of the project."); return; }
        instrumentFor(p, md, o.style);
        String label = AutoParts.PART_NAMES[p] + " · " + AutoParts.STYLES[p][o.style];
        Core.Track t = new Core.Track("Auto · " + AutoParts.PART_NAMES[p], COLORS[p]); t.volume = p == AutoParts.BASS ? .78 : p == AutoParts.MELODY ? .74 : .66;
        if (p == AutoParts.RHYTHM || p == AutoParts.SOLO) { t.fx.room = true; t.fx.roomMix = .08; }
        t.clips.add(ProMidi.clip(label, proNotes(o.notes), config, pack, packId, startFrame, lengthFrames));
        Core.Track chords = null;
        if (addChords) {
            Synth.Config piano = Synth.factory(1); SampledInstruments.Pack ydp = null; String ydpId = null;
            try { for (SampledInstruments.Info info : a.instrumentLibrary.list()) if (info.filename.startsWith("factory-freepats-ydp-grand-compact/")) { ydp = a.instrumentLibrary.load(info); ydpId = info.filename; break; } } catch (Exception ignored) { ydp = null; ydpId = null; }
            chords = new Core.Track("Auto · Chords", 0xff3ec6e0); chords.volume = .55; chords.fx.room = true; chords.fx.roomMix = .09;
            chords.clips.add(ProMidi.clip("Chords · " + an.describe(), proNotes(AutoParts.chordBlocks(an.chords, bpb, 66)), piano, ydp, ydpId, startFrame, lengthFrames));
        }
        ui.edit("Auto parts");
        if (chords != null) { project.tracks.add(chords); chordsAdded = true; }
        project.tracks.add(t); a.selected = t; a.clip = t.clips.get(0); a.save(false); a.refresh();
        a.toast("Added " + label + (chords != null ? " and matching chords" : "") + ". Open the clip to edit its notes, or tap ✎ in the track header to change the sound.");
    }
}
