package one.modality.event.server.lifecycle;

/**
 * Reading the erasure endpoints' arguments, which arrive codec-free from the React back office.
 *
 * <p>The bus decodes a JSON array into an {@code Object[]} and a JSON number into whichever boxed type fits
 * (Byte, Integer, Long, or Double when it had a fraction), so nothing here assumes one: an id is accepted from
 * any integral number, or from a digits-only string, and only when it is a positive {@code int}. Anything
 * else — a fraction, a negative, an overflow, a word — is no id at all, which the endpoints answer as
 * {@code EventErasureNotFoundError}: a malformed request and an unknown event get the same reply.
 *
 * <p>Read only once the caller has been allowed ({@link EventErasureAccess}): a caller who may not use the
 * endpoints learns only that, not what this server thinks of their arguments.
 *
 * @author Claude Code
 */
final class EventErasureArguments {

    private EventErasureArguments() {
    }

    /** The arguments as an array: an array as it came, null as empty, a single value as a one-element array. */
    static Object[] array(Object argument) {
        if (argument == null)
            return new Object[0];
        return argument instanceof Object[] array ? array : new Object[] { argument };
    }

    static Object at(Object[] arguments, int index) {
        return arguments != null && index < arguments.length ? arguments[index] : null;
    }

    /** A string argument as it came, or null for anything that is not a string. */
    static String string(Object value) {
        return value instanceof String s ? s : null;
    }

    /** A positive {@code int} id, or null when {@code value} is not exactly one. */
    static Integer positiveId(Object value) {
        long id;
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte)
            id = ((Number) value).longValue();
        else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (!Double.isFinite(d) || d != Math.rint(d) || d < 1 || d > Integer.MAX_VALUE)
                return null;
            id = (long) d;
        } else if (value instanceof String s && s.matches("[0-9]{1,10}"))
            id = Long.parseLong(s);
        else
            return null;
        return id >= 1 && id <= Integer.MAX_VALUE ? (int) id : null;
    }
}
