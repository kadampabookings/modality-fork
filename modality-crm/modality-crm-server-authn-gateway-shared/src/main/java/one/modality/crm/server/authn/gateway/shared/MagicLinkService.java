package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Objects;
import dev.webfx.platform.util.Strings;
import dev.webfx.platform.util.collection.Collections;
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

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * @author Bruno Salmon
 */
public final class MagicLinkService {

    private static final boolean SKIP_LINK_VALIDITY_CHECK = false; // Can be set to true when debugging the magic link client
    private static final Duration LINK_EXPIRATION_DURATION = Duration.ofMinutes(10);
    // BOOKING_ACCESS links are long-lived because guests book a long way ahead, and for a guest
    // with no account the link is the only way back to their booking.
    //
    // This was briefly cut to 90 days to shrink the pool of live 6-digit codes (each is drawn from
    // a 10^6 space and is redeemable without knowing whose it is, so the number alive at once
    // multiplies the odds of a blind guess landing on somebody). Measured against real bookings,
    // that was far too short: across 5435 public-talk bookings the longest lead time from booking
    // to event is 327 days, and London runs much longer than average — p90 150 days, p95 164, with
    // 203 of 730 bookings (28%) made more than 90 days ahead. Every one of the bookings already
    // taken for the 2027 London public talk is 262-313 days out. A 90-day window killed all of
    // them months before the talk they were for.
    //
    // A year covers 100% of observed usage, so that is what this is. Note the shape is still not
    // quite right: the window runs from creation, whereas what actually matters is that the link
    // outlives the EVENT. An event announced more than a year ahead would still strand its earliest
    // bookers. Tying expiry to event end + grace is the durable fix; the pool-size concern is
    // better addressed by scoping redemption to the email and rate-limiting it, which removes the
    // reason to keep this short at all.
    private static final Duration BOOKING_ACCESS_EXPIRATION_DURATION = Duration.ofDays(365);
    // A support member clicks straight through from the back office, so the window to redeem is the
    // few seconds that takes. Kept deliberately tight: an unredeemed grant sitting in a chat log or
    // a browser history for an hour is exactly the durable secret this design exists to avoid.
    private static final Duration SUPPORT_VIEW_REDEEM_DURATION = Duration.ofMinutes(2);

    /**
     * Source of every token and verification code minted here.
     *
     * <p>Deliberately NOT {@code dev.webfx.platform.util.uuid.Uuid}, whose {@code randomUuid()} is
     * built on {@code Math.random()} so it can compile for GWT. On the JVM that is
     * {@code java.util.Random}: a 48-bit linear congruential generator whose entire future output
     * follows from a couple of observed values. Anyone who can make the server mint two tokens to
     * an address they control could then predict other people's. This class is server-only, so it
     * is free to use the real thing.
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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
        String token = generateToken(); // used for the magic link
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
        return String.format("%06d", SECURE_RANDOM.nextInt(1000000));
    }

    /**
     * A fresh 128-bit bearer token, in the same UUID shape these have always had so that stored
     * links, URLs and the varchar(64) column are all unaffected. {@code UUID.randomUUID()} draws
     * from a cryptographically secure source — see {@link #SECURE_RANDOM} for why that matters and
     * why the platform's own Uuid helper is not used.
     */
    private static String generateToken() {
        return UUID.randomUUID().toString();
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
            // Two flavours of verification codes coexist:
            //
            //  LOGIN codes — short-lived (10 min), single-use. Scoped by the
            //    originating tab's loginRunId so the entry tab MUST be the
            //    request tab (prevents cross-user collisions while two users
            //    happen to share a 6-digit value in the same window).
            //
            //  BOOKING_ACCESS codes — long-lived (1 year), multi-use. Generated
            //    server-side without an originating tab (loginRunId is the
            //    sentinel "server-generated"). Redemption from any subsequent
            //    tab (e.g. the freshly-installed PWA after the user noted
            //    the code in Safari) must work, so neither loginRunId nor
            //    usageDate is filtered.
            //
            // We look up by code only and apply type-appropriate scoping in
            // Java rather than fork the SQL — keeps the query trivial and
            // the type-specific rules co-located with their justification.
            String loginRunId = ThreadLocalStateHolder.getRunId();
            findFuture = entityStore.<MagicLink>executeQuery(
                "select loginRunId,email,creationDate,usageDate,requestedPath,oldEmail,linkType from MagicLink where verificationCode=$1 order by id desc limit 1",
                tokenOrVerificationCode)
                .map(Collections::first)
                .map(ml -> {
                    if (ml == null) return null;
                    if (ml.isBookingAccess()) return ml;
                    // LOGIN path: enforce the original strict scoping.
                    if (ml.getUsageDate() != null) return null;
                    if (loginRunId == null || !loginRunId.equals(ml.getLoginRunId())) return null;
                    return ml;
                });
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
                // A SUPPORT_VIEW grant is not a login link and must never be redeemed as one. Every
                // caller of this method ends up authenticating the bearer AS the account holder with
                // full rights; a support pass is the opposite — a read-only visit by someone else.
                // The lookups above match on token (or code) alone, so without this the stronger
                // outcome would be reachable simply by pasting the support token into /magic-link.
                // Reported as "unrecognised" rather than "wrong type": to anyone holding a token
                // that does not belong on this path, that IS the truth, and it says nothing about
                // which tokens exist.
                if (magicLink.isSupportView())
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
        return createBookingAccessLink(generateToken(), email, requestedPath, clientOrigin, activityPath, lang, dataSourceModel);
    }

