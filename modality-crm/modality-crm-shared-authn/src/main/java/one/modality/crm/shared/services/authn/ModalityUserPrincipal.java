package one.modality.crm.shared.services.authn;

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

    public ModalityUserPrincipal(Object userPersonId, Object userAccountId) {
        this.userPersonId = userPersonId;
        this.userAccountId = userAccountId;
    }

    public Object getUserPersonId() {
        return userPersonId;
    }

    public Object getUserAccountId() {
        return userAccountId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ModalityUserPrincipal that = (ModalityUserPrincipal) o;
        return userPersonId.equals(that.userPersonId) && userAccountId.equals(that.userAccountId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userPersonId, userAccountId);
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
     */
    @Override
    public String toString() {
        return "person=" + userPersonId + ",account=" + userAccountId;
    }

    // Static methods helpers

    public static Object getUserPersonId(Object principal) {
        return principal instanceof ModalityUserPrincipal mup ? mup.getUserPersonId() : null;
    }

    public static Object getUserAccountId(Object principal) {
        return principal instanceof ModalityUserPrincipal mup ? mup.getUserAccountId() : null;
    }

}
