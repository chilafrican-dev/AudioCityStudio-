package org.json;
import java.util.*;
/** Minimal host-only subset of Android's org.json.JSONObject for project-format tests. Not included in the APK. */
public class JSONObject {
    public static final Object NULL = new Object() { public String toString() { return "null"; } };
    final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    public JSONObject() {}
    public JSONObject(String text) throws JSONException { Parser p = new Parser(text); Object v = p.value(); p.end(); if (!(v instanceof JSONObject)) throw new JSONException("Not an object"); map.putAll(((JSONObject) v).map); }
    public JSONObject put(String k, Object v) throws JSONException { if (k == null) throw new JSONException("null key"); if (v == null) { map.remove(k); return this; } if (v instanceof Double && !Double.isFinite((Double) v)) throw new JSONException("Non-finite number"); map.put(k, v); return this; }
    public JSONObject put(String k, double v) throws JSONException { return put(k, (Object) v); }
    public JSONObject put(String k, int v) throws JSONException { return put(k, (Object) v); }
    public JSONObject put(String k, long v) throws JSONException { return put(k, (Object) v); }
    public JSONObject put(String k, boolean v) throws JSONException { return put(k, (Object) v); }
    public boolean has(String k) { return map.containsKey(k); }
    public Object remove(String k) { return map.remove(k); }
    public boolean isNull(String k) { Object v = map.get(k); return v == null || v == NULL; }
    public int length() { return map.size(); }
    public Iterator<String> keys() { return map.keySet().iterator(); }
    public Object opt(String k) { return map.get(k); }
    public Object get(String k) throws JSONException { Object v = map.get(k); if (v == null) throw new JSONException("No value for " + k); return v; }
    static Double num(Object v) { if (v instanceof Number) return ((Number) v).doubleValue(); if (v instanceof String) try { return Double.valueOf((String) v); } catch (NumberFormatException e) { return null; } return null; }
    public double optDouble(String k) { return optDouble(k, Double.NaN); }
    public double optDouble(String k, double d) { Double n = num(map.get(k)); return n == null ? d : n; }
    public int optInt(String k) { return optInt(k, 0); }
    public int optInt(String k, int d) { Double n = num(map.get(k)); return n == null ? d : (int) n.doubleValue(); }
    public long optLong(String k) { return optLong(k, 0); }
    public long optLong(String k, long d) { Object v = map.get(k); if (v instanceof Long || v instanceof Integer) return ((Number) v).longValue(); Double n = num(v); return n == null ? d : (long) n.doubleValue(); }
    public boolean optBoolean(String k) { return optBoolean(k, false); }
    public boolean optBoolean(String k, boolean d) { Object v = map.get(k); if (v instanceof Boolean) return (Boolean) v; if (v instanceof String) { if ("true".equalsIgnoreCase((String) v)) return true; if ("false".equalsIgnoreCase((String) v)) return false; } return d; }
    public String optString(String k) { return optString(k, ""); }
    public String optString(String k, String d) { Object v = map.get(k); return v == null ? d : String.valueOf(v); }
    public JSONObject optJSONObject(String k) { Object v = map.get(k); return v instanceof JSONObject ? (JSONObject) v : null; }
    public JSONArray optJSONArray(String k) { Object v = map.get(k); return v instanceof JSONArray ? (JSONArray) v : null; }
    public String getString(String k) throws JSONException { Object v = get(k); return String.valueOf(v); }
    public int getInt(String k) throws JSONException { Double n = num(get(k)); if (n == null) throw new JSONException(k + " is not a number"); return (int) n.doubleValue(); }
    public long getLong(String k) throws JSONException { Object v = get(k); if (v instanceof Long || v instanceof Integer) return ((Number) v).longValue(); Double n = num(v); if (n == null) throw new JSONException(k + " is not a number"); return (long) n.doubleValue(); }
    public double getDouble(String k) throws JSONException { Double n = num(get(k)); if (n == null) throw new JSONException(k + " is not a number"); return n; }
    public boolean getBoolean(String k) throws JSONException { Object v = get(k); if (v instanceof Boolean) return (Boolean) v; throw new JSONException(k + " is not a boolean"); }
    public JSONObject getJSONObject(String k) throws JSONException { Object v = get(k); if (v instanceof JSONObject) return (JSONObject) v; throw new JSONException(k + " is not an object"); }
    public JSONArray getJSONArray(String k) throws JSONException { Object v = get(k); if (v instanceof JSONArray) return (JSONArray) v; throw new JSONException(k + " is not an array"); }
    public String toString() { StringBuilder b = new StringBuilder("{"); boolean first = true; for (Map.Entry<String, Object> e : map.entrySet()) { if (!first) b.append(','); first = false; quote(b, e.getKey()); b.append(':'); write(b, e.getValue()); } return b.append('}').toString(); }
    static void write(StringBuilder b, Object v) { if (v instanceof String) quote(b, (String) v); else if (v instanceof Double) { double d = (Double) v; if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d); else b.append(d); } else b.append(v); }
    static void quote(StringBuilder b, String s) { b.append('"'); for (char c : s.toCharArray()) { switch (c) { case '"': b.append("\\\""); break; case '\\': b.append("\\\\"); break; case '\n': b.append("\\n"); break; case '\r': b.append("\\r"); break; case '\t': b.append("\\t"); break; default: if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); } } b.append('"'); }

    static final class Parser {
        final String s; int i;
        Parser(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        void end() throws JSONException { ws(); if (i != s.length()) throw new JSONException("Trailing characters"); }
        Object value() throws JSONException {
            ws(); if (i >= s.length()) throw new JSONException("Unexpected end");
            char c = s.charAt(i);
            if (c == '{') { i++; JSONObject o = new JSONObject(); ws(); if (peek('}')) { i++; return o; } while (true) { ws(); Object k = value(); if (!(k instanceof String)) throw new JSONException("Expected key"); ws(); expect(':'); o.map.put((String) k, value()); ws(); if (peek(',')) { i++; continue; } expect('}'); return o; } }
            if (c == '[') { i++; JSONArray a = new JSONArray(); ws(); if (peek(']')) { i++; return a; } while (true) { a.list.add(value()); ws(); if (peek(',')) { i++; continue; } expect(']'); return a; } }
            if (c == '"') { i++; StringBuilder b = new StringBuilder(); while (true) { if (i >= s.length()) throw new JSONException("Unterminated string"); char d = s.charAt(i++); if (d == '"') return b.toString(); if (d == '\\') { char e = s.charAt(i++); switch (e) { case 'n': b.append('\n'); break; case 'r': b.append('\r'); break; case 't': b.append('\t'); break; case 'b': b.append('\b'); break; case 'f': b.append('\f'); break; case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break; default: b.append(e); } } else b.append(d); } }
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return NULL; }
            int st = i; while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(st, i); if (n.isEmpty()) throw new JSONException("Unexpected character " + c);
            try { if (n.matches("-?\\d+")) { long l = Long.parseLong(n); return l == (int) l ? (Object) (int) l : (Object) l; } return Double.parseDouble(n); } catch (NumberFormatException e) { throw new JSONException("Bad number " + n); }
        }
        boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }
        void expect(char c) throws JSONException { if (!peek(c)) throw new JSONException("Expected " + c + " at " + i); i++; }
    }
}
