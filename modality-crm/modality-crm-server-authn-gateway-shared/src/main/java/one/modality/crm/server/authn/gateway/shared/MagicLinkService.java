package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Objects;
import dev.webfx.platform.util.Strings;
import dev.webfx.platform.util.collection.Collections;
import dev.webfx.platform.util.uuid.Uuid;
import dev.webfx.stack.authn.AlternativeLoginActionCredentials;
import dev.webfx.stack.mail.MailMessage;
import dev.webfx.stack.mail.MailService;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.UpdateStore;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.base.server.mail.ModalityMailMessage;
import one.modality.base.shared.context.ModalityContext;
import one.modality.base.shared.entities.MagicLink;
import one.modality.base.shared.entities.MagicLinkType;
import one.modality.base.shared.entities.Person;
import one.modality.base.shared.util.ActivityHashUtil;
import one.modality.crm.shared.services.authn.ModalityAuthenticationI18nKeys;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * @author Bruno Salmon
 */
public final class MagicLinkService {

    private static final boolean SKIP_LINK_VALIDITY_CHECK = false; // Can be set to true when debugging the magic link client
    private static final Duration LINK_EXPIRATION_DURATION = Duration.ofMinutes(10);
    // BOOKING_ACCESS links are long-lived so guests can click them days or weeks after booking.
    private static final Duration BOOKING_ACCESS_EXPIRATION_DURATION = Duration.ofDays(365);

    // Designed to be used only from server front calls (not postponed by an async operation) in order to get the loginRunId
    public static Future<Void> createAndSendMagicLink(
        AlternativeLoginActionCredentials request,
        String activityPath,
        String fromName,
        String from,
        String subject,
        String body,
        DataSourceModel dataSourceModel) {
        return createAndSendMagicLink(
            null,
            request,
            null,
            activityPath,
            fromName,
            from,
            subject,
            body,
            dataSourceModel
        );
    }

    public static Future<Void> createAndSendMagicLink(
        String loginRunId,
        AlternativeLoginActionCredentials request,
        String oldEmail,
        String activityPath,
        String fromName,
        String from,
        String subject,
        String body,
        DataSourceModel dataSourceModel) {
        return createAndSendMagicLink(
            loginRunId,
            Strings.toSafeString(request.getLanguage()), // lang
            request.getClientOrigin(), // client origin
            request.getRequestedPath(),
            request.getEmail(),
            oldEmail,
            request.getContext(),
            activityPath,
            fromName,
            from,
            subject,
            body,
            dataSourceModel
        );
    }

    public static Future<Void> createAndSendMagicLink(
        String loginRunId,
        String lang,
        String clientOrigin,
        String requestedPath,
        String email,
        String oldEmail,
        Object context,
        String activityPath,
        String fromName,
        String from,
        String subject,
        String body,
        DataSourceModel dataSourceModel) {
        if (loginRunId == null)
            loginRunId = ThreadLocalStateHolder.getRunId(); // runId = this runId (runId of the session where the request originates)
        if (!clientOrigin.startsWith("http")) {
            clientOrigin = (clientOrigin.contains(":80") ? "http" : "https") + clientOrigin.substring(clientOrigin.indexOf("://"));
        }
        String verificationCode = generateVerificationCode();
        String token = Uuid.randomUuid(); // used for the magic link
        String link = clientOrigin + activityPath.replace(":token", token).replace(":lang", lang);
        requestedPath = ActivityHashUtil.withoutHashPrefix(requestedPath);
        UpdateStore updateStore = UpdateStore.create(dataSourceModel);
        MagicLink magicLink = updateStore.insertEntity(MagicLink.class);
        magicLink.setLoginRunId(loginRunId);
        magicLink.setVerificationCode(verificationCode);
        magicLink.setToken(token);
        magicLink.setLang(lang);
        magicLink.setLink(link);
        magicLink.setEmail(email);
        magicLink.setOldEmail(oldEmail);
        magicLink.setRequestedPath(requestedPath);
        return updateStore.submitChanges()
            .compose(ignoredBatch -> {
                ModalityContext modalityContext = context instanceof ModalityContext ? (ModalityContext) context
                    : new ModalityContext(1 /* default organizationId if no context is provided */, null, null, null);
                modalityContext.setMagicLinkId(magicLink.getPrimaryKey());
                String finalBody = body
                    .replaceAll("\\[magicLink\\]", magicLink.getLink())
                    .replaceAll("\\[verificationCode\\]", magicLink.getVerificationCode())
                    ;
                // `fromName` (e.g. "Kadampa Booking System") is carried via ModalityMailMessage
                // so the provider can set Mail.from_name alongside Mail.from_email. Null means
                // the caller didn't want a display name for this flow.
                return MailService.sendMail(new ModalityMailMessage(MailMessage.create(from, magicLink.getEmail(), subject, finalBody), modalityContext, fromName));
            });
    }

