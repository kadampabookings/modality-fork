package one.modality.crm.server.authn.gateway.guest;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.collection.Collections;
import dev.webfx.stack.authn.UserClaims;
import dev.webfx.stack.authn.logout.server.LogoutPush;
import dev.webfx.stack.authn.server.gateway.spi.ServerAuthenticationGateway;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.UpdateStore;
import dev.webfx.stack.push.server.PushServerService;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.base.shared.entities.Cart;
import one.modality.base.shared.entities.MagicLink;
import one.modality.crm.server.authn.gateway.shared.MagicLinkService;
import one.modality.crm.shared.services.authn.AuthenticateWithCartCredentials;
import one.modality.crm.shared.services.authn.ModalityGuestPrincipal;
import one.modality.ecommerce.document.service.GuestBookingAccessService;

/**
 * Handles authentication verification and claims for ModalityGuestPrincipal sessions.
 * Also authenticates guests via a cart UUID (AuthenticateWithCartCredentials) and
 * implements GuestBookingAccessService to persist the magic_link record and link it
 * to the booking cart after a guest booking is submitted.
 *
 * @author Bruno Salmon
 */
public final class ModalityGuestAuthenticationGateway implements ServerAuthenticationGateway, GuestBookingAccessService {

    // Same path as ModalityMagicLinkAuthenticationGateway.MAGIC_LINK_ACTIVITY_PATH_FULL,
    // duplicated here to avoid a module dependency on the magiclink plugin.
    private static final String MAGIC_LINK_ACTIVITY_PATH_FULL = "/magic-link/:token";

    @Override
    public boolean acceptsUserCredentials(Object userCredentials) {
        return userCredentials instanceof AuthenticateWithCartCredentials;
    }

    @Override
    public Future<?> authenticate(Object userCredentials) {
        if (!(userCredentials instanceof AuthenticateWithCartCredentials cred))
            return Future.failedFuture(getClass().getSimpleName() + ".authenticate() requires AuthenticateWithCartCredentials");
        return authenticateWithCart(cred.cartUuid());
    }

    /**
     * Looks up the booking cart by UUID, finds its associated BOOKING_ACCESS magic link,
     * validates it, and authenticates the caller as ModalityGuestPrincipal.
     */
    private Future<String> authenticateWithCart(String cartUuid) {
        String usageRunId = ThreadLocalStateHolder.getRunId();
        DataSourceModel dataSourceModel = dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService.getDefaultDataSourceModel();
        return EntityStore.create(dataSourceModel)
            .<Cart>executeQuery(
                "select id, uuid, magicLink.(id,token,email,linkType,creationDate,usageDate) from Cart where uuid=$1 limit 1",
                cartUuid)
            .compose(carts -> {
                Cart cart = Collections.first(carts);
                if (cart == null)
                    return Future.failedFuture("Cart not found: " + cartUuid);
                MagicLink magicLink = cart.getMagicLink();
                if (magicLink == null)
                    return Future.failedFuture("No magic link associated with cart: " + cartUuid);
                // Validate via the shared service (handles BOOKING_ACCESS expiry rules)
                return MagicLinkService.loadMagicLinkFromTokenOrVerificationCode(
                        magicLink.getToken(), true, dataSourceModel)
                    .compose(validMagicLink -> {
                        ModalityGuestPrincipal guestPrincipal = new ModalityGuestPrincipal(validMagicLink.getEmail());
                        return PushServerService.pushState(
                                StateAccessor.createUserIdState(guestPrincipal), usageRunId)
                            .map(ignored -> "");  // no requestedPath needed — CartPage handles navigation
                    });
            });
    }

    @Override
    public boolean acceptsUserId() {
        return ThreadLocalStateHolder.getUserId() instanceof ModalityGuestPrincipal;
    }

    @Override
    public Future<?> verifyAuthenticated() {
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
            Object cartPk,
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
            .compose(magicLink -> {
                // Link the magic link to the cart so /cart/:cartUuid can authenticate the guest.
                UpdateStore updateStore = UpdateStore.create(dataSourceModel);
                Cart cart = updateStore.updateEntity(Cart.class, cartPk);
                cart.setMagicLink(magicLink.getPrimaryKey());
                return updateStore.submitChanges().mapEmpty();
            });
    }

    @Override
    public Future<Void> logout() {
        return LogoutPush.pushLogoutMessageToClient();
    }
}
