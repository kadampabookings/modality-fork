package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;

/**
 * What the endpoints of the Data Protection Officer's "Erase personal data" page share besides the anonymisation
 * itself ({@link ErasureFind}, {@link ErasureResidueCheck}, {@link ErasureApprovals}): the log prefix, the failure key,
 * the framing of refusals, and reading values back from a query. No register of requests is kept (V0174).
 *
 * <p>What the log says, everywhere: the endpoint, the caller's person id, ids and counts. Never what was searched
 * for, an email, a name or a phone — the page exists to make a person disappear, and a log line would outlive that.
 *
 * @author Claude Code
 */
final class DpoErasure {

    private DpoErasure() {
    }

    static final String LOG_PREFIX = "[dpo-erasure] ";

    /**
     * Anything the database did not answer (a failed read or write, a check that took too long): the text says what
     * did not happen, never the database's words, which stay in the log by SQLSTATE.
     */
    static final String SQL_KEY = "ErasureSqlError";

    /** A refusal in the "[Key] text" framing the back office maps; logged by key and caller only. */
    static <T> Future<T> refusal(String key, String text, String operation, Integer callerId) {
        Console.log(LOG_PREFIX + operation + " by person " + callerId + " refused: " + key);
        return Future.failedFuture("[%s] %s".formatted(key, text));
    }

    /**
     * A failure: a refusal already framed by this feature passes through as it is; anything else is logged by its
     * SQLSTATE (or exception class) and answered with {@link #SQL_KEY} and {@code text}.
     */
    static <T> Future<T> failure(Throwable cause, String operation, Integer callerId, String text) {
        String message = cause == null ? null : cause.getMessage();
        if (message != null && (message.startsWith("[Erasure") || message.startsWith("[" + EventErasureAccess.NOT_YOUR_ORGANIZATION_KEY)
                                || message.startsWith("[" + PersonAnonymisationRunner.BUSY_KEY))
            && message.indexOf(']') > 0) {
            Console.log(LOG_PREFIX + operation + " by person " + callerId + " refused: " + message.substring(1, message.indexOf(']')));
            return Future.failedFuture(message);
        }
        Console.log(LOG_PREFIX + operation + " by person " + callerId + " failed: " + EventErasureRunner.sqlStateOf(cause));
        return Future.failedFuture("[%s] %s".formatted(SQL_KEY, text));
    }

    /** Whether {@code s} holds a control character (below U+0020, or DEL): never part of an email, name or phone. */
    static boolean hasControlCharacter(String s) {
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F)
                return true;
        }
        return false;
    }

    static String text(Object value) {
        return value == null ? null : value.toString();
    }

    static Number number(Object value) {
        return value instanceof Number n ? n : null;
    }

    static boolean bool(Object value) {
        return Boolean.TRUE.equals(value);
    }
}
