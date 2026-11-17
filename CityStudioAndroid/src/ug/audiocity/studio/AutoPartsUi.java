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

    /** Playable sounds per part: {name, sampled id or "", synth patch}. A sampled id falls back to its synth patch when not installed. */
    static final String[][][] SOUNDS = {
        {{"Grand piano", "freepats-ydp-grand-compact", "0"}, {"Electric piano", "freepats-fm-piano2", "1"}, {"Marimba", "", "8"}, {"Nylon pluck", "", "10"}, {"Silk lead", "", "6"}, {"Glass bell", "", "5"}},
        {{"Pluck bass", "", "15"}, {"Round bass", "", "2"}, {"Saw bass", "", "13"}, {"Square bass", "", "14"}, {"Sine bass", "", "12"}, {"Deep sub", "", "16"}, {"Sub 808", "", "11"}},
        {{"Clean guitar", "freepats-electric-guitar-clean", "10"}, {"Direct guitar", "freepats-electric-guitar-direct", "10"}, {"Jazz guitar", "freepats-electric-guitar-jazz", "10"}, {"Nylon pluck", "", "10"}, {"Afro pluck", "", "3"}},
        {{"Lead guitar", "freepats-electric-guitar-direct", "10"}, {"Jazz guitar", "freepats-electric-guitar-jazz", "10"}, {"Clean guitar", "freepats-electric-guitar-clean", "10"}, {"Silk lead", "", "6"}}};
    int sound = -1; String soundName = "";
    final MainActivity a; final ProUi ui;
    int part = 0, style = -1, mood = 1, bars = 8, key = 9; boolean minor = true, keyChosen = false;
    boolean chordsAdded = false;
    final List<Button> partButtons = new ArrayList<>(), moodButtons = new ArrayList<>(), styleButtons = new ArrayList<>();
    LinearLayout styleRow, soundRow, results, box;
    final List<Button> soundButtons = new ArrayList<>(); TextView barsValue, chordInfo, keyInfo;
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
        for (int i = 0; i < soundButtons.size(); i++) style(soundButtons.get(i), i - 1 == sound);
        barsValue.setText(String.valueOf(bars));
    }

    void buildStyles() {
        styleRow.removeAllViews(); styleButtons.clear(); String[] names = AutoParts.STYLES[part];
        for (int i = -1; i < names.length; i++) {
            final int s = i; Button b = chip(i < 0 ? "Surprise me" : names[i], new Runnable() { public void run() { style = s; } }); styleButtons.add(b);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, a.dp(44)); p.rightMargin = a.dp(6); styleRow.addView(b, p);
        }
    }

    void buildSounds() {
        soundRow.removeAllViews(); soundButtons.clear(); String[][] names = SOUNDS[part];
        for (int i = -1; i < names.length; i++) {
            final int sIdx = i; Button b = chip(i < 0 ? "Automatic" : names[i][0], new Runnable() { public void run() { sound = sIdx; } }); soundButtons.add(b);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, a.dp(44)); p.rightMargin = a.dp(6); soundRow.addView(b, p);
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
            final int p = i; Button b = chip(AutoParts.PART_NAMES[i], new Runnable() { public void run() { part = p; style = -1; sound = -1; buildStyles(); buildSounds(); results.removeAllViews(); } }); partButtons.add(b);
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

        box.addView(heading("SOUND"));
        HorizontalScrollView sv2 = new HorizontalScrollView(a); sv2.setHorizontalScrollBarEnabled(false); soundRow = new LinearLayout(a); sv2.addView(soundRow); box.addView(sv2); buildSounds();

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

    // ------------------------------------------------------------------ the one-tap way

    Core.Track previewTrack; long savedLoopStart, savedLoopEnd; boolean savedLoop, previewing; AutoParts.Option current; int currentPart; TextView nowPlaying; View playerCard; int lastStyle = -1, quickMood = 1;
    final java.util.Random random = new java.util.Random();

    /** The easy sheet: tap what you want, hear it with your song straight away, keep it or ask for another. */
    void showQuick() {
        if (!a.canEdit()) return;
        a.engine.pause();
        LinearLayout q = a.column(); q.setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(10)); q.setBackgroundColor(SHEET);
        q.addView(a.label("Make me a part", 20, VIOLET, true));
        TextView sub = a.label("Tap one. I listen to the chords in your song, write a part that fits, and play it with your music. No music skill needed.", 12, MUTED, false); sub.setPadding(0, a.dp(2), 0, a.dp(8)); q.addView(sub);
        String[] big = {"🎹  Melody", "🎸  Bass", "🎸  Strum", "🎸  Guitar solo"}; String[] small = {"a tune to sing along", "deep groove", "chords with rhythm", "a lead line"};
        for (int row = 0; row < 2; row++) {
            LinearLayout line = new LinearLayout(a);
            for (int col = 0; col < 2; col++) {
                final int part1 = row * 2 + col; final int partNo = part1 == 2 ? AutoParts.RHYTHM : part1 == 3 ? AutoParts.SOLO : part1;
                Button b = new Button(a); b.setAllCaps(false); b.setText(big[part1] + "\n" + small[part1]); b.setTextSize(15); b.setTextColor(INK); b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD)); b.setBackground(pill(COLORS[partNo], COLORS[partNo], 16));
                b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { quickMake(partNo); } });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, a.dp(84), 1); lp.rightMargin = a.dp(col == 0 ? 8 : 0); lp.bottomMargin = a.dp(8); line.addView(b, lp);
            }
            q.addView(line);
        }
        LinearLayout moods = new LinearLayout(a); final List<Button> mb = new ArrayList<>();
        for (int i = 0; i < AutoParts.MOODS.length; i++) {
            final int m = i; final Button b = new Button(a); b.setText(AutoParts.MOODS[i]); b.setAllCaps(false); b.setTextSize(13); b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0); mb.add(b);
            b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { quickMood = m; for (int k = 0; k < mb.size(); k++) style(mb.get(k), k == quickMood); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, a.dp(42), 1); lp.rightMargin = a.dp(5); moods.addView(b, lp);
        }
        for (int k = 0; k < mb.size(); k++) style(mb.get(k), k == quickMood);
        q.addView(moods);

        TextView own = a.label("OR MAKE YOUR OWN TUNE · NO SKILL NEEDED", 11, MUTED, true); own.setLetterSpacing(.08f); own.setPadding(0, a.dp(14), 0, a.dp(6)); q.addView(own);
        String[][] ways = {{"👆  Tap it", "Tap the rhythm of your tune. I pick the notes."}, {"✍  Draw it", "Draw a line up and down. I turn it into a melody."}, {"🎤  Hum it", "Hum or sing la-la-la. I turn it into notes."}};
        for (int i = 0; i < ways.length; i++) {
            final int way = i; Button b = new Button(a); b.setAllCaps(false); b.setText(ways[i][0] + "\n" + ways[i][1]); b.setTextSize(13); b.setTextColor(TXT); b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); b.setPadding(a.dp(14), 0, a.dp(10), 0); b.setBackground(pill(CHIP, EDGE, 14));
            b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (way == 0) tapIt(); else if (way == 1) drawIt(); else humIt(); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, a.dp(60)); lp.bottomMargin = a.dp(6); q.addView(b, lp);
        }

        LinearLayout card = a.column(); card.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10)); card.setBackground(pill(CHIP, EDGE, 14)); card.setVisibility(View.GONE); playerCard = card;
        nowPlaying = a.label("", 15, TXT, true); card.addView(nowPlaying);
        LinearLayout buttons = new LinearLayout(a);
        Button another = chip("🔄  Another", new Runnable() { public void run() { if (again != null) again.run(); else quickMake(currentPart); } }), keepB = chip("👍  Keep it", new Runnable() { public void run() { quickKeep(); } }), stop = chip("■ Stop", new Runnable() { public void run() { stopPreview(false); } });
        style(another, false); style(keepB, true); style(stop, false);
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, a.dp(52), 1); b1.rightMargin = a.dp(6); buttons.addView(another, b1);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, a.dp(52), 1); b2.rightMargin = a.dp(6); buttons.addView(keepB, b2);
        buttons.addView(stop, new LinearLayout.LayoutParams(a.dp(78), a.dp(52))); LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2); bp.topMargin = a.dp(8); card.addView(buttons, bp);
        LinearLayout tweaks = new LinearLayout(a); String[] tw = {"⬆ Higher", "⬇ Lower", "Simpler", "Busier"};
        for (int i = 0; i < tw.length; i++) { final int kind = i; Button b = chip(tw[i], new Runnable() { public void run() { tweak(kind); } }); style(b, false); b.setTextSize(12); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, a.dp(42), 1); lp.rightMargin = a.dp(i < 3 ? 5 : 0); tweaks.addView(b, lp); }
        LinearLayout.LayoutParams twp = new LinearLayout.LayoutParams(-1, -2); twp.topMargin = a.dp(6); card.addView(tweaks, twp);
        Button fix = chip("✨  Fix wrong notes", new Runnable() { public void run() { tweak(4); } }); style(fix, false); fix.setTextSize(12); LinearLayout.LayoutParams fxp = new LinearLayout.LayoutParams(-1, a.dp(42)); fxp.topMargin = a.dp(6); card.addView(fix, fxp);
        LinearLayout.LayoutParams cardP = new LinearLayout.LayoutParams(-1, -2); cardP.topMargin = a.dp(6); q.addView(card, 2, cardP);

        Button more = chip("More choices: style, sound, length…", new Runnable() { public void run() { stopPreview(false); sound = -1; if (dialog != null) dialog.dismiss(); show(); } }); style(more, false);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, a.dp(46)); mp.topMargin = a.dp(12); q.addView(more, mp);
        ScrollView scroll = new ScrollView(a); scroll.addView(q);
        dialog = new AlertDialog.Builder(a).setView(scroll).setNegativeButton("Close", null).create();
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() { public void onDismiss(android.content.DialogInterface d) { stopPreview(false); } });
        dialog.show();
    }

    void quickMake(final int partNo) {
        if (!a.canEdit()) return;
        stopPreview(false); currentPart = partNo; sound = sound < 0 ? 0 : sound + 1; // each press tries the next sound too
        again = new Runnable() { public void run() { quickMake(partNo); } };
        readProject(); final AutoParts.Analysis an = analysis; final int md = quickMood, bpb = ui.map().beatsPerBar(); final int styles = AutoParts.STYLES[partNo].length;
        int st; do { st = random.nextInt(styles); } while (styles > 1 && st == lastStyle); lastStyle = st; final int chosen = st;
        a.busy("Writing your " + AutoParts.PART_NAMES[partNo].toLowerCase(java.util.Locale.US) + "…", new MainActivity.Task() { public void run() throws Exception {
            final long seed = System.nanoTime(); final List<AiMidiGenerator.Note> notes = AutoParts.generate(partNo, chosen, md, an, bpb, kicks, seed);
            instrumentFor(partNo, md, chosen);
            current = new AutoParts.Option(AutoParts.PART_NAMES[partNo] + " · " + AutoParts.STYLES[partNo][chosen], chosen, seed, notes);
            a.runOnUiThread(new Runnable() { public void run() { startPreview(partNo, md, an, bpb); } });
        } });
    }

    void startPreview(int partNo, int md, AutoParts.Analysis an, int bpb) {
        if (dialog == null || !dialog.isShowing() || current == null) return;
        Core.Session s = a.session; Core.Track t = new Core.Track("Auto · Preview", COLORS[partNo]); t.volume = partNo == AutoParts.BASS ? .78 : .72; polish(t, partNo);
        t.clips.add(ProMidi.clip(current.title, proNotes(current.notes), config, pack, packId, startFrame, lengthFrames));
        if (!previewing) { savedLoop = a.engine.loop; savedLoopStart = s.loopStart; savedLoopEnd = s.loopEnd; }
        previewing = true; previewTrack = t; s.tracks.add(t); s.loopStart = startFrame; s.loopEnd = startFrame + lengthFrames; a.engine.loop = true; a.engine.position = startFrame;
        if (!an.found) { // no chords yet: add a quiet chord bed to the preview so the part is heard in context
            Core.Track bed = new Core.Track("Auto · Preview chords", 0xff3ec6e0); bed.volume = .5; polish(bed, AutoParts.MELODY);
            bed.clips.add(ProMidi.clip("Chords", proNotes(AutoParts.chordBlocks(an.chords, bpb, 60)), Synth.factory(1), null, null, startFrame, lengthFrames)); s.tracks.add(bed); previewBed = bed;
        }
        nowPlaying.setText(current.title + "  ·  " + soundName); playerCard.setVisibility(View.VISIBLE);
        if (a.requestAudioFocus()) a.engine.play(s);
        a.refresh();
    }
    Core.Track previewBed;

    /** Removes the temporary tracks and puts the loop settings back. */
    void stopPreview(boolean keepPlayerCard) {
        if (!previewing) { a.engine.pause(); return; }
        a.engine.pause(); Core.Session s = a.session;
        if (previewTrack != null) s.tracks.remove(previewTrack); if (previewBed != null) s.tracks.remove(previewBed); previewTrack = null; previewBed = null;
        s.loopStart = savedLoopStart; s.loopEnd = savedLoopEnd; a.engine.loop = savedLoop; previewing = false;
        if (!keepPlayerCard && playerCard != null) playerCard.setVisibility(View.GONE);
        a.refresh();
    }

    void quickKeep() {
        if (current == null || !previewing) return;
        final AutoParts.Option o = current; final int p = currentPart; final AutoParts.Analysis an = analysis; final int bpb = ui.map().beatsPerBar(); final int md = quickMood;
        stopPreview(true); keep(o, p, md, an, bpb); playerCard.setVisibility(View.GONE);
    }

    Runnable again;

    /** Plays a ready melody with the song and shows Keep / Another / tweak buttons. */
    void present(final int partNo, final String title, final List<AiMidiGenerator.Note> notes) {
        if (notes == null || notes.isEmpty()) { a.toast("That didn't give any notes. Try again."); return; }
        stopPreview(true); currentPart = partNo; final AutoParts.Analysis an = analysis; final int bpb = ui.map().beatsPerBar(), md = quickMood;
        a.busy("Getting it ready…", new MainActivity.Task() { public void run() throws Exception {
            instrumentFor(partNo, md, 0); current = new AutoParts.Option(title, 0, 0, notes);
            a.runOnUiThread(new Runnable() { public void run() { startPreview(partNo, md, an, bpb); } });
        } });
    }

    void tweak(int kind) {
        if (current == null) return; boolean bass = currentPart == AutoParts.BASS; List<AiMidiGenerator.Note> n;
        if (kind == 0) n = AutoParts.transpose(current.notes, 12, bass ? 24 : 45, bass ? 60 : 96); else if (kind == 1) n = AutoParts.transpose(current.notes, -12, bass ? 24 : 45, bass ? 60 : 96);
        else if (kind == 2) n = AutoParts.simpler(current.notes, ui.map().beatsPerBar()); else if (kind == 4) { n = AutoParts.fitToChords(current.notes, analysis, ui.map().beatsPerBar(), true); boolean same = true; for (int i = 0; i < n.size(); i++) if (n.get(i).pitch != current.notes.get(i).pitch) same = false; if (same) { a.toast("Every note already fits the chords."); return; } } else n = AutoParts.busier(current.notes, analysis);
        if (n == null) { a.toast(kind == 0 ? "That's as high as it goes." : "That's as low as it goes."); return; }
        if (n.size() > 400) { a.toast("That's as busy as it gets."); return; }
        present(currentPart, current.title, n);
    }

    // ---- Tap it

    void tapIt() {
        if (!a.canEdit()) return; stopPreview(false); readProject();
        a.busy("Getting ready…", new MainActivity.Task() { public void run() throws Exception { instrumentFor(AutoParts.MELODY, quickMood, 0); a.runOnUiThread(new Runnable() { public void run() { tapDialog(); } }); } });
    }

    void tapDialog() {
        final Core.Session s = a.session; final Pro.TempoMap map = ui.map(); final int bpb = map.beatsPerBar(); final double total = bars * (double) bpb; final AutoParts.Analysis an = analysis;
        final List<Double> taps = new ArrayList<>(); final boolean oldLoop = a.engine.loop, oldClick = a.engine.click; final long oldStart = s.loopStart, oldEnd = s.loopEnd;
        LinearLayout col = a.column(); col.setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(4)); col.setBackgroundColor(SHEET);
        col.addView(a.label("Tap your tune", 18, VIOLET, true));
        TextView help = a.label("Your song plays in a loop with a click. Tap the big pad every time your tune should have a note, like clapping along. I'll choose the notes.", 12, MUTED, false); help.setPadding(0, a.dp(2), 0, a.dp(10)); col.addView(help);
        final TextView status = a.label("Listen for the count, then tap.", 14, TXT, true); status.setGravity(Gravity.CENTER);
        final Button pad = new Button(a); pad.setAllCaps(false); pad.setText("TAP HERE"); pad.setTextSize(26); pad.setTextColor(INK); pad.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD)); pad.setBackground(pill(COLORS[0], COLORS[0], 22)); pad.setSoundEffectsEnabled(false);
        pad.setOnTouchListener(new View.OnTouchListener() { public boolean onTouch(View v, android.view.MotionEvent e) {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                v.setAlpha(.6f);
                if (!a.engine.playing || a.engine.countInRemaining > 0) return true;
                long pos = Math.max(startFrame, a.engine.position - a.engine.latencyFrames); double beat = map.beatAt(pos) - startBeat; beat = ((beat % total) + total) % total;
                if (!taps.isEmpty() && beat < taps.get(taps.size() - 1) - total / 2) taps.clear(); // the loop came round: start a fresh take
                taps.add(beat); status.setText(taps.size() + (taps.size() == 1 ? " tap" : " taps") + " · press Done when you finish");
                AutoParts.Chord c = an.chords[Math.min(an.chords.length - 1, (int) (beat / bpb))]; int pitch = 60 + ((c.root - 60) % 12 + 12) % 12;
                ui.auditionMidiNote(new Pro.Note(pitch, 96, 0, Core.RATE / 5, 0), config, pack);
            } else if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP || e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) v.setAlpha(1f);
            return true;
        } });
        col.addView(pad, new LinearLayout.LayoutParams(-1, a.dp(210))); LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2); sp.topMargin = a.dp(10); col.addView(status, sp);
        final AlertDialog d = new AlertDialog.Builder(a).setView(col).setNegativeButton("Cancel", null).setNeutralButton("Start over", null).setPositiveButton("Done", null).create();
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() { public void onDismiss(android.content.DialogInterface x) { a.engine.pause(); a.engine.click = oldClick; a.engine.loop = oldLoop; s.loopStart = oldStart; s.loopEnd = oldEnd; a.refresh(); } });
        d.show();
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() { public void onClick(View v) { taps.clear(); status.setText("Cleared. Tap again."); } });
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (taps.size() < 2) { a.toast("Tap at least two notes first."); return; }
            final double[] t = new double[taps.size()]; for (int i = 0; i < t.length; i++) t[i] = taps.get(i);
            d.dismiss();
            again = new Runnable() { public void run() { present(AutoParts.MELODY, "Melody · Tapped", AutoParts.fromTaps(t, an, bpb, quickMood, System.nanoTime())); } };
            again.run();
        } });
        s.loopStart = startFrame; s.loopEnd = startFrame + lengthFrames; a.engine.loop = true; a.engine.click = true; a.engine.position = startFrame;
        if (a.requestAudioFocus()) a.engine.playCounted(s, bpb);
    }

    // ---- Draw it

    void drawIt() {
        if (!a.canEdit()) return; stopPreview(false); readProject();
        final int bpb = ui.map().beatsPerBar(); final AutoParts.Analysis an = analysis; final double[] heights = new double[bars * bpb * 2]; java.util.Arrays.fill(heights, Double.NaN);
        LinearLayout col = a.column(); col.setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(4)); col.setBackgroundColor(SHEET);
        col.addView(a.label("Draw your tune", 18, VIOLET, true));
        TextView help = a.label("Slide your finger from left to right. Up = high notes, down = low notes. Leave a gap for silence. I'll fit it to your chords.", 12, MUTED, false); help.setPadding(0, a.dp(2), 0, a.dp(10)); col.addView(help);
        final View padView = new View(a) {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG); int lastStep = -1; double lastH;
            @Override protected void onDraw(android.graphics.Canvas c) {
                float w = getWidth(), h = getHeight(), sw = w / heights.length; p.setStyle(android.graphics.Paint.Style.FILL); p.setColor(0xff141821); c.drawRoundRect(0, 0, w, h, a.dp(14), a.dp(14), p);
                for (int bar = 0; bar <= bars; bar++) { p.setColor(bar % 4 == 0 ? 0x55ffffff : 0x22ffffff); float x = bar * bpb * 2 * sw; c.drawRect(x, 0, x + a.dp(1), h, p); }
                p.setColor(0x33ffffff); p.setTextSize(a.dp(10)); c.drawText("HIGH", a.dp(8), a.dp(16), p); c.drawText("LOW", a.dp(8), h - a.dp(8), p);
                p.setColor(COLORS[0]); for (int i = 0; i < heights.length; i++) if (!Double.isNaN(heights[i])) { float y = (float) ((1 - heights[i]) * (h - a.dp(16)) + a.dp(8)); c.drawRoundRect(i * sw + 1, y - a.dp(5), (i + 1) * sw - 1, y + a.dp(5), a.dp(4), a.dp(4), p); }
            }
            @Override public boolean onTouchEvent(android.view.MotionEvent e) {
                getParent().requestDisallowInterceptTouchEvent(true);
                int step = Math.max(0, Math.min(heights.length - 1, (int) (e.getX() / getWidth() * heights.length))); double hgt = Math.max(0, Math.min(1, 1 - (e.getY() - a.dp(8)) / (getHeight() - a.dp(16))));
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) { lastStep = step; lastH = hgt; heights[step] = hgt; }
                else if (e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE && lastStep >= 0) { int from = Math.min(lastStep, step), to = Math.max(lastStep, step); for (int i = from; i <= to; i++) { double f = to == from ? 1 : (i - lastStep) / (double) (step - lastStep); heights[i] = lastH + (hgt - lastH) * f; } lastStep = step; lastH = hgt; }
                invalidate(); return true;
            }
        };
        col.addView(padView, new LinearLayout.LayoutParams(-1, a.dp(230)));
        final AlertDialog d = new AlertDialog.Builder(a).setView(col).setNegativeButton("Cancel", null).setNeutralButton("Clear", null).setPositiveButton("Make my melody", null).create(); d.show();
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() { public void onClick(View v) { java.util.Arrays.fill(heights, Double.NaN); padView.invalidate(); } });
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            boolean any = false; for (double hv : heights) if (!Double.isNaN(hv)) any = true; if (!any) { a.toast("Draw a line first."); return; }
            d.dismiss();
            again = new Runnable() { public void run() { present(AutoParts.MELODY, "Melody · Drawn", AutoParts.fromShape(heights, an, bpb, quickMood, System.nanoTime())); } };
            again.run();
        } });
    }

    // ---- Hum it

    volatile boolean humming;

    void humIt() {
        if (!a.canEdit()) return; stopPreview(false);
        if (a.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { a.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 77); a.toast("Allow the microphone, then tap Hum it again."); return; }
        readProject(); final AutoParts.Analysis an = analysis; final int bpb = ui.map().beatsPerBar(), bpm = (int) Math.round(a.session.bpm); final Core.Session s = a.session;
        final boolean phones = a.headphonesConnected(); final long regionStart = startFrame; final int regionFrames = lengthFrames;
        if (regionFrames > Core.RATE * 70L) { a.toast("That is too long to hum in one go. Use a faster tempo or fewer bars."); return; }
        final boolean oldLoop = a.engine.loop, oldClick = a.engine.click; final long oldStart = s.loopStart, oldEnd = s.loopEnd; final float oldGain = a.engine.outputGain; final boolean[] restored = {true};
        final Runnable restore = new Runnable() { public void run() { if (restored[0]) return; restored[0] = true; a.engine.pause(); a.engine.click = oldClick; a.engine.loop = oldLoop; s.loopStart = oldStart; s.loopEnd = oldEnd; a.engine.outputGain = oldGain; a.refresh(); } };
        LinearLayout col = a.column(); col.setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(4)); col.setBackgroundColor(SHEET);
        col.addView(a.label("Hum your tune", 18, VIOLET, true));
        TextView help = a.label((phones ? "Your song plays in your headphones after a count." : "Your song plays quietly from the speaker after a count. I remove it from the recording afterwards. Headphones give the best result.") + " Hum or sing \"la la la\" along with it. Don't worry about wrong notes: I move them onto your key and chords.", 12, MUTED, false); help.setPadding(0, a.dp(2), 0, a.dp(10)); col.addView(help);
        final TextView status = a.label("Press Start, listen for the count, then hum.", 14, TXT, true); status.setGravity(Gravity.CENTER);
        final Button go = new Button(a); go.setAllCaps(false); go.setText("🎤  Start"); go.setTextSize(20); go.setTextColor(INK); go.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD)); go.setBackground(pill(COLORS[0], COLORS[0], 22));
        col.addView(go, new LinearLayout.LayoutParams(-1, a.dp(120))); LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2); sp.topMargin = a.dp(10); col.addView(status, sp);
        final AlertDialog d = new AlertDialog.Builder(a).setView(col).setNegativeButton("Cancel", null).create();
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() { public void onDismiss(android.content.DialogInterface x) { humming = false; restore.run(); } });
        go.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (humming) { humming = false; go.setEnabled(false); status.setText("Finding your notes…"); return; }
            humming = true; go.setText("■  Done"); status.setText("Count-in…");
            restored[0] = false; s.loopStart = regionStart; s.loopEnd = regionStart + regionFrames; a.engine.loop = true; a.engine.click = true; a.engine.position = regionStart; a.engine.outputGain = phones ? oldGain : oldGain * .5f;
            new Thread(new Runnable() { public void run() {
                long countFrames = Core.beat(s.bpm) * bpb; final float[] mic = new float[(int) Math.min(Core.RATE * 90L, regionFrames + countFrames + Core.RATE * 4L)]; int n = 0; String problem = null; android.media.AudioRecord rec = null;
                java.util.ArrayList<Long> stamps = new java.util.ArrayList<>(); long origin = -1;
                try {
                    int min = android.media.AudioRecord.getMinBufferSize(Core.RATE, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT);
                    rec = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, Core.RATE, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, Math.max(min, Core.RATE / 2));
                    short[] in = new short[Core.RATE / 50]; rec.startRecording(); long shown = 0;
                    a.runOnUiThread(new Runnable() { public void run() { if (humming && a.requestAudioFocus()) a.engine.playCounted(s, bpb); } });
                    while (humming && n < mic.length) {
                        int r = rec.read(in, 0, Math.min(in.length, mic.length - n)); if (r <= 0) break; float peak = 0;
                        for (int i = 0; i < r; i++) { float val = in[i] / 32768f; mic[n++] = val; peak = Math.max(peak, Math.abs(val)); }
                        // Where in the recording does the first beat of the song fall? Sample the playback position as we go.
                        if (a.engine.playing && a.engine.countInRemaining <= 0) { long pos = a.engine.position; if (pos > regionStart && pos < regionStart + regionFrames && stamps.size() < 400) stamps.add(n - (pos - regionStart - a.engine.latencyFrames)); }
                        if (stamps.size() >= 15) { java.util.ArrayList<Long> c = new java.util.ArrayList<>(stamps); java.util.Collections.sort(c); origin = c.get(c.size() / 2); if (n >= origin + regionFrames + Core.RATE * 3 / 10) break; }
                        final boolean started = !stamps.isEmpty(), quiet = peak < .01f;
                        if (System.currentTimeMillis() - shown > 400) { shown = System.currentTimeMillis(); a.runOnUiThread(new Runnable() { public void run() { if (humming) status.setText(started ? "Hum along now…" + (quiet ? "  ·  I can't hear you, hum louder" : "") : "Count-in…"); } }); }
                    }
                } catch (Exception ex) { problem = "The microphone could not start."; }
                finally { if (rec != null) try { rec.stop(); } catch (Exception ignored) {} if (rec != null) rec.release(); }
                final boolean cancelled = !d.isShowing(); humming = false; a.runOnUiThread(restore);
                a.runOnUiThread(new Runnable() { public void run() { if (d.isShowing()) { go.setEnabled(false); status.setText(phones ? "Finding your notes…" : "Removing the song from your hum, then finding your notes…"); } } });
                List<AiMidiGenerator.Note> found = new ArrayList<>();
                if (problem == null && !cancelled) try {
                    if (origin < 0) problem = "The song did not start. Try again.";
                    else {
                        float[] mono48;
                        if (phones) { int from = (int) Math.max(0, origin), len = Math.max(0, Math.min(regionFrames, n - from)); mono48 = java.util.Arrays.copyOfRange(mic, from, from + len); }
                        else {
                            // Take away the song that leaked from the speaker, and use its measured delay to line the hum up exactly.
                            final int margin = Core.RATE * 15 / 100, total = regionFrames + margin * 2, recorded = n; final long base = origin - margin;
                            float[] ref = new float[regionFrames * 2], block = new float[1024]; Core.Renderer renderer = new Core.Renderer(s); renderer.meterLoudness = false;
                            for (int p = 0; p < regionFrames; p += 512) { int k = Math.min(512, regionFrames - p); renderer.render(regionStart + p, k, block, true); System.arraycopy(block, 0, ref, p * 2, k * 2); }
                            final float[] cleaned = new float[total]; final int[] at = {0};
                            BleedRemover.Reader take = new BleedRemover.Reader() { public void read(long f, float[] out, int k) { for (int i = 0; i < k; i++) { long q = base + f + i; float val = q >= 0 && q < recorded ? mic[(int) q] : 0; out[i * 2] = val; out[i * 2 + 1] = val; } } };
                            BleedRemover.Report rep = BleedRemover.process(take, BleedRemover.of(ref), total, .9, new BleedRemover.Sink() { public void write(float[] sb, int k) { for (int i = 0; i < k && at[0] < total; i++) cleaned[at[0]++] = sb[i * 2]; } });
                            int lag = Math.max(0, Math.min(total - 1, rep.delayFrames)); mono48 = java.util.Arrays.copyOfRange(cleaned, lag, Math.min(total, lag + regionFrames));
                        }
                        int step = Core.RATE / 12000; float[] mono12 = new float[mono48.length / step]; for (int i = 0; i < mono12.length; i++) { float sum = 0; for (int k = 0; k < step; k++) sum += mono48[i * step + k]; mono12[i] = sum / step; }
                        found = AutoParts.fitToChords(AutoParts.fromHum(mono12, bpm, an, bpb, true), an, bpb, false);
                    }
                } catch (Exception ex) { problem = "Something went wrong while finding your notes. Try again."; } catch (OutOfMemoryError ex) { problem = "Not enough memory for a take this long. Try fewer bars."; }
                final List<AiMidiGenerator.Note> notes = found; final String issue = problem;
                a.runOnUiThread(new Runnable() { public void run() {
                    if (cancelled || a.dead || !d.isShowing()) return;
                    if (issue != null || notes.size() < 2) { go.setEnabled(true); go.setText("🎤  Try again"); status.setText(issue != null ? issue : "I couldn't hear a clear tune. Hum louder and closer to the phone."); return; }
                    d.dismiss(); again = new Runnable() { public void run() { humIt(); } }; present(AutoParts.MELODY, "Melody · Hummed", notes);
                } });
            } }, "hum").start();
        } });
        d.show();
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
        int pick = sound;
        if (pick < 0) { // automatic: the sound that suits the part, mood and style
            if (partNo == AutoParts.BASS) { int want = BASS_PATCH[Math.max(0, Math.min(BASS_PATCH.length - 1, styleNo))]; pick = 0; for (int i = 0; i < SOUNDS[1].length; i++) if (Integer.parseInt(SOUNDS[1][i][2]) == want) pick = i; }
            else if (partNo == AutoParts.MELODY) pick = moodNo == 0 || moodNo == 2 ? 0 : 1;
            else if (partNo == AutoParts.SOLO) pick = styleNo == 1 ? 1 : 0; else pick = 0;
        }
        String[][] list = SOUNDS[partNo]; String[] choice = list[Math.floorMod(pick, list.length)]; soundName = choice[0]; id = choice[1].isEmpty() ? null : choice[1]; patch = Synth.factory(Integer.parseInt(choice[2]));
        config = patch;
        if (id != null) try { for (SampledInstruments.Info info : a.instrumentLibrary.list()) if (info.filename.startsWith("factory-" + id + "/")) { SampledInstruments.Pack hit; synchronized (cache) { hit = cache.get(info.filename); } if (hit == null) { hit = a.instrumentLibrary.load(info); synchronized (cache) { cache.put(info.filename, hit); } } pack = hit; packId = info.filename; break; } } catch (Exception ignored) { pack = null; packId = null; }
    }

    /** Studio polish so parts do not sound dry and thin: space, a little echo, warmth for bass, gentle level control. */
    static void polish(Core.Track t, int partNo) {
        Core.Fx f = t.fx; f.eq = true; f.comp = true; f.threshold = -20; f.ratio = 2.5; f.room = true;
        switch (partNo) {
            case AutoParts.BASS: f.sat = true; f.drive = 2.2; f.low = 2; f.mid = -1; f.high = 1; f.roomMix = .02; f.highPassHz = 35; f.hpf = true; break;
            case AutoParts.MELODY: f.roomMix = .2; f.delay = true; f.delayMix = .11; f.delayFeedback = .3; f.high = 1.5; f.low = -1; break;
            case AutoParts.RHYTHM: f.roomMix = .14; f.delay = true; f.delayMix = .07; f.high = 1; f.low = -1.5; f.hpf = true; f.highPassHz = 80; break;
            default: f.roomMix = .2; f.delay = true; f.delayMix = .15; f.delayFeedback = .35; f.sat = true; f.drive = 1.6; f.high = 1; f.low = -2; f.hpf = true; f.highPassHz = 100; break;
        }
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
        int cut = o.title.lastIndexOf(" · "); String label = AutoParts.PART_NAMES[p] + " · " + (cut >= 0 ? o.title.substring(cut + 3) : o.title);
        Core.Track t = new Core.Track("Auto · " + AutoParts.PART_NAMES[p], COLORS[p]); t.volume = p == AutoParts.BASS ? .78 : p == AutoParts.MELODY ? .74 : .66;
        polish(t, p);
        t.clips.add(ProMidi.clip(label, proNotes(o.notes), config, pack, packId, startFrame, lengthFrames));
        Core.Track chords = null;
        if (addChords) {
            Synth.Config piano = Synth.factory(1); SampledInstruments.Pack ydp = null; String ydpId = null;
            try { for (SampledInstruments.Info info : a.instrumentLibrary.list()) if (info.filename.startsWith("factory-freepats-ydp-grand-compact/")) { ydp = a.instrumentLibrary.load(info); ydpId = info.filename; break; } } catch (Exception ignored) { ydp = null; ydpId = null; }
            chords = new Core.Track("Auto · Chords", 0xff3ec6e0); chords.volume = .55; polish(chords, AutoParts.MELODY);
            chords.clips.add(ProMidi.clip("Chords · " + an.describe(), proNotes(AutoParts.chordBlocks(an.chords, bpb, 66)), piano, ydp, ydpId, startFrame, lengthFrames));
        }
        ui.edit("Auto parts");
        if (chords != null) { project.tracks.add(chords); chordsAdded = true; }
        project.tracks.add(t); a.selected = t; a.clip = t.clips.get(0); a.save(false); a.refresh();
        a.toast("Added " + label + (chords != null ? " and matching chords" : "") + ". Open the clip to edit its notes, or tap ✎ in the track header to change the sound.");
    }
}
