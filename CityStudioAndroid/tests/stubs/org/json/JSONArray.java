package org.json;
import java.util.*;
/** Minimal host-only subset of Android's org.json.JSONArray for project-format tests. Not included in the APK. */
public class JSONArray {
    final ArrayList<Object> list = new ArrayList<>();
    public JSONArray() {}
    public JSONArray put(Object v) { list.add(v == null ? JSONObject.NULL : v); return this; }
    public JSONArray put(double v) throws JSONException { if (!Double.isFinite(v)) throw new JSONException("Non-finite number"); list.add(v); return this; }
    public JSONArray put(int v) { list.add(v); return this; }
    public JSONArray put(long v) { list.add(v); return this; }
    public JSONArray put(boolean v) { list.add(v); return this; }
    public int length() { return list.size(); }
    public Object get(int i) throws JSONException { if (i < 0 || i >= list.size()) throw new JSONException("Index " + i); return list.get(i); }
    public Object opt(int i) { return i < 0 || i >= list.size() ? null : list.get(i); }
    public JSONObject getJSONObject(int i) throws JSONException { Object v = get(i); if (v instanceof JSONObject) return (JSONObject) v; throw new JSONException("Not an object"); }
    public JSONArray getJSONArray(int i) throws JSONException { Object v = get(i); if (v instanceof JSONArray) return (JSONArray) v; throw new JSONException("Not an array"); }
    public JSONArray optJSONArray(int i) { Object v = opt(i); return v instanceof JSONArray ? (JSONArray) v : null; }
    public JSONObject optJSONObject(int i) { Object v = opt(i); return v instanceof JSONObject ? (JSONObject) v : null; }
    public String getString(int i) throws JSONException { return String.valueOf(get(i)); }
    public String optString(int i) { Object v = opt(i); return v == null ? "" : String.valueOf(v); }
    public int getInt(int i) throws JSONException { Double n = JSONObject.num(get(i)); if (n == null) throw new JSONException("Not a number"); return (int) n.doubleValue(); }
    public long getLong(int i) throws JSONException { Object v = get(i); if (v instanceof Long || v instanceof Integer) return ((Number) v).longValue(); Double n = JSONObject.num(v); if (n == null) throw new JSONException("Not a number"); return (long) n.doubleValue(); }
    public double getDouble(int i) throws JSONException { Double n = JSONObject.num(get(i)); if (n == null) throw new JSONException("Not a number"); return n; }
    public double optDouble(int i, double d) { Double n = JSONObject.num(opt(i)); return n == null ? d : n; }
    public long optLong(int i, long d) { Object v = opt(i); if (v instanceof Long || v instanceof Integer) return ((Number) v).longValue(); Double n = JSONObject.num(v); return n == null ? d : (long) n.doubleValue(); }
    public String toString() { StringBuilder b = new StringBuilder("["); for (int i = 0; i < list.size(); i++) { if (i > 0) b.append(','); JSONObject.write(b, list.get(i)); } return b.append(']').toString(); }
}
