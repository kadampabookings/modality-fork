package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.stack.session.state.StateAccessor;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

/**
 * The fence every back-office endpoint stands behind: {@link RouteAccessGuard#backOfficeCallerPersonId}.
 *
 * <p>It decides, before any grant is looked up, whether a caller is even the kind that could hold one — a
 * real principal, on a session this server established, not a support view, on a token whose audience says
 * back office. Four guards share it, so a regression here is a regression in all of them at once, and
 * nothing else in the module tests it.
 *
 * <p><b>Every refusal below differs from the one allowed case by exactly one attribute</b>, which is what
 * makes the answers mean something: a check whose positive and negative cases differ in two ways passes
 * for whichever reason happens to survive. The positive case is built first and each refusal is that same
 * state with one thing taken away or turned.
 *
 * <p>Pure: no database, because this half of the guard asks nothing of one. The scoped membership query
 * behind it is a separate question and is not covered here.
 *
 * @author Claude Code
 */
public class BackOfficeCallerFenceCheck {

    private static int failures = 0;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    /** A caller who should get all the way through: back-office audience, verified session, real principal. */
    private static Object allowedState() {
        Object state = StateAccessor.createEmptyState();
        state = StateAccessor.setUserId(state, new ModalityUserPrincipal(42, 7));
        state = StateAccessor.setSessionFamilyId(state, "family-1");
        state = StateAccessor.setVerifiedBackoffice(state, Boolean.TRUE);
        return state;
    }

    public static void main(String[] args) {
        System.out.println("BackOfficeCallerFenceCheck");

        // The positive control. Without this passing, every refusal below is meaningless.
        check("a verified back-office caller is let through, and is named",
              Integer.valueOf(42).equals(RouteAccessGuard.backOfficeCallerPersonId(allowedState())));

        check("an anonymous caller is refused",
              RouteAccessGuard.backOfficeCallerPersonId(StateAccessor.createEmptyState()) == null);

        // A userId that is not a principal: what an unauthenticated or foreign caller carries.
        Object notAPrincipal = StateAccessor.setVerifiedBackoffice(
            StateAccessor.setSessionFamilyId(
                StateAccessor.setUserId(StateAccessor.createEmptyState(), "someone"), "family-1"), Boolean.TRUE);
        check("a userId that is not a ModalityUserPrincipal is refused",
              RouteAccessGuard.backOfficeCallerPersonId(notAPrincipal) == null);

        // No session family: an identity asserted rather than one this server established.
        Object noFamily = StateAccessor.setVerifiedBackoffice(
            StateAccessor.setUserId(StateAccessor.createEmptyState(), new ModalityUserPrincipal(42, 7)),
            Boolean.TRUE);
        check("a caller on no established session is refused",
              RouteAccessGuard.backOfficeCallerPersonId(noFamily) == null);

        // Audience absent: nothing was verified on this message. NOT the same as "front office", and the
        // reason the accessor returns a Boolean — null must not read as false and let the caller through.
        Object noAudience = StateAccessor.setSessionFamilyId(
            StateAccessor.setUserId(StateAccessor.createEmptyState(), new ModalityUserPrincipal(42, 7)),
            "family-1");
        check("a caller whose token verified no audience is refused",
              RouteAccessGuard.backOfficeCallerPersonId(noAudience) == null);

        // Audience says front office: the hole the $aud claim exists to close.
        Object frontOffice = StateAccessor.setVerifiedBackoffice(
            StateAccessor.setSessionFamilyId(
                StateAccessor.setUserId(StateAccessor.createEmptyState(), new ModalityUserPrincipal(42, 7)),
                "family-1"), Boolean.FALSE);
        check("a front-office session is refused at a back-office fence",
              RouteAccessGuard.backOfficeCallerPersonId(frontOffice) == null);

        // A support view is somebody looking at a member's front office; it must not act as staff.
        Object supportView = StateAccessor.setVerifiedBackoffice(
            StateAccessor.setSessionFamilyId(
                StateAccessor.setUserId(StateAccessor.createEmptyState(), new ModalityUserPrincipal(42, 7, 99)),
                "family-1"), Boolean.TRUE);
        check("a support view is refused",
              RouteAccessGuard.backOfficeCallerPersonId(supportView) == null);

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILURE(S)");
        if (failures > 0)
            System.exit(1);
    }
}
