package ug.audiocity.studio;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * "AI MIDI Generator" sheet. Drawn in the new amber / violet design language, but only inside this dialog:
 * the rest of the app (including the Mixer) keeps its existing look.
 * Generates melody, chords and bass as live MIDI clips on new tracks, aligned to the bar at the playhead.
 * Drums become a live MIDI clip played by the Audio City drum-pad kit (rack pads on MIDI notes 36..43).
 */
final class AiGenerateUi {
    // New design palette, local to this dialog.
    static final int SHEET = 0xff181b22, CHIP = 0xff20242d, EDGE = 0xff2b303b, TXT = 0xffe8ebf1, MUTED = 0xff9ba5b6, VIOLET = 0xff9d8cff, INK = 0xff10102a;
    static final String[] KEYS = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    final MainActivity a; final ProUi ui;
    int key = 9; boolean minor = true; int bars = 8; int creativity = 62;
    boolean melody = true, chords = true, bass = true, drums = true;
    final List<Button> keyButtons = new ArrayList<>();
    Button majorButton, minorButton, melodyButton, chordsButton, bassButton, drumsButton; TextView barsValue, creativityValue;
    AlertDialog dialog;

    AiGenerateUi(MainActivity activity, ProUi proUi) { a = activity; ui = proUi; }

    GradientDrawable pill(int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable(); g.setColor(fill); g.setCornerRadius(a.dp(radiusDp)); g.setStroke(a.dp(1), stroke); return g;
    }

