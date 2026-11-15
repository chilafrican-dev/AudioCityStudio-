package ug.audiocity.studio;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;
import android.widget.*;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Professional-workstation screens: routing and sends, buses, insert slots, parametric EQ, master chain with loudness
 * metering, automation, takes and comping, slip/ripple edits, piano roll, tempo map and markers, recording settings
 * (low latency, monitoring, USB inputs, latency calibration), export formats, undo history, templates, channel presets,
 * autosave and crash recovery. Kept out of MainActivity so the existing screens stay unchanged.
 */
final class ProUi {
    final MainActivity a;
    ProUi(MainActivity activity) { a = activity; }

    static final int EXPORT_REQUEST = 30;
    final WeakHashMap<Core.Session, String> history = new WeakHashMap<>();
    String pendingLabel;
    boolean writeAutomation, showAutomation; String shownLane = "volume"; long lastWrite = -1; long lastAutomationToast;
    boolean bluetoothWarned; int exportFormat, exportNormalise; Runnable meterTick;

    SharedPreferences prefs() { return a.getPreferences(0); }
    int dp(float v) { return a.dp(v); }
    TextView label(String s, int size, int color, boolean bold) { return a.label(s, size, color, bold); }
    LinearLayout page() { LinearLayout p = a.column(); p.setPadding(dp(16), dp(8), dp(16), dp(8)); return p; }
    ScrollView scroll(View v) { ScrollView s = new ScrollView(a); s.addView(v); return s; }
    AlertDialog show(String title, View body) { return new AlertDialog.Builder(a).setTitle(title).setView(scroll(body)).setPositiveButton("Done", (d, w) -> { a.save(false); a.refresh(); }).show(); }
    Button button(String text, Runnable r) { return a.button(text, r); }
    void row(LinearLayout parent, View... views) { LinearLayout row = new LinearLayout(a); row.setGravity(Gravity.CENTER_VERTICAL); for (View v : views) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(42), 1); p.rightMargin = dp(4); row.addView(v, p); } LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(4); parent.addView(row, lp); }
    Switch toggle(String text, boolean on, MainActivity.Enabled e) { Switch s = new Switch(a); s.setText(text); s.setTextColor(MainActivity.TEXT); s.setChecked(on); s.setOnCheckedChangeListener((v, b) -> { a.checkpoint(); e.set(b); a.save(false); }); return s; }
    Spinner spinner(String[] items, int selected, IntAction picked) {
        Spinner s = new Spinner(a); s.setAdapter(new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, items)); s.setSelection(Math.max(0, Math.min(items.length - 1, selected)));
        final boolean[] ready = {false};
        s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { public void onItemSelected(AdapterView<?> p, View v, int i, long id) { if (ready[0]) picked.run(i); ready[0] = true; } public void onNothingSelected(AdapterView<?> p) {} });
        return s;
    }
    interface IntAction { void run(int i); }
    MainActivity.Knob knob(String name, double min, double max, String unit, MainActivity.Value v) { return a.new Knob(name, min, max, unit, v); }
    String time(long frames) { long ms = frames * 1000 / Core.RATE; return String.format(Locale.US, "%d:%02d.%03d", ms / 60000, (ms / 1000) % 60, ms % 1000); }
    String position(long frames) { return new Pro.TempoMap(a.session).label(frames) + "  (" + time(frames) + ")"; }
    String label() { String l = pendingLabel == null ? "Edit" : pendingLabel; pendingLabel = null; return l + " · " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()); }
    void edit(String what) { pendingLabel = what; a.checkpoint(); }

    // ---------------------------------------------------------------- entry points

    void menu() {
        if (!a.canEdit()) return;
        String[] items = {"Routing, sends & sidechain", "Insert slots (selected track)", "Buses (reverb, delay, groups)", "Master chain & loudness meter", "Automation", "Tempo, time signature & markers", "Recording & audio settings", "Undo history", "Project templates", "Channel presets", "Editing: crossfades & ripple", "Track folders", "Loop & edit range…", "Range edit (cut, copy, paste, insert time)…", "Project notes", "Duplicate this project", "Keyboard shortcuts", "Clip launcher (scenes)", "MIDI controller mapping", "Get loops online (free CC0)"};
        new AlertDialog.Builder(a).setTitle("Pro tools").setItems(items, (d, i) -> {
            switch (i) {
                case 0: if (needTrack()) routing(a.selected); break;
                case 1: if (needTrack()) inserts(a.selected.inserts, a.selected.name + " · inserts"); break;
                case 2: buses(); break;
                case 3: master(); break;
                case 4: automation(); break;
                case 5: tempo(); break;
                case 6: audioSettings(); break;
                case 7: undoHistory(); break;
                case 8: templates(); break;
                case 9: if (needTrack()) presets(a.selected); break;
                case 10: editing(); break;
                case 11: folders(); break;
                case 12: loopRange(); break;
                case 13: rangeEdit(); break;
                case 14: notes(); break;
                case 15: duplicateProject(); break;
                case 16: shortcuts(); break;
                case 17: launcher(); break;
                case 18: midiMapping(); break;
                default: onlineLoops(); break;
            }
        }).show();
    }
    boolean needTrack() { if (a.selected == null) { a.toast("Select a track first."); return false; } return true; }

    /** Extra items for the track menu. */
    String[] trackItems(Core.Track t) { return new String[]{"Routing, sends & sidechain", "Insert slots", "Automation", "Channel presets", t.folder == null ? "Put in a folder…" : "Folder: " + t.folder + " · change…", t.autoCrossfade ? "Overlap crossfades: ON (tap to turn off)" : "Overlap crossfades: OFF (tap to turn on)", "New MIDI clip at playhead (4 bars)…"}; }
    void trackItem(Core.Track t, int i) {
        switch (i) {
            case 0: routing(t); break; case 1: inserts(t.inserts, t.name + " · inserts"); break; case 2: automationFor(t); break; case 3: presets(t); break; case 4: chooseFolder(t); break; case 6: newMidiClip(t); break;
            default: edit("Crossfades"); t.autoCrossfade = !t.autoCrossfade; a.save(false); a.refresh(); a.toast(t.autoCrossfade ? "Overlapping clips now crossfade; the later clip plays on top." : "Overlapping clips now mix together."); }
    }

    /** Extra items for the clip menu. */
    void clipTools(Core.Track t, Core.Clip c) {
        ArrayList<String> items = new ArrayList<>(Arrays.asList("Takes & comping…", "Slip audio inside clip…", "Ripple delete (this track)", "Ripple delete (all tracks)", "Nudge…", "Duplicate several times…", "Normalise clip to −1 dBFS", "Reverse clip", "Strip silence…", "Copy clip", "Paste at playhead", "Set loop range to this clip", "Bounce track to new audio track…", c.notes != null ? "Change instrument…" : "Time-stretch…", "Quantize audio timing…", "Put in clip-launcher slot…"));
        if (c.drumPattern != null) { items.add("Edit BeatPilot drum piano roll…"); items.add("Edit BeatPilot 16-step grid…"); }
        if (c.drumPattern == null && (c.midiFile != null || c.notes != null)) items.add(c.notes != null ? "Edit notes (piano roll)…" : "Edit MIDI notes (piano roll)…");
        if (c.drumPattern == null && c.midiFile != null && c.notes == null) items.add("Convert to live MIDI clip");
        if (c.midiFile == null && c.notes == null) items.add("Vocal editor (notes, pitch, timing)…");
        new AlertDialog.Builder(a).setTitle(c.name).setItems(items.toArray(new String[0]), (d, i) -> {
            if (items.get(i).startsWith("Edit BeatPilot drum piano")) { try { new BeatPilotDrumPianoRoll(a,t,c).show(); } catch (RuntimeException ex) { a.error("Drum piano roll",ex.getMessage()); } return; }
            if (items.get(i).startsWith("Edit BeatPilot 16-step")) { if(c.drumPattern.startsWith("v3|")){a.toast("Freehand MIDI roll notes cannot be represented in a 16-step grid. Use Drum piano roll.");return;} new BeatPilotDrumScreen(a).openClip(c); return; }
            if (items.get(i).startsWith("Edit notes") || items.get(i).startsWith("Edit MIDI notes")) { pianoRoll(t,c); return; }
            if (items.get(i).startsWith("Convert to live MIDI")) { convertToLive(t,c); return; }
            if (items.get(i).startsWith("Vocal editor")) { vocalEditor(t, c); return; }
            if (i == 0) takes(t, c);
            else if (i == 1) slip(c);
            else if (i == 2 || i == 3) { a.engine.pause(); edit("Ripple delete"); Pro.rippleDelete(a.session, t, c, i == 3); a.clip = null; a.save(false); a.refresh(); }
            else if (i == 4) nudge(c);
            else if (i == 5) a.input("How many copies after this clip?", "3", true, v -> { int n = (int) Double.parseDouble(v); if (n < 1 || n > 64) throw new IllegalArgumentException("Use 1–64."); edit("Duplicate"); ProEdit.duplicate(t, c, n); a.save(false); a.refresh(); });
            else if (i == 6) { edit("Normalise clip"); double ch = ProEdit.normalise(c, -1); a.save(false); a.refresh(); a.toast(String.format(Locale.US, "Clip gain changed by %+.1f dB.", ch)); }
            else if (i == 7) reverse(t, c);
            else if (i == 8) stripSilence(t, c);
            else if (i == 9) { clipboard = ProEdit.copy(a.session, Collections.singletonList(t), c.start, c.start + c.length); clipboardLength = c.length; a.toast("Clip copied. Paste at the playhead from the clip menu or with Ctrl+V."); }
            else if (i == 10) pasteAtPlayhead();
            else if (i == 11) { edit("Loop range"); a.session.loopStart = c.start; a.session.loopEnd = c.start + c.length; a.engine.loop = true; a.save(false); a.refresh(); }
            else if (i == 12) bounceTrack(t);
            else if (i == 13) { if (c.notes != null) chooseInstrument(c); else stretch(t, c); }
            else if (i == 14) quantizeAudio(t, c);
            else if (i == 15) putInSlot(t, c);
            else if (i == 16) pianoRoll(t, c);
            else convertToLive(t, c);
        }).show();
    }

    // ---------------------------------------------------------------- routing, sends, sidechain, groups

    void routing(Core.Track t) {
        LinearLayout p = page(); Core.Session s = a.session;
        p.addView(label("OUTPUT", 11, MainActivity.DIM, true));
        String[] outs = new String[s.buses.size() + 1]; outs[0] = "Stereo output (master)"; int sel = 0;
        for (int i = 0; i < s.buses.size(); i++) { outs[i + 1] = "Bus: " + s.buses.get(i).name; if (s.buses.get(i).id.equals(t.output)) sel = i + 1; }
        p.addView(spinner(outs, sel, i -> { edit("Output"); t.output = i == 0 ? null : s.buses.get(i - 1).id; a.save(false); }));
        p.addView(label("SENDS", 11, MainActivity.DIM, true));
        if (s.buses.isEmpty()) { p.addView(label("No buses yet. Create a reverb or delay bus, then send tracks to it.", 12, MainActivity.TEXT, false)); p.addView(button("Create reverb bus", () -> { addBus("Reverb", true, false); a.toast("Reverb bus added. Open Routing again to send to it."); })); }
        for (Core.Track bus : s.buses) {
            Pro.Send existing = null; for (Pro.Send x : t.sends) if (x.bus.equals(bus.id)) existing = x;
            final Pro.Send[] send = {existing};
            LinearLayout card = a.column(); card.setBackground(a.round(MainActivity.PANEL, 14)); card.setPadding(dp(12), dp(8), dp(12), dp(8)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(6); p.addView(card, lp);
            card.addView(toggle("Send to " + bus.name, existing != null, new MainActivity.Enabled() { public boolean get() { return send[0] != null; } public void set(boolean b) { if (b && send[0] == null) { send[0] = new Pro.Send(bus.id, .5, false); t.sends.add(send[0]); } else if (!b && send[0] != null) { t.sends.remove(send[0]); send[0] = null; } } }));
            card.addView(toggle("Pre-fader (ignores this channel's fader)", existing != null && existing.pre, new MainActivity.Enabled() { public boolean get() { return send[0] != null && send[0].pre; } public void set(boolean b) { if (send[0] != null) send[0].pre = b; } }));
            a.knobs(card, knob("SEND LEVEL", 0, 2, "×", new MainActivity.Value() { public double get() { return send[0] == null ? 0 : send[0].level; } public void set(double v) { if (send[0] == null) { send[0] = new Pro.Send(bus.id, v, false); t.sends.add(send[0]); } send[0].level = v; } }));
        }
        p.addView(label("SIDECHAIN (compressor key)", 11, MainActivity.DIM, true));
        ArrayList<Core.Track> keys = new ArrayList<>(); for (Core.Track x : s.tracks) if (x != t) keys.add(x);
        String[] keyNames = new String[keys.size() + 1]; keyNames[0] = "None (compress from this track)"; int keySel = 0;
        for (int i = 0; i < keys.size(); i++) { keyNames[i + 1] = keys.get(i).name; if (keys.get(i).id.equals(t.sidechain)) keySel = i + 1; }
        p.addView(spinner(keyNames, keySel, i -> { edit("Sidechain"); t.sidechain = i == 0 ? null : keys.get(i - 1).id; if (i > 0 && !t.fx.comp) { t.fx.comp = true; a.toast("Compressor turned on so the key can duck this track."); } a.save(false); }));
        TextView hint = label("Example: key a bass or pad from the kick to make room for it. The key works even if its track is muted.", 10, MainActivity.DIM, false); p.addView(hint);
        p.addView(label("FADER GROUP", 11, MainActivity.DIM, true));
        p.addView(button(t.group == null ? "Not grouped · tap to set a group name" : "Group: " + t.group + " · tap to change", () -> a.input("Group name (empty = none)", t.group == null ? "" : t.group, false, v -> { edit("Group"); t.group = v.trim().isEmpty() ? null : v.trim().substring(0, Math.min(40, v.trim().length())); a.save(false); a.toast(t.group == null ? "Track removed from its group." : "Faders in group “" + t.group + "” now move together."); })));
        show(t.name + " · routing", p);
    }

    Core.Track addBus(String name, boolean reverb, boolean delay) {
        if (a.session.buses.size() >= Core.MAX_BUSES) { a.toast("This build supports " + Core.MAX_BUSES + " buses."); return null; }
        edit("Add bus"); Core.Track b = new Core.Track(name, 0xffc9b06b); b.bus = true; b.volume = .8; b.fx.eq = false; b.fx.wet = true;
        if (reverb) { b.fx.room = true; b.fx.roomMix = .5; }
        if (delay) { b.fx.delay = true; b.fx.delayMix = .5; b.fx.delayFeedback = .4; }
        a.session.buses.add(b); a.save(false); return b;
    }

    // ---------------------------------------------------------------- buses

    void buses() {
        LinearLayout p = page(); Core.Session s = a.session;
        p.addView(label("Buses receive sends and routed tracks. Reverb and delay buses are 100% wet: blend them with each track's send level.", 11, MainActivity.DIM, false));
        row(p, button("+ Reverb bus", () -> { addBus("Reverb", true, false); reopen(this::buses); }), button("+ Delay bus", () -> { addBus("Delay", false, true); reopen(this::buses); }), button("+ Group bus", () -> { Core.Track b = addBus("Group", false, false); if (b != null) b.fx.wet = false; reopen(this::buses); }));
        for (Core.Track b : s.buses) {
            LinearLayout card = a.column(); card.setBackground(a.round(MainActivity.PANEL, 14)); card.setPadding(dp(12), dp(8), dp(12), dp(8)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(8); p.addView(card, lp);
            int users = 0; for (Core.Track t : s.tracks) { if (b.id.equals(t.output)) users++; for (Pro.Send x : t.sends) if (x.bus.equals(b.id)) users++; }
            card.addView(label(b.name + "  ·  " + users + " source" + (users == 1 ? "" : "s"), 14, MainActivity.TEXT, true));
            a.knobs(card, knob("VOLUME", 0, 2, "×", val(() -> b.volume, v -> b.volume = v)), knob("PAN", -1, 1, "", val(() -> b.pan, v -> b.pan = v)),
                    knob("ROOM", 0, .6, "%", val(() -> b.fx.roomMix, v -> b.fx.roomMix = v)), knob("DELAY", 0, .6, "%", val(() -> b.fx.delayMix, v -> b.fx.delayMix = v)));
            LinearLayout sw = new LinearLayout(a); card.addView(sw);
            sw.addView(toggle("Mute", b.mute, en(() -> b.mute, v -> b.mute = v)), new LinearLayout.LayoutParams(0, -2, 1));
            sw.addView(toggle("Room", b.fx.room, en(() -> b.fx.room, v -> b.fx.room = v)), new LinearLayout.LayoutParams(0, -2, 1));
            sw.addView(toggle("Delay", b.fx.delay, en(() -> b.fx.delay, v -> b.fx.delay = v)), new LinearLayout.LayoutParams(0, -2, 1));
            row(card, button("Inserts (" + b.inserts.size() + ")", () -> inserts(b.inserts, b.name + " · inserts")), button("Rename", () -> a.input("Bus name", b.name, false, v -> { edit("Rename bus"); b.name = v.trim().isEmpty() ? "Bus" : v.trim(); a.save(false); reopen(this::buses); })),
                    button("Remove", () -> { edit("Remove bus"); s.buses.remove(b); for (Core.Track t : s.tracks) { if (b.id.equals(t.output)) t.output = null; t.sends.removeIf(x -> x.bus.equals(b.id)); } a.save(false); reopen(this::buses); }));
        }
        lastDialog = show("Buses", p);
    }
    AlertDialog lastDialog;
    void reopen(Runnable r) { if (lastDialog != null) { try { lastDialog.dismiss(); } catch (Exception ignored) {} lastDialog = null; } r.run(); }
    interface Getter { double get(); } interface Setter { void set(double v); } interface BoolGet { boolean get(); } interface BoolSet { void set(boolean v); }
    MainActivity.Value val(Getter g, Setter s) { return new MainActivity.Value() { public double get() { return g.get(); } public void set(double v) { s.set(v); } }; }
    MainActivity.Enabled en(BoolGet g, BoolSet s) { return new MainActivity.Enabled() { public boolean get() { return g.get(); } public void set(boolean b) { s.set(b); } }; }

    // ---------------------------------------------------------------- insert slots

    void inserts(List<Pro.Insert> list, String title) {
        LinearLayout p = page();
        p.addView(label("Signal flows top to bottom, after the channel strip (low cut → EQ → dynamics → space). Drag-free: use ▲ ▼ to reorder.", 11, MainActivity.DIM, false));
        for (int idx = 0; idx < list.size(); idx++) {
            Pro.Insert ins = list.get(idx); final int at = idx;
            LinearLayout card = a.column(); card.setBackground(a.round(MainActivity.PANEL, 14)); card.setPadding(dp(12), dp(6), dp(12), dp(6)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(6); p.addView(card, lp);
            LinearLayout head = new LinearLayout(a); head.setGravity(Gravity.CENTER_VERTICAL); card.addView(head);
            head.addView(label((at + 1) + "  " + ins.info().title, 13, MainActivity.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
            head.addView(toggle("", ins.enabled, en(() -> ins.enabled, v -> ins.enabled = v)));
            row(card, button("Edit", () -> editInsert(ins)), button("▲", () -> { if (at > 0) { edit("Reorder inserts"); list.remove(at); list.add(at - 1, ins); a.save(false); reopen(() -> inserts(list, title)); } }),
                    button("▼", () -> { if (at < list.size() - 1) { edit("Reorder inserts"); list.remove(at); list.add(at + 1, ins); a.save(false); reopen(() -> inserts(list, title)); } }),
                    button("✕", () -> { edit("Remove insert"); list.remove(ins); a.save(false); reopen(() -> inserts(list, title)); }));
        }
        p.addView(button("+ Add insert", () -> {
            if (list.size() >= ProjectIO.MAX_INSERTS) { a.toast("Up to " + ProjectIO.MAX_INSERTS + " inserts per channel."); return; }
            ArrayList<Pro.Kind> kinds = new ArrayList<>(Pro.KINDS.values()); String[] names = new String[kinds.size()]; for (int i = 0; i < names.length; i++) names[i] = kinds.get(i).title;
            new AlertDialog.Builder(a).setTitle("Add insert").setItems(names, (d, i) -> { edit("Add insert"); Pro.Insert n = new Pro.Insert(kinds.get(i).id); list.add(n); a.save(false); reopen(() -> inserts(list, title)); editInsert(n); }).show();
        }));
        lastDialog = show(title, p);
    }

    void editInsert(Pro.Insert ins) {
        if (ins.kind.equals(Pro.EQ8)) { eqEditor(ins); return; }
        LinearLayout p = page(); Pro.Kind k = ins.info(); ArrayList<MainActivity.Knob> pending = new ArrayList<>();
        for (int i = 0; i < k.names.length; i++) {
            final int index = i; boolean binary = k.min[i] == 0 && k.max[i] == 1 && k.units[i].isEmpty();
            if (binary) { p.addView(toggle(title(k.names[i]), ins.values[i] >= .5, en(() -> ins.values[index] >= .5, v -> { ins.set(index, v ? 1 : 0); ins.touch(); }))); continue; }
            pending.add(knob(k.names[i], k.min[i], k.max[i], k.units[i].equals("%") ? "pct" : k.units[i], val(() -> ins.values[index], v -> { ins.set(index, v); ins.touch(); })));
            if (pending.size() == 3) { a.knobs(p, pending.toArray(new MainActivity.Knob[0])); pending.clear(); }
        }
        if (!pending.isEmpty()) a.knobs(p, pending.toArray(new MainActivity.Knob[0]));
        String fxHelp = ProFx2.help(ins.kind); if (fxHelp != null) p.addView(label(fxHelp, 10, MainActivity.DIM, false));
        if (ins.kind.equals(Pro.LIMITER)) p.addView(label("Look-ahead delays this channel by the look-ahead time. Exports compensate for master-chain look-ahead automatically.", 10, MainActivity.DIM, false));
        if (ins.kind.equals(Pro.MULTIBAND)) p.addView(label("Linkwitz–Riley crossovers. With every ratio at 1:1 and gains at 0 dB the band split is transparent.", 10, MainActivity.DIM, false));
        new AlertDialog.Builder(a).setTitle(k.title).setView(scroll(p)).setPositiveButton("Done", (d, w) -> a.save(false)).show();
    }
    static String title(String s) { return s.substring(0, 1) + s.substring(1).toLowerCase(Locale.ROOT); }

    static final String[] EQ_TYPES = {"Bell", "Low shelf", "High shelf", "Low cut 24 dB/oct", "High cut 24 dB/oct", "Notch"};
    void eqEditor(Pro.Insert ins) {
        LinearLayout p = page(); EqCurve curve = new EqCurve(ins); p.addView(curve, new LinearLayout.LayoutParams(-1, dp(190)));
        LinearLayout controls = a.column(); p.addView(controls);
        curve.onSelect = band -> fillBand(controls, ins, band, curve);
        fillBand(controls, ins, 2, curve);
        new AlertDialog.Builder(a).setTitle("Parametric EQ · 8 band").setView(scroll(p)).setPositiveButton("Done", (d, w) -> a.save(false)).show();
    }
    void fillBand(LinearLayout box, Pro.Insert ins, int band, EqCurve curve) {
        box.removeAllViews(); curve.selected = band; curve.invalidate(); int o = band * 5;
        String[] bands = new String[Pro.EQ_BANDS]; for (int i = 0; i < bands.length; i++) bands[i] = "Band " + (i + 1) + (ins.values[i * 5] >= .5 ? "" : " (off)");
        box.addView(spinner(bands, band, b -> fillBand(box, ins, b, curve)));
        LinearLayout r = new LinearLayout(a); r.setGravity(Gravity.CENTER_VERTICAL); box.addView(r);
        r.addView(toggle("On", ins.values[o] >= .5, en(() -> ins.values[o] >= .5, v -> { ins.set(o, v ? 1 : 0); ins.touch(); curve.invalidate(); })), new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(spinner(EQ_TYPES, (int) Math.round(ins.values[o + 1]), t -> { a.checkpoint(); ins.set(o + 1, t); ins.touch(); curve.invalidate(); a.save(false); }), new LinearLayout.LayoutParams(0, -2, 2));
        a.knobs(box, knob("FREQ", 20, 20000, "Hz", val(() -> ins.values[o + 2], v -> { ins.set(o + 2, v); ins.touch(); curve.invalidate(); })),
                knob("GAIN", -18, 18, "dB", val(() -> ins.values[o + 3], v -> { ins.set(o + 3, v); ins.touch(); curve.invalidate(); })),
                knob("Q", .2, 12, "", val(() -> ins.values[o + 4], v -> { ins.set(o + 4, v); ins.touch(); curve.invalidate(); })));
        box.addView(label("Drag a band's dot on the curve: left/right = frequency, up/down = gain. Tap a knob for exact values.", 10, MainActivity.DIM, false));
    }

    /** Frequency-response display with draggable band handles. */
    final class EqCurve extends View {
        final Pro.Insert ins; final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); int selected = 2, dragging = -1; IntAction onSelect; boolean moved;
        EqCurve(Pro.Insert i) { super(a); ins = i; setContentDescription("Parametric EQ response curve"); }
        float x(double hz, float w) { return (float) (Math.log10(hz / 20) / 3 * w); }
        double hz(float x, float w) { return 20 * Math.pow(10, 3 * Math.max(0, Math.min(1, x / w))); }
        float y(double db, float h) { return (float) (h / 2 - db / 24 * (h / 2 - dp(8))); }
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(); c.drawColor(0xff20272b); p.setStrokeWidth(dp(1)); p.setStyle(Paint.Style.STROKE);
            p.setColor(0xff3a4249); for (double f : new double[]{50, 100, 200, 500, 1000, 2000, 5000, 10000}) c.drawLine(x(f, w), 0, x(f, w), h, p);
            for (int db = -18; db <= 18; db += 6) c.drawLine(0, y(db, h), w, y(db, h), p);
            p.setColor(0xff657079); c.drawLine(0, y(0, h), w, y(0, h), p);
            Path path = new Path(); for (int i = 0; i <= (int) w; i += 2) { float yy = y(Math.max(-24, Math.min(24, ins.response(hz(i, w)))), h); if (i == 0) path.moveTo(i, yy); else path.lineTo(i, yy); }
            p.setColor(MainActivity.ACCENT); p.setStrokeWidth(dp(2)); c.drawPath(path, p); p.setStyle(Paint.Style.FILL); p.setTextSize(dp(9));
            for (int b = 0; b < Pro.EQ_BANDS; b++) { int o = b * 5; if (ins.values[o] < .5) continue; int type = (int) Math.round(ins.values[o + 1]); double g = type == 3 || type == 4 || type == 5 ? 0 : ins.values[o + 3];
                float bx = x(ins.values[o + 2], w), by = y(g, h); p.setColor(b == selected ? 0xffffffff : 0xff9fb8c8); c.drawCircle(bx, by, dp(b == selected ? 9 : 7), p); p.setColor(0xff20272b); p.setTextAlign(Paint.Align.CENTER); c.drawText(String.valueOf(b + 1), bx, by + dp(3), p); }
            p.setColor(MainActivity.DIM); p.setTextAlign(Paint.Align.LEFT); c.drawText("20", dp(2), h - dp(3), p); c.drawText("1k", x(1000, w) + dp(2), h - dp(3), p); c.drawText("20k Hz", w - dp(40), h - dp(3), p); c.drawText("+18 dB", dp(2), dp(11), p);
        }
        public boolean onTouchEvent(MotionEvent e) {
            float w = getWidth(), h = getHeight();
            if (e.getAction() == MotionEvent.ACTION_DOWN) { dragging = -1; moved = false; float best = dp(28);
                for (int b = 0; b < Pro.EQ_BANDS; b++) { int o = b * 5; if (ins.values[o] < .5) continue; int type = (int) Math.round(ins.values[o + 1]); double g = type >= 3 ? 0 : ins.values[o + 3]; float d = (float) Math.hypot(e.getX() - x(ins.values[o + 2], w), e.getY() - y(g, h)); if (d < best) { best = d; dragging = b; } }
                getParent().requestDisallowInterceptTouchEvent(dragging >= 0); return true; }
            if (e.getAction() == MotionEvent.ACTION_MOVE && dragging >= 0) { if (!moved) { a.checkpoint(); moved = true; } int o = dragging * 5; ins.set(o + 2, hz(e.getX(), w)); int type = (int) Math.round(ins.values[o + 1]); if (type < 3) ins.set(o + 3, (h / 2 - e.getY()) / (h / 2 - dp(8)) * 24); ins.touch(); invalidate(); return true; }
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) { getParent().requestDisallowInterceptTouchEvent(false); if (dragging >= 0 && onSelect != null) onSelect.run(dragging); if (moved) a.save(false); dragging = -1; return true; }
            return true;
        }
    }

    // ---------------------------------------------------------------- master chain and loudness

    void master() {
        LinearLayout p = page(); Core.Session s = a.session;
        TextView meter = label("Press Play to meter loudness.", 13, MainActivity.TEXT, true); meter.setTypeface(android.graphics.Typeface.MONOSPACE); p.addView(meter);
        Spectrum spectrum = new Spectrum(); p.addView(spectrum, new LinearLayout.LayoutParams(-1, dp(120)));
        p.addView(label("REFERENCE A/B", 11, MainActivity.DIM, true));
        p.addView(label(s.reference == null ? "Load a finished song you like to compare against, level-matched to your mix." : "Reference: " + s.reference.name + String.format(Locale.US, " · matched %+.1f dB", Pro.db(s.referenceGain)), 11, MainActivity.TEXT, false));
        row(p, button(s.reference == null ? "Load reference…" : "Replace reference…", this::loadReference), button(s.referenceOn ? "▶ Hearing REFERENCE · tap for mix" : "▶ Hearing MIX · tap for reference", () -> { if (s.reference == null) { a.toast("Load a reference first."); return; } s.referenceOn = !s.referenceOn; a.toast(s.referenceOn ? "Playing the reference (master chain bypassed)." : "Playing your mix."); reopen(this::master); }));
        if (s.reference != null) row(p, button("Re-match loudness", this::matchReference), button("Remove reference", () -> { edit("Remove reference"); s.reference = null; s.referenceOn = false; a.save(false); reopen(this::master); }));
        p.addView(label("Integrated loudness is measured from when playback started (EBU R128 gating). Streaming services commonly target around −14 LUFS with true peaks below −1 dBTP.", 10, MainActivity.DIM, false));
        row(p, button("Reset meter", () -> { Core.Renderer r = a.engine.live; if (r != null) r.loudness.reset(); }), button("Analyse whole mix", () -> analyseMix()));
        p.addView(label("MASTER INSERTS (" + s.masterInserts.size() + ")", 11, MainActivity.DIM, true));
        for (Pro.Insert i : s.masterInserts) p.addView(label("•  " + i.info().title + (i.enabled ? "" : "  (bypassed)"), 12, MainActivity.TEXT, false));
        row(p, button("Edit master inserts", () -> inserts(s.masterInserts, "Master inserts")), button("Quick mastering chain", () -> {
            edit("Mastering chain"); s.masterInserts.clear(); Pro.Insert eq = new Pro.Insert(Pro.EQ8); eq.set(0, 1); eq.set(2, 30); eq.touch(); s.masterInserts.add(eq);
            Pro.Insert mb = new Pro.Insert(Pro.MULTIBAND); s.masterInserts.add(mb); Pro.Insert lim = new Pro.Insert(Pro.LIMITER); lim.set(0, 3); lim.set(1, -1); s.masterInserts.add(lim); a.save(false);
            a.toast("Added low cut, gentle multiband and a −1 dBTP limiter. Adjust the limiter input to taste while watching LUFS.");
        }));
        row(p, button("Mastering preset…", this::masteringPresets));
        AlertDialog d = show("Master · loudness", p); lastDialog = d;
        meterTick = new Runnable() { public void run() {
            if (!d.isShowing()) return; Core.Renderer r = a.engine.live;
            if (r != null && a.engine.playing) { Pro.Loudness m = r.loudness;
                meter.setText(String.format(Locale.US, "Momentary   %6s LUFS\nShort-term  %6s LUFS\nIntegrated  %6s LUFS\nRange       %6.1f LU\nTrue peak   %6s dBTP\nLimiter GR  %6.1f dB\nCorrelation %+6.2f%s", f(m.momentary), f(m.shortTermLufs), f(m.integrated()), m.range(), f(m.truePeakDb), Math.max(r.masterReduction, r.limiterDb), r.correlation, r.correlation < 0 ? "  ⚠ phase" : "")); spectrum.invalidate(); }
            a.handler.postDelayed(this, 250); } };
        a.handler.post(meterTick);
    }
    static String f(double v) { return Double.isFinite(v) ? String.format(Locale.US, "%.1f", v) : "−∞"; }
    void analyseMix() {
        if (!a.canEdit()) return; Core.Session snap = a.session.copy();
        a.busy("Measuring the whole mix…", () -> { long frames = Math.min(Core.MAX_FRAMES, snap.end() + Export.tailFrames(snap)); Pro.Loudness m = Pro.analyse(snap, frames);
            a.runOnUiThread(() -> a.error("Mix loudness", String.format(Locale.US, "Integrated: %s LUFS\nLoudness range: %.1f LU\nTrue peak: %s dBTP\n\nTo reach −14 LUFS: %+.1f dB (limited by a −1 dBTP ceiling). Export can apply this automatically.", f(m.integrated()), m.range(), f(m.truePeakDb), Pro.normaliseGain(m, -14, -1)))); });
    }

    // ---------------------------------------------------------------- automation

    void automation() {
        String[] who = new String[a.session.tracks.size() + 1]; who[0] = "Master volume"; for (int i = 0; i < a.session.tracks.size(); i++) who[i + 1] = a.session.tracks.get(i).name;
        new AlertDialog.Builder(a).setTitle("Automate which channel?").setItems(who, (d, i) -> { if (i == 0) masterAutomation(); else automationFor(a.session.tracks.get(i - 1)); }).show();
    }
    String[] params(Core.Track t) { ArrayList<String> p = new ArrayList<>(Arrays.asList(Pro.AUTOMATABLE)); for (Pro.Send x : t.sends) p.add("send:" + x.bus); return p.toArray(new String[0]); }
    String paramName(String param) { for (int i = 0; i < Pro.AUTOMATABLE.length; i++) if (Pro.AUTOMATABLE[i].equals(param)) return Pro.AUTOMATABLE_NAMES[i]; if (param.startsWith("send:")) { Core.Track b = a.session.bus(param.substring(5)); return "Send → " + (b == null ? "?" : b.name); } return param; }
    double current(Core.Track t, String param) {
        Core.Fx f = t.fx;
        switch (param) { case "volume": return t.volume; case "pan": return t.pan; case "fx.inputDb": return f.inputDb; case "fx.outputDb": return f.outputDb; case "fx.low": return f.low; case "fx.mid": return f.mid; case "fx.high": return f.high; case "fx.threshold": return f.threshold; case "fx.ratio": return f.ratio; case "fx.deessAmount": return f.deessAmount; case "fx.drive": return f.drive; case "fx.delayMix": return f.delayMix; case "fx.roomMix": return f.roomMix; case "fx.delayFeedback": return f.delayFeedback; }
        if (param.startsWith("send:")) for (Pro.Send x : t.sends) if (param.equals("send:" + x.bus)) return x.level;
        return 0;
    }

    void automationFor(Core.Track t) {
        String[] ps = params(t); String[] names = new String[ps.length];
        for (int i = 0; i < ps.length; i++) { Pro.Lane l = t.automation.get(ps[i]); names[i] = paramName(ps[i]) + (l != null && l.size() > 0 ? "  · " + l.size() + " pts" : ""); }
        new AlertDialog.Builder(a).setTitle(t.name + " · automate").setItems(names, (d, i) -> laneEditor(t, ps[i])).show();
    }
    void masterAutomation() { Core.Session s = a.session; if (s.masterAutomation == null) { edit("Master automation"); s.masterAutomation = new Pro.Lane("volume"); } laneDialog(null, s.masterAutomation, "Master volume"); }
    void laneEditor(Core.Track t, String param) { Pro.Lane l = t.automation.get(param); if (l == null) { l = new Pro.Lane(param); t.automation.put(param, l); } laneDialog(t, l, paramName(param)); }

    void laneDialog(Core.Track t, Pro.Lane lane, String name) {
        LinearLayout p = page(); double[] range = Pro.range(lane.param);
        p.addView(label(String.format(Locale.US, "%s · range %.2f to %.2f", name, range[0], range[1]), 12, MainActivity.DIM, true));
        p.addView(toggle("Lane active (overrides the control while it has points)", lane.enabled, en(() -> lane.enabled, v -> lane.enabled = v)));
        p.addView(toggle("Write mode: record mixer fader/pan moves while playing", writeAutomation, en(() -> writeAutomation, v -> { writeAutomation = v; lastWrite = -1; })));
        if (t != null) p.addView(toggle("Show this lane on the arrangement", showAutomation && shownLane.equals(lane.param) && a.selected == t, en(() -> showAutomation, v -> { showAutomation = v; shownLane = lane.param; a.selected = t; })));
        LinearLayout list = a.column(); p.addView(list); fillPoints(list, t, lane);
        row(p, button("+ Point at playhead", () -> { double v = t == null ? a.session.master : current(t, lane.param); double now = lane.at(a.engine.position); edit("Automation point"); lane.add(a.engine.position, Double.isNaN(now) ? v : now); a.save(false); fillPoints(list, t, lane); }),
                button("Set value at playhead…", () -> a.input("Value (" + range[0] + " to " + range[1] + ")", String.format(Locale.US, "%.3f", Double.isNaN(lane.at(a.engine.position)) ? (t == null ? a.session.master : current(t, lane.param)) : lane.at(a.engine.position)), true, v -> { double x = Double.parseDouble(v); edit("Automation point"); lane.add(a.engine.position, x); a.save(false); fillPoints(list, t, lane); })));
        row(p, button("Clear lane", () -> { edit("Clear automation"); lane.clear(); a.save(false); fillPoints(list, t, lane); }), button("Remove lane", () -> { edit("Remove automation"); if (t == null) a.session.masterAutomation = null; else t.automation.remove(lane.param); a.save(false); a.toast("Lane removed; the control's own value applies again."); }));
        p.addView(label("Points interpolate linearly. Before the first point and after the last, the nearest point's value holds. Tap a point to delete it.", 10, MainActivity.DIM, false));
        show(name + " automation", p);
    }
    void fillPoints(LinearLayout list, Core.Track t, Pro.Lane lane) {
        list.removeAllViews(); if (lane.size() == 0) { list.addView(label("No points yet.", 12, MainActivity.TEXT, false)); return; }
        for (int i = 0; i < lane.size() && i < 200; i++) { final int index = i; Button b = button(String.format(Locale.US, "%s   →   %.3f", position(lane.frame(i)), lane.value(i)), () -> { edit("Delete automation point"); lane.remove(index); a.save(false); fillPoints(list, t, lane); }); list.addView(b, new LinearLayout.LayoutParams(-1, dp(40))); }
        if (lane.size() > 200) list.addView(label("…and " + (lane.size() - 200) + " more", 11, MainActivity.DIM, false));
    }

    /** Called by the mixer after a fader or pan move: group linking and automation writing. */
    void faderMoved(Core.Track t, String param, double before) {
        Core.Session s = a.session; double now = t == null ? s.master : param.equals("volume") ? t.volume : t.pan;
        if (t != null && t.group != null && param.equals("volume") && before > 1e-6) { double ratio = now / before; for (Core.Track o : s.tracks) if (o != t && t.group.equals(o.group)) o.volume = Math.max(0, Math.min(2, o.volume * ratio)); }
        if (t != null && t.group != null && param.equals("pan")) { double delta = now - before; for (Core.Track o : s.tracks) if (o != t && t.group.equals(o.group)) o.pan = Math.max(-1, Math.min(1, o.pan + delta)); }
        Pro.Lane lane = t == null ? s.masterAutomation : t.automation.get(param);
        if (writeAutomation && a.engine.playing) {
            if (lane == null) { lane = new Pro.Lane(param); if (t == null) s.masterAutomation = lane; else t.automation.put(param, lane); }
            long pos = a.engine.position; long from = lastWrite < 0 || lastWrite > pos || pos - lastWrite > Core.RATE ? pos : lastWrite; lane.write(from, pos, now); lastWrite = pos;
        } else if (lane != null && lane.enabled && lane.size() > 0 && SystemClock.uptimeMillis() - lastAutomationToast > 4000) {
            lastAutomationToast = SystemClock.uptimeMillis(); a.toast("This control is automated. Turn on write mode in Pro tools → Automation, or clear the lane.");
        }
        if (!a.engine.playing) lastWrite = -1;
    }
    /** The value the mixer should display (automation wins while a lane is active). */
    double shownVolume(Core.Track t) { Core.Session s = a.session; if (t == null) { Pro.Lane l = s.masterAutomation; double v = l == null ? Double.NaN : l.at(a.engine.position); return Double.isNaN(v) ? s.master : v; } return Pro.automated(t, "volume", a.engine.position, t.volume); }

    // ---------------------------------------------------------------- takes, comping, slip

    void takes(Core.Track t, Core.Clip c) {
        ArrayList<Core.Clip> list = new ArrayList<>(); for (Core.Clip k : t.takes) if (k.start < c.start + c.length && k.start + k.length > c.start) list.add(k);
        if (list.isEmpty()) { a.error("No takes here", "Every recording is kept as a take. Record again over this region, then choose the best parts of each take here."); return; }
        String[] names = new String[list.size()]; for (int i = 0; i < names.length; i++) { Core.Clip k = list.get(i); names[i] = (i + 1) + ". " + k.name + "  ·  " + time(k.start) + " – " + time(k.start + k.length); }
        new AlertDialog.Builder(a).setTitle("Choose a take").setItems(names, (d, i) -> compRange(t, c, list.get(i))).setNegativeButton("Cancel", null).show();
    }
    void compRange(Core.Track t, Core.Clip c, Core.Clip take) {
        long pos = a.engine.position, prev = 0, next = Core.MAX_FRAMES;
        for (Pro.Marker m : a.session.markers) { if (m.frame <= pos) prev = Math.max(prev, m.frame); else next = Math.min(next, m.frame); }
        final long mFrom = prev, mTo = next;
        String[] ranges = {"This clip's range (" + time(c.start) + " – " + time(c.start + c.length) + ")", "Between the markers around the playhead (" + time(mFrom) + " – " + (mTo == Core.MAX_FRAMES ? "end" : time(mTo)) + ")", "From the playhead to the end of the clip", "The whole take"};
        new AlertDialog.Builder(a).setTitle("Use " + take.name + " for…").setItems(ranges, (d, i) -> {
            long from = i == 0 ? c.start : i == 1 ? mFrom : i == 2 ? pos : take.start, to = i == 0 ? c.start + c.length : i == 1 ? mTo : i == 2 ? c.start + c.length : take.start + take.length;
            try { a.engine.pause(); edit("Comp take"); a.clip = Pro.comp(t, take, from, to, a.session.crossfadeFrames); a.save(false); a.refresh(); a.toast("Comped with " + Math.round(a.session.crossfadeFrames * 1000.0 / Core.RATE) + " ms crossfades. Undo restores the previous comp."); }
            catch (Exception e) { a.error("Comp", e.getMessage()); }
        }).show();
    }
    void slip(Core.Clip c) {
        LinearLayout p = page(); TextView info = label("", 13, MainActivity.TEXT, true); p.addView(info);
        Runnable update = () -> info.setText("Source offset " + time(c.offset) + "  ·  clip stays at " + time(c.start));
        update.run(); long beat = Core.beat(a.session.bpm); final boolean[] first = {true};
        IntAction nudge = frames -> { if (first[0]) { edit("Slip"); first[0] = false; } Pro.slip(c, frames); update.run(); a.save(false); };
        row(p, button("− beat", () -> nudge.run((int) -beat)), button("− 10 ms", () -> nudge.run(-480)), button("− 1 ms", () -> nudge.run(-48)));
        row(p, button("+ 1 ms", () -> nudge.run(48)), button("+ 10 ms", () -> nudge.run(480)), button("+ beat", () -> nudge.run((int) beat)));
        p.addView(label("Slip moves the audio inside the clip without moving the clip or its fades. “+” moves the audio later in time, “−” earlier.", 10, MainActivity.DIM, false));
        show("Slip " + c.name, p);
    }

    // ---------------------------------------------------------------- editing settings

    void editing() {
        LinearLayout p = page(); Core.Session s = a.session;
        a.knobs(p, knob("CROSSFADE", 1, 200, "ms", val(() -> s.crossfadeFrames * 1000.0 / Core.RATE, v -> s.crossfadeFrames = (int) Math.max(48, Math.round(v * Core.RATE / 1000)))));
        p.addView(label("Crossfade length is used for comping and for overlapping clips on tracks with overlap crossfades turned on (track menu).", 10, MainActivity.DIM, false));
        if (a.selected != null) { Core.Track t = a.selected; p.addView(toggle("Overlap crossfades on " + t.name, t.autoCrossfade, en(() -> t.autoCrossfade, v -> t.autoCrossfade = v))); }
        p.addView(label("Slip, ripple delete and takes are in each clip's menu → Pro clip tools.", 10, MainActivity.DIM, false));
        show("Editing", p);
    }

    // ---------------------------------------------------------------- tempo map, time signature, markers

    void tempo() {
        LinearLayout p = page(); Core.Session s = a.session;
        p.addView(label("TIME SIGNATURE (beats per bar)", 11, MainActivity.DIM, true));
        String[] sigs = {"2 beats", "3 beats", "4 beats", "5 beats", "6 beats", "7 beats", "9 beats", "12 beats"}; int[] values = {2, 3, 4, 5, 6, 7, 9, 12}; int sel = 2; for (int i = 0; i < values.length; i++) if (values[i] == s.timeSignature) sel = i;
        p.addView(spinner(sigs, sel, i -> { edit("Time signature"); s.timeSignature = values[i]; a.save(false); a.refresh(); }));
        p.addView(label("The click accents the first beat of each bar; bar numbers on the ruler follow the time signature.", 10, MainActivity.DIM, false));
        p.addView(label("TEMPO CHANGES (base tempo " + s.bpm + " BPM)", 11, MainActivity.DIM, true));
        ArrayList<Pro.TempoChange> sorted = new ArrayList<>(s.tempoChanges); sorted.sort(Comparator.comparingInt(c -> c.bar));
        for (Pro.TempoChange c : sorted) p.addView(button("Bar " + (c.bar + 1) + " → " + c.bpm + " BPM   ✕", () -> { edit("Remove tempo change"); s.tempoChanges.remove(c); a.save(false); reopen(this::tempo); }), new LinearLayout.LayoutParams(-1, dp(40)));
        p.addView(button("+ Add tempo change", () -> a.input("Bar where the new tempo starts (2 or later)", "5", true, bar -> { int b = (int) Double.parseDouble(bar); if (b < 2) throw new IllegalArgumentException("Use bar 2 or later; the base tempo covers bar 1."); a.input("Tempo from bar " + b + " (40–240 BPM)", String.valueOf(s.bpm), true, bpm -> { int v = (int) Double.parseDouble(bpm); if (v < 40 || v > 240) throw new IllegalArgumentException("Use 40–240 BPM."); edit("Tempo change"); s.tempoChanges.removeIf(c -> c.bar == b - 1); s.tempoChanges.add(new Pro.TempoChange(b - 1, v)); a.save(false); reopen(this::tempo); }); })));
        p.addView(label("Tempo changes drive the click, the transport's bar display and snapping in Pro edits. Audio clips stay at their recorded speed.", 10, MainActivity.DIM, false));
        p.addView(label("MARKERS", 11, MainActivity.DIM, true));
        ArrayList<Pro.Marker> ms = new ArrayList<>(s.markers); ms.sort(Comparator.comparingLong(m -> m.frame));
        for (Pro.Marker m : ms) p.addView(button(m.name + "   ·   " + position(m.frame), () -> marker(m)), new LinearLayout.LayoutParams(-1, dp(40)));
        p.addView(button("+ Marker at playhead", () -> a.input("Marker name", "Section " + (s.markers.size() + 1), false, n -> { edit("Add marker"); s.markers.add(new Pro.Marker(a.engine.position, n.trim().isEmpty() ? "Marker" : n.trim())); a.save(false); a.refresh(); reopen(this::tempo); })));
        lastDialog = show("Tempo & markers", p);
    }
    void marker(Pro.Marker m) {
        new AlertDialog.Builder(a).setTitle(m.name).setItems(new String[]{"Jump here", "Move to playhead", "Rename", "Delete"}, (d, i) -> {
            if (i == 0) { a.engine.seek(m.frame); a.updateTransport(); if (a.timeline != null) a.timeline.invalidate(); }
            else if (i == 1) { edit("Move marker"); m.frame = a.engine.position; a.save(false); }
            else if (i == 2) a.input("Marker name", m.name, false, n -> { edit("Rename marker"); m.name = n.trim().isEmpty() ? "Marker" : n.trim(); a.save(false); });
            else { edit("Delete marker"); a.session.markers.remove(m); a.save(false); }
            reopen(this::tempo); a.refresh();
        }).show();
    }
    /** Ruler/clock label in bars using the tempo map. */
    String clock(long pos) { Pro.TempoMap m = new Pro.TempoMap(a.session); double b = m.beatAt(pos); int sig = m.beatsPerBar(); return String.format(Locale.US, "%03d : %d : %03d", (int) (b / sig) + 1, (int) (b % sig) + 1, (int) ((b - Math.floor(b)) * 960)); }

    /** Draws markers and the visible automation lane on the arrangement. */
    void drawTimeline(Canvas c, Paint p, float w, float h, float head, float ruler, float row, float beatPx) {
        Core.Session s = a.session; if (s == null) return; double beat = Core.beat(s.bpm);
        c.save(); c.clipRect(head, 0, w, h); p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.LEFT); p.setTextSize(dp(8));
        if (guideFrame >= 0) { float gx = head + (float) (guideFrame / beat * beatPx) - a.timelineScroll; p.setColor(0xffffd34d); c.drawRect(gx - dp(.75f), 0, gx + dp(.75f), h - dp(22), p); if (guideText != null) { p.setTextSize(dp(9)); float tw = p.measureText(guideText) + dp(8); p.setColor(0xffffd34d); c.drawRect(gx + dp(3), ruler + dp(2), gx + dp(3) + tw, ruler + dp(16), p); p.setColor(0xff24282c); c.drawText(guideText, gx + dp(7), ruler + dp(13), p); p.setTextSize(dp(8)); } }
        if (s.loopEnd > s.loopStart) { float x0 = head + (float) (s.loopStart / beat * beatPx) - a.timelineScroll, x1 = head + (float) (s.loopEnd / beat * beatPx) - a.timelineScroll; p.setColor(a.engine.loop ? 0x5578956c : 0x33a7afb5); c.drawRect(Math.max(head, x0), 0, Math.min(w, x1), ruler, p); p.setColor(a.engine.loop ? 0x1478956c : 0x0aa7afb5); c.drawRect(Math.max(head, x0), ruler, Math.min(w, x1), h - dp(22), p); }
        for (Pro.Marker m : s.markers) { float x = head + (float) (m.frame / beat * beatPx) - a.timelineScroll; if (x < head - dp(80) || x > w) continue; p.setColor(0xffe0b45a); p.setStrokeWidth(dp(1)); c.drawLine(x, ruler - dp(8), x, h - dp(22), p); c.drawRect(x, ruler - dp(9), x + Math.min(dp(90), p.measureText(m.name) + dp(8)), ruler - dp(1), p); p.setColor(0xff24282c); c.drawText(m.name, x + dp(3), ruler - dp(2), p); }
        if (showAutomation && a.selected != null) {
            int index = rows().indexOf(a.selected); Pro.Lane lane = a.selected.automation.get(shownLane);
            if (index >= 0 && lane != null && lane.size() > 0) {
                float top = ruler + index * row - a.timelineVertical + dp(8), bottom = top + row - dp(16); double[] r = Pro.range(lane.param);
                p.setColor(0xffffc56b); p.setStrokeWidth(dp(2)); p.setStyle(Paint.Style.STROKE); Path path = new Path();
                for (int x = (int) head; x <= w; x += 3) { long frame = (long) ((x - head + a.timelineScroll) / beatPx * beat); double v = lane.at(frame); float y = (float) (bottom - (v - r[0]) / (r[1] - r[0]) * (bottom - top)); if (x == (int) head) path.moveTo(x, y); else path.lineTo(x, y); }
                c.drawPath(path, p); p.setStyle(Paint.Style.FILL);
                for (int i = 0; i < lane.size(); i++) { float x = head + (float) (lane.frame(i) / beat * beatPx) - a.timelineScroll; if (x < head || x > w) continue; float y = (float) (bottom - (lane.value(i) - r[0]) / (r[1] - r[0]) * (bottom - top)); c.drawCircle(x, y, dp(3), p); }
                p.setColor(0xffffc56b); c.drawText(paramName(lane.param), head + dp(6), top + dp(9), p);
            }
        }
        c.restore();
    }

    // ---------------------------------------------------------------- recording and audio settings

    void applyAudioSettings() {
        SharedPreferences pr = prefs(); a.engine.lowLatency = pr.getBoolean("lowLatency", false); a.engine.useNative = pr.getBoolean("aaudio", true);
        try { AudioManager am = (AudioManager) a.getSystemService(android.content.Context.AUDIO_SERVICE); String burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER); if (burst != null) a.engine.burstFrames = Integer.parseInt(burst); } catch (Exception ignored) {}
    }
    AudioDeviceInfo inputDevice() {
        int id = prefs().getInt("inputDevice", -1); if (id < 0) return null; String name = prefs().getString("inputName", "");
        AudioManager am = (AudioManager) a.getSystemService(android.content.Context.AUDIO_SERVICE);
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) if (d.getId() == id || (d.getProductName() != null && d.getProductName().toString().equals(name) && d.getType() == prefs().getInt("inputType", -1))) return d;
        return null;
    }
    /** Called before recording starts. */
    void configureCapture(AudioIO.Capture take, Core.Track target) {
        take.device = inputDevice(); take.channel = take.device == null ? 0 : prefs().getInt("inputChannel", 0); take.stereo = prefs().getBoolean("inputStereo", false);
        if (prefs().getBoolean("monitor", false)) { Pro.Ring ring = new Pro.Ring(8192); take.monitor = ring; a.engine.monitor = ring; a.engine.monitorTrack = prefs().getBoolean("monitorFx", true) ? target : null; }
        else { a.engine.monitor = null; a.engine.monitorTrack = null; }
        checkBluetooth();
    }
    /** Called when a recorded clip is ready, before it is placed on the track. */
    void afterRecording(Core.Track target, Core.Clip c, AudioIO.Capture take) {
        a.engine.monitor = null; a.engine.monitorTrack = null;
        if (prefs().getBoolean("compensate", true)) Pro.compensate(c, prefs().getInt("latencyFrames", 0));
        if (punchFrom > c.start && punchFrom < c.start + c.length) Pro.trimTo(c, punchFrom, c.start + c.length);
        punchFrom = -1; if (restoreClick != null) { a.engine.click = restoreClick; restoreClick = null; }
        boolean overlap = false;
        for (Core.Clip existing : target.clips) if (existing.start < c.start + c.length && existing.start + existing.length > c.start && existing.midiFile == null) { Pro.rememberTake(target, existing); overlap = true; }
        Pro.rememberTake(target, c);
        if (overlap && !target.autoCrossfade) { target.autoCrossfade = true; a.toast("The new take plays on top of the earlier one, which is kept: clip menu → Pro clip tools → Takes & comping."); }
        if (take != null && take.route != null) a.toast(take.route);
    }

    void audioSettings() {
        LinearLayout p = page(); SharedPreferences pr = prefs();
        p.addView(label("PLAYBACK", 11, MainActivity.DIM, true));
        p.addView(prefToggle("Low-latency output (smaller buffers; may crackle on slow phones)", "lowLatency", false, this::applyAudioSettings));
        p.addView(prefToggle(NativeAudio.AVAILABLE ? "Use native AAudio in low-latency mode (recommended)" : "Use native AAudio (not built into this APK)", "aaudio", true, this::applyAudioSettings));
        p.addView(label("Output path: " + (a.engine.outputPath.isEmpty() ? "press Play to see" : a.engine.outputPath), 11, MainActivity.TEXT, false));
        TextView lat = label("Output buffer: " + (a.engine.latencyFrames > 0 ? String.format(Locale.US, "%.1f ms (measured while playing)", a.engine.latencyFrames * 1000.0 / Core.RATE) : "press Play to measure"), 11, MainActivity.TEXT, false); p.addView(lat);
        p.addView(label("INPUT", 11, MainActivity.DIM, true));
        AudioDeviceInfo dev = inputDevice();
        p.addView(button(dev == null ? "Input: phone microphone · tap to choose" : "Input: " + deviceName(dev) + " · tap to change", this::chooseInput), new LinearLayout.LayoutParams(-1, dp(44)));
        p.addView(prefToggle("Record a stereo pair (two adjacent inputs)", "inputStereo", false, null));
        p.addView(prefToggle("Monitor input while recording", "monitor", false, null));
        p.addView(prefToggle("Monitor through the track's channel FX", "monitorFx", true, null));
        p.addView(label("Monitoring passes through Android's audio path and is delayed. With headphones and low-latency output it is usable for many phones; otherwise use your interface's direct monitoring.", 10, MainActivity.DIM, false));
        p.addView(label("COUNT-IN", 11, MainActivity.DIM, true));
        p.addView(spinner(new String[]{"No count-in", "1 bar count-in", "2 bars count-in"}, pr.getInt("countIn", 0), i -> pr.edit().putInt("countIn", i).apply()));
        p.addView(prefToggle("Click on while recording", "recordClick", false, null));
        p.addView(label("LATENCY COMPENSATION", 11, MainActivity.DIM, true));
        p.addView(prefToggle("Line up new recordings automatically", "compensate", true, null));
        TextView value = label(String.format(Locale.US, "Current offset: %.1f ms", pr.getInt("latencyFrames", 0) * 1000.0 / Core.RATE), 12, MainActivity.TEXT, true); p.addView(value);
        row(p, button("Calibrate…", this::calibrateIntro), button("Set manually", () -> a.input("Recording offset in milliseconds", String.format(Locale.US, "%.1f", pr.getInt("latencyFrames", 0) * 1000.0 / Core.RATE), true, v -> { double ms = Double.parseDouble(v); if (ms < 0 || ms > 1000) throw new IllegalArgumentException("Use 0–1000 ms."); pr.edit().putInt("latencyFrames", (int) Math.round(ms * Core.RATE / 1000)).apply(); value.setText(String.format(Locale.US, "Current offset: %.1f ms", ms)); })));
        show("Recording & audio", p);
    }
    Switch prefToggle(String text, String key, boolean def, Runnable after) { Switch s = new Switch(a); s.setText(text); s.setTextColor(MainActivity.TEXT); s.setChecked(prefs().getBoolean(key, def)); s.setOnCheckedChangeListener((v, b) -> { prefs().edit().putBoolean(key, b).apply(); if (after != null) after.run(); }); return s; }
    String deviceName(AudioDeviceInfo d) { int most = 0; for (int c : d.getChannelCounts()) most = Math.max(most, c); String type = d.getType() == AudioDeviceInfo.TYPE_USB_DEVICE || d.getType() == AudioDeviceInfo.TYPE_USB_HEADSET ? "USB" : d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC ? "Built-in" : d.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET ? "Headset" : d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ? "Bluetooth" : "Input"; return type + " · " + d.getProductName() + (most > 0 ? " · " + most + " ch" : ""); }
    void chooseInput() {
        AudioManager am = (AudioManager) a.getSystemService(android.content.Context.AUDIO_SERVICE); AudioDeviceInfo[] all = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
        ArrayList<AudioDeviceInfo> list = new ArrayList<>(); ArrayList<String> names = new ArrayList<>(); names.add("Phone microphone (default)"); list.add(null);
        for (AudioDeviceInfo d : all) if (d.isSource() && d.getType() != AudioDeviceInfo.TYPE_TELEPHONY && d.getType() != AudioDeviceInfo.TYPE_FM_TUNER) { list.add(d); names.add(deviceName(d)); }
        new AlertDialog.Builder(a).setTitle("Recording input").setItems(names.toArray(new String[0]), (dd, i) -> {
            AudioDeviceInfo d = list.get(i); SharedPreferences.Editor e = prefs().edit();
            if (d == null) { e.putInt("inputDevice", -1).putInt("inputChannel", 0).apply(); a.toast("Recording from the phone microphone."); return; }
            e.putInt("inputDevice", d.getId()).putString("inputName", String.valueOf(d.getProductName())).putInt("inputType", d.getType()).apply();
            int most = 0; for (int c : d.getChannelCounts()) most = Math.max(most, c);
            if (most <= 2) { e.putInt("inputChannel", 0).apply(); a.toast("Recording from " + d.getProductName() + "."); return; }
            boolean stereo = prefs().getBoolean("inputStereo", false); int choices = stereo ? most / 2 : most; String[] ch = new String[choices];
            for (int k = 0; k < choices; k++) ch[k] = stereo ? "Inputs " + (2 * k + 1) + "–" + (2 * k + 2) : "Input " + (k + 1);
            new AlertDialog.Builder(a).setTitle("Which input on " + d.getProductName() + "?").setItems(ch, (x, k) -> { prefs().edit().putInt("inputChannel", stereo ? 2 * k : k).apply(); a.toast("Recording from " + ch[k] + "."); }).show();
        }).show();
    }
    void checkBluetooth() {
        if (bluetoothWarned) return;
        try { AudioManager am = (AudioManager) a.getSystemService(android.content.Context.AUDIO_SERVICE);
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) { int t = d.getType(); if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == 26 || t == 27) { bluetoothWarned = true; a.error("Bluetooth audio delay", "Bluetooth headphones or speakers add roughly 150–300 ms of delay, so overdubs and live playing will feel late and recordings will not line up. Use wired, USB-C or USB-interface headphones for recording."); return; } } }
        catch (Exception ignored) {}
    }

    void calibrateIntro() {
        a.error("Latency calibration", "Unplug headphones and turn the volume up. The studio will play a series of clicks through the speaker and record them with the microphone to measure the delay. Keep the room quiet for about three seconds.");
        a.handler.postDelayed(this::calibrate, 600);
    }
    void calibrate() {
        if (!a.canEdit()) return;
        if (a.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { a.toast("Allow microphone access by recording once, then calibrate."); return; }
        if (!a.requestAudioFocus()) return;
        a.engine.pause(); a.audition.stop(); final long keepPosition = a.engine.position; final boolean keepLoop = a.engine.loop, keepClick = a.engine.click;
        final float[] clicks = Pro.calibrationClicks(8, Core.RATE / 4); float[] stereo = new float[clicks.length * 2]; for (int i = 0; i < clicks.length; i++) stereo[2 * i] = stereo[2 * i + 1] = clicks[i];
        Core.Session test = new Core.Session(); test.master = 1; Core.Track t = new Core.Track("Calibration", 0); t.volume = 1; t.fx.eq = false; t.clips.add(new Core.Clip("clicks", new Core.Memory(stereo), "clicks", 0)); test.tracks.add(t);
        final File file = new File(a.getCacheDir(), "latency.pcm"); file.delete();
        final AudioIO.Capture take = new AudioIO.Capture(); take.device = inputDevice(); take.channel = take.device == null ? 0 : prefs().getInt("inputChannel", 0);
        take.begin(file, () -> a.runOnUiThread(() -> {
            a.engine.pause(); a.engine.position = keepPosition; a.engine.loop = keepLoop; a.engine.click = keepClick; a.updateTransport();
            if (take.error != null) { a.error("Calibration", take.error); return; }
            a.busy("Measuring latency…", () -> {
                AudioIO.Disk d = new AudioIO.Disk(file); float[] rec = new float[d.frames()]; for (int i = 0; i < rec.length; i++) rec[i] = d.sample(i, 0);
                Pro.LatencyResult r = Pro.estimateLatency(clicks, rec, Core.RATE / 2); file.delete();
                a.runOnUiThread(() -> {
                    if (r.confidence < 2.5) { a.error("Calibration", String.format(Locale.US, "The clicks were not heard clearly (confidence %.1f). Turn the volume up, unplug headphones and try again in a quieter place.", r.confidence)); return; }
                    prefs().edit().putInt("latencyFrames", r.frames).putBoolean("compensate", true).apply();
                    a.error("Calibration complete", String.format(Locale.US, "Measured round-trip latency: %.1f ms (confidence %.1f). New recordings will be moved earlier by this amount so they line up with the beat.", r.ms(), r.confidence));
                });
            });
        }));
        a.engine.loop = false; a.engine.click = false; a.engine.position = 0; a.engine.play(test); // same output path and buffer settings as real recording
        a.handler.postDelayed(take::stop, (long) (clicks.length * 1000L / Core.RATE + 700));
    }

    // ---------------------------------------------------------------- export

    static final String[] FORMATS = {"WAV 24-bit · production master", "WAV 16-bit · sharing", "FLAC 24-bit · lossless, smaller", "FLAC 16-bit · lossless", "AAC 256 kbps (.m4a) · phones & streaming previews", "Stems · one WAV per track (ZIP)", "Stems · one FLAC per track (ZIP)"};
    static final String[] NORMALISE = {"No loudness change", "Normalise to −14 LUFS (streaming)", "Normalise to −16 LUFS (podcast)", "Normalise to −23 LUFS (broadcast)"};
    static final double[] TARGETS = {0, -14, -16, -23};
    void export() {
        if (!a.canEdit()) return; if (a.session.end() == 0) { a.toast("Add audio before exporting."); return; }
        LinearLayout p = page(); exportFormat = prefs().getInt("exportFormat", 0); exportNormalise = prefs().getInt("exportNormalise", 0);
        p.addView(label("FORMAT", 11, MainActivity.DIM, true)); p.addView(spinner(FORMATS, exportFormat, i -> exportFormat = i));
        p.addView(label("LOUDNESS", 11, MainActivity.DIM, true)); p.addView(spinner(NORMALISE, exportNormalise, i -> exportNormalise = i));
        exportRate = prefs().getInt("exportRate", Core.RATE); exportDither = prefs().getBoolean("exportDither", true);
        p.addView(label("SAMPLE RATE (WAV, FLAC, stems)", 11, MainActivity.DIM, true)); p.addView(spinner(new String[]{"48 kHz (project rate)", "44.1 kHz (CD / most distributors)"}, exportRate == 44100 ? 1 : 0, i -> exportRate = i == 1 ? 44100 : Core.RATE));
        Switch dith = new Switch(a); dith.setText("TPDF dither (recommended for 16-bit)"); dith.setTextColor(MainActivity.TEXT); dith.setChecked(exportDither); dith.setOnCheckedChangeListener((v, b) -> exportDither = b); p.addView(dith);
        p.addView(label("Normalising measures the mix (EBU R128) and adjusts the master level, keeping true peaks at or below −1 dBTP. Stems are never normalised. MP3 is not offered because Android has no built-in MP3 encoder; AAC sounds better at the same size.", 10, MainActivity.DIM, false));
        new AlertDialog.Builder(a).setTitle("Export").setView(scroll(p)).setNegativeButton("Cancel", null).setPositiveButton("Choose destination", (d, w) -> {
            prefs().edit().putInt("exportFormat", exportFormat).putInt("exportNormalise", exportNormalise).putInt("exportRate", exportRate).putBoolean("exportDither", exportDither).apply(); a.engine.pause(); a.save(false);
            String base = a.session.title.replaceAll("[^a-zA-Z0-9 _-]", ""); if (base.trim().isEmpty()) base = "Mix";
            String[] ext = {"_24bit.wav", "_16bit.wav", "_24bit.flac", "_16bit.flac", ".m4a", "_stems_wav.zip", "_stems_flac.zip"}; String[] mime = {"audio/wav", "audio/wav", "audio/flac", "audio/flac", "audio/mp4", "application/zip", "application/zip"};
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType(mime[exportFormat]); i.putExtra(Intent.EXTRA_TITLE, base + ext[exportFormat]);
            try { a.startActivityForResult(i, EXPORT_REQUEST); } catch (Exception e) { a.error("Export", e.getMessage()); }
        }).show();
    }
    boolean result(int request, Uri uri) {
        if (request == REFERENCE_REQUEST) { if (a.session != null) referenceChosen(uri); return true; }
        if (request != EXPORT_REQUEST) return false; if (a.session == null) return true;
        final Core.Session snapshot = a.session.copy(); snapshot.referenceOn = false; final int format = exportFormat, norm = exportNormalise, rate = exportRate; final boolean dither = exportDither;
        a.busy("Rendering " + FORMATS[format].split(" ·")[0] + "…", () -> {
            Core.Session mix = snapshot; String note = "";
            if (norm > 0 && format < 5) { double[] report = new double[3]; Core.Session n = Export.normalised(snapshot, TARGETS[norm], -1, report); if (n != null) { mix = n; note = String.format(Locale.US, " Loudness %.1f → %.1f LUFS target (%+.1f dB).", report[0], TARGETS[norm], report[2]); } }
            if (format == 4) { try (ParcelFileDescriptor fd = a.getContentResolver().openFileDescriptor(uri, "rwt")) { if (fd == null) throw new IOException("Cannot open the export destination."); AudioIO.aac(fd.getFileDescriptor(), mix, 256000); } }
            else try (OutputStream out = a.getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("Cannot open the export destination.");
                if (format <= 1) Export.wav(out, mix, format == 0 ? 24 : 16, rate, dither); else if (format <= 3) Export.flac(out, mix, format == 2 ? 24 : 16, rate, dither); else { int n = Export.stems(out, mix, 24, format == 6, rate); note = " " + n + " stems."; }
                if (format != 4 && rate != Core.RATE) note += " Resampled to 44.1 kHz.";
            }
            final String done = FORMATS[format].split(" ·")[0] + " exported." + note; a.runOnUiThread(() -> a.toast(done));
        });
        return true;
    }

    // ---------------------------------------------------------------- undo history

    void undoHistory() {
        if (a.undo.isEmpty()) { a.toast("No edits to undo yet."); return; }
        ArrayList<Core.Session> list = new ArrayList<>(a.undo); Collections.reverse(list); String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) { String l = history.get(list.get(i)); names[i] = (i + 1) + " step" + (i == 0 ? "" : "s") + " back · before: " + (l == null ? "Edit" : l); }
        new AlertDialog.Builder(a).setTitle("Undo history (newest first)").setItems(names, (d, i) -> { for (int k = 0; k <= i; k++) a.undo(); a.toast("Went back " + (i + 1) + " step" + (i == 0 ? "" : "s") + ". Redo can step forward again."); }).setNegativeButton("Close", null).show();
    }

    // ---------------------------------------------------------------- templates and channel presets

    File folder(String name) { File f = new File(a.getFilesDir(), name); f.mkdirs(); return f; }
    void templates() {
        File[] files = folder("templates").listFiles((d, n) -> n.endsWith(".json")); ArrayList<String> items = new ArrayList<>(Arrays.asList("Save current project as template…", "New: Vocal + beat (reverb & delay buses)", "New: Band (8 tracks, drum group)", "New: Podcast (2 voices, −16 LUFS master)"));
        ArrayList<File> user = new ArrayList<>(); if (files != null) { Arrays.sort(files); for (File f : files) { user.add(f); items.add("New from “" + f.getName().replace(".json", "") + "”"); } }
        if (!user.isEmpty()) items.add("Delete a saved template…");
        new AlertDialog.Builder(a).setTitle("Project templates").setItems(items.toArray(new String[0]), (d, i) -> {
            if (i == 0) a.input("Template name", a.session.title, false, n -> { String name = n.replaceAll("[^a-zA-Z0-9 _-]", "").trim(); if (name.isEmpty()) throw new IllegalArgumentException("Use letters or numbers."); try (OutputStream o = new FileOutputStream(new File(folder("templates"), name + ".json"))) { o.write(ProjectIO.template(a.session).getBytes("UTF-8")); } catch (Exception e) { throw new IllegalArgumentException(e.getMessage()); } a.toast("Template saved: tracks, buses, routing and effects, without audio."); });
            else if (i <= 3) newFromTemplate(builtIn(i), new String[]{"", "Vocal + beat", "Band", "Podcast"}[i]);
            else if (i - 4 < user.size()) { File f = user.get(i - 4); try { newFromTemplate(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"), f.getName().replace(".json", "")); } catch (IOException e) { a.error("Template", e.getMessage()); } }
            else { String[] names = new String[user.size()]; for (int k = 0; k < names.length; k++) names[k] = user.get(k).getName().replace(".json", ""); new AlertDialog.Builder(a).setTitle("Delete template").setItems(names, (x, k) -> { user.get(k).delete(); a.toast("Template deleted."); }).show(); }
        }).show();
    }
    void newFromTemplate(String json, String title) {
        a.engine.stop(); a.save(false);
        a.busy("Creating project from template…", () -> { File dir = a.newDir(); Core.Session s = ProjectIO.fromTemplate(json, dir.getAbsolutePath()); s.title = title; AudioIO.save(s); a.runOnUiThread(() -> a.bind(s)); });
    }
    String builtIn(int which) {
        try {
            Core.Session s = new Core.Session(); int[] colors = MainActivity.COLORS;
            if (which == 1) {
                Core.Track rev = new Core.Track("Vocal reverb", 0xffc9b06b); rev.bus = true; rev.fx.eq = false; rev.fx.wet = true; rev.fx.room = true; rev.fx.roomMix = .5; s.buses.add(rev);
                Core.Track dly = new Core.Track("Vocal delay", 0xffb79f61); dly.bus = true; dly.fx.eq = false; dly.fx.wet = true; dly.fx.delay = true; dly.fx.delayMix = .45; dly.fx.delayFeedback = .35; s.buses.add(dly);
                Core.Track beat = new Core.Track("Beat", colors[0]); beat.volume = .7; s.tracks.add(beat);
                String[] names = {"Lead vocal", "Double", "Ad-libs"};
                for (int i = 0; i < 3; i++) { Core.Track v = new Core.Track(names[i], colors[(i + 1) % colors.length]); v.fx = Core.preset(1); v.fx.room = false; v.sends.add(new Pro.Send(rev.id, i == 0 ? .35 : .5, false)); if (i != 1) v.sends.add(new Pro.Send(dly.id, .25, false)); v.pan = i == 1 ? -.3 : i == 2 ? .3 : 0; s.tracks.add(v); }
            } else if (which == 2) {
                Core.Track drums = new Core.Track("Drum group", 0xffc9b06b); drums.bus = true; drums.fx.eq = false; drums.fx.comp = true; drums.fx.threshold = -16; drums.fx.ratio = 3; s.buses.add(drums);
                Core.Track rev = new Core.Track("Room", 0xffb79f61); rev.bus = true; rev.fx.eq = false; rev.fx.wet = true; rev.fx.room = true; rev.fx.roomMix = .45; s.buses.add(rev);
                String[] names = {"Kick", "Snare", "Overheads", "Bass", "Guitar", "Keys", "Lead vocal", "Backing vocals"};
                for (int i = 0; i < names.length; i++) { Core.Track t = new Core.Track(names[i], colors[i % colors.length]); if (i < 3) { t.output = drums.id; t.group = "Drums"; } if (i >= 4) t.sends.add(new Pro.Send(rev.id, .3, false)); if (i == 3) t.fx.comp = true; s.tracks.add(t); }
                s.tracks.get(3).sidechain = s.tracks.get(0).id;
            } else {
                for (int i = 0; i < 2; i++) { Core.Track v = new Core.Track("Voice " + (i + 1), colors[i]); v.fx = Core.preset(1); v.fx.room = false; v.fx.gate = true; v.fx.gateFloor = .3; s.tracks.add(v); }
                Core.Track m = new Core.Track("Music bed", colors[2]); m.volume = .4; m.sidechain = s.tracks.get(0).id; m.fx.comp = true; m.fx.threshold = -30; m.fx.ratio = 4; s.tracks.add(m);
                Pro.Insert lim = new Pro.Insert(Pro.LIMITER); lim.set(0, 4); s.masterInserts.add(lim); s.master = 1;
            }
            return ProjectIO.template(s);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    void presets(Core.Track t) {
        File[] files = folder("presets").listFiles((d, n) -> n.endsWith(".json")); ArrayList<File> list = new ArrayList<>(); if (files != null) { Arrays.sort(files); list.addAll(Arrays.asList(files)); }
        String[] items = new String[list.size() + 1]; items[0] = "Save " + t.name + "'s channel strip & inserts…"; for (int i = 0; i < list.size(); i++) items[i + 1] = "Load “" + list.get(i).getName().replace(".json", "") + "”";
        new AlertDialog.Builder(a).setTitle("Channel presets").setItems(items, (d, i) -> {
            if (i == 0) a.input("Preset name", t.name, false, n -> { String name = n.replaceAll("[^a-zA-Z0-9 _-]", "").trim(); if (name.isEmpty()) throw new IllegalArgumentException("Use letters or numbers."); try (OutputStream o = new FileOutputStream(new File(folder("presets"), name + ".json"))) { o.write(ProjectIO.trackPreset(t).getBytes("UTF-8")); } catch (Exception e) { throw new IllegalArgumentException(e.getMessage()); } a.toast("Channel preset saved."); });
            else { try { String json = new String(java.nio.file.Files.readAllBytes(list.get(i - 1).toPath()), "UTF-8"); edit("Load channel preset"); ProjectIO.applyTrackPreset(t, json); a.save(false); a.refresh(); a.toast("Preset loaded onto " + t.name + "."); } catch (Exception e) { a.error("Channel preset", e.getMessage()); } }
        }).show();
    }

    // ---------------------------------------------------------------- autosave, crash recovery, orientation

    boolean recovered;
    void install() {
        recovered = prefs().getBoolean("running", false);
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { try { if (a.session != null && a.session.directory != null) AudioIO.save(a.session.copy()); } catch (Throwable ignored) {} if (previous != null) previous.uncaughtException(thread, error); });
        a.handler.postDelayed(new Runnable() { public void run() { if (a.dead) return; if (a.session != null && !a.working && a.capture == null && a.midiTake == null) { try { AudioIO.save(a.session.copy()); } catch (Exception ignored) {} } a.handler.postDelayed(this, 60000); } }, 60000);
        if (a.getResources().getConfiguration().smallestScreenWidthDp >= 600) a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
        applyAudioSettings();
    }
    void foreground(boolean on) { prefs().edit().putBoolean("running", on).apply(); }
    /** After the first project opens: tell the user if the previous run ended unexpectedly. */
    void opened() {
        if (!recovered) return; recovered = false;
        a.error("Recovered after an unexpected close", "The studio did not close normally last time. Your project was reopened from its most recent save (projects are saved after each edit and every minute). If something is missing, Undo history and project backups may help.");
    }

    // ---------------------------------------------------------------- audible MIDI editing
    // Preview through the independent audition engine so the backing arrangement is not paused.
    // Use the clip's assigned synth patch, rather than the app's current default patch.
    void auditionMidiNote(Pro.Note note, Synth.Config patch) { auditionMidiNote(note, patch, null); }
    void auditionMidiNote(Pro.Note note, Synth.Config patch, SampledInstruments.Pack samplePack) {
        if (note == null || patch == null || note.pitch < 0 || note.pitch > 127) return;
        if (!a.requestAudioFocus()) return;
        SynthPoly voices = a.liveMidi.voices;
        voices.setConfig(15, patch);
        voices.setSamplePack(15, samplePack);
        if (!a.liveMidi.running) a.liveMidi.start();
        int velocity = Math.max(1, Math.min(127, note.velocity));
        voices.message(0x9f, note.pitch, velocity);
        voices.scheduleMessage(Math.max(1, Math.round(Core.RATE * .22)), 0x8f, note.pitch, 0);
    }
    void auditionMidiPhrase(List<Pro.Note> notes, Synth.Config patch, SampledInstruments.Pack samplePack, int length) {
        if (patch == null) return;
        ArrayList<Pro.Note> copy = new ArrayList<>();
        for (Pro.Note n : notes) if (n.pitch >= 0 && n.pitch <= 127) copy.add(n.copy());
        if (copy.isEmpty()) { a.toast("Add MIDI notes to hear your pattern."); return; }
        long limit = Math.min(Core.MAX_FRAMES, Math.round(Core.beat(a.session.bpm) * a.session.timeSignature * 8));
        copy.removeIf(n -> n.start >= limit);
        for (Pro.Note n : copy) n.length = Math.max(1, Math.min(n.length, limit - n.start));
        ArrayList<MidiTake.Event> events = new ArrayList<>();
        for (Pro.Note n : copy) {
            events.add(new MidiTake.Event((int)n.start, 0x9f, n.pitch, Math.max(1, Math.min(127, n.velocity))));
            events.add(new MidiTake.Event((int)n.end(), 0x8f, n.pitch, 0));
        }
        events.sort((x, y) -> x.frame == y.frame ? Integer.compare(x.status & 0xf0, y.status & 0xf0) : Integer.compare(x.frame, y.frame));
        if (!a.engine.playing) {
            if (!a.requestAudioFocus()) return;
            if (!a.engine.loop && a.session.end() > 0 && a.engine.position >= a.session.end()) a.engine.position = 0;
            a.engine.play(a.session);
        }
        SynthPoly voices = a.liveMidi.voices;
        voices.cancelScheduled(15);
        voices.setConfig(15, patch);
        voices.setSamplePack(15, samplePack);
        if (!a.liveMidi.running) a.liveMidi.start();
        long beat = Math.max(1, Core.beat(a.session.bpm));
        long delay = beat - Math.floorMod(a.engine.position, beat);
        voices.schedule(events, delay, 15);
    }

    // ---------------------------------------------------------------- piano roll

    void pianoRoll(Core.Track t, Core.Clip c) {
        if (c.midiFile == null && c.notes == null) { a.toast("This clip has no MIDI notes."); return; }
        final ArrayList<MidiTake.Event> other = new ArrayList<>(); final ArrayList<Pro.Note> notes;
        if (c.notes != null) { notes = new ArrayList<>(); for (Pro.Note n : c.notes) notes.add(n.copy()); }
        else try (InputStream in = new FileInputStream(new File(a.session.directory, c.midiFile))) { notes = new ArrayList<>(Pro.notes(Pro.readMidi(in), other)); }
        catch (Exception e) { a.error("Piano roll", e.getMessage()); return; }
        SampledInstruments.Pack clipSamples;
        try { clipSamples = a.instrumentPack(c); } catch (IOException e) { a.error("MIDI instrument", e.getMessage()); return; }
        LinearLayout p = a.column(); p.setPadding(dp(6), dp(4), dp(6), dp(4)); Roll roll = new Roll(notes); roll.previewInstrument = (c.instrument != null ? c.instrument : a.synthConfig).copy(); roll.previewSamples = clipSamples; p.addView(roll, new LinearLayout.LayoutParams(-1, dp(330)));
        row(p, button("▶ Hear MIDI pattern", () -> auditionMidiPhrase(notes, roll.previewInstrument, roll.previewSamples, c.length)),
            button("■ Stop preview", () -> a.liveMidi.voices.cancelScheduled(15)));
        p.addView(label("Tap a note or the piano-key strip to hear its pitch. Preview uses this clip's assigned instrument and stays on the project audio engine.", 10, MainActivity.DIM, false));
        String[] grids = {"Grid 1/4", "Grid 1/8", "Grid 1/16", "Grid 1/32"}; double beat = Core.beat(a.session.bpm);
        p.addView(spinner(grids, 2, i -> { roll.grid = beat / (1 << i); roll.invalidate(); }));
        row(p, button("Quantize", () -> roll.apply(sel -> Pro.quantize(sel, roll.grid, 1, 0, false))), button("Q 50%", () -> roll.apply(sel -> Pro.quantize(sel, roll.grid, .5, 0, false))), button("Swing", () -> roll.apply(sel -> Pro.quantize(sel, roll.grid, 1, .5, false))));
        row(p, button("Vel −", () -> roll.apply(sel -> Pro.velocity(sel, 1, -10))), button("Vel +", () -> roll.apply(sel -> Pro.velocity(sel, 1, 10))), button("−1", () -> roll.apply(sel -> Pro.transpose(sel, -1))), button("+1", () -> roll.apply(sel -> Pro.transpose(sel, 1))));
        row(p, button("−oct", () -> roll.apply(sel -> Pro.transpose(sel, -12))), button("+oct", () -> roll.apply(sel -> Pro.transpose(sel, 12))), button("Zoom −", () -> { roll.zoom = Math.max(dp(8), roll.zoom / 1.5f); roll.invalidate(); }), button("Zoom +", () -> { roll.zoom = Math.min(dp(400), roll.zoom * 1.5f); roll.invalidate(); }));
        row(p, button("Select none", () -> { roll.selected.clear(); roll.invalidate(); }), button("Delete selected", () -> roll.apply(sel -> { if (sel != notes) notes.removeAll(sel); })), button("Undo", roll::undo));
        String instrument = clipSamples == null ? Synth.NAMES[Math.max(0, Math.min(Synth.NAMES.length - 1, (c.instrument != null ? c.instrument : a.synthConfig).patch))] : clipSamples.name;
        p.addView(label("Tap empty space to add a note · drag its body to move it · drag its right edge to resize · tap to select · long-press to delete. Buttons act on selected notes, or all notes when none are selected. " + (c.notes != null ? "Live MIDI clip: changes apply instantly and stay editable. Instrument: " + instrument + "." : "Printing uses the current instrument: " + instrument + "."), 10, MainActivity.DIM, false));
        if (c.notes != null) { new AlertDialog.Builder(a).setTitle(c.name + " · notes").setView(scroll(p)).setNegativeButton("Discard", null).setNeutralButton("Instrument…", (d, w) -> { applyNotes(c, notes); chooseInstrument(c); }).setPositiveButton("Apply", (d, w) -> applyNotes(c, notes)).setOnDismissListener(d -> restoreMidiPreviewInstrument()).show(); return; }
        new AlertDialog.Builder(a).setTitle(c.name + " · notes").setView(scroll(p)).setNegativeButton("Discard", null).setPositiveButton("Print to audio", (d, w) -> printNotes(t, c, notes, other, roll.previewInstrument, roll.previewSamples)).setOnDismissListener(d -> restoreMidiPreviewInstrument()).show();
    }
    void restoreMidiPreviewInstrument() { a.liveMidi.voices.cancelScheduled(15); a.liveMidi.voices.setConfig(15, a.synthConfig); a.liveMidi.voices.setSamplePack(15, a.selectedSampleInstrument); }
    interface NotesAction { void run(List<Pro.Note> notes); }

    final class Roll extends View {
        final ArrayList<Pro.Note> notes; final HashSet<Pro.Note> selected = new HashSet<>(); final ArrayDeque<ArrayList<Pro.Note>> undo = new ArrayDeque<>();
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); double grid = Core.beat(a.session.bpm) / 4.0; float zoom = dp(60), scrollX, rowH = dp(13), keys = dp(30); int topPitch;
        Synth.Config previewInstrument; SampledInstruments.Pack previewSamples; int lastDragPitch = -1;
        Pro.Note touched; float downX, downY; long downAt, origStart, lastDragStart; int origPitch; boolean moved, scrolling, resizing; float startScroll; int startTop;
        Roll(ArrayList<Pro.Note> n) { super(a); notes = n; int hi = 72; for (Pro.Note x : n) hi = Math.max(hi, x.pitch + 4); topPitch = Math.min(127, hi); setContentDescription("Piano roll note editor"); }
        double beat() { return Core.beat(a.session.bpm); }
        float x(long frame) { return keys + (float) (frame / beat() * zoom) - scrollX; }
        long frame(float x) { return Math.max(0, (long) ((x - keys + scrollX) / zoom * beat())); }
        int pitch(float y) { return Math.max(0, Math.min(127, topPitch - (int) (y / rowH))); }
        float y(int pitch) { return (topPitch - pitch) * rowH; }
        void snapshot() { ArrayList<Pro.Note> copy = new ArrayList<>(); for (Pro.Note n : notes) copy.add(n.copy()); undo.push(copy); while (undo.size() > 40) undo.removeLast(); }
        void undo() { if (undo.isEmpty()) { a.toast("Nothing to undo in the piano roll."); return; } notes.clear(); notes.addAll(undo.pop()); selected.clear(); invalidate(); }
        void apply(NotesAction action) { snapshot(); List<Pro.Note> target = selected.isEmpty() ? notes : new ArrayList<>(selected); action.run(target); if (target != notes) selected.retainAll(notes); invalidate(); }
        Pro.Note at(float px, float py) { int pitch = pitch(py); long f = frame(px); for (int i = notes.size() - 1; i >= 0; i--) { Pro.Note n = notes.get(i); if (n.pitch == pitch && f >= n.start && f <= n.end()) return n; } return null; }
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(); c.drawColor(0xff252c31); p.setStyle(Paint.Style.FILL);
            for (int pitch = topPitch; y(pitch) < h; pitch--) { if (pitch < 0) break; int pc = pitch % 12; boolean black = pc == 1 || pc == 3 || pc == 6 || pc == 8 || pc == 10; p.setColor(black ? 0xff20262a : 0xff2b3237); c.drawRect(keys, y(pitch), w, y(pitch) + rowH, p); p.setColor(black ? 0xff15191c : 0xffd9dee1); c.drawRect(0, y(pitch), keys - dp(2), y(pitch) + rowH - 1, p); if (pc == 0) { p.setColor(0xff59646c); p.setTextSize(dp(8)); c.drawText("C" + (pitch / 12 - 1), dp(2), y(pitch) + rowH - dp(3), p); } }
            int sig = a.session.timeSignature; long step = Math.max(1, Math.round(grid)); long first = frame(keys) / step * step;
            for (long f = first; x(f) < w; f += step) { double b = f / beat(); boolean bar = Math.abs(b / sig - Math.round(b / sig)) < 1e-6, isBeat = Math.abs(b - Math.round(b)) < 1e-6; p.setColor(bar ? 0xff7d8a93 : isBeat ? 0xff4a555c : 0xff353e44); c.drawRect(x(f), 0, x(f) + (bar ? dp(1.5f) : dp(.7f)), h, p); }
            for (Pro.Note n : notes) { float l = x(n.start), r = Math.max(l + dp(3), x(n.end())), t = y(n.pitch); if (r < keys || l > w) continue; boolean sel = selected.contains(n); p.setColor(sel ? 0xffffffff : android.graphics.Color.rgb(90 + n.velocity, 150 + n.velocity / 2, 110)); c.drawRoundRect(Math.max(keys, l), t + 1, r, t + rowH - 1, dp(2), dp(2), p); p.setColor(sel ? 0xff3f4b52 : 0xffe6f0e2); c.drawRect(Math.max(keys, r - dp(4)), t + 2, r, t + rowH - 2, p); }
            p.setColor(0xffe2e9e9); float cursor = x(a.engine.position - (a.clip == null ? 0 : a.clip.start - a.clip.offset)); if (cursor >= keys && cursor < w) c.drawRect(cursor, 0, cursor + dp(1), h, p);
        }
        public boolean onTouchEvent(MotionEvent e) {
            float ex = e.getX(), ey = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: getParent().requestDisallowInterceptTouchEvent(true); downX = ex; downY = ey; downAt = SystemClock.uptimeMillis(); moved = scrolling = false; touched = ex > keys ? at(ex, ey) : null; resizing = touched != null && Math.abs(ex - x(touched.end())) <= dp(12); if (ex <= keys) auditionMidiNote(new Pro.Note(pitch(ey), 100, 0, Math.round(grid), 0), previewInstrument, previewSamples); else if (touched != null) auditionMidiNote(touched, previewInstrument, previewSamples); lastDragPitch = touched == null ? -1 : touched.pitch; startScroll = scrollX; startTop = topPitch; if (touched != null) { origStart = touched.start; lastDragStart = origStart; origPitch = touched.pitch; } return true;
                case MotionEvent.ACTION_MOVE:
                    if (!moved && Math.hypot(ex - downX, ey - downY) > dp(6)) { moved = true; if (touched != null) snapshot(); else scrolling = true; }
                    if (moved && touched != null) { if (resizing) { long length = Math.max(1, Math.round((frame(ex) - origStart) / grid) * Math.round(grid)); if (length != touched.length) { touched.length = length; auditionMidiNote(touched, previewInstrument, previewSamples); } } else { long delta = Math.round(((ex - downX) / zoom * beat()) / grid) * Math.round(grid); touched.start = Math.max(0, origStart + delta); touched.pitch = Math.max(0, Math.min(127, origPitch - Math.round((ey - downY) / rowH))); if (touched.pitch != lastDragPitch || touched.start != lastDragStart) { lastDragPitch = touched.pitch; lastDragStart = touched.start; auditionMidiNote(touched, previewInstrument, previewSamples); } } invalidate(); }
                    else if (scrolling) { scrollX = Math.max(0, startScroll - (ex - downX)); topPitch = Math.max(12, Math.min(127, startTop + Math.round((ey - downY) / rowH))); invalidate(); }
                    return true;
                case MotionEvent.ACTION_UP:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    if (!moved) {
                        if (touched != null) { if (SystemClock.uptimeMillis() - downAt > 450) { snapshot(); notes.remove(touched); selected.remove(touched); } else if (!selected.remove(touched)) selected.add(touched); }
                        else if (ex > keys) { snapshot(); long start = Math.round(frame(ex) / grid) * Math.round(grid); Pro.Note n = new Pro.Note(pitch(ey), 100, Math.max(0, start), Math.max(1, Math.round(grid)), 0); notes.add(n); auditionMidiNote(n, previewInstrument, previewSamples); }
                        invalidate();
                    }
                    return true;
                default: return true;
            }
        }
    }

    // ---------------------------------------------------------------- online loops (Freesound, CC0 only)

    android.media.MediaPlayer loopPlayer; ProLoops.Loop loopPlaying;
    File loopDir() { File d = new File(a.getFilesDir(), "loops"); d.mkdirs(); return d; }
    String loopKey() { return prefs().getString("freesoundKey", ""); }
    void stopLoopPreview() { if (loopPlayer != null) { try { loopPlayer.stop(); } catch (Exception ignored) {} loopPlayer.release(); loopPlayer = null; } loopPlaying = null; }

    void onlineLoops() {
        if (loopKey().isEmpty()) { loopKeyDialog(this::onlineLoops); return; }
        LinearLayout p = page();
        p.addView(label("FREE LOOPS · CC0 PUBLIC DOMAIN", 11, MainActivity.DIM, true));
        p.addView(label("Searches Freesound and shows only CC0 loops: free to use in released music, no credit required (the app still keeps one). Downloads are Freesound's HQ MP3 previews, the same quality as the bundled Afrobeat loops.", 10, MainActivity.DIM, false));
        EditText words = new EditText(a); words.setSingleLine(); words.setTextSize(13); words.setHint("Search words (optional), e.g. shaker, 808, guitar riff"); words.setText(prefs().getString("loopWords", "")); p.addView(words);
        String[] genres = new String[ProLoops.GENRES.length + 1], parts = new String[ProLoops.PARTS.length + 1]; genres[0] = "Any style"; parts[0] = "Any instrument";
        System.arraycopy(ProLoops.GENRES, 0, genres, 1, ProLoops.GENRES.length); System.arraycopy(ProLoops.PARTS, 0, parts, 1, ProLoops.PARTS.length);
        final int[] pick = { prefs().getInt("loopGenre", 1), prefs().getInt("loopPart", 1) };
        p.addView(spinner(genres, Math.min(pick[0], genres.length - 1), i -> pick[0] = i)); p.addView(spinner(parts, Math.min(pick[1], parts.length - 1), i -> pick[1] = i));
        CheckBox near = new CheckBox(a); near.setText("Only loops near the project tempo (" + a.session.bpm + " BPM ± 8)"); near.setTextColor(MainActivity.TEXT); near.setChecked(prefs().getBoolean("loopNear", false)); p.addView(near);
        TextView status = label("", 11, MainActivity.TEXT, true); p.addView(status); LinearLayout list = a.column(); p.addView(list);
        final int[] page = {1};
        Runnable[] search = new Runnable[1];
        search[0] = () -> {
            prefs().edit().putString("loopWords", words.getText().toString()).putInt("loopGenre", pick[0]).putInt("loopPart", pick[1]).putBoolean("loopNear", near.isChecked()).apply();
            final String url; try { url = ProLoops.url(words.getText().toString(), pick[0] == 0 ? "" : genres[pick[0]], pick[1] == 0 ? "" : parts[pick[1]], page[0], loopKey()); } catch (Exception e) { a.error("Loops", e.getMessage()); return; }
            final boolean onlyNear = near.isChecked(); final int bpm = a.session.bpm; status.setText("Searching Freesound…");
            a.busy("Searching free loops…", () -> {
                ProLoops.Page res = ProLoops.parse(httpText(url));
                ArrayList<ProLoops.Loop> shown = new ArrayList<>(); for (ProLoops.Loop l : res.loops) if (!onlyNear || (l.bpm > 0 && Math.abs(l.bpm - bpm) <= 8)) shown.add(l);
                a.runOnUiThread(() -> { status.setText(res.total == 0 ? "No CC0 loops found. Try other words or Any style." : String.format(Locale.US, "Page %d · %d CC0 loops match on Freesound · showing %d", page[0], res.total, shown.size())); fillLoops(list, shown, false); if (res.more) list.addView(button("More results →", () -> { page[0]++; search[0].run(); })); });
            });
        };
        row(p, button("Search", () -> { page[0] = 1; search[0].run(); }), button("Downloaded (" + ProLoops.library(loopDir()).size() + ")", () -> { status.setText("Loops saved on this phone (work offline)"); fillLoops(list, ProLoops.library(loopDir()), true); }));
        row(p, button("Change API key", () -> loopKeyDialog(null)), button("Stop preview", this::stopLoopPreview));
        AlertDialog d = new AlertDialog.Builder(a).setTitle("Get loops online").setView(scroll(p)).setPositiveButton("Close", null).create();
        d.setOnDismissListener(x -> stopLoopPreview()); d.show();
    }

    void loopKeyDialog(Runnable after) {
        LinearLayout p = page();
        p.addView(label("Freesound needs a free API key to search (one time only). Sign in or create a free Freesound account, open the key page, fill in the short form (any name, e.g. Audio City Studio), then copy the long “Client secret / Api key” and paste it here. Freesound's free API is for non-commercial use; the CC0 loops themselves are free for any music you make.", 12, MainActivity.TEXT, false));
        EditText key = new EditText(a); key.setSingleLine(); key.setHint("Paste API key"); key.setText(loopKey()); key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD); p.addView(key);
        p.addView(button("Open the Freesound key page", () -> { try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ProLoops.KEY_PAGE))); } catch (Exception e) { a.error("Freesound", ProLoops.KEY_PAGE); } }));
        new AlertDialog.Builder(a).setTitle("Freesound API key").setView(scroll(p)).setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
            String k = key.getText().toString().trim(); if (!k.matches("[A-Za-z0-9]{20,64}")) { a.toast("That doesn't look like a Freesound API key: it should be one long word of letters and numbers."); return; }
            prefs().edit().putString("freesoundKey", k).apply(); if (after != null) after.run();
        }).show();
    }

    void fillLoops(LinearLayout list, List<ProLoops.Loop> loops, boolean saved) {
        list.removeAllViews();
        for (ProLoops.Loop l : loops) {
            LinearLayout card = a.column(); card.setPadding(dp(10), dp(8), dp(10), dp(8)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(8); list.addView(card, lp); card.setBackground(a.round(MainActivity.PANEL, 12));
            TextView name = label(l.name, 13, MainActivity.TEXT, true); name.setOnClickListener(v -> new AlertDialog.Builder(a).setTitle(l.name).setMessage("Creator: " + l.user + "\n\nLicence: CC0 1.0 (public-domain dedication)\n\nTags: " + l.tags + "\n\nSource:\n" + l.page).setNegativeButton("Close", null).setPositiveButton("Open source page", (d, w) -> { try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(l.page))); } catch (Exception e) { a.error("Source", l.page); } }).show());
            card.addView(name); card.addView(label(l.info(), 10, MainActivity.DIM, false));
            Button[] play = new Button[1];
            play[0] = button("▶  Preview", () -> {
                if (loopPlaying == l) { stopLoopPreview(); play[0].setText("▶  Preview"); return; }
                stopLoopPreview(); a.engine.pause(); File local = new File(loopDir(), l.id + ".mp3");
                try { loopPlayer = new android.media.MediaPlayer(); if (local.isFile()) loopPlayer.setDataSource(local.getAbsolutePath()); else loopPlayer.setDataSource(l.preview); loopPlayer.setLooping(true); loopPlaying = l;
                    loopPlayer.setOnPreparedListener(mp -> mp.start()); loopPlayer.setOnErrorListener((mp, w, e) -> { a.toast("Could not play this preview. Check the internet connection."); stopLoopPreview(); return true; }); loopPlayer.prepareAsync(); play[0].setText("■  Stop"); }
                catch (Exception e) { stopLoopPreview(); a.error("Preview", e.getMessage()); }
            });
            row(card, play[0], button("Insert on track", () -> insertLoop(l)));
            if (saved) row(card, button("Remove from phone", () -> { new File(loopDir(), l.id + ".mp3").delete(); new File(loopDir(), l.id + ".json").delete(); list.removeView(card); }));
        }
        if (loops.isEmpty()) list.addView(label(saved ? "No downloaded loops yet. Insert a loop from the search and it is kept here." : "No loops to show.", 12, MainActivity.DIM, false));
    }

    static String httpText(String url) throws IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection(); c.setConnectTimeout(15000); c.setReadTimeout(20000); c.setRequestProperty("Accept", "application/json");
        try { int code = c.getResponseCode(); InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream(); String body = in == null ? "" : readAll(in, 4 << 20); if (code >= 400) throw new IOException(ProLoops.problem(code, body)); return body; }
        catch (java.net.UnknownHostException e) { throw new IOException("No internet connection. Connect to Wi-Fi or mobile data and try again."); }
        finally { c.disconnect(); }
    }
    static String readAll(InputStream in, int max) throws IOException { ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) { o.write(b, 0, n); if (o.size() > max) throw new IOException("The server sent too much data."); } return new String(o.toByteArray(), "UTF-8"); }
    /** Downloads the preview once into the phone's loop library (checked to be real MP3, at most 20 MB). */
    File fetchLoop(ProLoops.Loop l) throws Exception {
        File dir = loopDir(), mp3 = new File(dir, l.id + ".mp3"), meta = new File(dir, l.id + ".json"); if (mp3.isFile() && meta.isFile()) return mp3;
        if (!l.preview.startsWith("https://")) throw new IOException("Unsafe download address.");
        File part = new File(dir, l.id + ".part"); java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(l.preview).openConnection(); c.setConnectTimeout(15000); c.setReadTimeout(30000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("Download failed (" + c.getResponseCode() + ").");
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) { byte[] b = new byte[16384], head = new byte[4]; int n, total = 0; while ((n = in.read(b)) > 0) { if (total == 0) System.arraycopy(b, 0, head, 0, Math.min(4, n)); total += n; if (total > 20 << 20) throw new IOException("This loop is too large."); out.write(b, 0, n); } if (!ProLoops.mp3(head, 4)) throw new IOException("The download was not an MP3 file."); }
            if (!part.renameTo(mp3)) throw new IOException("Could not save the loop.");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(meta), "UTF-8")) { w.write(l.json().toString()); }
            return mp3;
        } catch (java.net.UnknownHostException e) { throw new IOException("No internet connection."); }
        finally { c.disconnect(); part.delete(); }
    }

    void insertLoop(ProLoops.Loop l) {
        if (!a.canEdit()) return; if (a.selected == null) { a.toast("Select a track before inserting a loop."); return; }
        stopLoopPreview(); a.engine.pause(); final int bpm = a.session.bpm;
        if (!l.timed() || l.bpm == bpm) { placeLoop(l, 0); return; }
        new AlertDialog.Builder(a).setTitle(l.name).setItems(new String[]{"Keep original " + l.bpm + " BPM", "Fit to project " + bpm + " BPM (keeps pitch)", "Fit to project " + bpm + " BPM (resample, changes pitch)"}, (d, i) -> placeLoop(l, i)).setNegativeButton("Cancel", null).show();
    }
    /** mode 0 keep, 1 time-stretch keeping pitch, 2 resample. */
    void placeLoop(ProLoops.Loop l, int mode) {
        final Core.Track target = a.selected; final Core.Session project = a.session; final File dir = new File(project.directory); final long at = a.engine.position; final int bpm = project.bpm;
        a.busy("Getting the loop…", () -> {
            File mp3 = fetchLoop(l); Core.Clip decoded = AudioIO.decode(a, Uri.fromFile(mp3), dir, l.name, at); Core.Clip result = decoded;
            if (l.timed()) {
                float[] data = new float[decoded.length * 2]; for (int i = 0; i < decoded.length; i++) { data[2 * i] = decoded.source.sample(i, 0); data[2 * i + 1] = decoded.source.sample(i, 1); }
                data = ProLoops.trimToBars(data, l.bars, l.bpm);
                if (mode == 1) { final ArrayList<float[]> chunks = new ArrayList<>(); final float[][] buf = { new float[8192] }; final int[] fill = {0};
                    TimeStretch.preservePitch(new Core.Memory(data), bpm / (double) l.bpm, (x, y) -> { buf[0][fill[0]++] = x; buf[0][fill[0]++] = y; if (fill[0] == buf[0].length) { chunks.add(buf[0]); buf[0] = new float[8192]; fill[0] = 0; } });
                    float[] out = new float[chunks.size() * 8192 + fill[0]]; int o = 0; for (float[] ch : chunks) { System.arraycopy(ch, 0, out, o, ch.length); o += ch.length; } System.arraycopy(buf[0], 0, out, o, fill[0]); data = ProLoops.exact(out, l.bars, bpm); }
                else if (mode == 2) data = SampleAudio.fitLoop(data, l.bars, bpm);
                result = AudioIO.store(dir, data, l.name + (mode == 0 ? "" : " · " + bpm + " BPM"), at); new File(dir, decoded.file).delete();
            }
            final Core.Clip added = result;
            a.runOnUiThread(() -> {
                edit("Insert loop"); target.clips.add(added); a.clip = added;
                String credit = "Loop " + l.credit(); if (project.notes == null) project.notes = ""; if (!project.notes.contains(l.page)) project.notes = (project.notes.isEmpty() ? "" : project.notes + "\n") + credit;
                a.save(false); a.refresh(); a.toast("Loop added. Its credit is saved in Project notes.");
            });
        });
    }

    // ---------------------------------------------------------------- vocal editor (note-by-note pitch, timing, level, formant)

    void vocalEditor(Core.Track t, Core.Clip c) {
        if (c.notes != null || c.midiFile != null) { a.toast("The vocal editor works on audio clips. Use the piano roll for MIDI."); return; }
        if (c.length > Core.RATE * 600) { a.toast("Split the clip first: the vocal editor handles up to 10 minutes at a time."); return; }
        a.engine.pause(); final File dir = new File(a.session.directory); final int key = a.session.tuneKey, scale = a.session.tuneScale;
        a.busy("Finding the sung notes…", () -> {
            AudioIO.VocalRegion region = AudioIO.vocalRegion(c, dir); ProVocal.Analysis analysis = ProVocal.analyse(region.region, 65, 1000);
            List<ProVocal.Note> found = ProVocal.segment(analysis); boolean kept = false; ArrayList<ProVocal.Note> notes = new ArrayList<>();
            if (c.vocalNotes != null && c.vocalOffset == region.offset && c.vocalLength == region.length && !c.vocalNotes.isEmpty() && c.vocalNotes.get(c.vocalNotes.size() - 1).end <= analysis.hops()) { for (ProVocal.Note n : c.vocalNotes) notes.add(n.copy()); kept = true; }
            else notes.addAll(found);
            final boolean restored = kept, stale = c.vocalNotes != null && !kept;
            a.runOnUiThread(() -> {
                if (notes.isEmpty()) { a.toast("No sung notes were found in this clip."); return; }
                showVocalEditor(t, c, region, analysis, notes, key, scale);
                if (restored) a.toast("Your earlier note edits are loaded. They play from the original recording.");
                else if (stale) a.toast("The clip was trimmed since the last edit, so notes were detected again.");
            });
        });
    }

    void showVocalEditor(Core.Track t, Core.Clip c, AudioIO.VocalRegion region, ProVocal.Analysis analysis, ArrayList<ProVocal.Note> notes, int key, int scale) {
        LinearLayout p = a.column(); p.setPadding(dp(6), dp(4), dp(6), dp(4)); VocalView view = new VocalView(analysis, notes); p.addView(view, new LinearLayout.LayoutParams(-1, dp(300)));
        TextView info = label("", 11, MainActivity.TEXT, true); p.addView(info); view.info = info; view.describe();
        String[] tools = {"Drag: pitch (snaps to semitones)", "Drag: pitch (free)", "Drag: timing inside phrase"};
        p.addView(spinner(tools, 0, i -> { view.tool = i; view.describe(); }));
        String[] keys = {"C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"}, scales = {"chromatic", "major", "minor"};
        String keyName = keys[Math.max(0, Math.min(11, key))] + " " + scales[Math.max(0, Math.min(2, scale))];
        row(p, button("Snap to " + keyName, () -> view.apply(n -> ProVocal.snap(n, key, scale))), button("Centre on semitone", () -> view.apply(n -> n.pitch = Math.round(n.pitch))));
        row(p, button("−1", () -> view.apply(n -> n.pitch -= 1)), button("+1", () -> view.apply(n -> n.pitch += 1)), button("−oct", () -> view.apply(n -> n.pitch -= 12)), button("+oct", () -> view.apply(n -> n.pitch += 12)));
        LinearLayout knobs = a.column(); p.addView(knobs);
        MainActivity.Knob drift = knob("DRIFT", 0, 2, "%", view.value(n -> n.drift, (n, v) -> n.drift = v)), vib = knob("VIBRATO", 0, 2, "%", view.value(n -> n.vibrato, (n, v) -> n.vibrato = v)),
                gain = knob("GAIN", -24, 12, "dB", view.value(n -> n.gainDb, (n, v) -> n.gainDb = v)), formant = knob("FORMANT", -12, 12, "st", view.value(n -> n.formant, (n, v) -> n.formant = v));
        for (MainActivity.Knob k : new MainActivity.Knob[]{drift, vib, gain, formant}) k.projectEdit = false; view.knobs = new View[]{drift, vib, gain, formant};
        a.knobs(knobs, drift, vib, gain, formant);
        row(p, button("Split at middle", view::splitSelected), button("Join with next", view::joinSelected), button("Reset note", () -> view.apply(n -> { n.pitch = n.centre; n.drift = n.vibrato = 1; n.gainDb = n.formant = 0; })));
        row(p, button("Select all", () -> { view.selected.clear(); view.selected.addAll(notes); view.changed(); }), button("Select none", () -> { view.selected.clear(); view.changed(); }), button("Undo", view::undo), button("Detect again", () -> { view.snapshot(); notes.clear(); notes.addAll(ProVocal.segment(analysis)); view.selected.clear(); view.changed(); }));
        p.addView(label("Each block is a sung note; the line through it is the pitch you will hear. Tap a note to select it, then drag it, use the knobs, or long-press it to split there. Knobs and buttons change the selected notes, or every note when none is selected. "
                + "Drift is the slow wander into and out of a note; vibrato is the fast wobble (0 % flattens it, 200 % doubles it). Formant changes the tone colour of the voice without changing the note. Timing moves the boundary between two joined notes, so one gets longer and the other shorter; to move a whole phrase, move the clip. "
                + "Everything is rendered from the original recording, so you can come back and change it, and Undo in the studio restores the clip as it was.", 10, MainActivity.DIM, false));
        new AlertDialog.Builder(a).setTitle(c.name + " · vocal editor").setView(scroll(p)).setNegativeButton("Discard", null).setNeutralButton("Back to original", (d, w) -> {
            if (c.tuneSourceFile == null) { a.toast("This clip is already the original recording."); return; }
            a.restoreDryVocal(t, c);
        }).setPositiveButton("Apply", (d, w) -> {
            final ArrayList<ProVocal.Note> copy = new ArrayList<>(); for (ProVocal.Note n : notes) copy.add(n.copy()); final Core.Clip work = c.copy(); final File dir = new File(a.session.directory);
            a.busy("Rendering vocal edits…", () -> {
                AudioIO.vocalEditClip(work, dir, region, analysis, copy);
                a.runOnUiThread(() -> { edit("Vocal editor"); int i = t.clips.indexOf(c); if (i >= 0) t.clips.set(i, work); a.clip = work; a.save(false); a.refresh(); a.toast("Vocal edits applied. Undo restores the previous version."); });
            });
        }).show();
    }
    interface NoteGet { double get(ProVocal.Note n); }
    interface NoteSet { void set(ProVocal.Note n, double v); }
    interface NoteEdit { void run(ProVocal.Note n); }

    final class VocalView extends View {
        final ProVocal.Analysis an; final ArrayList<ProVocal.Note> notes; final HashSet<ProVocal.Note> selected = new HashSet<>(); final ArrayDeque<ArrayList<ProVocal.Note>> undo = new ArrayDeque<>();
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); final android.graphics.Path path = new android.graphics.Path(); float zoom = dp(3), scrollX, rowH = dp(14), keys = dp(30); double topPitch; int tool; TextView info; View[] knobs = new View[0];
        ProVocal.Curves curves; ProVocal.Note touched; float downX, downY; long downAt; double origPitch, lastShift; boolean moved, scrolling, edge; float startScroll; double startTop;
        VocalView(ProVocal.Analysis a2, ArrayList<ProVocal.Note> n) {
            super(a); an = a2; notes = n; double hi = 60, lo = 127; for (ProVocal.Note x : n) { hi = Math.max(hi, x.pitch); lo = Math.min(lo, x.pitch); }
            topPitch = Math.min(127, Math.max(hi + 4, lo + 20)); int first = n.isEmpty() ? 0 : n.get(0).start; scrollX = Math.max(0, first * zoom - dp(20)); setContentDescription("Vocal note editor"); changed();
        }
        float x(double hop) { return keys + (float) (hop * zoom) - scrollX; }
        double hop(float px) { return (px - keys + scrollX) / zoom; }
        float y(double pitch) { return (float) ((topPitch - pitch) * rowH); }
        double pitchAt(float py) { return topPitch - py / rowH; }
        int index(ProVocal.Note n) { return notes.indexOf(n); }
        double outStart(ProVocal.Note n) { return ProVocal.outStart(notes, index(n)); }
        double outEnd(ProVocal.Note n) { return ProVocal.outEnd(notes, index(n)); }
        /** Screen position of an input hop inside a note, after timing edits. */

        void changed() { curves = ProVocal.curves(an, notes); describe(); for (View k : knobs) k.invalidate(); invalidate(); }
        void snapshot() { ArrayList<ProVocal.Note> copy = new ArrayList<>(); for (ProVocal.Note n : notes) copy.add(n.copy()); undo.push(copy); while (undo.size() > 60) undo.removeLast(); }
        void undo() { if (undo.isEmpty()) { a.toast("Nothing to undo in the vocal editor."); return; } notes.clear(); notes.addAll(undo.pop()); selected.clear(); changed(); }
        List<ProVocal.Note> targets() { return selected.isEmpty() ? notes : new ArrayList<>(selected); }
        void apply(NoteEdit e) { snapshot(); for (ProVocal.Note n : targets()) { e.run(n); n.pitch = Math.max(n.centre - 24, Math.min(n.centre + 24, n.pitch)); } changed(); }
        MainActivity.Value value(NoteGet g, NoteSet s) {
            return new MainActivity.Value() {
                boolean fresh = true;
                public double get() { List<ProVocal.Note> l = targets(); return l.isEmpty() ? 0 : g.get(l.get(0)); }
                public void set(double v) { if (fresh) { snapshot(); fresh = false; postDelayed(() -> fresh = true, 600); } for (ProVocal.Note n : targets()) s.set(n, v); changed(); }
            };
        }
        void describe() {
            if (info == null) return; String[] names = {"C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"};
            if (selected.size() == 1) { ProVocal.Note n = selected.iterator().next(); int r = (int) Math.round(n.pitch); double cents = (n.pitch - r) * 100;
                info.setText(String.format(Locale.US, "%s%d %+.0f cents · sung %+.0f cents · %.2f s · drift %d %% · vibrato %d %% · %+.1f dB · formant %+.1f", names[((r % 12) + 12) % 12], r / 12 - 1, cents, (n.centre - Math.round(n.centre)) * 100, (outEnd(n) - outStart(n)) * ProVocal.HOP / Core.RATE, Math.round(n.drift * 100), Math.round(n.vibrato * 100), n.gainDb, n.formant)); }
            else info.setText(selected.isEmpty() ? notes.size() + " notes · nothing selected, so controls change every note" : selected.size() + " notes selected");
        }
        void splitSelected() { if (selected.size() != 1) { a.toast("Select one note to split, or long-press a note where you want the cut."); return; } ProVocal.Note n = selected.iterator().next(); split(n, (n.start + n.end) / 2); }
        void split(ProVocal.Note n, int at) { snapshot(); ProVocal.Note r = ProVocal.split(notes, n, at, an); if (r == null) { undo.pop(); a.toast("That note is too short to split there."); return; } selected.clear(); selected.add(r); changed(); }
        void joinSelected() { if (selected.size() != 1) { a.toast("Select one note to join with the next."); return; } ProVocal.Note n = selected.iterator().next(); snapshot(); if (!ProVocal.join(notes, n, an)) { undo.pop(); a.toast("Only notes that touch, or nearly touch, can be joined."); return; } selected.retainAll(notes); changed(); }
        ProVocal.Note at(float px, float py) { double h = hop(px), pitch = pitchAt(py); ProVocal.Note best = null; double dist = 1.2; for (int i = 0; i < notes.size(); i++) { ProVocal.Note n = notes.get(i); if (h >= ProVocal.outStart(notes, i) - 1 && h <= ProVocal.outEnd(notes, i) + 1 && Math.abs(n.pitch - pitch) < dist) { dist = Math.abs(n.pitch - pitch); best = n; } } return best; }
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(); c.drawColor(0xff252c31); p.setStyle(Paint.Style.FILL);
            for (int pitch = (int) Math.floor(topPitch); y(pitch) < h && pitch >= 0; pitch--) { int pc = pitch % 12; boolean black = pc == 1 || pc == 3 || pc == 6 || pc == 8 || pc == 10; float top = y(pitch + .5); p.setColor(black ? 0xff20262a : 0xff2b3237); c.drawRect(keys, top, w, top + rowH, p); p.setColor(black ? 0xff15191c : 0xffd9dee1); c.drawRect(0, top, keys - dp(2), top + rowH - 1, p); if (pc == 0) { p.setColor(0xff59646c); p.setTextSize(dp(8)); c.drawText("C" + (pitch / 12 - 1), dp(2), top + rowH - dp(3), p); } }
            double second = Core.RATE / (double) ProVocal.HOP; for (double s0 = Math.floor(hop(keys) / second) * second; x(s0) < w; s0 += second) { p.setColor(0xff353e44); c.drawRect(x(s0), 0, x(s0) + dp(.7f), h, p); }
            for (int ni = 0; ni < notes.size(); ni++) {
                ProVocal.Note n = notes.get(ni); double os = ProVocal.outStart(notes, ni), oe = ProVocal.outEnd(notes, ni), per = (oe - os) / Math.max(1, n.end - n.start); float l = x(os), r = x(oe); if (r < keys || l > w) continue; boolean sel = selected.contains(n); float top = y(n.pitch + .5);
                p.setStyle(Paint.Style.FILL); p.setColor(sel ? 0xccf2f6f7 : n.edited() ? 0xaa6fb7a0 : 0x996b8b9a); c.drawRoundRect(Math.max(keys, l) + 1, top + 1, r - 1, top + rowH - 1, dp(4), dp(4), p);
                if (Math.abs(n.pitch - n.centre) > .02) { p.setColor(0x55ffffff); float ot = y(n.centre + .5); c.drawRect(Math.max(keys, l), ot + rowH / 2 - dp(.5f), r, ot + rowH / 2 + dp(.5f), p); }
                path.reset(); boolean open = false;
                for (int k = n.start; k < n.end; k++) { double m = an.midi[k]; if (Double.isNaN(m)) { open = false; continue; } float px = x(os + per * (k - n.start)), py = y(m + curves.shift[k]); if (!open) { path.moveTo(px, py); open = true; } else path.lineTo(px, py); }
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1.6f)); p.setColor(sel ? 0xff1d2226 : 0xffe2e9e9); c.drawPath(path, p);
            }
            p.setStyle(Paint.Style.FILL);
        }
        public boolean onTouchEvent(MotionEvent e) {
            float ex = e.getX(), ey = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: getParent().requestDisallowInterceptTouchEvent(true); downX = ex; downY = ey; downAt = SystemClock.uptimeMillis(); moved = scrolling = false; lastShift = 0;
                    touched = ex > keys ? at(ex, ey) : null; startScroll = scrollX; startTop = topPitch; if (touched != null) { origPitch = touched.pitch; edge = Math.abs(ex - x(outEnd(touched))) < dp(12); } return true;
                case MotionEvent.ACTION_MOVE:
                    if (!moved && Math.hypot(ex - downX, ey - downY) > dp(6)) { moved = true; if (touched != null) { snapshot(); if (!selected.contains(touched)) { selected.clear(); selected.add(touched); } } else scrolling = true; }
                    if (moved && touched != null) {
                        if (tool == 2) { double want = (ex - downX) / zoom, step = want - lastShift; int i = index(touched); if (edge) { if (ProVocal.moveEnd(notes, i, step)) lastShift = want; } else lastShift += ProVocal.shift(notes, i, step); }
                        else { double v = origPitch - (ey - downY) / rowH; touched.pitch = Math.max(touched.centre - 24, Math.min(touched.centre + 24, tool == 0 ? Math.round(v) : v)); }
                        changed();
                    } else if (scrolling) { scrollX = Math.max(0, startScroll - (ex - downX)); topPitch = Math.max(24, Math.min(127, startTop + (ey - downY) / rowH)); invalidate(); }
                    return true;
                case MotionEvent.ACTION_UP:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    if (!moved) {
                        if (touched != null && SystemClock.uptimeMillis() - downAt > 450) { double hh = hop(ex), s0 = outStart(touched), e0 = outEnd(touched); split(touched, (int) Math.round(touched.start + (hh - s0) / Math.max(1e-6, e0 - s0) * (touched.end - touched.start))); }
                        else if (touched != null) { if (!selected.remove(touched)) selected.add(touched); changed(); }
                        else { selected.clear(); changed(); }
                    } else if (touched != null && tool == 2 && !edge && lastShift == 0) a.toast("The first and last notes of a phrase stay in place; drag a note between them, or drag a note's right edge.");
                    return true;
                default: return true;
            }
        }
    }

    void printNotes(Core.Track t, Core.Clip c, List<Pro.Note> notes, List<MidiTake.Event> other, Synth.Config config, SampledInstruments.Pack samplePack) {
        if (notes.isEmpty()) { a.toast("Add at least one note before printing."); return; }
        final Synth.Config instrument = config.copy(); final int bpm = a.session.bpm; final Core.Session project = a.session; final ArrayList<Pro.Note> copy = new ArrayList<>(); for (Pro.Note n : notes) copy.add(n.copy());
        a.busy("Printing edited notes…", () -> {
            MidiTake events = Pro.take(instrument, bpm, copy, other);
            MidiTake take = new MidiTake(instrument, bpm, null, 0, samplePack); take.events.addAll(events.events); take.frames = events.frames; take.recording = false;
            File audio = new File(project.directory, UUID.randomUUID() + ".pcm"), mid = new File(project.directory, UUID.randomUUID() + ".mid"); boolean ok = false;
            try {
                try (AudioIO.Writer w = new AudioIO.Writer(audio)) { take.print((l, r) -> w.sample(l, r)); }
                try (OutputStream o = new FileOutputStream(mid)) { take.writeMidi(o); }
                long origin = c.start - c.offset; Core.Clip printed = new Core.Clip(c.name, new AudioIO.Disk(audio), audio.getName(), Math.max(0, origin));
                if (origin < 0) { int cut = (int) Math.min(printed.length - 1, -origin); printed.offset = cut; printed.envelopeOffset = cut; printed.length -= cut; }
                printed.length = (int) Math.max(1, Math.min(printed.length, Core.MAX_FRAMES - printed.start)); printed.midiFile = mid.getName(); printed.gain = c.gain;
                ok = true;
                a.runOnUiThread(() -> { edit("Edit MIDI notes"); int index = t.clips.indexOf(c); if (index >= 0) t.clips.set(index, printed); else t.clips.add(printed); a.clip = printed; a.save(false); a.refresh(); a.toast("Notes printed to audio. Undo restores the previous version."); });
            } finally { if (!ok) { audio.delete(); mid.delete(); } }
        });
    }

    // ---------------------------------------------------------------- folders and tempo-aware arrangement grid

    final ArrayList<Core.Track> visible = new ArrayList<>(); long visibleKey = Long.MIN_VALUE;
    /** Tracks shown in the arrangement: members of collapsed folders are hidden. */
    List<Core.Track> rows() {
        Core.Session s = a.session; if (s == null) return visible;
        long key = System.identityHashCode(s) * 31L + s.tracks.size() * 7919L + s.collapsed.hashCode(); for (Core.Track t : s.tracks) key = key * 31 + System.identityHashCode(t) + (t.folder == null ? 0 : t.folder.hashCode());
        if (key != visibleKey) { visible.clear(); for (Core.Track t : s.tracks) if (t.folder == null || !s.collapsed.contains(t.folder)) visible.add(t); visibleKey = key; }
        return visible;
    }
    String trackHeader() { int all = a.session.tracks.size(), shown = rows().size(); return shown == all ? "TRACKS / " + all : "TRACKS / " + shown + " of " + all; }
    LinkedHashMap<String, List<Core.Track>> folderMap() { LinkedHashMap<String, List<Core.Track>> m = new LinkedHashMap<>(); for (Core.Track t : a.session.tracks) if (t.folder != null) m.computeIfAbsent(t.folder, k -> new ArrayList<>()).add(t); return m; }
    void chooseFolder(Core.Track t) {
        ArrayList<String> names = new ArrayList<>(folderMap().keySet()); ArrayList<String> items = new ArrayList<>(); items.add("New folder…"); for (String n : names) items.add("Move to “" + n + "”"); if (t.folder != null) items.add("Take out of folder");
        new AlertDialog.Builder(a).setTitle(t.name + " · folder").setItems(items.toArray(new String[0]), (d, i) -> {
            if (i == 0) a.input("Folder name", "Drums", false, n -> { String name = n.trim(); if (name.isEmpty()) throw new IllegalArgumentException("Enter a folder name."); edit("Folder"); t.folder = name.substring(0, Math.min(40, name.length())); a.save(false); a.refresh(); });
            else if (i <= names.size()) { edit("Folder"); t.folder = names.get(i - 1); a.save(false); a.refresh(); }
            else { edit("Folder"); t.folder = null; a.save(false); a.refresh(); }
        }).show();
    }
    void folders() {
        LinearLayout p = page(); Core.Session s = a.session; LinkedHashMap<String, List<Core.Track>> m = folderMap();
        p.addView(label("Folders tidy the arrangement: collapse one to hide its tracks (they still play). Mute and solo act on every track inside. Use a track's menu to put it in a folder.", 11, MainActivity.DIM, false));
        if (m.isEmpty()) p.addView(label("No folders yet.", 13, MainActivity.TEXT, true));
        for (Map.Entry<String, List<Core.Track>> e : m.entrySet()) {
            String name = e.getKey(); List<Core.Track> members = e.getValue();
            LinearLayout card = a.column(); card.setBackground(a.round(MainActivity.PANEL, 14)); card.setPadding(dp(12), dp(8), dp(12), dp(8)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(8); p.addView(card, lp);
            StringBuilder list = new StringBuilder(); for (Core.Track t : members) list.append(list.length() == 0 ? "" : ", ").append(t.name);
            card.addView(label((s.collapsed.contains(name) ? "▸ " : "▾ ") + name + "  ·  " + members.size() + " track" + (members.size() == 1 ? "" : "s"), 14, MainActivity.TEXT, true));
            card.addView(label(list.toString(), 11, MainActivity.DIM, false));
            boolean allMuted = true, allSolo = true; for (Core.Track t : members) { allMuted &= t.mute; allSolo &= t.solo; } final boolean muted = allMuted, soloed = allSolo;
            row(card, button(s.collapsed.contains(name) ? "Expand" : "Collapse", () -> { edit("Folder view"); if (!s.collapsed.remove(name)) s.collapsed.add(name); a.save(false); a.refresh(); reopen(this::folders); }),
                    button(muted ? "Unmute all" : "Mute all", () -> { edit("Folder mute"); for (Core.Track t : members) t.mute = !muted; a.save(false); a.refresh(); reopen(this::folders); }),
                    button(soloed ? "Unsolo all" : "Solo all", () -> { edit("Folder solo"); for (Core.Track t : members) t.solo = !soloed; a.save(false); a.refresh(); reopen(this::folders); }));
            a.knobs(card, knob("FOLDER LEVEL", -24, 12, "dB", new MainActivity.Value() { double shown = 0; public double get() { return shown; } public void set(double v) { double ratio = Pro.gain(v - shown); shown = v; for (Core.Track t : members) t.volume = Math.max(0, Math.min(2, t.volume * ratio)); } }));
            row(card, button("Rename", () -> a.input("Folder name", name, false, n -> { String nn = n.trim(); if (nn.isEmpty()) throw new IllegalArgumentException("Enter a folder name."); edit("Rename folder"); for (Core.Track t : members) t.folder = nn; if (s.collapsed.remove(name)) s.collapsed.add(nn); a.save(false); a.refresh(); reopen(this::folders); })),
                    button("Dissolve", () -> { edit("Dissolve folder"); for (Core.Track t : members) t.folder = null; s.collapsed.remove(name); a.save(false); a.refresh(); reopen(this::folders); }));
        }
        p.addView(label("The folder level knob moves every member fader by the same number of decibels (relative trim).", 10, MainActivity.DIM, false));
        lastDialog = show("Track folders", p);
    }

    Pro.TempoMap cachedMap; int mapBpm = -1, mapSig = -1, mapHash;
    Pro.TempoMap map() { Core.Session s = a.session; int h = s.tempoChanges.hashCode(); if (cachedMap == null || mapBpm != s.bpm || mapSig != s.timeSignature || mapHash != h) { cachedMap = new Pro.TempoMap(s); mapBpm = s.bpm; mapSig = s.timeSignature; mapHash = h; } return cachedMap; }
    /** Arrangement x for a beat, following tempo changes (the timeline itself is linear in time at the base tempo's scale). */
    float beatX(int beat, float beatPx, float head) { return head + (float) (map().frameAt(beat) / (double) Core.beat(a.session.bpm) * beatPx) - a.timelineScroll; }
    int firstBeat(float beatPx) { long frame = (long) (a.timelineScroll / beatPx * Core.beat(a.session.bpm)); return Math.max(0, (int) Math.floor(map().beatAt(frame))); }
    int lastBeat(float w, float head, float beatPx) { long frame = (long) ((a.timelineScroll + w - head) / beatPx * Core.beat(a.session.bpm)); return (int) Math.ceil(map().beatAt(frame)) + 2; }
    long snap(long frame) { Core.Session s = a.session; Pro.TempoMap m = map(); int div = ProSnap.effectiveDivision(s, pxPerBeat(), dp(14)); if (m.constant() && div > 0) return Core.snap(frame, s.bpm, div); return ProSnap.grid(s, m, frame, div); }
    float pxPerBeat() { return a.zoom * a.getResources().getDisplayMetrics().density; }

    // ---------------------------------------------------------------- 0.8: loop/edit range, range edits, clip tools

    List<ProEdit.Item> clipboard = new ArrayList<>(); long clipboardLength; long punchFrom = -1; Boolean restoreClick; int exportRate = Core.RATE; boolean exportDither = true;

    boolean hasRange() { return a.session.loopEnd > a.session.loopStart; }
    String rangeText() { return hasRange() ? position(a.session.loopStart) + "  →  " + position(a.session.loopEnd) : "No range set (loop plays the whole project)"; }
    void loopRange() {
        LinearLayout p = page(); Core.Session s = a.session; TextView now = label(rangeText(), 12, MainActivity.TEXT, true); p.addView(now);
        long pos = a.engine.position, prev = 0, next = -1; for (Pro.Marker m : s.markers) { if (m.frame <= pos) prev = Math.max(prev, m.frame); else if (next < 0 || m.frame < next) next = m.frame; }
        final long mFrom = prev, mTo = next;
        Runnable done = () -> { a.engine.loop = true; a.save(false); a.refresh(); now.setText(rangeText()); };
        row(p, button("Start = playhead", () -> { edit("Loop range"); s.loopStart = a.engine.position; if (s.loopEnd <= s.loopStart) s.loopEnd = s.loopStart + Core.beat(s.bpm) * s.timeSignature; done.run(); }), button("End = playhead", () -> { long e = a.engine.position; if (e <= s.loopStart) { a.toast("Put the playhead after the range start."); return; } edit("Loop range"); s.loopEnd = e; done.run(); }));
        row(p, button("Between markers", () -> { if (mTo < 0) { a.toast("Add a marker after the playhead first."); return; } edit("Loop range"); s.loopStart = mFrom; s.loopEnd = mTo; done.run(); }), button("Selected clip", () -> { if (a.clip == null) { a.toast("Select a clip first."); return; } edit("Loop range"); s.loopStart = a.clip.start; s.loopEnd = a.clip.start + a.clip.length; done.run(); }));
        row(p, button("Next bars…", () -> a.input("How many bars from the playhead?", "4", true, v -> { int n = (int) Double.parseDouble(v); if (n < 1 || n > 512) throw new IllegalArgumentException("Use 1–512 bars."); edit("Loop range"); Pro.TempoMap m = map(); double b = Math.floor(m.beatAt(a.engine.position) / m.beatsPerBar()) * m.beatsPerBar(); s.loopStart = m.frameAt(b); s.loopEnd = m.frameAt(b + n * m.beatsPerBar()); done.run(); })), button("Clear range", () -> { edit("Clear loop range"); s.loopStart = s.loopEnd = 0; a.save(false); a.refresh(); now.setText(rangeText()); }));
        p.addView(label("With Loop on, playback cycles inside the range. Range edit uses the same range. The range is shaded on the ruler.", 10, MainActivity.DIM, false));
        show("Loop & edit range", p);
    }
    void rangeEdit() {
        if (!hasRange()) { a.toast("Set a loop & edit range first (Pro tools → Loop & edit range)."); loopRange(); return; }
        Core.Session s = a.session; long from = s.loopStart, to = s.loopEnd;
        String[] items = {"Copy · all tracks", "Copy · selected track", "Cut · all tracks (close gap)", "Cut · selected track (close gap)", "Delete · all tracks (leave gap)", "Delete · selected track (leave gap)", "Paste at playhead", "Insert silence the length of the range at its start (all tracks)", "Split every clip at the range edges"};
        new AlertDialog.Builder(a).setTitle("Range: " + time(from) + " – " + time(to)).setItems(items, (d, i) -> {
            List<Core.Track> all = new ArrayList<>(s.tracks), one = a.selected == null ? new ArrayList<>() : Collections.singletonList(a.selected); List<Core.Track> target = i % 2 == 0 ? all : one;
            if (i <= 5 && target.isEmpty()) { a.toast("Select a track first."); return; }
            a.engine.pause();
            if (i <= 3) { clipboard = ProEdit.copy(s, target, from, to); clipboardLength = to - from; }
            if (i == 0 || i == 1) { a.toast(clipboard.size() + " clip piece" + (clipboard.size() == 1 ? "" : "s") + " copied."); return; }
            if (i == 2 || i == 3) { edit("Cut range"); ProEdit.delete(s, target, from, to, true); s.loopEnd = s.loopStart; }
            else if (i == 4 || i == 5) { edit("Delete range"); ProEdit.delete(s, target, from, to, false); }
            else if (i == 6) { pasteAtPlayhead(); return; }
            else if (i == 7) { edit("Insert time"); ProEdit.insertTime(s, all, from, to - from); }
            else { edit("Split at range"); for (Core.Track t : all) { for (Core.Clip c : new ArrayList<>(t.clips)) ProEdit.split(t, c, from); for (Core.Clip c : new ArrayList<>(t.clips)) ProEdit.split(t, c, to); } }
            a.clip = null; a.save(false); a.refresh();
        }).show();
    }
    void pasteAtPlayhead() {
        if (clipboard.isEmpty()) { a.toast("Nothing copied yet."); return; }
        a.engine.pause(); edit("Paste"); List<Core.Clip> added = ProEdit.paste(a.session, clipboard, snap(a.engine.position)); if (!added.isEmpty()) a.clip = added.get(0); a.save(false); a.refresh(); a.toast(added.size() + " clip" + (added.size() == 1 ? "" : "s") + " pasted.");
    }
    void nudge(Core.Clip c) {
        LinearLayout p = page(); TextView info = label("Clip starts at " + position(c.start), 12, MainActivity.TEXT, true); p.addView(info); final boolean[] first = {true};
        long grid = Math.max(1, (long) (Core.beat(a.session.bpm) * 4.0 / Math.max(1, a.session.snapDivision * 4)));
        IntAction go = f -> { if (first[0]) { edit("Nudge"); first[0] = false; } ProEdit.nudge(c, f); info.setText("Clip starts at " + position(c.start)); a.save(false); if (a.timeline != null) a.timeline.invalidate(); };
        row(p, button("− grid", () -> go.run((int) -grid)), button("− 10 ms", () -> go.run(-480)), button("− 1 ms", () -> go.run(-48)));
        row(p, button("+ 1 ms", () -> go.run(48)), button("+ 10 ms", () -> go.run(480)), button("+ grid", () -> go.run((int) grid)));
        show("Nudge " + c.name, p);
    }
    void reverse(Core.Track t, Core.Clip c) {
        final Core.Session project = a.session; final float[] data = ProEdit.reversed(c);
        a.busy("Reversing clip…", () -> { Core.Clip r = AudioIO.store(new File(project.directory), data, c.name + " (reversed)", c.start); r.gain = c.gain; r.fadeIn = c.fadeOut; r.fadeOut = c.fadeIn; r.envelopeLength = r.length;
            a.runOnUiThread(() -> { edit("Reverse clip"); int i = t.clips.indexOf(c); if (i >= 0) t.clips.set(i, r); else t.clips.add(r); a.clip = r; a.save(false); a.refresh(); }); });
    }
    void stripSilence(Core.Track t, Core.Clip c) {
        LinearLayout p = page(); final double[] v = {-45, 300, 20};
        a.knobs(p, knob("THRESHOLD", -80, -10, "dB", val(() -> v[0], x -> v[0] = x)), knob("MIN GAP", 20, 2000, "ms", val(() -> v[1], x -> v[1] = x)), knob("PAD", 0, 200, "ms", val(() -> v[2], x -> v[2] = x)));
        p.addView(label("Removes pauses quieter than the threshold and longer than the minimum gap, keeping a little padding and short fades on each phrase. Great for vocals and podcasts.", 10, MainActivity.DIM, false));
        new AlertDialog.Builder(a).setTitle("Strip silence · " + c.name).setView(p).setNegativeButton("Cancel", null).setPositiveButton("Strip", (d, w) -> {
            List<Core.Clip> parts = ProEdit.stripSilence(c, v[0], (int) (v[1] * Core.RATE / 1000), (int) (v[2] * Core.RATE / 1000));
            if (parts.isEmpty()) { a.toast("Everything is below the threshold; nothing was changed."); return; }
            edit("Strip silence"); int i = t.clips.indexOf(c); t.clips.remove(c); t.clips.addAll(Math.max(0, Math.min(i, t.clips.size())), parts); a.clip = parts.get(0); a.save(false); a.refresh(); a.toast("Kept " + parts.size() + " phrase" + (parts.size() == 1 ? "" : "s") + ".");
        }).show();
    }
    void bounceTrack(Core.Track t) {
        if (a.session.tracks.size() >= Core.MAX_TRACKS) { a.toast("No free track for the bounce."); return; }
        String[] opts = {"Whole track", "Loop & edit range only"};
        new AlertDialog.Builder(a).setTitle("Bounce " + t.name).setItems(opts, (d, i) -> {
            final Core.Session project = a.session; final int index = project.tracks.indexOf(t); long start = 0, end = 0; for (Core.Clip c : t.clips) { if (end == 0 || c.start < start) start = c.start; end = Math.max(end, c.start + c.length); }
            if (i == 1) { if (!hasRange()) { a.toast("Set a loop & edit range first."); return; } start = project.loopStart; end = project.loopEnd; } else end = Math.min(Core.MAX_FRAMES, end + Export.tailFrames(project));
            final long from = start, to = end; final Core.Session snapshot = project.copy();
            a.busy("Bouncing " + t.name + " with its effects…", () -> { float[] audio = ProEdit.bounce(snapshot, index, from, to); Core.Clip c = AudioIO.store(new File(project.directory), audio, t.name + " (bounced)", from);
                a.runOnUiThread(() -> { edit("Bounce track"); Core.Track nt = new Core.Track(t.name + " print", t.color); nt.fx = new Core.Fx(); nt.fx.eq = false; nt.volume = 1; nt.clips.add(c); project.tracks.add(Math.min(project.tracks.size(), index + 1), nt); t.mute = true; a.selected = nt; a.clip = c; a.save(false); a.refresh(); a.toast("Printed to “" + nt.name + "” with effects and fader. The original track is muted, not deleted."); }); });
        }).show();
    }

    // ---------------------------------------------------------------- 0.8: mastering helpers

    static final int REFERENCE_REQUEST = 31;
    void loadReference() { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("audio/*"); try { a.startActivityForResult(i, REFERENCE_REQUEST); } catch (Exception e) { a.error("Reference", e.getMessage()); } }
    void referenceChosen(Uri uri) {
        final Core.Session project = a.session;
        a.busy("Importing reference and matching loudness…", () -> { Core.Clip c = AudioIO.decode(a, uri, new File(project.directory), a.fileName(uri), 0); double gain = matchGain(project, c);
            a.runOnUiThread(() -> { edit("Reference track"); project.reference = c; project.referenceGain = gain; a.save(false); a.toast(String.format(Locale.US, "Reference loaded and level-matched (%+.1f dB). Use the A/B button in Master.", Pro.db(gain))); master(); }); });
    }
    /** Gain that makes the reference's integrated loudness equal the mix's (so A/B judges tone, not volume). */
    static double matchGain(Core.Session mix, Core.Clip ref) throws IOException {
        Pro.Loudness r = new Pro.Loudness(); float[] block = new float[1024]; for (int p = 0; p < ref.length; p += 512) { int n = Math.min(512, ref.length - p); for (int i = 0; i < n; i++) { int src = ref.offset + p + i; block[2 * i] = ref.source.sample(src, 0); block[2 * i + 1] = ref.source.sample(src, 1); } r.feed(block, n); }
        Core.Session m = mix.copy(); m.referenceOn = false; double mixLufs = mix.end() == 0 ? -14 : Pro.analyse(m, Math.min(Core.MAX_FRAMES, m.end())).integrated(); double refLufs = r.integrated();
        if (!Double.isFinite(refLufs)) return 1; if (!Double.isFinite(mixLufs)) mixLufs = -14; return Pro.gain(Math.max(-40, Math.min(40, mixLufs - refLufs)));
    }
    void matchReference() { final Core.Session project = a.session; if (project.reference == null) return; a.busy("Matching loudness…", () -> { double g = matchGain(project, project.reference); a.runOnUiThread(() -> { project.referenceGain = g; a.save(false); a.toast(String.format(Locale.US, "Reference re-matched (%+.1f dB).", Pro.db(g))); reopen(this::master); }); }); }
    void masteringPresets() {
        String[] names = {"Streaming · clean, about −14 LUFS", "Loud · club/rap, about −9 LUFS", "Warm & gentle · acoustic, about −16 LUFS", "Podcast voice · −16 LUFS", "Clear the master chain"};
        new AlertDialog.Builder(a).setTitle("Mastering preset").setItems(names, (d, i) -> {
            Core.Session s = a.session; edit("Mastering preset"); s.masterInserts.clear();
            if (i < 4) { Pro.Insert eq = new Pro.Insert(Pro.EQ8); eq.set(0, 1); eq.set(2, i == 3 ? 70 : 28); if (i == 2) { eq.set(30, 1); eq.set(31, 2); eq.set(32, 9000); eq.set(33, -1.5); } if (i == 1) { eq.set(5, 1); eq.set(6, 1); eq.set(7, 90); eq.set(8, 1.5); } eq.touch(); s.masterInserts.add(eq);
                Pro.Insert comp = new Pro.Insert(ProFx.COMP); comp.set(0, i == 1 ? -14 : -18); comp.set(1, i == 1 ? 3 : 2); comp.set(2, 30); comp.set(3, 200); comp.set(4, 8); comp.set(5, i == 1 ? 2 : 1); comp.touch(); s.masterInserts.add(comp);
                if (i == 1) { Pro.Insert tape = new Pro.Insert(ProFx.TAPE); tape.set(0, 4); tape.set(4, 40); tape.touch(); s.masterInserts.add(tape); }
                if (i != 3) s.masterInserts.add(new Pro.Insert(Pro.MULTIBAND));
                Pro.Insert lim = new Pro.Insert(Pro.LIMITER); lim.set(0, i == 1 ? 8 : i == 2 ? 1 : 3); lim.set(1, -1); lim.touch(); s.masterInserts.add(lim); }
            a.save(false); a.toast(i == 4 ? "Master chain cleared." : "Preset loaded. Play the loudest section and adjust the limiter input until Integrated LUFS is near the target; export can also normalise exactly."); reopen(this::master);
        }).show();
    }

    /** Live master spectrum (20 Hz–20 kHz) from the renderer's output scope. */
    final class Spectrum extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); float[] smooth;
        Spectrum() { super(a); setContentDescription("Master spectrum analyser"); }
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(); c.drawColor(0xff20272b); Core.Renderer r = a.engine.live; int bands = 48;
            p.setColor(0xff3a4249); p.setStrokeWidth(dp(1)); for (int db = -72; db <= 0; db += 12) { float y = h * (-db / 84f); c.drawLine(0, y, w, y, p); }
            p.setColor(MainActivity.DIM); p.setTextSize(dp(8)); c.drawText("20 Hz", dp(2), h - dp(3), p); c.drawText("20 kHz", w - dp(36), h - dp(3), p); c.drawText("0 dB", dp(2), dp(9), p);
            if (r == null || !a.engine.playing) { c.drawText("Play to see the spectrum", w / 2 - dp(50), h / 2, p); return; }
            float[] sp = ProEdit.spectrum(r.scope, r.scopePos, 4096, bands); if (smooth == null) smooth = sp.clone();
            float bw = w / bands; p.setColor(MainActivity.ACCENT);
            for (int b = 0; b < bands; b++) { smooth[b] = Math.max(sp[b], smooth[b] - 1.5f); float y = Math.max(0, Math.min(h, h * (-smooth[b] / 84f))); c.drawRect(b * bw + 1, y, (b + 1) * bw - 1, h, p); }
        }
    }

    // ---------------------------------------------------------------- 0.8: workflow

    /** Called just before recording: count-in pre-roll and optional click. */
    void preRoll() {
        int bars = prefs().getInt("countIn", 0); punchFrom = -1;
        if (prefs().getBoolean("recordClick", false)) { restoreClick = a.engine.click; a.engine.click = true; }
        if (bars > 0) { Pro.TempoMap m = map(); long pos = a.engine.position; punchFrom = pos; double b = m.beatAt(pos) - bars * m.beatsPerBar(); a.engine.position = Math.max(0, m.frameAt(Math.max(0, b))); if (restoreClick == null) { restoreClick = a.engine.click; a.engine.click = true; } }
    }
    void notes() {
        EditText field = new EditText(a); field.setText(a.session.notes); field.setTextColor(MainActivity.TEXT); field.setMinLines(6); field.setGravity(Gravity.TOP); field.setHint("Lyrics, mix notes, to-dos…");
        LinearLayout wrap = page(); wrap.addView(field);
        new AlertDialog.Builder(a).setTitle("Project notes").setView(wrap).setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> { a.session.notes = field.getText().toString(); a.save(false); a.toast("Notes saved with the project."); }).show();
    }
    void duplicateProject() {
        a.engine.stop(); a.save(false); final Core.Session src = a.session;
        a.busy("Duplicating project…", () -> { File dir = a.newDir(); File from = new File(src.directory); File[] files = from.listFiles(); if (files != null) for (File f : files) if (f.isFile()) java.nio.file.Files.copy(f.toPath(), new File(dir, f.getName()).toPath());
            Core.Session copy = AudioIO.load(dir); copy.title = (src.title + " copy").substring(0, Math.min(60, src.title.length() + 5)); AudioIO.save(copy); a.runOnUiThread(() -> { a.bind(copy); a.toast("Now working in “" + copy.title + "”. The original is unchanged (Open project)."); }); });
    }
    void shortcuts() {
        a.error("Keyboard shortcuts (USB or Bluetooth keyboard)", "Space  play / pause\nR  record\nHome  go to start · End  go to end\n← →  move playhead one beat (with a clip selected: nudge it by the grid; Shift = 10 ms)\nL  loop on/off · K  click on/off\nM  add marker · [ ]  previous / next marker\nS  split selected clip at playhead\nDelete  remove selected clip\nCtrl+C / Ctrl+X / Ctrl+V  copy / cut / paste clip\nCtrl+D  duplicate clip\nCtrl+Z  undo · Ctrl+Shift+Z or Ctrl+Y  redo\nCtrl+S  save · Ctrl+E  export · Ctrl+P  Pro tools\n?  this list");
    }
    /** Hardware keyboard shortcuts; returns true when handled. */
    boolean key(int code, KeyEvent e) {
        if (a.session == null || e.getRepeatCount() > 0 && code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        boolean ctrl = e.isCtrlPressed(), shift = e.isShiftPressed(); Core.Session s = a.session;
        switch (code) {
            case KeyEvent.KEYCODE_SPACE: a.togglePlay(); return true;
            case KeyEvent.KEYCODE_R: if (ctrl) return false; a.record(); return true;
            case KeyEvent.KEYCODE_Z: if (!ctrl) return false; if (shift) a.redoEdit(); else a.undo(); return true;
            case KeyEvent.KEYCODE_Y: if (!ctrl) return false; a.redoEdit(); return true;
            case KeyEvent.KEYCODE_S: if (ctrl) a.save(true); else a.split(); return true;
            case KeyEvent.KEYCODE_E: if (!ctrl) return false; a.export(); return true;
            case KeyEvent.KEYCODE_P: if (!ctrl) return false; menu(); return true;
            case KeyEvent.KEYCODE_L: a.engine.loop = !a.engine.loop; a.refresh(); return true;
            case KeyEvent.KEYCODE_K: a.engine.click = !a.engine.click; a.refresh(); return true;
            case KeyEvent.KEYCODE_M: if (!a.canEdit()) return true; edit("Add marker"); s.markers.add(new Pro.Marker(a.engine.position, "Marker " + (s.markers.size() + 1))); a.save(false); a.refresh(); return true;
            case KeyEvent.KEYCODE_LEFT_BRACKET: case KeyEvent.KEYCODE_RIGHT_BRACKET: { long pos = a.engine.position, best = code == KeyEvent.KEYCODE_LEFT_BRACKET ? 0 : -1; for (Pro.Marker m : s.markers) { if (code == KeyEvent.KEYCODE_LEFT_BRACKET && m.frame < pos - Core.RATE / 10) best = Math.max(best, m.frame); if (code == KeyEvent.KEYCODE_RIGHT_BRACKET && m.frame > pos && (best < 0 || m.frame < best)) best = m.frame; } if (best >= 0) { a.engine.seek(best); a.updateTransport(); a.refresh(); } return true; }
            case KeyEvent.KEYCODE_MOVE_HOME: a.engine.seek(hasRange() && a.engine.loop ? s.loopStart : 0); a.updateTransport(); a.refresh(); return true;
            case KeyEvent.KEYCODE_MOVE_END: a.engine.seek(s.end()); a.updateTransport(); a.refresh(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: { int dir = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                if (a.clip != null && a.canEdit()) { long grid = shift ? 480 : Math.max(1, (long) (Core.beat(s.bpm) / Math.max(1, s.snapDivision))); if (e.getRepeatCount() == 0) edit("Nudge"); ProEdit.nudge(a.clip, dir * grid); a.save(false); if (a.timeline != null) a.timeline.invalidate(); }
                else { Pro.TempoMap m = map(); double b = Math.round(m.beatAt(a.engine.position)) + dir; a.engine.seek(Math.max(0, m.frameAt(Math.max(0, b)))); a.updateTransport(); if (a.timeline != null) a.timeline.invalidate(); } return true; }
            case KeyEvent.KEYCODE_FORWARD_DEL: case KeyEvent.KEYCODE_DEL: if (a.clip == null || a.selected == null || !a.canEdit()) return false; edit("Remove clip"); a.selected.clips.remove(a.clip); a.clip = null; a.save(false); a.refresh(); return true;
            case KeyEvent.KEYCODE_C: case KeyEvent.KEYCODE_X: if (!ctrl || a.clip == null || a.selected == null) return false; clipboard = ProEdit.copy(s, Collections.singletonList(a.selected), a.clip.start, a.clip.start + a.clip.length); clipboardLength = a.clip.length; if (code == KeyEvent.KEYCODE_X && a.canEdit()) { edit("Cut clip"); a.selected.clips.remove(a.clip); a.clip = null; a.save(false); a.refresh(); } else a.toast("Clip copied."); return true;
            case KeyEvent.KEYCODE_V: if (!ctrl) return false; if (a.canEdit()) pasteAtPlayhead(); return true;
            case KeyEvent.KEYCODE_D: if (!ctrl || a.clip == null || a.selected == null || !a.canEdit()) return false; edit("Duplicate clip"); List<Core.Clip> d = ProEdit.duplicate(a.selected, a.clip, 1); if (!d.isEmpty()) a.clip = d.get(0); a.save(false); a.refresh(); return true;
            case KeyEvent.KEYCODE_SLASH: if (!shift) return false; shortcuts(); return true;
            default: return false;
        }
    }

    // ---------------------------------------------------------------- 0.9: live MIDI clips and time-stretch

    /** MIDI recordings become live, editable clips unless the user prefers printed audio. Returns true when handled. */
    boolean liveMidiTake(MidiTake take, Core.Track target, long start, String name) {
        if (!prefs().getBoolean("liveMidi", true)) return false;
        List<Pro.Note> notes = ProMidi.fromTake(take);
        if (notes.isEmpty()) { a.toast("No MIDI notes were received."); return true; }
        take.samplePackId = take.samplePack == null ? null : a.selectedSampleInstrumentId;
        final Synth.Config instrument = take.config.copy();
        a.busy("Creating live MIDI clip…", () -> { Core.Clip c = ProMidi.clip(name, notes, instrument, take.samplePack, take.samplePackId, start, Math.max(1, take.frames));
            c.length = (int) Math.max(1, Math.min(c.length, Core.MAX_FRAMES - start));
            a.runOnUiThread(() -> { target.clips.add(c); a.selected = target; a.clip = c; a.screen = "ARRANGE"; a.save(false); a.refresh(); a.toast("Performance saved as a live MIDI clip: edit notes or change the instrument any time from the clip menu."); }); });
        return true;
    }
    void newMidiClip(Core.Track t) {
        Pro.TempoMap m = map(); double b = Math.floor(m.beatAt(a.engine.position) / m.beatsPerBar()) * m.beatsPerBar(); long at = m.frameAt(b); int len = (int) (m.frameAt(b + 4 * m.beatsPerBar()) - at);
        if (at + len > Core.MAX_FRAMES) { a.toast("Move the playhead earlier."); return; }
        edit("New MIDI clip"); Core.Clip c = ProMidi.clip("MIDI " + (t.clips.size() + 1), new ArrayList<>(), a.synthConfig, at, len); t.clips.add(c); a.selected = t; a.clip = c; a.save(false); a.refresh(); pianoRoll(t, c);
    }
    void applyNotes(Core.Clip c, List<Pro.Note> notes) {
        final ArrayList<Pro.Note> copy = new ArrayList<>(); for (Pro.Note n : notes) copy.add(n.copy());
        a.busy("Rendering notes…", () -> { Core.Clip probe = c.copy(); probe.notes = copy; ProMidi.refresh(probe);
            a.runOnUiThread(() -> { edit("Edit notes"); c.notes = probe.notes; c.source = probe.source; c.peaks = probe.peaks; c.length = probe.length; c.envelopeLength = probe.envelopeLength; a.save(false); a.refresh(); }); });
    }
    void chooseInstrument(Core.Clip c) {
        ArrayList<SampledInstruments.Info> packs = new ArrayList<>(a.instrumentLibrary.list());
        String[] choices = new String[Synth.NAMES.length + packs.size()];
        for (int i = 0; i < Synth.NAMES.length; i++) choices[i] = "SYNTH · " + Synth.NAMES[i];
        for (int i = 0; i < packs.size(); i++) choices[Synth.NAMES.length + i] = "SAMPLED · " + packs.get(i).name;
        new AlertDialog.Builder(a).setTitle("Instrument for " + c.name).setItems(choices, (d, i) -> {
            final Synth.Config config = i < Synth.NAMES.length ? Synth.factory(i) : c.instrument == null ? a.synthConfig.copy() : c.instrument.copy();
            a.busy("Loading instrument…", () -> {
                SampledInstruments.Pack pack = i < Synth.NAMES.length ? null : a.instrumentLibrary.load(packs.get(i - Synth.NAMES.length));
                String packId = i < Synth.NAMES.length ? null : packs.get(i - Synth.NAMES.length).filename;
                Core.Clip probe = c.copy(); probe.instrument = config; probe.samplePack = pack; probe.instrumentPackId = packId; ProMidi.refresh(probe);
                a.runOnUiThread(() -> { edit("Change instrument"); c.instrument = config; c.samplePack = pack; c.instrumentPackId = packId; c.source = probe.source; c.peaks = probe.peaks; c.length = probe.length; c.envelopeLength = probe.envelopeLength; a.save(false); a.refresh(); a.toast(c.name + " now plays " + (pack == null ? Synth.NAMES[config.patch] : pack.name) + "."); });
            });
        }).show();
    }
    void convertToLive(Core.Track t, Core.Clip c) {
        final File dir = new File(a.session.directory); final Synth.Config cfg = a.synthConfig.copy();
        a.busy("Converting to a live MIDI clip…", () -> { List<Pro.Note> notes; try (InputStream in = new FileInputStream(new File(dir, c.midiFile))) { notes = Pro.notes(Pro.readMidi(in), null); }
            Core.Clip live = ProMidi.clip(c.name, notes, cfg, c.start - c.offset < 0 ? 0 : c.start - c.offset, c.offset + c.length); live.offset = Math.min(c.offset, live.source.frames() - 1); live.length = Math.max(1, Math.min(c.length, live.source.frames() - live.offset)); live.start = c.start; live.gain = c.gain; live.fadeIn = c.fadeIn; live.fadeOut = c.fadeOut; live.envelopeOffset = live.offset;
            a.runOnUiThread(() -> { edit("Convert to live MIDI"); int i = t.clips.indexOf(c); if (i >= 0) t.clips.set(i, live); else t.clips.add(live); a.clip = live; a.save(false); a.refresh(); a.toast("Now a live MIDI clip using " + Synth.NAMES[cfg.patch] + ". The original printed audio is kept in the project files."); }); });
    }
    void stretch(Core.Track t, Core.Clip c) {
        final Core.Session s = a.session; final int original = c.stretchFile != null ? c.stretchLength : c.length; final double current = c.stretchFile != null ? c.stretchRatio : 1;
        String[] options = {"Fit to a number of bars…", "From the clip's own tempo (BPM) to the project tempo…", "By percentage…", c.stretchFile != null ? "Reset to original length" : "Halve speed (×2 length)", "Double speed (×½ length)"};
        new AlertDialog.Builder(a).setTitle(String.format(Locale.US, "Time-stretch %s · now %.1f%%", c.name, current * 100)).setItems(options, (d, i) -> {
            if (i == 0) a.input("Length in bars", String.format(Locale.US, "%.2f", c.length / (Core.beat(s.bpm) * (double) s.timeSignature)), true, v -> doStretch(t, c, ProMidi.barsRatio(original, Double.parseDouble(v), s.bpm, s.timeSignature)));
            else if (i == 1) a.input("The clip's original tempo (BPM)", "90", true, v -> doStretch(t, c, ProMidi.tempoRatio(Double.parseDouble(v), s.bpm)));
            else if (i == 2) a.input("New length in % of the original (50–200)", String.format(Locale.US, "%.1f", current * 100), true, v -> doStretch(t, c, Double.parseDouble(v) / 100));
            else if (i == 3) doStretch(t, c, c.stretchFile != null ? 1 : 2);
            else doStretch(t, c, .5);
        }).show();
    }
    /** Renders the clip's original audio at a new length with pitch-preserving WSOLA, keeping the original for later changes. */
    void doStretch(Core.Track t, Core.Clip c, double ratio) {
        if (!(ratio >= .5 && ratio <= 2)) throw new IllegalArgumentException("Use 50%–200% of the original length (the stretcher works best within that range).");
        final File dir = new File(a.session.directory); final String origFile = c.stretchFile != null ? c.stretchFile : c.file; final int origOffset = c.stretchFile != null ? c.stretchOffset : c.offset, origLength = c.stretchFile != null ? c.stretchLength : c.length;
        final Core.Source current = c.source; final String currentFile = c.file;
        a.busy("Time-stretching (pitch kept)…", () -> {
            Core.Source orig = origFile.equals(currentFile) ? current : new AudioIO.Disk(new File(dir, origFile));
            File out = new File(dir, UUID.randomUUID() + ".pcm"); boolean ok = false;
            try {
                if (Math.abs(ratio - 1) < 1e-6) { try (AudioIO.Writer w = new AudioIO.Writer(out)) { for (int i = 0; i < origLength; i++) w.sample(orig.sample(origOffset + i, 0), orig.sample(origOffset + i, 1)); } }
                else try (AudioIO.Writer w = new AudioIO.Writer(out)) { TimeStretch.preservePitch(ProMidi.window(orig, origOffset, origLength), 1 / ratio, (l, r) -> w.sample(l, r)); }
                Core.Clip n = new Core.Clip(c.name, new AudioIO.Disk(out), out.getName(), c.start); n.gain = c.gain; n.fadeIn = (int) Math.round(c.fadeIn * ratio / Math.max(.01, c.stretchFile != null ? c.stretchRatio : 1)); n.fadeOut = (int) Math.round(c.fadeOut * ratio / Math.max(.01, c.stretchFile != null ? c.stretchRatio : 1));
                n.length = (int) Math.min(n.length, Core.MAX_FRAMES - n.start); n.envelopeLength = Math.max(n.envelopeLength, n.length);
                if (Math.abs(ratio - 1) >= 1e-6) { n.stretchFile = origFile; n.stretchOffset = origOffset; n.stretchLength = origLength; n.stretchRatio = ratio; }
                ok = true;
                a.runOnUiThread(() -> { edit("Time-stretch"); int i = t.clips.indexOf(c); if (i >= 0) t.clips.set(i, n); else t.clips.add(n); a.clip = n; a.save(false); a.refresh(); a.toast(String.format(Locale.US, "Stretched to %.1f%% of the original. Stretch again any time; it always starts from the original audio.", ratio * 100)); });
            } finally { if (!ok) out.delete(); }
        });
    }

    // ---------------------------------------------------------------- 0.10: audio quantize, launcher, controller mapping

    void quantizeAudio(Core.Track t, Core.Clip c) {
        if (c.notes != null) { a.toast("For MIDI clips use Quantize in the piano roll."); return; }
        LinearLayout p = page(); final double[] v = {100, 50}; final int[] grid = {prefs().getInt("aqGrid", 2)};
        p.addView(label("GRID", 11, MainActivity.DIM, true)); p.addView(spinner(new String[]{"1/4 notes", "1/8 notes", "1/16 notes", "1/32 notes"}, grid[0], i -> grid[0] = i));
        a.knobs(p, knob("STRENGTH", 0, 100, "%", val(() -> v[0] / 100, x -> v[0] = x * 100)), knob("SENSITIVITY", 0, 100, "%", val(() -> v[1] / 100, x -> v[1] = x * 100)));
        p.addView(label("Finds each hit, cuts the clip there and moves every hit toward the grid, with short crossfades between slices. Best on drums, percussion and plucked parts. Undo restores the original clip.", 10, MainActivity.DIM, false));
        new AlertDialog.Builder(a).setTitle("Quantize audio · " + c.name).setView(scroll(p)).setNegativeButton("Cancel", null).setPositiveButton("Quantize", (d, w) -> {
            prefs().edit().putInt("aqGrid", grid[0]).apply(); a.engine.pause(); edit("Quantize audio");
            List<Core.Clip> slices = ProLive.quantizeAudio(t, c, map(), 1 << grid[0], v[0] / 100, v[1] / 100, Math.min(a.session.crossfadeFrames, Core.RATE / 200));
            if (slices.isEmpty()) { a.undo(); a.toast("No clear hits were found. Raise the sensitivity."); return; }
            a.clip = slices.get(0); a.save(false); a.refresh(); a.toast(slices.size() + " hits lined up to the grid.");
        }).show();
    }
    void putInSlot(Core.Track t, Core.Clip c) {
        String[] slots = new String[ProLive.SLOTS]; for (int i = 0; i < slots.length; i++) { Core.Clip x = i < t.slots.size() ? t.slots.get(i) : null; slots[i] = "Slot " + (i + 1) + (x == null ? " · empty" : " · " + x.name); }
        new AlertDialog.Builder(a).setTitle("Put “" + c.name + "” in a launcher slot").setItems(slots, (d, i) -> { edit("Launcher slot"); ProLive.put(t, i, c); a.save(false); a.toast("Added to " + t.name + ", slot " + (i + 1) + ". Open Pro tools → Clip launcher to play it."); }).show();
    }

    AlertDialog launcherDialog;
    void launcher() {
        LinearLayout p = page(); LinearLayout grid = a.column(); p.addView(grid);
        p.addView(label("Tap a clip to start it on the next bar; it loops and replaces that track's arrangement until stopped. Tap a scene to start a whole column. Put clips in slots from each clip's menu.", 10, MainActivity.DIM, false));
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            grid.removeAllViews(); Core.Session s = a.session; long pos = a.engine.position; int cols = 4;
            for (Core.Track t : s.tracks) for (int i = 0; i < t.slots.size(); i++) if (t.slots.get(i) != null) cols = Math.max(cols, Math.min(ProLive.SLOTS, i + 1));
            LinearLayout scenes = new LinearLayout(a); grid.addView(scenes);
            scenes.addView(label("SCENES", 10, MainActivity.DIM, true), new LinearLayout.LayoutParams(dp(72), dp(44)));
            for (int i = 0; i < cols; i++) { final int col = i; Button b = button("▶ " + (i + 1), () -> { startIfStopped(); ProLive.scene(s, col, a.engine.position, a.engine.playing); draw[0].run(); }); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1); lp.rightMargin = dp(3); scenes.addView(b, lp); }
            for (Core.Track t : s.tracks) {
                LinearLayout row = new LinearLayout(a); row.setGravity(Gravity.CENTER_VERTICAL); LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = dp(4); grid.addView(row, rl);
                Button stop = button("■ " + shorten(t.name, 7), () -> { ProLive.stop(s, t, a.engine.position, a.engine.playing); draw[0].run(); }); row.addView(stop, new LinearLayout.LayoutParams(dp(72), dp(48)));
                for (int i = 0; i < cols; i++) {
                    final int slot = i; Core.Clip c = i < t.slots.size() ? t.slots.get(i) : null; boolean active = t.launched != null && t.launchSlot == i && t.launchStop == Long.MAX_VALUE;
                    String text = c == null ? "+" : (active ? (pos >= t.launchAt ? "▶ " : "◷ ") : "") + shorten(c.name, 8);
                    Button b = button(text, () -> { if (c == null) { if (a.clip != null) { edit("Launcher slot"); ProLive.put(t, slot, a.clip); a.save(false); draw[0].run(); } else a.toast("Select a clip in the arrangement first, then tap + to put it here."); return; } startIfStopped(); ProLive.launch(s, t, slot, a.engine.position, a.engine.playing); draw[0].run(); });
                    if (c != null) b.setOnLongClickListener(v -> { slotMenu(t, slot, draw[0]); return true; });
                    if (active) b.setTextColor(MainActivity.ACCENT);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1); lp.rightMargin = dp(3); row.addView(b, lp);
                }
            }
            row(grid, button("Stop all", () -> { ProLive.stopAll(s, a.engine.position, a.engine.playing); draw[0].run(); }), button(a.engine.playing ? "Ⅱ Pause" : "▶ Play", () -> { a.togglePlay(); draw[0].run(); }));
        };
        draw[0].run();
        launcherDialog = new AlertDialog.Builder(a).setTitle("Clip launcher").setView(scroll(p)).setPositiveButton("Done", (d, w) -> a.save(false)).show();
        a.handler.postDelayed(new Runnable() { public void run() { if (launcherDialog == null || !launcherDialog.isShowing()) return; draw[0].run(); a.handler.postDelayed(this, 400); } }, 400);
    }
    static String shorten(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }
    void startIfStopped() { if (!a.engine.playing && a.requestAudioFocus()) { a.engine.loop = false; a.engine.play(a.session); } }
    void slotMenu(Core.Track t, int slot, Runnable redraw) {
        Core.Clip c = t.slots.get(slot);
        new AlertDialog.Builder(a).setTitle(c.name).setItems(new String[]{"Copy to arrangement at playhead", "Replace with selected clip", "Clear slot"}, (d, i) -> {
            edit("Launcher slot");
            if (i == 0) { Core.Clip x = c.copy(); x.start = snap(a.engine.position); if (x.start + x.length <= Core.MAX_FRAMES) t.clips.add(x); a.refresh(); }
            else if (i == 1) { if (a.clip == null) { a.toast("Select a clip first."); return; } ProLive.put(t, slot, a.clip); }
            else { t.slots.set(slot, null); if (t.launchSlot == slot) t.launched = null; }
            a.save(false); redraw.run();
        }).show();
    }

    // MIDI controller mapping ------------------------------------------------
    volatile String learnTarget; volatile String learnLabel;
    /** Called for every incoming MIDI message; returns true when it was used as a control (so it does not play a note). */
    boolean midiControl(int status, int x, int y) {
        int type = status & 0xF0; boolean cc = type == 0xB0, noteOn = type == 0x90 && y > 0; if (!cc && !noteOn && type != 0x80) return false;
        Core.Session s = a.session; if (s == null) return false;
        String learn = learnTarget;
        if (learn != null && (cc || noteOn)) { learnTarget = null; final ProLive.Mapping m = new ProLive.Mapping(-1, x, noteOn, learn); final String what = learnLabel;
            a.handler.post(() -> { s.midiMap.removeIf(o -> o.number == m.number && o.note == m.note); s.midiMap.add(m); a.save(false); a.toast(m.label() + " now controls " + what + "."); }); return true; }
        for (ProLive.Mapping m : s.midiMap) if (m.matches(status, x) || (m.note && type == 0x80 && x == m.number) || (m.note && type == 0x90 && y == 0 && x == m.number)) {
            if (!noteOn && !cc) return true; final int value = y;
            a.handler.post(() -> { String action = ProLive.apply(s, m, value); if (action != null) transport(action); if (a.mixer != null) a.mixer.invalidate(); if (a.timeline != null) a.timeline.invalidate(); });
            return true;
        }
        return false;
    }
    void transport(String action) {
        switch (action) {
            case ProLive.PLAY: a.togglePlay(); break; case ProLive.RECORD: a.record(); break;
            case ProLive.LOOP: a.engine.loop = !a.engine.loop; a.refresh(); break;
            case ProLive.UNDO: a.undo(); break;
            case ProLive.MARKER: edit("Add marker"); a.session.markers.add(new Pro.Marker(a.engine.position, "Marker " + (a.session.markers.size() + 1))); a.save(false); a.refresh(); break;
        }
    }
    void midiMapping() {
        LinearLayout p = page(); Core.Session s = a.session;
        p.addView(label(a.midi != null && a.midi.connected ? "Controller: " + a.midi.name : "No controller connected: connect one from the project menu → MIDI controller input.", 12, MainActivity.TEXT, true));
        if (s.midiMap.isEmpty()) p.addView(label("No mappings yet.", 12, MainActivity.DIM, false));
        for (ProLive.Mapping m : s.midiMap) p.addView(button(m.label() + "  →  " + describe(m.target) + "   ✕", () -> { edit("Remove MIDI mapping"); s.midiMap.remove(m); a.save(false); reopen(this::midiMapping); }), new LinearLayout.LayoutParams(-1, dp(44)));
        p.addView(button("+ Learn a new mapping…", this::chooseLearnTarget), new LinearLayout.LayoutParams(-1, dp(48)));
        p.addView(label("Pick what to control, then move a knob or fader (CC) or hit a pad (note) on your controller. Mapped notes and knobs no longer play the synth. Mappings are saved with the project.", 10, MainActivity.DIM, false));
        lastDialog = show("MIDI controller mapping", p);
    }
    String describe(String target) {
        String[] p = target.split(":", 3); if (p[0].equals("transport")) return "Transport · " + p[1]; if (p[0].equals("master")) return "Master volume";
        if (p.length < 3) return target; Core.Track t = a.session.track(p[1]); if (t == null) t = a.session.bus(p[1]); String who = t == null ? "(removed)" : t.name;
        if (p[2].startsWith("send:")) { Core.Track b = a.session.bus(p[2].substring(5)); return who + " · send → " + (b == null ? "?" : b.name); }
        if (p[2].startsWith("insert:")) { String[] q = p[2].split(":"); if (t != null && q.length == 3) for (Pro.Insert i : t.inserts) if (i.id.equals(q[1])) return who + " · " + i.info().title + " · " + i.info().names[Integer.parseInt(q[2])]; return who + " · insert"; }
        return who + " · " + p[2];
    }
    void chooseLearnTarget() {
        ArrayList<String> targets = new ArrayList<>(), names = new ArrayList<>(); Core.Session s = a.session;
        String[] transport = {ProLive.PLAY, ProLive.RECORD, ProLive.LOOP, ProLive.MARKER, ProLive.UNDO}; for (String t : transport) { targets.add(t); names.add(describe(t)); }
        targets.add("master:volume"); names.add("Master volume");
        Core.Track sel = a.selected;
        for (Core.Track t : s.tracks) { for (String f : new String[]{"volume", "pan", "mute", "solo"}) { targets.add("track:" + t.id + ":" + f); names.add(t.name + " · " + f); } for (Pro.Send x : t.sends) { targets.add("track:" + t.id + ":send:" + x.bus); names.add(describe("track:" + t.id + ":send:" + x.bus)); } }
        for (Core.Track b : s.buses) { targets.add("track:" + b.id + ":volume"); names.add(b.name + " (bus) · volume"); }
        if (sel != null) for (Pro.Insert i : sel.inserts) for (int k = 0; k < i.info().names.length; k++) { targets.add("track:" + sel.id + ":insert:" + i.id + ":" + k); names.add(sel.name + " · " + i.info().title + " · " + i.info().names[k]); }
        new AlertDialog.Builder(a).setTitle("Control what?").setItems(names.toArray(new String[0]), (d, i) -> { learnTarget = targets.get(i); learnLabel = names.get(i); a.toast("Now move a knob or hit a pad on your controller…"); }).show();
    }

    // ---------------------------------------------------------------- 0.11: Logic-style snapping and drag modes

    long guideFrame = -1; String guideText; int dragEdge; long dragStart; int dragOffset, dragLength, dragEnv;
    /** Called when a finger lands on a clip: near an edge (and the clip is wide enough) it trims, otherwise it moves. */
    void beginDrag(Core.Clip c, float x, float head, float bp) {
        double beat = Core.beat(a.session.bpm); float left = head + (float) (c.start / beat * bp) - a.timelineScroll, right = left + (float) (c.length / beat * bp);
        dragEdge = right - left < dp(56) ? 0 : Math.abs(x - left) < dp(16) ? -1 : Math.abs(x - right) < dp(16) ? 1 : 0;
        dragStart = c.start; dragOffset = c.offset; dragLength = c.length; dragEnv = c.envelopeOffset; guideFrame = -1; guideText = null;
    }
    void drag(Core.Track t, Core.Clip c, long oldStart, float mx, float bp, boolean bypass) {
        Core.Session s = a.session; Pro.TempoMap m = map(); long delta = (long) (mx / bp * Core.beat(s.bpm)); int div = ProSnap.effectiveDivision(s, bp, dp(14)); long magnet = (long) (dp(12) / bp * Core.beat(s.bpm));
        ProSnap.Result r;
        if (dragEdge == 0) { r = ProSnap.move(s, m, c, oldStart, oldStart + delta, a.engine.position, div, magnet, bypass); c.start = Math.min(r.frame, Core.MAX_FRAMES - c.length); }
        else {
            long proposed = dragEdge < 0 ? dragStart + delta : dragStart + dragLength + delta;
            r = ProSnap.point(s, m, c, proposed, a.engine.position, div, magnet, bypass, true); long f = r.frame;
            if (!r.magnet && prefs().getBoolean("zeroCross", true) && c.notes == null) { Core.Clip probe = c.copy(); probe.start = dragStart; probe.offset = dragOffset; f = ProSnap.zeroCrossing(probe, f, Core.RATE / 500); }
            if (dragEdge < 0) ProSnap.trimStart(c, dragStart, dragOffset, dragLength, dragEnv, f); else ProSnap.trimEnd(c, f);
        }
        boolean locked = r.guide >= 0 && r.guide != guideFrame; guideFrame = r.guide; guideText = r.what;
        if (locked && a.timeline != null) a.timeline.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
    }
    void drop(Core.Track t, Core.Clip c) { if (c != null && dragEdge == 0) ProSnap.drop(a.session, t, c); guideFrame = -1; guideText = null; dragEdge = 0; }
    long snapSeek(long frame, float bp) { Core.Session s = a.session; ProSnap.Result r = ProSnap.point(s, map(), null, frame, a.engine.position, ProSnap.effectiveDivision(s, bp, dp(14)), (long) (dp(12) / bp * Core.beat(s.bpm)), false, false); return r.frame; }
    String snapLabel() { Core.Session s = a.session; if (s == null) return "Snap"; int d = s.snapDivision; String base = d == ProSnap.SMART ? "Smart" : d == ProSnap.BAR ? "Bar" : d == 0 ? "Snap off" : "1/" + (4 * d); return base + (s.magnet ? " ⦿" : "") + " ▾"; }
    void snapDialog() {
        Core.Session s = a.session; LinearLayout p = page();
        String[] names = {"Smart · follows zoom", "Bar", "Beat · 1/4", "1/8", "1/16", "1/32", "1/64", "Off · free placement"}; int[] values = {ProSnap.SMART, ProSnap.BAR, 1, 2, 4, 8, 16, 0}; int sel = 0; for (int i = 0; i < values.length; i++) if (values[i] == s.snapDivision) sel = i;
        p.addView(label("SNAP TO", 11, MainActivity.DIM, true)); p.addView(spinner(names, sel, i -> { edit("Snap"); s.snapDivision = values[i]; a.save(false); a.updateEditKeys(); }));
        p.addView(toggle("Magnetic: lock to clip edges, markers, playhead and loop edges", s.magnet, en(() -> s.magnet, v -> { s.magnet = v; a.updateEditKeys(); })));
        p.addView(toggle("Relative: move by whole steps, keeping the clip's offset from the grid", s.snapRelative, en(() -> s.snapRelative, v -> s.snapRelative = v)));
        p.addView(prefToggle("Trim edges at zero crossings (avoids clicks)", "zeroCross", true, null));
        p.addView(label("DRAG MODE", 11, MainActivity.DIM, true)); p.addView(spinner(new String[]{"Overlap · clips stack", "No Overlap · trims what's underneath", "X-Fade · crossfades overlaps", "Shuffle · clips push each other along"}, s.dragMode, i -> { edit("Drag mode"); s.dragMode = i; a.save(false); }));
        p.addView(label("Drag a clip's middle to move it, or its left or right edge to trim it. A yellow guide and a short buzz show when it locks to something. Rest a second finger on the screen while dragging to place it freely.", 10, MainActivity.DIM, false));
        show("Snap & drag", p);
    }
}
