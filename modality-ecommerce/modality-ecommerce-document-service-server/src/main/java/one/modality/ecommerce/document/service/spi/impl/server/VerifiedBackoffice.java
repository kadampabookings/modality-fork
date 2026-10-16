package one.modality.ecommerce.document.service.spi.impl.server;

import dev.webfx.stack.session.state.StateAccessor;

/**
 * Whether a document-service call really comes from the back office — the one question this service
 * grants on, asked of the VERIFIED audience rather than the client's own claim.
 *
 * <p>Until 2026-10-16 the service read {@code ThreadLocalStateHolder.isBackoffice()}, which is the flag
 * the client sets on its own messages and may change between any two of them. On the strength of that
 * claim alone, a caller could:
 * <ul>
 *   <li>load ANY booking by its primary key — the access check that limits a member to their account's
 *       bookings and a guest to their own email was skipped for "back-office callers", so an anonymous
 *       connection could walk the sequential ids and read names, emails, phones, lines and payments;</li>
 *   <li>book past the database rules the back office is trusted to override — EVENT_ON_HOLD,
 *       EVENT_CLOSED, DOUBLEBOOKING — because the submit chose the back-office transaction parameters;</li>
 *   <li>take a sharing place in a room with no free bed, the check being skipped for the back office.</li>
 * </ul>
 *
 * <p>{@link StateAccessor#getVerifiedBackoffice} is the audience signed into the identity token when the
 * credential was checked, which a caller cannot move by asking; the server clears it from every incoming
 * message before setting it from a token whose signature held. Only TRUE counts. NULL — no token, or one
 * that did not verify — is not an answer, so it is treated as the front office: that is the side whose
 * rules apply to anybody who cannot prove otherwise.
 *
 * <p>Consequences, accepted: the legacy GWT/JavaFX back office never obtains a token, so it is the front
 * office here (it is being retired, and KBS2 writes to the database directly). A local server with no
 * SESSION_TOKEN_SIGNING_KEY mints no tokens either, so the React back office is the front office there.
 *
 * <p>MUST be given the state read on the caller's thread, before any async step: the thread-local is
 * gone after the first hop, and an absent state reads as "not the back office" — safe, but wrong.
 */
final class VerifiedBackoffice {

    private VerifiedBackoffice() {
    }

    /** True only when a verified token says this message comes from the back office. */
    static boolean of(Object state) {
        return Boolean.TRUE.equals(StateAccessor.getVerifiedBackoffice(state));
    }
}
