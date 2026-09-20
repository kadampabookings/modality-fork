package one.modality.crm.server.person;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.util.function.BiFunction;

/**
 * The check a front-office operation makes: is this an ordinary member, acting as themselves?
 *
 * <p>The sibling of {@link one.modality.crm.server.authn.gateway.shared.RouteAccessGuard}, for the other
 * half of the person operations. That one asks whether a caller may reach a back-office SCREEN, which is
 * the right question for staff and the wrong one for a member: a member holds no route grant and never
 * will, and the thing that entitles them is not a permission but a relationship — this is my account,
 * this invitation was sent to me. So the grant lookup is not merely unnecessary here, it would refuse
 * every legitimate caller.
 *
 * <p>What it does share is the fence: a <b>support view is refused</b>, because its principal carries the
 * VIEWED member's person id, so every ownership test below it would be asked about the wrong person and
 * answered yes. A guest carries a different principal type and is refused by the same test. And it
 * requires a session this server established rather than an identity a caller asserted — the same
 * reasoning as the back-office guard, for the same reason: linking hands somebody another person's
 * bookings and media, and production still accepts a bare claim until the identity flip.
 *
 * @author Claude Code
 */
final class MemberSessionGuard {

    private MemberSessionGuard() {
    }

    /**
     * Runs {@code work} with the caller's own person and account, or refuses.
     *
     * <p>MUST be called on the caller's thread: it reads the principal and the session family before any
     * async step, because {@link ThreadLocalStateHolder} is restored as soon as the synchronous part of
     * the call returns.
     *
     * <p>Both ids come from the principal the server minted, never from the argument. That is the whole
     * point of passing them in rather than letting the operation read them: an operation cannot
     * accidentally act for somebody the caller named.
     *
     * @param work given the caller's person id and account id, in that order
     */
    static <T> Future<T> whenCallerIsMember(BiFunction<Object, Object, Future<T>> work) {
        Object state = ThreadLocalStateHolder.getThreadLocalState();
        Object userId = StateAccessor.getUserId(state);
        boolean verifiedSession = StateAccessor.getSessionFamilyId(state) != null;
        if (!(userId instanceof ModalityUserPrincipal principal) || principal.isSupportView() || !verifiedSession)
            return refused();
        Object personId = principal.getUserPersonId();
        Object accountId = principal.getUserAccountId();
        if (personId == null || accountId == null)
            return refused();
        return work.apply(personId, accountId);
    }

    /**
     * What every refusal here says, whatever its reason.
     *
     * <p>Deliberately the same sentence as the back-office guard's, and deliberately saying nothing about
     * which test failed: these operations are asked about other people's rows, and a refusal that
     * explained itself would answer questions about them.
     */
    static <T> Future<T> refused() {
        return Future.failedFuture("This operation is not available to you");
    }
}