    Button chip(String text, final Runnable action) {
        Button b = new Button(a); b.setText(text); b.setAllCaps(false); b.setTextSize(14); b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(a.dp(10), 0, a.dp(10), 0); b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { action.run(); styleAll(); } });
        return b;
    }

    void style(Button b, boolean on) { b.setBackground(pill(on ? VIOLET : CHIP, on ? VIOLET : EDGE, 10)); b.setTextColor(on ? INK : TXT); b.setTypeface(Typeface.create("sans-serif-medium", on ? Typeface.BOLD : Typeface.NORMAL)); }

    void styleAll() {
        for (int i = 0; i < keyButtons.size(); i++) style(keyButtons.get(i), i == key);
        style(majorButton, !minor); style(minorButton, minor); style(melodyButton, melody); style(chordsButton, chords); style(bassButton, bass); style(drumsButton, drums);
        barsValue.setText(String.valueOf(bars)); creativityValue.setText(String.format(java.util.Locale.US, "%.2f", creativity / 100.0));
    }

    TextView heading(String s) { TextView t = a.label(s, 11, MUTED, true); t.setLetterSpacing(.08f); t.setPadding(0, a.dp(14), 0, a.dp(6)); return t; }

    void show() {
        if (!a.canEdit()) return;
        LinearLayout box = a.column(); box.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8)); box.setBackgroundColor(SHEET);
        TextView title = a.label("AI MIDI Generator", 18, VIOLET, true); box.addView(title);
        TextView sub = a.label("Creates new tracks with live MIDI clips at the playhead. Undo removes everything it made.", 12, MUTED, false); sub.setPadding(0, a.dp(2), 0, 0); box.addView(sub);

        box.addView(heading("KEY"));
        HorizontalScrollView keyScroll = new HorizontalScrollView(a); keyScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout keyRow = new LinearLayout(a); keyScroll.addView(keyRow);
        keyButtons.clear();
        for (int i = 0; i < KEYS.length; i++) {
            final int k = i; Button b = chip(KEYS[i], new Runnable() { public void run() { key = k; } }); keyButtons.add(b);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(a.dp(48), a.dp(48)); p.rightMargin = a.dp(6); keyRow.addView(b, p);
        }
        box.addView(keyScroll);

        box.addView(heading("MODE"));
        LinearLayout modeRow = new LinearLayout(a);
        majorButton = chip("Major", new Runnable() { public void run() { minor = false; } }); minorButton = chip("Minor", new Runnable() { public void run() { minor = true; } });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, a.dp(48), 1); half.rightMargin = a.dp(6);
        modeRow.addView(majorButton, half); modeRow.addView(minorButton, new LinearLayout.LayoutParams(0, a.dp(48), 1)); box.addView(modeRow);

        box.addView(heading("BARS"));
        LinearLayout barsRow = new LinearLayout(a); barsRow.setGravity(Gravity.CENTER_VERTICAL);
        Button minus = chip("−", new Runnable() { public void run() { bars = Math.max(AiMidiGenerator.MIN_BARS, bars - (bars > 8 ? 4 : 1)); } });
        Button plus = chip("+", new Runnable() { public void run() { bars = Math.min(32, bars + (bars >= 8 ? 4 : 1)); } });
        minus.setContentDescription("Fewer bars"); plus.setContentDescription("More bars");
        barsValue = a.label("8", 18, TXT, true); barsValue.setGravity(Gravity.CENTER); barsValue.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        barsRow.addView(minus, new LinearLayout.LayoutParams(a.dp(56), a.dp(48))); barsRow.addView(barsValue, new LinearLayout.LayoutParams(0, -2, 1)); barsRow.addView(plus, new LinearLayout.LayoutParams(a.dp(56), a.dp(48))); box.addView(barsRow);

        box.addView(heading("GENERATE"));
        LinearLayout partRow = new LinearLayout(a);
        melodyButton = chip("Melody", new Runnable() { public void run() { melody = !melody; } }); chordsButton = chip("Chords", new Runnable() { public void run() { chords = !chords; } }); bassButton = chip("Bass", new Runnable() { public void run() { bass = !bass; } }); drumsButton = chip("Drums", new Runnable() { public void run() { drums = !drums; } });
        for (Button b : new Button[]{melodyButton, chordsButton, bassButton, drumsButton}) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, a.dp(48), 1); p.rightMargin = a.dp(6); partRow.addView(b, p); }
        box.addView(partRow);

        LinearLayout creativityHead = new LinearLayout(a); creativityHead.setPadding(0, a.dp(14), 0, a.dp(2));
        TextView cl = a.label("CREATIVITY", 11, MUTED, true); cl.setLetterSpacing(.08f); creativityHead.addView(cl, new LinearLayout.LayoutParams(0, -2, 1));
        creativityValue = a.label("0.62", 13, TXT, true); creativityValue.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); creativityHead.addView(creativityValue); box.addView(creativityHead);
        SeekBar seek = new SeekBar(a); seek.setMax(100); seek.setProgress(creativity); seek.setContentDescription("Creativity");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { creativity = p; styleAll(); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(seek, new LinearLayout.LayoutParams(-1, a.dp(40)));

        Button go = new Button(a); go.setText("Generate at playhead"); go.setAllCaps(false); go.setTextSize(15); go.setTextColor(INK); go.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        go.setBackground(pill(VIOLET, VIOLET, 12));
        go.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { generate(); } });
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, a.dp(52)); gp.topMargin = a.dp(16); box.addView(go, gp);

        ScrollView scroll = new ScrollView(a); scroll.addView(box); styleAll();
        dialog = new AlertDialog.Builder(a).setView(scroll).setNegativeButton("Close", null).show();
    }

    void generate() {
        if (!melody && !chords && !bass && !drums) { a.toast("Choose at least one part to generate."); return; }
        final Core.Session project = a.session;
        int newTracks = (melody ? 1 : 0) + (chords ? 1 : 0) + (bass ? 1 : 0) + (drums ? 1 : 0);
        if (project.tracks.size() + newTracks > Core.MAX_TRACKS) { a.toast("Not enough free tracks: this build supports " + Core.MAX_TRACKS + "."); return; }
        final Pro.TempoMap m = ui.map(); final int bpb = m.beatsPerBar();
        final double startBeat = Math.floor(m.beatAt(a.engine.position) / bpb) * bpb;
        final long at = m.frameAt(startBeat); final int len = (int) (m.frameAt(startBeat + bars * (double) bpb) - at);
        if (at + len > Core.MAX_FRAMES) { a.toast("Move the playhead earlier; this would run past the end of the project."); return; }
        final AiMidiGenerator.Options o = new AiMidiGenerator.Options();
        o.key = key; o.minor = minor; o.bars = bars; o.beatsPerBar = bpb; o.creativity = creativity / 100.0; o.seed = System.nanoTime();
        o.melody = melody; o.chords = chords; o.bass = bass; o.drums = drums;
        final String label = KEYS[key] + (minor ? " minor" : " major");
        if (dialog != null) dialog.dismiss();
        a.busy("Composing " + label + "…", new MainActivity.Task() { public void run() throws Exception {
            AiMidiGenerator.Result r = AiMidiGenerator.generate(o);
            final List<Core.Track> tracks = new ArrayList<>();
            if (o.melody) tracks.add(track("AI · Melody", 0xffff7aa8, .78, Synth.factory(6), AiMidiGenerator.toNotes(r.melody, m, startBeat, 0), at, len));
            if (o.chords) { Core.Track t = track("AI · Chords", 0xff3ec6e0, .6, Synth.factory(1), AiMidiGenerator.toNotes(r.chords, m, startBeat, 0), at, len); t.fx.room = true; t.fx.roomMix = .09; tracks.add(t); }
            if (o.bass) tracks.add(track("AI · Bass", 0xff9d8cff, .76, Synth.factory(2), AiMidiGenerator.toNotes(r.bass, m, startBeat, 0), at, len));
            if (o.drums) {
                float[][] kit = BeatPilotDrumScreen.loadKit(a);
                Core.Track d = new Core.Track("AI · Drums", 0xffffa726); d.volume = .82;
                d.clips.add(ProMidi.clip("AI · Drums", AiMidiGenerator.toNotes(AiMidiGenerator.toRackPitches(r.drums), m, startBeat, 9), Synth.factory(0), BeatPilotDrumScreen.pack(kit), null, at, len));
                tracks.add(d);
            }
            a.runOnUiThread(new Runnable() { public void run() {
                if (a.session != project) { a.toast("Project changed; nothing was added."); return; }
                ui.edit("AI generate");
                for (Core.Track t : tracks) project.tracks.add(t);
                a.selected = tracks.get(0); a.clip = tracks.get(0).clips.isEmpty() ? null : tracks.get(0).clips.get(0); a.screen = "ARRANGE"; a.save(false); a.refresh();
                a.toast("Added " + tracks.size() + " AI track" + (tracks.size() == 1 ? "" : "s") + " in " + label + ". Open any clip to edit its notes.");
            } });
        } });
    }

    Core.Track track(String name, int color, double volume, Synth.Config instrument, List<Pro.Note> notes, long at, int len) {
        Core.Track t = new Core.Track(name, color); t.volume = volume;
        t.clips.add(ProMidi.clip(name, notes, instrument, at, len));
        return t;
    }
}
