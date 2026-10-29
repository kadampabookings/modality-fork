package one.modality.event.server.lifecycle;

import java.util.List;

/**
 * The few JSON texts the erasure endpoints answer with, written by hand and escaped with care.
 *
 * <p>Why not the WebFX AST: the replies carry explicit nulls ({@code "errorKey": null}), and whether a null
 * survives {@code set(key, null)} depends on which AST factory the server happens to load; and the grid is
 * stored as {@code jsonb}, whose text Postgres already guarantees to be valid JSON and which is embedded here
 * as it is rather than parsed and re-serialised. What remains is small enough to write out, and a writer this
 * size can be checked line by line — which matters, because the values include event and organization names
 * (the grid's {@code event/name}), and those can hold quotes, backslashes and control characters.
 *
 * <p>Escaping follows RFC 8259: {@code "} and {@code \} are escaped, every control character below U+0020
 * becomes an escape, and so do U+2028/U+2029 and any unpaired surrogate — valid JSON either way, but a lone
 * surrogate would otherwise be mangled on its way to UTF-8. Everything else passes through unchanged.
 *
 * @author Claude Code
 */
final class EventErasureJson {

    private EventErasureJson() {
    }

    /** A JSON object, key by key, in the order the keys are added. */
    static final class ObjectWriter {

        private final StringBuilder sb = new StringBuilder("{");
        private boolean empty = true;

        private StringBuilder key(String key) {
            if (!empty)
                sb.append(',');
            empty = false;
            return appendQuoted(key, sb).append(':');
        }

        /** A string value, or null. */
        ObjectWriter string(String key, String value) {
            appendQuoted(value, key(key));
            return this;
        }

        /** An integral number, or null. */
        ObjectWriter number(String key, Number value) {
            key(key).append(value == null ? "null" : Long.toString(value.longValue()));
            return this;
        }

        ObjectWriter bool(String key, boolean value) {
            key(key).append(value);
            return this;
        }

        /**
         * JSON text that is already valid — a {@code jsonb} column's text, or another writer's output — or
         * null. Never pass text that came from anywhere else: it is not checked.
         */
        ObjectWriter raw(String key, String json) {
            key(key).append(json == null ? "null" : json);
            return this;
        }

        String toJson() {
            return sb.toString() + '}';
        }
    }

    /** A JSON array of already-written JSON texts (the output of {@link ObjectWriter#toJson()}). */
    static String array(List<String> elements) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0)
                sb.append(',');
            sb.append(elements.get(i));
        }
        return sb.append(']').toString();
    }

    /** {@code value} as a JSON string literal, or {@code null}. */
    static String quote(String value) {
        return appendQuoted(value, new StringBuilder()).toString();
    }

    private static StringBuilder appendQuoted(String value, StringBuilder sb) {
        if (value == null)
            return sb.append("null");
        sb.append('"');
        for (int i = 0, n = value.length(); i < n; i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ')
                        appendUnicodeEscape(c, sb);
                    else if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(value.charAt(i + 1))) {
                        sb.append(c).append(value.charAt(i + 1)); // a valid pair: kept as it is
                        i++;
                    } else if (Character.isSurrogate(c))
                        appendUnicodeEscape(c, sb); // unpaired: escaped rather than mangled
                    else
                        sb.append(c);
                }
            }
        }
        return sb.append('"');
    }

    private static void appendUnicodeEscape(char c, StringBuilder sb) {
        String hex = Integer.toHexString(c);
        sb.append("\\u");
        for (int pad = hex.length(); pad < 4; pad++)
            sb.append('0');
        sb.append(hex);
    }
}
