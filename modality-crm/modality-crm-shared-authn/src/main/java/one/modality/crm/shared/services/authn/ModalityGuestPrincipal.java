package one.modality.crm.shared.services.authn;

/**
 * Represents a guest (or unregistered user) who successfully logged in but doesn't have an account in Modality.
 * In that case the userId will be an instance of this class instead of ModalityUserPrincipal.
 *
 * @author Bruno Salmon
 */

public class ModalityGuestPrincipal {

    private final String email;

    public ModalityGuestPrincipal(String email) {
        this.email = email;
    }

    public String getEmail() {
        return email;
    }

    /**
     * Two guest principals for the same address are the same guest.
     *
     * <p>Without this the class inherited identity comparison, so a guest decoded from one message never
     * equalled the guest decoded from the next — even though both named the same person. The principal is
     * the authorization cache key and the value the session syncer compares to detect a login transition,
     * so that meant every guest request missed the cache and re-ran the whole login path: re-check the
     * identity, recompute the grants, push them again. It never failed, so nothing ever reported it.
     *
     * <p>Email is the whole identity of a guest — it is what the grants are looked up by — so it is the
     * whole of the comparison.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return java.util.Objects.equals(email, ((ModalityGuestPrincipal) o).email);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(email);
    }

    /** Named in the audit trail the same way a registered principal is, rather than as an object address. */
    @Override
    public String toString() {
        return "guest=" + email;
    }
}
