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
import dev.webfx.stack.push.server.PushServerService;
import dev.webfx.stack.session.token.AuthenticatedState;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.base.server.mail.ModalityMailMessage;
import one.modality.base.shared.context.ModalityContext;
import one.modality.base.shared.entities.Cart;
import one.modality.base.shared.entities.MagicLink;
import one.modality.crm.server.authn.gateway.shared.AccountSignInRestrictionStore;
import one.modality.crm.server.authn.gateway.shared.GuestBookingAccess;
import one.modality.crm.server.authn.gateway.shared.LocalizedMailTemplate;
import one.modality.crm.server.authn.gateway.shared.MagicLinkService;
import one.modality.crm.server.authn.gateway.shared.PasswordClosedNotice;
import one.modality.crm.shared.services.authn.AuthenticateWithCartCredentials;
import one.modality.crm.shared.services.authn.ModalityGuestPrincipal;
import one.modality.crm.shared.services.authn.SendBookingAccessEmailCredentials;
import one.modality.ecommerce.document.service.GuestBookingAccessService;

import java.util.List;

/**
 * Handles authentication verification and claims for ModalityGuestPrincipal sessions.
 * Also authenticates guests via a cart UUID (AuthenticateWithCartCredentials) and
 * implements GuestBookingAccessService to keep each booking cart's guest link in line
 * with its bookings after a submit. Which carts get a link is decided in
 * GuestBookingAccess, from the database.
 *
 * @author Bruno Salmon
 */
public final class ModalityGuestAuthenticationGateway implements ServerAuthenticationGateway, GuestBookingAccessService {

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
     * Sends the guest a mail with a /cart/:cartUuid link for each of their booking carts that
     * still carries a guest link (i.e. they have not created an account since) — see
     * GuestBookingAccess.cartUuidsToMail, which also re-issues a link that has aged out.
     * Always resolves successfully to avoid email-enumeration.
     * <p>
     * The links point at this server's configured front office. cred.clientOrigin() is
     * deliberately NOT read: this endpoint needs no sign-in, and a mail built on the caller's
     * origin let anyone send a booker a genuine KBS mail whose button handed their host the
     * cart id. With no origin configured, no mail goes out (and the log says why).
     */
    private Future<Void> sendBookingAccessEmail(SendBookingAccessEmailCredentials cred) {
        String email      = cred.email();
        String lang       = cred.lang() != null ? cred.lang() : "en";
        DataSourceModel ds = dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService.getDefaultDataSourceModel();

        // One mail per address per window, answered the same way either side of it (see GuestBookingAccess)
        if (!GuestBookingAccess.mayMailRecoveryTo(email))
            return Future.succeededFuture();
        String origin = GuestBookingAccess.recoveryMailOrigin();
        if (origin == null)
            return Future.succeededFuture();

        // An address whose account has its password closed (V0096) gets the passkey notice, not cart links: those sign
        // their holder in on the strength of the mailbox, which is what that control shuts. Read fail-open here; the
        // cart redeem below reads it again, fail-closed.
        return AccountSignInRestrictionStore.isPasswordClosedForEmail(email)
            .compose(closed -> Boolean.TRUE.equals(closed)
                ? PasswordClosedNotice.send(email, lang)
                : mailBookingAccessLinks(email, origin, lang, ds));
    }

    private Future<Void> mailBookingAccessLinks(String email, String origin, String lang, DataSourceModel ds) {
        return GuestBookingAccess.cartUuidsToMail(email, ds)
            .compose(cartUuids -> {
                if (cartUuids.isEmpty())
                    return Future.<Void>succeededFuture(); // silent — don't reveal whether email has bookings
                return mailCartLinks(cartUuids, email, origin, lang);
            })
            .mapEmpty();
    }

    /** Builds and sends the "here are your bookings" mail, one CTA per cart. */
    private static Future<Void> mailCartLinks(List<String> cartUuids, String email, String origin, String lang) {
        String subject = RESTORE_MAIL.renderSubject(lang);
        String body    = RESTORE_MAIL.renderBody(lang)
            .replace("[cartButtons]", GuestBookingAccess.cartButtonsHtml(origin, cartUuids));

        return MailService.sendMail(
            new ModalityMailMessage(
                MailMessage.create(MAIL_FROM, email, subject, body),
                new ModalityContext(1, null, null, null),
                MAIL_FROM_NAME
            )
        );
    }

    /**
     * Looks up the booking cart by UUID, finds the BOOKING_ACCESS magic link its bookings
     * call for, validates it, and authenticates the caller as ModalityGuestPrincipal.
     * <p>
     * The link is judged against the cart's bookings at every click (see
     * GuestBookingAccess.linkToRedeem), and a cart that never had one gets it here — which is
     * what makes the letters already sent for back-office bookings work. An expired link is NOT
     * replaced here; the recovery mail does that.
     */
    private Future<String> authenticateWithCart(String cartUuid) {
        String usageRunId = ThreadLocalStateHolder.getRunId();
        // Read on THIS thread, before the first async hop: ThreadLocalStateHolder is restored when the
        // synchronous part of the call returns, and this decides the session's lifetime tier for good.
        boolean backofficeSession = ThreadLocalStateHolder.isBackoffice();
        DataSourceModel dataSourceModel = dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService.getDefaultDataSourceModel();
        return EntityStore.create(dataSourceModel)
            .<Cart>executeQuery("select id from Cart where uuid=$1 limit 1", cartUuid)
            .compose(carts -> {
                Cart cart = Collections.first(carts);
                if (cart == null)
                    return Future.failedFuture("Cart not found: " + cartUuid);
                return GuestBookingAccess.linkToRedeem(cart.getPrimaryKey(), dataSourceModel).compose(magicLink -> {
                    if (magicLink == null)
                        return Future.failedFuture("No magic link associated with cart: " + cartUuid);
                    // Validate via the shared service (handles BOOKING_ACCESS expiry rules)
                    // Refuses an address whose account has its password closed (V0096), as for every link it validates
                    return MagicLinkService.loadMagicLinkFromTokenOrVerificationCode(
                            magicLink.getToken(), true, dataSourceModel)
                        .compose(validMagicLink -> {
                            ModalityGuestPrincipal guestPrincipal = new ModalityGuestPrincipal(validMagicLink.getEmail());
                            // Mints, rather than merely asserting the principal: the cart uuid and its magic link
                            // have just been validated, so this IS a credential check, and a guest reaching the
                            // cart page is exactly as much in need of a proven identity as a logged-in user.
                            return AuthenticatedState.createFor(guestPrincipal, backofficeSession)
                                .compose(authenticatedState -> PushServerService.pushState(authenticatedState, usageRunId))
                                .map(ignored -> "");  // no requestedPath needed — CartPage handles navigation
                        });
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
    public Future<Void> syncCartAccessLink(Object cartPk, DataSourceModel dataSourceModel) {
        return GuestBookingAccess.syncCartLink(cartPk, dataSourceModel);
    }

    @Override
    public Future<Void> logout() {
        return LogoutPush.pushLogoutMessageToClient();
    }
}
