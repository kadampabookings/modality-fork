package one.modality.crm.server.authn.gateway.guest;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.authn.UserClaims;
import dev.webfx.stack.authn.logout.server.LogoutPush;
import dev.webfx.stack.authn.server.gateway.spi.ServerAuthenticationGateway;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.MagicLinkService;
import one.modality.crm.shared.services.authn.ModalityGuestPrincipal;
import one.modality.ecommerce.document.service.GuestBookingAccessService;

/**
 * Handles authentication verification and claims for ModalityGuestPrincipal sessions,
 * and also implements GuestBookingAccessService to persist the magic_link record after
 * a guest booking is submitted.
 *
 * Without this gateway, every request that carries a ModalityGuestPrincipal userId in its
 * state headers would fail the UserIdCheck in ServerSideStateSessionSyncer: the portal finds
 * no accepting gateway, the failure is recovered to LOGOUT_USER_ID, and that value is written
 * back into the client state headers — wiping the guest session.
 *
 * Guest principals are self-validating: they were created by the magic-link authentication
 * flow and already carry the user's verified email. No further DB round-trip is needed.
 *
 * @author Bruno Salmon
 */
public final class ModalityGuestAuthenticationGateway implements ServerAuthenticationGateway, GuestBookingAccessService {

    // Same path as ModalityMagicLinkAuthenticationGateway.MAGIC_LINK_ACTIVITY_PATH_FULL,
    // duplicated here to avoid a module dependency on the magiclink plugin.
    private static final String MAGIC_LINK_ACTIVITY_PATH_FULL = "/magic-link/:token";

    @Override
    public boolean acceptsUserCredentials(Object userCredentials) {
        return false; // guests authenticate via the magic-link gateway, not here
    }

    @Override
    public Future<?> authenticate(Object userCredentials) {
        return Future.failedFuture(getClass().getSimpleName() + ".authenticate() is not supported");
    }

    @Override
    public boolean acceptsUserId() {
        return ThreadLocalStateHolder.getUserId() instanceof ModalityGuestPrincipal;
    }

    @Override
    public Future<?> verifyAuthenticated() {
        // Trust the stored guest principal as-is — no DB check needed.
        return Future.succeededFuture(ThreadLocalStateHolder.getUserId());
    }

    @Override
    public Future<UserClaims> getUserClaims() {
        Object userId = ThreadLocalStateHolder.getUserId();
        if (!(userId instanceof ModalityGuestPrincipal guestPrincipal))
            return Future.failedFuture(getClass().getSimpleName() + ": current userId is not a ModalityGuestPrincipal");
        return Future.succeededFuture(new UserClaims(null, guestPrincipal.getEmail(), null, null));
    }

    @Override
    public boolean acceptsUpdateCredentialsArgument(Object updateCredentialsArgument) {
        return false;
    }

    @Override
    public Future<?> updateCredentials(Object updateCredentialsArgument) {
        return Future.failedFuture(getClass().getSimpleName() + ".updateCredentials() is not supported");
    }

    @Override
    public Future<Void> registerBookingAccessMagicLink(
            String token,
            Object documentPk,
            String personEmail,
            String personLang,
            String clientOrigin,
            DataSourceModel dataSourceModel) {
        return MagicLinkService.createBookingAccessLink(
                token,
                personEmail,
                "/order/" + documentPk,
                clientOrigin,
                MAGIC_LINK_ACTIVITY_PATH_FULL,
                personLang,
                dataSourceModel)
            .mapEmpty();
    }

    @Override
    public Future<Void> logout() {
        return LogoutPush.pushLogoutMessageToClient();
    }
}