    private static String generateVerificationCode() {
        // Generating a 6-digit verification code
        return String.format("%06d", (int) (Math.random() * 1000000));
    }

    public static Future<MagicLink> loadMagicLinkFromTokenOrVerificationCode(String tokenOrVerificationCode, boolean checkValidity, DataSourceModel dataSourceModel) {
        // 1) Checking the existence of the magic link in the database, and if so, loading it with required info.
        // Verification codes are 6-digit strings; magic-link tokens are UUIDs.
        // For verification codes we additionally scope by loginRunId — the tab that requested the code
        // must be the one submitting it — to prevent cross-user collisions in the 10-minute window.
        // We also filter usageDate is null in the query (rather than post-load) and order by id desc
        // so concurrent duplicate codes always resolve to the most-recent row for the right client.
        boolean isVerificationCode = tokenOrVerificationCode != null && tokenOrVerificationCode.matches("\\d{6}");
        EntityStore entityStore = EntityStore.create(dataSourceModel);
        // Map each branch immediately to Future<MagicLink> to avoid EntityList/List type mismatch.
        Future<MagicLink> findFuture;
        if (isVerificationCode) {
            // Scope by loginRunId: the client that entered the code is the same tab that requested it.
            String loginRunId = ThreadLocalStateHolder.getRunId();
            findFuture = entityStore.<MagicLink>executeQuery(
                "select loginRunId,email,creationDate,usageDate,requestedPath,oldEmail,linkType from MagicLink where verificationCode=$1 and loginRunId=$2 and usageDate is null order by id desc limit 1",
                tokenOrVerificationCode, loginRunId)
                .map(Collections::first);
        } else {
            // BOOKING_ACCESS links are multi-use (usageDate is set after first use), so we do NOT
            // filter by usageDate=null here; the validity check below uses type-appropriate expiry.
            findFuture = entityStore.<MagicLink>executeQuery(
                "select loginRunId,email,creationDate,usageDate,requestedPath,oldEmail,linkType from MagicLink where token=$1 order by id desc limit 1",
                tokenOrVerificationCode)
                .map(Collections::first);
        }
        return findFuture
            .compose(magicLink -> {
                if (magicLink == null)
                    return Future.failedFuture("[%s] Magic link not found (token: %s)".formatted(ModalityAuthenticationI18nKeys.LoginLinkUnrecognisedError, tokenOrVerificationCode));
                // 2) Checking the magic link is still valid. BOOKING_ACCESS links are multi-use and
                //    have a much longer expiry than single-use LOGIN links.
                if (checkValidity && !SKIP_LINK_VALIDITY_CHECK) {
                    boolean isBookingAccess = magicLink.isBookingAccess();
                    // LOGIN links become invalid once used; BOOKING_ACCESS links tolerate repeated use.
                    if (!isBookingAccess && magicLink.getUsageDate() != null)
                        return Future.failedFuture("[%s] Magic link already used (token: %s)".formatted(ModalityAuthenticationI18nKeys.LoginLinkAlreadyUsedError, tokenOrVerificationCode));
                    Duration expiry = isBookingAccess ? BOOKING_ACCESS_EXPIRATION_DURATION : LINK_EXPIRATION_DURATION;
                    Instant now = now();
                    if (magicLink.getCreationDate() == null || now.isAfter(magicLink.getCreationDate().plus(expiry))) {
                        return Future.failedFuture("[%s] Magic link expired (token: %s)".formatted(ModalityAuthenticationI18nKeys.LoginLinkExpiredError, tokenOrVerificationCode));
                    }
                }
                return Future.succeededFuture(magicLink);
            });
    }