    /**
     * Implementation, deliberately private: the token is this class's to mint, never a
     * caller's to supply. It was public until a caller passed one built on the
     * GWT-compatible Uuid helper — Math.random(), i.e. a 48-bit LCG on the JVM — which
     * made every guest's booking-access link predictable from a couple of observed ones.
     * The public entry point above takes no token and draws it from {@link #SECURE_RANDOM}.
     * (Its former justification, a document.magic_link_token round-trip needed by the
     * [bookingUrl] bracket pattern, no longer exists: that resolves via the cart.)
     * <p>
     * Always assigns a 6-digit verification code alongside the token, so the same link
     * can be redeemed either via URL (token) or via code entry (verification code) on
     * the post-install PWA login screen.
     */
    private static Future<MagicLink> createBookingAccessLink(String token, String email, String requestedPath, String clientOrigin, String activityPath, String lang, DataSourceModel dataSourceModel) {
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
        magicLink.setVerificationCode(generateVerificationCode());
        magicLink.setEmail(email);
        magicLink.setLang(lang != null ? lang : "en");
        magicLink.setLink(link);
        magicLink.setRequestedPath(requestedPath);
        magicLink.setLinkType(MagicLinkType.BOOKING_ACCESS);
        return updateStore.submitChanges()
            .map(ignored -> magicLink);
    }

    /**
     * Whether a BOOKING_ACCESS link created at this instant would still be redeemable.
     * <p>
     * Exposed because the expiry is checked at REDEMPTION against creationDate, which means
     * shortening {@link #BOOKING_ACCESS_EXPIRATION_DURATION} retroactively kills links that
     * are already out in confirmation emails. The guest recovery flow needs to be able to ask
     * "would this still work?" before mailing a link out again: a recovery mail carrying a URL
     * the server is going to refuse is worse than no mail at all, because it looks like the
     * route back and is a dead end.
     *
     * @param creationDate the link's creationDate; null counts as expired
     */
    public static boolean isBookingAccessLinkStillValid(Instant creationDate) {
        return creationDate != null && now().isBefore(creationDate.plus(BOOKING_ACCESS_EXPIRATION_DURATION));
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
            // Only the frontendAccount id is ever needed. This used to select the password hash as well, for the
            // magic-link password reset — which no longer needs it (that flow proves identity by the link itself,
            // not by echoing the stored hash back as the "old password"). Not loading a credential that nothing
            // reads is worth the one-line change on its own.
            .<Person>executeQuery("select frontendAccount.id from Person p where lower(frontendAccount.username)=lower($1) order by p.id limit 1", email)
            .map(Collections::first); // the owner of the account is the first person recorded in that account.
    }

