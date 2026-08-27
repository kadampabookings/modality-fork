package one.modality.crm.shared.services.authn;

import dev.webfx.platform.util.Numbers;

import java.util.Objects;

/**
 * Represents a registered user who successfully logged in and who has an account in Modality. All users who logged in
 * using username/password are in this case. But also registered users who used another login method such as magic link
 * or SSO login will be finally recognized as registered user, and their userId will be an instance of this class.
 * Non-registered users will have an instance of ModalityGuestPrincipal instead.
 *
 * @author Bruno Salmon
 */
public final class ModalityUserPrincipal {

    private final Object userPersonId;
    private final Object userAccountId;
    private final Object supportAgentPersonId;

    public ModalityUserPrincipal(Object userPersonId, Object userAccountId) {
        this(userPersonId, userAccountId, null);
    }

    /**
     * @param supportAgentPersonId the person id of the support member viewing this account, or null
     *                             for an ordinary login. Non-null makes this a <b>support view</b>:
     *                             a read-only, time-boxed session that a member of staff opened onto
     *                             someone else's front office. See {@link #isSupportView()}.
     */
    public ModalityUserPrincipal(Object userPersonId, Object userAccountId, Object supportAgentPersonId) {
        this.userPersonId = userPersonId;
        this.userAccountId = userAccountId;
        this.supportAgentPersonId = supportAgentPersonId;
    }

    public Object getUserPersonId() {
        return userPersonId;
    }

    public Object getUserAccountId() {
        return userAccountId;
    }

    /** The support member behind this session, or null when the account holder logged in themselves. */
    public Object getSupportAgentPersonId() {
        return supportAgentPersonId;
    }

    /**
     * True when a support member is viewing this account rather than its owner using it.
     *
     * <p>Such a session is restricted to reads (enforced on the server's write path, not merely in
     * the UI) and expires on its own. Anything that decides what a session may DO — as opposed to
     * whose data it shows — should consult this.
     */
    public boolean isSupportView() {
        return supportAgentPersonId != null;
    }

    /**
     * A support view is deliberately NOT equal to the account holder's own session.
     *
     * <p>The principal is the cache key for authorizations and the value the session syncer compares
     * to detect a login transition, so collapsing the two would let a support view inherit whatever
     * was computed for the real user, and vice versa.
     *
     * <p><b>Ids compare by numeric VALUE, not by boxed type.</b> They are declared as Object and arrive
     * from whatever produced them — a database primary key on one path, a decoded wire value on another —
     * and nothing guarantees those agree on Integer versus Long versus Byte. They demonstrably do not:
     * a principal serialized to JSON text and parsed back comes out with Byte ids for small values, and
     * {@code Integer.equals(Byte)} is false.
     *
     * <p>The consequence of getting this wrong is invisible rather than loud, which is why it is spelled
     * out here. Read the two sentences above again: cache key, and login-transition comparator. A
     * principal that does not equal itself means the authorization cache misses on every request and the
     * session syncer reads every message as a fresh login, re-checking the identity and re-pushing the
     * whole grant set each time. Nothing fails. It just does all of that, forever.
     *
     * <p>What identity means here is "person 42, account 7" — not "an Integer 42".
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ModalityUserPrincipal that = (ModalityUserPrincipal) o;
        return Numbers.identicalObjectsOrNumberValues(userPersonId, that.userPersonId)
               && Numbers.identicalObjectsOrNumberValues(userAccountId, that.userAccountId)
               && Numbers.identicalObjectsOrNumberValues(supportAgentPersonId, that.supportAgentPersonId);
    }

    /**
     * Hashes the ids by numeric value too, or equal principals would land in different buckets and the
     * equality above would never be consulted — the classic way a fixed equals() stays broken.
     */
    @Override
    public int hashCode() {
        return Objects.hash(canonicalId(userPersonId), canonicalId(userAccountId), canonicalId(supportAgentPersonId));
    }

    /** One representation per numeric value, so Integer 42, Byte 42 and Long 42 hash alike. */
    private static Object canonicalId(Object id) {
        return id instanceof Number number ? number.longValue() : id;
    }

    /**
     * Short, stable, parseable — this is what identifies the actor in the database audit trail.
     *
     * The submit provider stamps the current principal onto each write transaction, and the audit
     * triggers store it verbatim, so the default Object.toString() would have filled the trail
     * with ModalityUserPrincipal@1a2b3c and answered nothing. Person first because that is who a
     * support question is usually about.
     *
     * <p><b>THIS FORMAT IS PARSED BY THE DATABASE.</b> V0068's kbs_audit_person_id() reads the
     * person id out of it with {@code substring(note from 'person=(\d+)')} to populate
     * changed_by_person_id on person_account_move and person_link_change. Changing "person=" here
     * silently blanks the actor on every audit row from then on — nothing fails, the column just
     * goes null. It is deliberately not guarded by a test because this repository has no JUnit
     * wiring at all; the migration comment names this method in return, so the coupling is
     * findable from either end.
     *
     * <p>A support view appends {@code ,supportAgent=<id>} so the trail names the human behind the
     * session. The suffix is safe for the regex above, which scans left to right and finds
     * {@code person=} in the leading segment first; "supportAgent" deliberately contains no
     * lowercase "person=" of its own. Support views cannot write at all, so in practice this is
     * belt and braces rather than the primary record — the durable one is the magic_link row.
     */
    @Override
    public String toString() {
        String s = "person=" + userPersonId + ",account=" + userAccountId;
        return supportAgentPersonId == null ? s : s + ",supportAgent=" + supportAgentPersonId;
    }

    // Static methods helpers

    public static Object getUserPersonId(Object principal) {
        return principal instanceof ModalityUserPrincipal mup ? mup.getUserPersonId() : null;
    }

    public static Object getUserAccountId(Object principal) {
        return principal instanceof ModalityUserPrincipal mup ? mup.getUserAccountId() : null;
    }

}