    /**
     * Creates a BOOKING_ACCESS magic link for a guest booking confirmation email.
     * Unlike LOGIN links this link is long-lived (1 year) and multi-use — the guest can
     * click it from any device or browser without getting "already used" errors.
     *
     * @param email          the guest's email address
     * @param requestedPath  the path to redirect to after authentication (e.g. "/order/42")
     * @param clientOrigin   the frontend origin URL (e.g. "https://kbs.kadampa.net")
     * @param activityPath   the magic-link route pattern (e.g. "/magic-link/:token")
     * @param lang           the guest's preferred language code
     * @param dataSourceModel the data source to write to
     * @return the persisted MagicLink entity with its primary key and link URL set
     */
    public static Future<MagicLink> createBookingAccessLink(String email, String requestedPath, String clientOrigin, String activityPath, String lang, DataSourceModel dataSourceModel) {
        return createBookingAccessLink(Uuid.randomUuid(), email, requestedPath, clientOrigin, activityPath, lang, dataSourceModel);
    }

    /**
     * Variant that accepts a pre-generated token. Use this when the token has already been
     * written onto the document (document.magic_link_token) before the INSERT so the DB
     * bracket pattern can resolve it at email-generation time.
     */
    public static Future<MagicLink> createBookingAccessLink(String token, String email, String requestedPath, String clientOrigin, String activityPath, String lang, DataSourceModel dataSourceModel) {
        String normalizedOrigin = clientOrigin;
        if (normalizedOrigin != null && !normalizedOrigin.startsWith("http")) {
            normalizedOrigin = (normalizedOrigin.contains(":80") ? "http" : "https") + normalizedOrigin.substring(normalizedOrigin.indexOf("://"));
        }
        String link = normalizedOrigin + activityPath.replace(":token", token);
        UpdateStore updateStore = UpdateStore.create(dataSourceModel);
        MagicLink magicLink = updateStore.insertEntity(MagicLink.class);
        // loginRunId is required NOT NULL in the DB; for server-generated links there is no
        // originating client session, so we use a sentinel value.
        magicLink.setLoginRunId("server-generated");
        magicLink.setToken(token);
        magicLink.setEmail(email);
        magicLink.setLang(lang != null ? lang : "en");
        magicLink.setLink(link);
        magicLink.setRequestedPath(requestedPath);
        magicLink.setLinkType(MagicLinkType.BOOKING_ACCESS);
        return updateStore.submitChanges()
            .map(ignored -> magicLink);
    }

    public static Future<Void> markMagicLinkAsUsed(MagicLink magicLink, String usageRunId) {
        // We record the usage date in the database. This will indicate that the magic link has been used, and can't be
        // reused a second time.
        UpdateStore updateStore = UpdateStore.createAbove(magicLink.getStore());
        MagicLink ml = updateStore.updateEntity(magicLink);
        ml.setUsageDate(now());
        ml.setUsageRunId(usageRunId);
        return updateStore.submitChanges().map(x -> null);
    }

    public static Future<MagicLink> loadMagicLinkFromTokenAndMarkAsUsed(String token, DataSourceModel dataSourceModel) {
        String usageRunId = ThreadLocalStateHolder.getRunId();
        return loadMagicLinkFromTokenOrVerificationCode(token, true, dataSourceModel)
            .compose( magicLink -> markMagicLinkAsUsed(magicLink, usageRunId)
                .map(ignored -> magicLink)
            );
    }

    public static Future<Person> loadUserPersonFromMagicLink(MagicLink magicLink) {
        String email = Objects.coalesce(magicLink.getOldEmail(), magicLink.getEmail());
        return magicLink.getStore()
            // In most cases, only the frontendAccount id is needed, but when resetting the password from the magic link,
            // the old password (encrypted) is also needed.
            .<Person>executeQuery("select frontendAccount.password from Person p where lower(frontendAccount.username)=lower($1) order by p.id limit 1", email)
            .map(Collections::first); // the owner of the account is the first person recorded in that account.
    }

    private static Instant now() {
        return Instant.now(Clock.systemUTC());
    }

}