    // ======================================== SUPPORT VIEW ========================================

    /**
     * Records a support member's one-time pass to open a customer's front office read-only.
     *
     * <p>Stored as a MagicLink row because that is already the system's table of short-lived bearer
     * grants, and because the row doubles as the audit record: who asked ({@code oldEmail}), whose
     * account ({@code email}), when it was issued ({@code creationDate}) and whether it was actually
     * used ({@code usageDate}). That record is the reason this mechanism can answer "who looked at
     * this person's data", which the password-hash practice it replaces never could.
     *
     * <p>No verification code is minted. A 6-digit code is a reasonable trade for someone reading it
     * out of their own inbox, but it is six digits: guessable at leisure by anyone who knows the
     * endpoint exists. A support pass gets 128 bits and nothing else.
     *
     * @param targetUsername the customer account being opened (goes to {@code email})
     * @param agentEmail     the support member requesting it (goes to {@code oldEmail})
     * @param agentRunId     the back-office client that asked, for the audit trail
     * @param link           the front-office URL the agent will open
     * @param requestedPath  where the front office should land after redeeming
     * @return the persisted grant, whose {@code token} the caller returns to the agent
     */
    public static Future<MagicLink> createSupportViewLink(String targetUsername, String agentEmail, String agentRunId, String link, String requestedPath, String lang, DataSourceModel dataSourceModel) {
        UpdateStore updateStore = UpdateStore.create(dataSourceModel);
        MagicLink magicLink = updateStore.insertEntity(MagicLink.class);
        magicLink.setLinkType(MagicLinkType.SUPPORT_VIEW);
        magicLink.setToken(generateToken());
        magicLink.setEmail(targetUsername);
        magicLink.setOldEmail(agentEmail);
        // login_run_id is NOT NULL. Here it genuinely is the originating client: the back-office tab
        // the support member asked from.
        magicLink.setLoginRunId(agentRunId != null ? agentRunId : "back-office");
        magicLink.setLink(link);
        magicLink.setRequestedPath(requestedPath);
        magicLink.setLang(lang != null ? lang : "en");
        return updateStore.submitChanges()
            .map(ignored -> magicLink);
    }

    /**
     * Validates a support-view token and burns it, or fails.
     *
     * <p>Strict by construction, and separate from
     * {@link #loadMagicLinkFromTokenOrVerificationCode} on purpose: that method carries leniencies
     * earned by other link types (codes are accepted, BOOKING_ACCESS links may be replayed for a
     * year) which would each be a hole here. This one accepts a token only, of this type only,
     * unused only, within a two-minute window only.
     *
     * <p>Every failure returns the same key. Distinguishing "expired" from "already used" from
     * "never existed" would confirm to a holder of a guessed token which of those it was.
     */
    public static Future<MagicLink> loadSupportViewLinkAndMarkAsUsed(String token, DataSourceModel dataSourceModel) {
        String usageRunId = ThreadLocalStateHolder.getRunId();
        if (Strings.isEmpty(token))
            return Future.failedFuture("[%s] Invalid support view pass".formatted(ModalityAuthenticationI18nKeys.SupportViewLinkInvalidError));
        return EntityStore.create(dataSourceModel)
            .<MagicLink>executeQuery(
                "select email,oldEmail,creationDate,usageDate,requestedPath,linkType from MagicLink where token=$1 order by id desc limit 1",
                token)
            .map(Collections::first)
            .compose(magicLink -> {
                // The CLIENT is told nothing beyond "invalid", so a holder of a guessed token
                // learns nothing from the difference. The OPERATOR needs the opposite — without
                // this, every one of the five ways to be rejected looked identical in the log and
                // a failing support view could not be diagnosed at all.
                String rejection = supportViewRejectionReason(magicLink);
                if (rejection != null) {
                    // The row id, never the token. A token is a bearer credential and this line goes
                    // to a rolling file that ships to log aggregation and backups — and the
                    // "wrong link type" branch would otherwise write a LIVE login or year-long
                    // booking-access token there in clear. The id identifies the grant for support
                    // purposes without being usable to redeem anything.
                    Console.log("🚫 Support view pass rejected (%s)%s".formatted(
                        rejection, magicLink == null ? "" : " magicLinkId=" + magicLink.getPrimaryKey()));
                    return Future.failedFuture("[%s] Invalid support view pass".formatted(ModalityAuthenticationI18nKeys.SupportViewLinkInvalidError));
                }
                // Burn it before handing it back, so the pass cannot be replayed. usageDate is also
                // the clock the session lifetime is measured from (see the gateway).
                return markMagicLinkAsUsed(magicLink, usageRunId)
                    .map(ignored -> magicLink);
            });
    }

