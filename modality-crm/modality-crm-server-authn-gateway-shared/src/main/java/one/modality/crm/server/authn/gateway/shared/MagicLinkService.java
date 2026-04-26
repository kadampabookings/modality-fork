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
                "select loginRunId,email,creationDate,usageDate,requestedPath,oldEmail from MagicLink where verificationCode=$1 and loginRunId=$2 and usageDate is null order by id desc limit 1",
                tokenOrVerificationCode, loginRunId)
                .map(Collections::first);
        } else {
            findFuture = entityStore.<MagicLink>executeQuery(
                "select loginRunId,email,creationDate,usageDate,requestedPath,oldEmail from MagicLink where token=$1 and usageDate is null order by id desc limit 1",
                tokenOrVerificationCode)
                .map(Collections::first);
        }
        return findFuture
            .compose(magicLink -> {
                if (magicLink == null)
                    return Future.failedFuture("[%s] Magic link not found (token: %s)".formatted(ModalityAuthenticationI18nKeys.LoginLinkUnrecognisedError, tokenOrVerificationCode));
                // 2) Checking the magic link is still valid (not already used and not expired)
                if (checkValidity && !SKIP_LINK_VALIDITY_CHECK) {
                    if (magicLink.getUsageDate() != null)
                        return Future.failedFuture("[%s] Magic link already used (token: %s)".formatted(ModalityAuthenticationI18nKeys.LoginLinkAlreadyUsedError, tokenOrVerificationCode));
                    Instant now = now();
                    if (magicLink.getCreationDate() == null || now.isAfter(magicLink.getCreationDate().plus(LINK_EXPIRATION_DURATION))) {
                        return Future.failedFuture("[%s] Magic link expired (token: %s)".formatted(ModalityAuthenticationI18nKeys.LoginLinkExpiredError, tokenOrVerificationCode));
                    }
                }
                return Future.succeededFuture(magicLink);
            });
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
