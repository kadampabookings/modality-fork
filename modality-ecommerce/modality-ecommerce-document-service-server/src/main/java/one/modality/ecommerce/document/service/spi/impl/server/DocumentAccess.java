package one.modality.ecommerce.document.service.spi.impl.server;

import dev.webfx.stack.session.state.SystemUserId;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

/**
 * Who the caller is, and the clause that limits a booking query to what they may read.
 *
 * <p>The document service composes its own queries, so neither {@code ClientReadDenyList} nor
 * {@code ClientQueryGuard} sees them — those guard a client's own DQL. The rule therefore has to live
 * here, and it has to be the SAME rule everywhere, which is why it is one class rather than three copies.
 *
 * <p>The rule, unchanged from the one {@code loadBatchForDocumentPk} already applied by primary key:
 * <ul>
 *   <li>the SERVER itself reads anything, when it is acting on nobody's behalf — see {@link SystemUserId}
 *       below;</li>
 *   <li>a verified back office reads anything — {@link VerifiedBackoffice}, never the client's own claim;</li>
 *   <li>a member reads the bookings their account may access, via {@code accountCanAccessPersonOrders};</li>
 *   <li>a guest reads the bookings whose {@code person_email} is theirs;</li>
 *   <li>anybody else reads nothing.</li>
 * </ul>
 *
 * <p><b>Why it was needed in more than one place.</b> The by-primary-key path applied this and the
 * person/account path did not: it resolved booking ids from {@code person=$1 and event=$2 or id=$3} with
 * all three taken from the REQUEST, then loaded each with the check switched off, on the grounds that
 * round 1 had already filtered. Round 1 filtered by ids the caller chose, so it filtered nothing: sending
 * any person id with a target booking id returned that booking, and ids are sequential. The history path
 * had no check at all, on either of its branches, and {@code History.changes} holds the booking's events
 * — the names and emails as submitted.
 *
 * <p><b>The system is a caller too.</b> Not every reader is a request: the payment service removes the
 * unpaid options from a recurring booking when a payment is cancelled, and reads the booking's history to
 * find them. It runs after several async hops, so there is no principal on the thread by then — which this
 * rule would otherwise read as "refuse", breaking a cancellation rather than leaking anything. A
 * {@link SystemUserId} says the server is acting on nobody's behalf, and it CANNOT arrive from a client:
 * it has no serial codec, so it is not representable on the wire, and the only way onto a thread is server
 * code calling {@code SystemUserId.SYSTEM.run}/{@code callAndReturn}. That is already how this codebase
 * marks a system action — the same payment provider wraps its two {@code submitDocumentChanges} calls in it.
 *
 * <p><b>MUST be built on the caller's thread, before the first {@code .compose}.</b> The principal and the
 * verified audience are thread-locals that are gone after the first async hop, where they read as absent —
 * which is the safe direction for the audience (front office) and the UNSAFE direction for the principal
 * (no account and no guest email reads as "refuse", so a legitimate caller would be refused rather than a
 * stranger admitted; still wrong, and still a bug).
 *
 * @author Claude Code
 */
final class DocumentAccess {

    private final boolean unfiltered;
    private final Object accountId;
    private final String guestEmail;
    /** The principal's type, or null when the thread carried none. Kept for the refusal; see {@link #describeCaller}. */
    private final String principalType;

    private DocumentAccess(boolean unfiltered, Object accountId, String guestEmail, String principalType) {
        this.unfiltered = unfiltered;
        this.accountId = accountId;
        this.guestEmail = guestEmail;
        this.principalType = principalType;
    }

    /** Reads the caller from the thread. Call synchronously, before any async step. */
    static DocumentAccess ofCaller() {
        Object userId = ThreadLocalStateHolder.getUserId();
        boolean unfiltered = VerifiedBackoffice.of(ThreadLocalStateHolder.getThreadLocalState())
                          || userId instanceof SystemUserId;
        return new DocumentAccess(unfiltered,
            ServerDocumentServiceProvider.getUserAccountId(userId),
            ServerDocumentServiceProvider.getGuestEmail(userId),
            userId == null ? null : userId.getClass().getSimpleName());
    }

    /** For the checks: a caller of each kind, without a thread to read it from. */
    static DocumentAccess of(boolean unfiltered, Object accountId, String guestEmail) {
        return new DocumentAccess(unfiltered, accountId, guestEmail, null);
    }

    /** For the checks: a caller carrying a principal of the named type. */
    static DocumentAccess of(boolean unfiltered, Object accountId, String guestEmail, String principalType) {
        return new DocumentAccess(unfiltered, accountId, guestEmail, principalType);
    }

    /**
     * Why a refused caller was refused, for the log line — and ONLY the two cases that reach it.
     *
     * <p>{@link #readsNothing} is the only state that refuses, so a guest whose email does not match and a
     * member whose account has no access never arrive here: their rows are filtered out and they receive an
     * empty result, not an error. What is left is a caller with no account and no guest email, and the two
     * ways to be one are worth telling apart. No session at all is an ordinary unauthenticated request —
     * someone opening a booking link while signed out. A session that exists but yields NEITHER is a
     * session this service could not interpret, which is a bug rather than a visitor, and the principal's
     * TYPE is what identifies which kind.
     *
     * <p>A type name, never an id or an address: a person id identifies a person, and the refusal goes to
     * the log where this file keeps counts and names and no values.
     */
    String describeCaller() {
        return principalType == null ? "no session"
             : "a " + principalType + " carrying neither an account nor a guest email";
    }

    /** A verified back office, or the server acting on nobody's behalf, is not filtered. */
    boolean readsAnything() {
        return unfiltered;
    }

    /** Neither an account nor a guest email: there is nothing to filter BY, so nothing may be read. */
    boolean readsNothing() {
        return !unfiltered && accountId == null && guestEmail == null;
    }

    /**
     * The clause to AND into a query, for one whose rows reach a Document by the given paths.
     *
     * <p>{@code personPath} and {@code emailPath} differ per query: {@code person}/{@code person_email}
     * selecting from Document, {@code document.person}/{@code document.person_email} from History.
     *
     * <p>The index is the caller's to supply because each query already has its own parameters, and it is
     * the one thing here that cannot be checked by reading: a wrong index silently filters by the wrong
     * value. The checks assert the composed text.
     */
    String clause(int parameterIndex, String personPath, String emailPath) {
        if (readsAnything() || readsNothing())
            throw new IllegalStateException("clause() is for a filtered caller: ask readsAnything/readsNothing first");
        return accountId != null
            ? "accountCanAccessPersonOrders($" + parameterIndex + ", " + personPath + ")"
            : "lower(" + emailPath + ")=lower($" + parameterIndex + ")";
    }

    /** The value for the clause's parameter: the account for a member, the email for a guest. */
    Object parameter() {
        return accountId != null ? accountId : guestEmail;
    }
}
