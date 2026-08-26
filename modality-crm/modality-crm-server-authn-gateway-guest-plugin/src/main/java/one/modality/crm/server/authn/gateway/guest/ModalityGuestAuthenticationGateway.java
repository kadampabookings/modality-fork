package one.modality.crm.server.authn.gateway.guest;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.collection.Collections;
import dev.webfx.stack.authn.UserClaims;
import dev.webfx.stack.authn.logout.server.LogoutPush;
import dev.webfx.stack.authn.server.gateway.spi.ServerAuthenticationGateway;
import dev.webfx.stack.mail.MailMessage;
import dev.webfx.stack.mail.MailService;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.UpdateStore;
import dev.webfx.stack.push.server.PushServerService;
import dev.webfx.stack.session.token.AuthenticatedState;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.base.server.mail.ModalityMailMessage;
import one.modality.base.shared.context.ModalityContext;
import one.modality.base.shared.entities.Cart;
import one.modality.base.shared.entities.MagicLink;
import one.modality.crm.server.authn.gateway.shared.LocalizedMailTemplate;
import one.modality.crm.server.authn.gateway.shared.MagicLinkService;
import one.modality.crm.shared.services.authn.AuthenticateWithCartCredentials;
import one.modality.crm.shared.services.authn.ModalityGuestPrincipal;
import one.modality.crm.shared.services.authn.SendBookingAccessEmailCredentials;
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

    private static final String MAIL_FROM      = "kbs@kadampa.net";
    private static final String MAIL_FROM_NAME = "Kadampa Booking System";

    private static final LocalizedMailTemplate RESTORE_MAIL = LocalizedMailTemplate.load(
        "BookingRestoreMailBody", "BookingRestoreMailMessages",
        ModalityGuestAuthenticationGateway.class);

    @Override
    public boolean acceptsUserCredentials(Object userCredentials) {
        return userCredentials instanceof AuthenticateWithCartCredentials
            || userCredentials instanceof SendBookingAccessEmailCredentials;
    }

    @Override
    public Future<?> authenticate(Object userCredentials) {
        if (userCredentials instanceof AuthenticateWithCartCredentials cred)
            return authenticateWithCart(cred.cartUuid());
        if (userCredentials instanceof SendBookingAccessEmailCredentials cred)
            return sendBookingAccessEmail(cred);
        return Future.failedFuture(getClass().getSimpleName() + ": unsupported credentials type");
    }

    /**
     * Finds all booking carts still linked to a magic link for the given email
     * (i.e. the guest has not yet created a registered account) and sends them
     * an email containing a /cart/:cartUuid link for each.
     * Always resolves successfully to avoid email-enumeration.
     */
    private Future<Void> sendBookingAccessEmail(SendBookingAccessEmailCredentials cred) {
        String email      = cred.email();
        String origin     = cred.clientOrigin();
        String lang       = cred.lang() != null ? cred.lang() : "en";
        DataSourceModel ds = dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService.getDefaultDataSourceModel();

        return EntityStore.create(ds)
            .<Cart>executeQuery(
                "select id, uuid from Cart c where magicLink!=null and exists(" +
                "select Document d where d.cart=c and lower(d.person_email)=lower($1))",
                email)
            .compose(carts -> {
                if (carts.isEmpty())
                    return Future.succeededFuture(); // silent — don't reveal whether email has bookings

                // Build CTA buttons for every active cart.
                StringBuilder buttons = new StringBuilder();
                for (Cart cart : carts) {
                    String url = origin + "/cart/" + cart.getUuid();
                    buttons.append("<div class=\"cta-wrap\">")
                        .append("<a href=\"").append(url).append("\" class=\"cta-btn\">View my booking</a>")
                        .append("</div>")
                        .append("<p class=\"fallback\">Button not working? Copy and paste:<br>")
                        .append("<a href=\"").append(url).append("\">").append(url).append("</a></p>");
                }

                String subject = RESTORE_MAIL.renderSubject(lang);
                String body    = RESTORE_MAIL.renderBody(lang)
                    .replace("[cartButtons]", buttons.toString());

                return MailService.sendMail(
                    new ModalityMailMessage(
                        MailMessage.create(MAIL_FROM, email, subject, body),
                        new ModalityContext(1, null, null, null),
                        MAIL_FROM_NAME
                    )
                );
            })
            .mapEmpty();
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
                        // Mints, rather than merely asserting the principal: the cart uuid and its magic link
                        // have just been validated, so this IS a credential check, and a guest reaching the
                        // cart page is exactly as much in need of a proven identity as a logged-in user.
                        return PushServerService.pushState(
                                AuthenticatedState.createFor(guestPrincipal), usageRunId)
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
