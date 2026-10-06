package one.modality.ecommerce.document.service.spi.impl.server;

/**
 * Whether a booking has gained lines since a client loaded the version its changes were built on.
 *
 * <p>A front-office modification is a diff against the booking the client loaded, and the server
 * applies it as sent. A client still holding that booking after its own submit sent the same diff
 * again and the same lines were added twice — bookings 674 and 1164 paid their shuttles twice after a
 * reconnect took the wizard back to the manage page. So the client says what it loaded (the highest
 * document line and attendance ids, see {@code SubmitDocumentChangesArgument.baseDocument}) and the
 * submit is refused when the booking now holds a higher one of either.
 *
 * <p>Both ids come from sequences, so anything added since the load is higher, whoever added it — a
 * submit from another tab, or the back office splitting a line. Letters, payments and flags add no
 * line or attendance and never count. Edits to existing rows (a cancellation, a date change) create no
 * id and are not seen: they cannot duplicate anything, which is what this guards against.
 *
 * <p>Pure on purpose, like {@link MateLinkRules}: {@link ServerDocumentServiceProvider} loads the
 * current ids and this only compares, so {@code BookingChangedRuleCheck} covers it without a server.
 */
final class BookingChangedRule {

    private BookingChangedRule() {}

    /**
     * @param baseLastLine       the highest line id the client loaded (absent or 0 when it had none)
     * @param baseLastAttendance the highest attendance id the client loaded (absent or 0 when it had none)
     * @param lastLine           the booking's highest line id now (null when it has none)
     * @param lastAttendance     the booking's highest attendance id now (null when it has none)
     * @return true when the booking holds a line or an attendance the client did not load
     */
    static boolean changedSince(Object baseLastLine, Object baseLastAttendance, Object lastLine, Object lastAttendance) {
        return idOf(lastLine) > idOf(baseLastLine) || idOf(lastAttendance) > idOf(baseLastAttendance);
    }

    /**
     * An id as a long: a number from the database or the wire (JSON may bring it as a double), or its
     * text. Absent or unreadable reads as 0 — "nothing loaded", so on the base side a garbled value can
     * only make the check stricter, never let a changed booking through.
     */
    static long idOf(Object id) {
        if (id instanceof Number number)
            return number.longValue();
        if (id instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