    /**
     * Why a support-view pass cannot be redeemed, or null when it can.
     *
     * <p>Split out so the reason can be logged for the operator while the caller still returns one
     * indistinguishable message to the client: the client must not be able to tell "expired" from
     * "already used" from "never existed", but whoever is running the server must.
     */
    private static String supportViewRejectionReason(MagicLink magicLink) {
        if (magicLink == null)
            return "no such token";
        if (!magicLink.isSupportView())
            return "wrong link type: " + magicLink.getLinkType();
        if (magicLink.getUsageDate() != null)
            return "already used at " + magicLink.getUsageDate();
        Instant creationDate = magicLink.getCreationDate();
        if (creationDate == null)
            return "no creation date on the row";
        Instant now = now();
        if (now.isAfter(creationDate.plus(SUPPORT_VIEW_REDEEM_DURATION)))
            return "expired: created %s, now %s, window %s".formatted(creationDate, now, SUPPORT_VIEW_REDEEM_DURATION);
        return null;
    }

    /**
     * Decides whether a support-view session may continue, by re-reading the grant it was built on.
     *
     * <p>This is what gives the session an end the browser cannot argue with. Called on the claims
     * and verification round-trips of a support-view principal — rare enough (a handful of support
     * visits a day) that the extra query costs nothing worth optimising.
     *
     * <p>Matched on <b>both</b> the account viewed and the agent viewing it. Keying on the account
     * alone would be subtly wrong: a second support member opening the same customer would mint a
     * fresh grant and silently restart the first member's expired clock.
     *
     * <p>Reads the last few grants rather than filtering on {@code usageDate} in the query, because
     * a just-minted, not-yet-redeemed grant sorts first and would otherwise look like "no live
     * session" — and because null comparison is a place DQL and SQL disagree.
     *
     * @param agentUsername the support member's own account username ({@code oldEmail} on the grant)
     * @return the grant if the session is still within its lifetime, otherwise a failed future
     */
    public static Future<MagicLink> loadLiveSupportViewLink(String targetUsername, String agentUsername, Duration sessionDuration, DataSourceModel dataSourceModel) {
        if (Strings.isEmpty(targetUsername) || Strings.isEmpty(agentUsername))
            return Future.failedFuture("[%s] Support view session has ended".formatted(ModalityAuthenticationI18nKeys.SupportViewLinkInvalidError));
        return EntityStore.create(dataSourceModel)
            .<MagicLink>executeQuery(
                "select email,oldEmail,usageDate,linkType from MagicLink"
                + " where lower(email)=lower($1) and lower(oldEmail)=lower($2) and linkType=$3"
                + " order by id desc limit 5",
                targetUsername, agentUsername, MagicLinkType.SUPPORT_VIEW.name())
            .compose(magicLinks -> {
                Instant deadline = now().minus(sessionDuration);
                for (MagicLink magicLink : magicLinks) {
                    Instant usageDate = magicLink.getUsageDate();
                    if (usageDate != null && usageDate.isAfter(deadline))
                        return Future.succeededFuture(magicLink);
                }
                return Future.failedFuture("[%s] Support view session has ended".formatted(ModalityAuthenticationI18nKeys.SupportViewLinkInvalidError));
            });
    }

    private static Instant now() {
        return Instant.now(Clock.systemUTC());
    }

}
