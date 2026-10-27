package com.mycompany.admin.dashboard;
 
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
 
/**
 * MiniJson - Bộ phân tích JSON tối giản, không cần thư viện ngoài.
 *
 * parse() trả về:
 *   JSON object -> Map<String, Object>
 *   JSON array  -> List<Object>
 *   chuỗi       -> String
 *   số          -> Long (số nguyên) hoặc Double (số thực)
 *   true/false  -> Boolean,  null -> null
 *
 * quote() dùng để ghép chuỗi vào JSON gửi đi một cách an toàn
 * (escape dấu " và \), tránh lỗi "JSON injection".
 */
public final class MiniJson {
 
    private final String s;
    private int pos;
 
    private MiniJson(String s) {
        this.s = s;
    }
 
    public static Object parse(String json) {
        MiniJson p = new MiniJson(json);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (p.pos != p.s.length()) {
            throw p.error("Dư ký tự sau khi kết thúc JSON");
        }
        return v;
    }
 
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object v = parse(json);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("JSON không phải object");
        }
        return (Map<String, Object>) v;
    }
 
    /** Bọc chuỗi trong dấu nháy kép và escape ký tự đặc biệt. */
    public static String quote(String value) {
        if (value == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
 
    // ------------------------------------------------------------------
 
    private Object readValue() {
        if (pos >= s.length()) throw error("JSON kết thúc đột ngột");
        char c = s.charAt(pos);
        switch (c) {
            case '{': return readObject();
            case '[': return readArray();
            case '"': return readString();
            case 't': expect("true");  return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null");  return null;
            default:
                if (c == '-' || Character.isDigit(c)) return readNumber();
                throw error("Ký tự không hợp lệ '" + c + "'");
        }
    }
 
    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // bỏ '{'
        skipWs();
        if (peek() == '}') { pos++; return map; }
        while (true) {
            skipWs();
            if (peek() != '"') throw error("Cần tên trường dạng chuỗi");
            String key = readString();
            skipWs();
            if (peek() != ':') throw error("Thiếu dấu ':'");
            pos++;
            skipWs();
            map.put(key, readValue());
            skipWs();
            char c = peek();
            pos++;
            if (c == '}') return map;
            if (c != ',') throw error("Thiếu dấu ',' hoặc '}'");
        }
    }
 
    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // bỏ '['
        skipWs();
        if (peek() == ']') { pos++; return list; }
        while (true) {
            skipWs();
            list.add(readValue());
            skipWs();
            char c = peek();
            pos++;
            if (c == ']') return list;
            if (c != ',') throw error("Thiếu dấu ',' hoặc ']'");
        }
    }
 
    private String readString() {
        StringBuilder sb = new StringBuilder();
        pos++; // bỏ dấu " mở
        while (true) {
            if (pos >= s.length()) throw error("Chuỗi chưa đóng");
            char c = s.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = s.charAt(pos++);
            switch (e) {
                case '"': case '\\': case '/': sb.append(e); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default: throw error("Escape không hợp lệ \\" + e);
            }
        }
    }
 
    private Number readNumber() {
        int start = pos;
        if (peek() == '-') pos++;
        while (pos < s.length() && "0123456789.eE+-".indexOf(s.charAt(pos)) >= 0) pos++;
        String num = s.substring(start, pos);
        if (num.contains(".") || num.contains("e") || num.contains("E")) {
            return Double.parseDouble(num);
        }
        return Long.parseLong(num);
    }
 
    private void expect(String word) {
        if (!s.startsWith(word, pos)) throw error("Cần '" + word + "'");
        pos += word.length();
    }
 
    private char peek() {
        if (pos >= s.length()) throw error("JSON kết thúc đột ngột");
        return s.charAt(pos);
    }
 
    private void skipWs() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }
 
    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException(msg + " (vị trí " + pos + ")");
    }
}