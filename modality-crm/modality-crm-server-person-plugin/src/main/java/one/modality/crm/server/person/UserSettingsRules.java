package one.modality.crm.server.person;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a user-settings patch may contain, and the JSON it becomes. Pure, so it is checked without a database.
 *
 * <p>The server does not know the settings catalogue — what {@code experimental.news} means lives in the
 * front-office code that reads it. So this judges SHAPE, not meaning, and the shape is chosen to keep personal
 * data out of {@code account_settings} (V0158): values are booleans, integers or short tokens. A token cannot
 * hold a sentence, an email address or a phone number with spaces, which is what lets the anonymiser classify
 * the column {@code kept}. A setting that ever needs free text does not belong in this table.
 *
 * <p>The argument is {@code [key, value, key, value, …]}, the name/value shape the person endpoints already use.
 * A {@code null} value removes the key, so the setting falls back to its default.
 *
 * <p>The JSON is built here rather than by a serialiser, and it is safe to build by hand only BECAUSE of the
 * validation: neither a key nor a token can contain a quote, a backslash or a control character, so nothing
 * needs escaping. Loosen either pattern and this must start escaping.
 *
 * @author Claude Code
 */
final class UserSettingsRules {

    private UserSettingsRules() {
    }

    /** More than a settings page ever sends at once; a bound on an array the caller chooses. */
    static final int MAX_KEYS = 32;

    static final int MAX_KEY_LENGTH = 64;

    /** Lower-camel segments joined by dots, at most four: {@code experimental.news}, {@code notify.podcasts}. */
    private static final Pattern KEY = Pattern.compile("[a-z][a-zA-Z0-9]*(\\.[a-z][a-zA-Z0-9]*){0,3}");

    static final int MAX_TOKEN_LENGTH = 64;

    /** An enum value or an identifier: {@code dark}, {@code en-GB}, {@code fr}. No space, quote or {@code @}. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.:-]*");

    /** Integers stay well inside both a Java long and a JavaScript number, so neither side rounds them. */
    static final long MAX_INTEGER = 1_000_000_000L;

    /**
     * The patch as a JSON object text, or null when the argument is not an acceptable patch.
     *
     * <p>One bad pair refuses the whole patch: applying the rest would store something the caller did not
     * ask for. A key given twice is refused too, rather than letting the last one win silently.
     */
    static String patchJsonOf(Object argument) {
        if (!(argument instanceof Object[] pairs) || pairs.length == 0 || pairs.length % 2 != 0
            || pairs.length / 2 > MAX_KEYS)
            return null;
        StringBuilder json = new StringBuilder("{");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (!(pairs[i] instanceof String key) || !isKey(key) || !seen.add(key))
                return null;
            String value = jsonValueOf(pairs[i + 1]);
            if (value == null)
                return null;
            if (i > 0)
                json.append(',');
            json.append('"').append(key).append("\":").append(value);
        }
        return json.append('}').toString();
    }

    static boolean isKey(String key) {
        return key.length() <= MAX_KEY_LENGTH && KEY.matcher(key).matches();
    }

    /** The JSON text of an acceptable value — {@code null} is the JSON null that removes a key — or Java null. */
    static String jsonValueOf(Object value) {
        if (value == null)
            return "null";
        if (value instanceof Boolean b)
            return b.toString();
        if (value instanceof String s)
            return s.length() <= MAX_TOKEN_LENGTH && TOKEN.matcher(s).matches() ? '"' + s + '"' : null;
        if (value instanceof Number n) {
            // JavaScript has one number type, so an integer may arrive as a Double. Accept it when it IS an
            // integer; a fraction, NaN or an infinity is not a setting value.
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d) || Math.abs(d) > MAX_INTEGER)
                return null;
            return Long.toString((long) d);
        }
        return null;
    }
}
