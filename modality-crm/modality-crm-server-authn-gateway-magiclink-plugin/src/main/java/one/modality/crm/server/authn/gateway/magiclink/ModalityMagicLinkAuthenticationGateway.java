package one.modality.crm.server.authn.gateway.magiclink;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.async.Promise;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Strings;
import dev.webfx.platform.util.collection.Collections;
import dev.webfx.stack.authn.*;
import dev.webfx.stack.authn.logout.server.LogoutPush;
import dev.webfx.stack.authn.server.gateway.spi.ServerAuthenticationGateway;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.domainmodel.HasDataSourceModel;
import dev.webfx.stack.orm.entity.EntityList;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.push.server.PushServerService;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.token.AuthenticatedState;
import dev.webfx.stack.session.token.SecurityAlarm;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.base.shared.entities.FrontendAccount;
import one.modality.base.shared.entities.MagicLink;
import one.modality.base.shared.entities.MagicLinkType;
import one.modality.base.shared.entities.Person;
import one.modality.base.shared.util.ActivityHashUtil;
import one.modality.crm.server.authn.gateway.shared.AccountSignInRestrictionStore;
import one.modality.crm.server.authn.gateway.shared.GuestPersonLinker;
import one.modality.crm.server.authn.gateway.shared.LocalizedMailTemplate;
import one.modality.crm.server.authn.gateway.shared.MagicLinkService;
import one.modality.crm.server.authn.gateway.shared.PasswordClosedNotice;
import one.modality.crm.server.authn.gateway.shared.RecoveryWindow;
import one.modality.crm.server.authn.gateway.shared.RoleOperationMembership;
import one.modality.crm.server.authn.gateway.shared.SetPasswordAfterRecoveryCredentials;
import one.modality.crm.server.authn.gateway.shared.SuperAdminMembership;
import one.modality.crm.shared.services.authn.AuthenticateWithBackOfficeViewCredentials;
import one.modality.crm.shared.services.authn.AuthenticateWithSupportViewCredentials;
import one.modality.crm.shared.services.authn.ModalityAuthenticationI18nKeys;
import one.modality.crm.shared.services.authn.ModalityGuestPrincipal;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;
import one.modality.crm.shared.services.authn.RequestBackOfficeViewCredentials;
import one.modality.crm.shared.services.authn.RequestSupportViewCredentials;

/**
 * @author Bruno Salmon
 */
public final class ModalityMagicLinkAuthenticationGateway implements ServerAuthenticationGateway, HasDataSourceModel {

    private static final String MAGIC_LINK_ACTIVITY_PATH_PREFIX = "/magic-link";
    public static final String MAGIC_LINK_ACTIVITY_PATH_FULL = MAGIC_LINK_ACTIVITY_PATH_PREFIX + "/:token";
    // 👆 public because used by ModalityPasswordAuthenticationGateway in case a user requests an account creation
    // on an existing account. In this case, ModalityPasswordAuthenticationGateway emails him a magic link.

    // Temporarily hardcoded (to replace with database letters)
    private static final String MAIL_FROM = "kbs@kadampa.net";
    // Display name shown alongside MAIL_FROM in the recipient's inbox; flow-specific
    // so it's set on the call site rather than as a platform-wide default.
    private static final String MAIL_FROM_NAME = "Kadampa Booking System";

    // Recovery + unknown-account emails — dictionary-driven localization.
    // Each template is a per-language HTML body (baseName.html + baseName_<lang>.html)
    // paired with a .properties file holding the translated subject.
    // See LocalizedMailTemplate for the wiring details.
    private static final LocalizedMailTemplate RECOVERY_WITH_VERIFICATION_CODE_OR_MAGIC_LINK_MAIL =
        LocalizedMailTemplate.load(
            "RecoveryWithVerificationCodeOrMagicLinkMailBody",
            "RecoveryWithVerificationCodeOrMagicLinkMailMessages",
            ModalityMagicLinkAuthenticationGateway.class);
    private static final LocalizedMailTemplate RECOVERY_WITH_VERIFICATION_CODE_ONLY_MAIL =
        LocalizedMailTemplate.load(
            "RecoveryWithVerificationCodeOnlyMailBody",
            "RecoveryWithVerificationCodeOnlyMailMessages",
            ModalityMagicLinkAuthenticationGateway.class);
    private static final LocalizedMailTemplate UNKNOWN_ACCOUNT_MAIL =
        LocalizedMailTemplate.load(
            "UnknownAccountMailBody",
            "UnknownAccountMailMessages",
            ModalityMagicLinkAuthenticationGateway.class);

    private final DataSourceModel dataSourceModel;

    public ModalityMagicLinkAuthenticationGateway() {
        this(DataSourceModelService.getDefaultDataSourceModel());
    }

    public ModalityMagicLinkAuthenticationGateway(DataSourceModel dataSourceModel) {
        this.dataSourceModel = dataSourceModel;
    }

    @Override
    public DataSourceModel getDataSourceModel() {
        return dataSourceModel;
    }

    @Override
    public boolean acceptsUserCredentials(Object userCredentials) {
        // IssueBookingAccessMagicLinkCredentials is deliberately absent, and must stay absent.
        // It asked the server to mint a BOOKING_ACCESS link for a caller-supplied email and hand
        // the 6-digit verification code back in the reply. Nothing on this bus address is
        // authenticated, so anyone could name any address and be returned a working sign-in code
        // for it — redeemable below as that person, with no email sent to warn them.
        //
        // The booking-submit path still mints BOOKING_ACCESS links, and what separates it from
        // this one is narrow but real: it never hands the verification code to anybody. It
        // returns Future<Void> and the submit result carries no code, so the only way to the
        // code is the magic link emailed to the address on the booking. (It is NOT that the
        // email is trusted there — the booker types it — nor that the submit reply reveals
        // nothing: that reply does carry cartUuid, which AuthenticateWithCartCredentials turns
        // into a guest principal for the same address. That is a weaker grant than the full
        // ModalityUserPrincipal a code buys, and predates this gateway, but do not read the
        // submit path as handing back nothing.)
        return userCredentials instanceof SendMagicLinkCredentials
               || userCredentials instanceof RenewMagicLinkCredentials
               || userCredentials instanceof AuthenticateWithMagicLinkCredentials
               || userCredentials instanceof AuthenticateWithVerificationCodeCredentials
               || userCredentials instanceof RequestSupportViewCredentials
               || userCredentials instanceof AuthenticateWithSupportViewCredentials
               || userCredentials instanceof RequestBackOfficeViewCredentials
               || userCredentials instanceof AuthenticateWithBackOfficeViewCredentials
            ;
    }

    /**
     * Whether this argument is a sign-in by emailed secret — the credential an alarm suspends.
     *
     * <p>The four magic-link paths, and deliberately NOT the support or back-office views. Those are also
     * redeemed from a link, but they are STAFF-initiated and already gated on staff privileges: they are
     * not "an attacker with mailbox access", and cutting them off would remove the tool somebody needs
     * during exactly the incident that raised the alarm.
     *
     * <p>Issuing is refused as well as redeeming, because a link that arrives and cannot be used is worse
     * than none; and redeeming is refused as well as issuing, because the links already sitting in inboxes
     * when the alarm went up are the ones it is aimed at.
     */
    private static boolean isSignInByEmailedSecret(Object userCredentials) {
        return userCredentials instanceof SendMagicLinkCredentials
               || userCredentials instanceof RenewMagicLinkCredentials
               || userCredentials instanceof AuthenticateWithMagicLinkCredentials
               || userCredentials instanceof AuthenticateWithVerificationCodeCredentials;
    }

    @Override
    public Future<?> authenticate(Object userCredentials) {
        // Control 4: the weakest credential in the system, and the one an attacker with mailbox access
        // reaches for, stops working while an alarm is raised. Checked here rather than in each branch so
        // a path added later is suspended by default instead of by remembering.
        if (SecurityAlarm.isRaised(System.currentTimeMillis()) && isSignInByEmailedSecret(userCredentials)) {
            // Counted, not itemised: who was refused is not interesting and naming them would write
            // attacker-chosen addresses into the log, exactly as the untokened-claim counter avoids.
            Console.log("🛡 Sign-in by emailed link or code refused: an alarm is raised");
            return Future.failedFuture("[%s] Sign-in by link is temporarily unavailable"
                .formatted(ModalityAuthenticationI18nKeys.AuthnMagicLinkSuspendedError));
        }
        if (userCredentials instanceof SendMagicLinkCredentials sendMagicLinkCredentials)
            return createAndSendMagicLink(sendMagicLinkCredentials);
        if (userCredentials instanceof RenewMagicLinkCredentials renewMagicLinkCredentials)
            return renewAndSendMagicLink(renewMagicLinkCredentials);
        if (userCredentials instanceof AuthenticateWithMagicLinkCredentials authenticateWithMagicLinkCredentials)
            return authenticateWithMagicLink(authenticateWithMagicLinkCredentials);
        if (userCredentials instanceof AuthenticateWithVerificationCodeCredentials authenticateWithVerificationCodeCredentials)
            return authenticateWithVerificationCode(authenticateWithVerificationCodeCredentials);
        if (userCredentials instanceof RequestSupportViewCredentials requestSupportViewCredentials)
            return requestSupportView(requestSupportViewCredentials);
        if (userCredentials instanceof AuthenticateWithSupportViewCredentials authenticateWithSupportViewCredentials)
            return authenticateWithSupportView(authenticateWithSupportViewCredentials);
        if (userCredentials instanceof RequestBackOfficeViewCredentials requestBackOfficeViewCredentials)
            return requestBackOfficeView(requestBackOfficeViewCredentials);
        if (userCredentials instanceof AuthenticateWithBackOfficeViewCredentials authenticateWithBackOfficeViewCredentials)
            return authenticateWithBackOfficeView(authenticateWithBackOfficeViewCredentials);
        return Future.failedFuture("%s.authenticate() requires a %s, %s or %s argument".formatted(getClass().getSimpleName(), SendMagicLinkCredentials.class.getSimpleName(), RenewMagicLinkCredentials.class.getSimpleName(), AuthenticateWithMagicLinkCredentials.class.getSimpleName()));
    }

    private Future<Void> createAndSendMagicLink(SendMagicLinkCredentials request) {
        // We check that the requested account exists in the database. If it exists, we send a "Password recovery" email
        // as requested. But if it doesn't exist, we send an "Unknown account" email instead. For this later case, we
        // still create a login link in the database for history purpose, even though it's not technically necessary as
        // the "Unknown account" email doesn't propose any further action.
        String loginRunId = ThreadLocalStateHolder.getRunId(); // Capturing the loginRunId before async operation
        // Pick up the user's chosen UI language so the recovery email matches what they saw on the login page.
        String lang = Strings.toSafeString(request.getLanguage());
        return EntityStore.create(dataSourceModel)
            .<FrontendAccount>executeQuery("select FrontendAccount where corporation=$1 and lower(username)=lower($2) limit 1", 1, request.getEmail())
            .compose(accounts -> {
                    boolean unknown = accounts.isEmpty();
                    // An account whose owner stopped their password working gets no link and no code (V0096): the
                    // passkey is its way in. Asked by address, resolving the account as the link's redeem would.
                    // Read fail-OPEN here, because the redeem path reads it again fail-closed: a link sent on a blip
                    // still opens nothing.
                    Future<Boolean> passwordClosed = unknown ? Future.succeededFuture(false)
                        : AccountSignInRestrictionStore.isPasswordClosedForEmail(request.getEmail());
                    return passwordClosed.compose(closed -> Boolean.TRUE.equals(closed)
                        ? PasswordClosedNotice.send(request.getEmail(), lang)
                        : sendRecoveryOrUnknownAccountMail(request, unknown, loginRunId, lang));
                }
            );
    }

    private Future<Void> sendRecoveryOrUnknownAccountMail(SendMagicLinkCredentials request, boolean unknown, String loginRunId, String lang) {
        LocalizedMailTemplate template = unknown
            ? UNKNOWN_ACCOUNT_MAIL
            : request.isVerificationCodeOnly()
                ? RECOVERY_WITH_VERIFICATION_CODE_ONLY_MAIL
                : RECOVERY_WITH_VERIFICATION_CODE_OR_MAGIC_LINK_MAIL;
        return MagicLinkService.createAndSendMagicLink(
            loginRunId,
            request,
            null,
            MAGIC_LINK_ACTIVITY_PATH_FULL,
            MAIL_FROM_NAME,
            MAIL_FROM,
            template.renderSubject(lang),
            template.renderBody(lang),
            dataSourceModel
        );
    }

    private Future<Void> renewAndSendMagicLink(RenewMagicLinkCredentials request) {
        return EntityStore.create(dataSourceModel)
            .<MagicLink>executeQuery("select loginRunId, lang, link, email, requestedPath, linkType from MagicLink where token=$1 order by id desc limit 1", request.previousToken())
            .map(Collections::first)
            .compose(magicLink -> {
                // Only a LOGIN link is renewable — an allowlist, like the redeem fence, so a
                // future link type has to argue its way in. For support passes of either flavour
                // this must be impossible (never emailed; their `link` holds a path, not an
                // absolute URL, so the substring below would throw anyway — but failing by
                // accident is not the same as refusing on purpose). For BOOKING_ACCESS links this
                // DELIBERATELY drops a previously reachable behaviour: an EXPIRED (>1 year)
                // booking-access token presented here used to mint and email a fresh LOGIN link,
                // i.e. a long-lived guest token could be traded up for a full sign-in link. The
                // guest recovery flow (sendBookingAccessEmail) is the supported way back in.
                if (magicLink == null || magicLink.getLinkType() != MagicLinkType.LOGIN)
                    return Future.failedFuture("[%s] Magic link token not found".formatted(ModalityAuthenticationI18nKeys.LoginLinkUnrecognisedError));
                String link = magicLink.getLink();
                String clientOrigin = ActivityHashUtil.withoutHashSuffix(link.substring(0, link.indexOf(MAGIC_LINK_ACTIVITY_PATH_PREFIX)));
                // Renewals reuse the language persisted on the original MagicLink so the user stays in
                // the language they started the flow with, even if their session was lost in between.
                String lang = Strings.toSafeString(magicLink.getLang());
                // A renewal is a new link: none for an account whose password is closed (see createAndSendMagicLink)
                return AccountSignInRestrictionStore.isPasswordClosedForEmail(magicLink.getEmail())
                    .compose(closed -> Boolean.TRUE.equals(closed)
                        ? PasswordClosedNotice.send(magicLink.getEmail(), lang)
                        : MagicLinkService.createAndSendMagicLink(
                    magicLink.getLoginRunId(),
                    magicLink.getLang(),
                    clientOrigin,
                    magicLink.getRequestedPath(),
                    magicLink.getEmail(),
                    null,
                    null,
                    MAGIC_LINK_ACTIVITY_PATH_FULL,
                    MAIL_FROM_NAME,
                    MAIL_FROM,
                    RECOVERY_WITH_VERIFICATION_CODE_OR_MAGIC_LINK_MAIL.renderSubject(lang),
                    RECOVERY_WITH_VERIFICATION_CODE_OR_MAGIC_LINK_MAIL.renderBody(lang),
                    dataSourceModel
                ));
            });
    }

    private Future<String> authenticateWithMagicLink(AuthenticateWithMagicLinkCredentials credentials) {
        return authenticateWithMagicLink(credentials.token());
    }

    private Future<String> authenticateWithVerificationCode(AuthenticateWithVerificationCodeCredentials credentials) {
        return authenticateWithMagicLink(credentials.verificationCode());
    }

    private Future<String> authenticateWithMagicLink(String tokenOrVerificationCode) {
        String usageRunId = ThreadLocalStateHolder.getRunId();
        // Read on THIS thread, before the first async hop: ThreadLocalStateHolder is restored when the
        // synchronous part of the call returns, and this decides the session's lifetime tier for good.
        boolean backofficeSession = ThreadLocalStateHolder.isBackoffice();
        // 1) Checking the existence of the magic link in the database, and if so, loading it with required info
        return MagicLinkService.loadMagicLinkFromTokenOrVerificationCode(tokenOrVerificationCode, true, dataSourceModel)
            .compose(magicLink -> {
                // 2) The magic link is valid, so we check if the request comes from
                // a registered or unregistered user (with or without an account)
                // (An account whose password is closed never reaches here: the link was refused above, where it is
                // validated — see MagicLinkService.)
                return MagicLinkService.loadUserPersonFromMagicLink(magicLink)
                    .compose(userPerson -> {
                        // 3) Preparing the userId = ModalityUserPrincipal for registered users, ModalityGuestPrincipal for unregistered users
                        Object userId;
                        Future<Void> guestBookingsAttached = Future.succeededFuture();
                        if (userPerson != null) {
                            Object accountId = userPerson.getForeignEntity("frontendAccount").getPrimaryKey();
                            userId = new ModalityUserPrincipal(userPerson.getPrimaryKey(), accountId);
                            // Link any guest Person records with the same email. Fire-and-forget.
                            GuestPersonLinker.linkGuestPersonsToAccount(magicLink.getEmail(), accountId, dataSourceModel)
                                .onFailure(err -> Console.log("GuestPersonLinker failed on magic-link login for " + magicLink.getEmail() + ": " + err));
                            // Attach the person-less guest bookings made under this address: the link or
                            // code just redeemed was mailed to it, so it is verified in-flow. Awaited before
                            // the login push so the first /orders load sees them; a failure is logged and
                            // never blocks the sign-in.
                            guestBookingsAttached = GuestPersonLinker.linkGuestDocumentsToPerson(magicLink.getEmail(), userPerson.getPrimaryKey(), true, null, dataSourceModel)
                                .otherwise(err -> {
                                    Console.log("GuestPersonLinker (documents) failed on magic-link login for " + magicLink.getEmail() + ": " + err);
                                    return null;
                                });
                        } else {
                            userId = new ModalityGuestPrincipal(magicLink.getEmail());
                        }
                        // 4) Pushing the userId to the magic link client which is identified by runId = usageRunId.
                        // Pushing the userId will cause a login, and subsequently a push of the authorizations.
                        return guestBookingsAttached
                            .compose(ignoredAttach -> AuthenticatedState.createFor(userId, backofficeSession))
                            .compose(authenticatedState -> PushServerService.pushState(authenticatedState, usageRunId))
                            .compose(ignored -> { // indicates that the magic link client acknowledged this login push
                                // 5) For LOGIN links: mark as used (single-use) and push userId to the original
                                //    login-page client so both tabs end up authenticated.
                                //    For BOOKING_ACCESS links: skip both — they are multi-use and were generated
                                //    server-side with no originating login tab to notify.
                                if (magicLink.isBookingAccess()) {
                                    return Future.succeededFuture(magicLink.getRequestedPath());
                                }
                                return MagicLinkService.markMagicLinkAsUsed(magicLink, usageRunId)
                                    .map(ignored2 -> magicLink.getRequestedPath())
                                    .onFailure(Console::error)
                                    .onSuccess(ignored2 -> {
                                        // 6) Push userId to the original login client as well — but only while
                                        //    somebody is plausibly still sitting on it. The link itself lives an
                                        //    hour for the tab that clicks it; the tab that asked for it is signed
                                        //    in only when the click lands inside the requester window. Past that,
                                        //    the requester is far more likely to be someone who asked for a link
                                        //    to an address that is not theirs, waiting for its owner to click.
                                        //    KNOWN LIMITATION on the lifetime tier, not on the identity: this
                                        //    token is minted with the REDEEMING client's backoffice flag but
                                        //    handed to the tab that requested the link, which may be a different
                                        //    app — click a back-office login link on a phone and that back-office
                                        //    session lands on the front-office tier. It affects how long the
                                        //    session may live, never what it may do, and it is the same gap the
                                        //    audience field closes: the tier will come from the session's own
                                        //    audience rather than from whichever client happened to redeem.
                                        if (MagicLinkService.isWithinRequesterPushWindow(magicLink))
                                            AuthenticatedState.createFor(userId, backofficeSession)
                                                .compose(authenticatedState -> PushServerService.pushState(authenticatedState, magicLink.getLoginRunId()));
                                    });
                            });
                    });
            });
    }

    // ======================================== SUPPORT VIEW ========================================

    /** Operation a back-office member must hold to open a customer's front office. */
    private static final String VIEW_AS_CUSTOMER_OPERATION_CODE = "ViewAsCustomer";

    /** Where the front office lands once the pass is redeemed — the customer's own home page. */
    private static final String SUPPORT_VIEW_LANDING_PATH = "/home";

    /**
     * Issues a support member a one-time pass onto a customer's front office.
     *
     * <p>Everything the caller sent is treated as a request, not an instruction. The identity comes
     * from the session, the permission is re-checked here (the client's copy of its grants decides
     * what buttons to draw, never what the server will do), and the target is resolved and vetted
     * server-side. The reply is a token — never anything belonging to the customer.
     *
     * @return the token the agent's browser will redeem at {@code /support-view/<token>}
     */
    private Future<String> requestSupportView(RequestSupportViewCredentials credentials) {
        // Capture the client state before the first async hop wipes the thread local.
        String agentRunId = ThreadLocalStateHolder.getRunId();
        Object callerUserId = ThreadLocalStateHolder.getUserId();

        if (!(callerUserId instanceof ModalityUserPrincipal agentPrincipal))
            return Future.failedFuture("[%s] Only a signed-in staff member can open a support view".formatted(ModalityAuthenticationI18nKeys.SupportViewNotPermittedError));
        // No nesting. A support view is read-only, so it could not mint anything anyway once the
        // write guard is in place — but refusing here keeps the audit chain honest: every grant is
        // traceable to a person who authenticated as themselves, not to a chain of borrowed views.
        if (agentPrincipal.isSupportView())
            return Future.failedFuture("[%s] A support view cannot open another support view".formatted(ModalityAuthenticationI18nKeys.SupportViewNotPermittedError));

        Object targetPersonId = normaliseId(credentials.targetPersonId());
        if (targetPersonId == null)
            return Future.failedFuture("[%s] No customer specified".formatted(ModalityAuthenticationI18nKeys.SupportViewInvalidTargetError));

        EntityStore entityStore = EntityStore.create(dataSourceModel);
        // Both identities are needed, and they are NOT interchangeable:
        //  - the principal's person id is what the authorization system keys grants on
        //    (AuthorizationSuperAdmin.superAdmin, AuthorizationOrganizationUserAccess.user — the same key
        //    ModalityAuthorizationServerServiceProvider uses for what it pushes).
        //  - frontendAccount.username is what identifies the ACCOUNT, and is what the grant row records.
        Object agentPersonId = agentPrincipal.getUserPersonId();
        return entityStore.<Person>executeQuery(
                "select frontendAccount.username from Person where id=$1 limit 1", agentPersonId)
            .map(Collections::first)
            .compose(agentPerson -> {
                String agentUsername = agentPerson == null ? null : agentPerson.evaluate("frontendAccount.username");
                if (Strings.isEmpty(agentUsername))
                    return Future.failedFuture("[%s] Your account could not be identified".formatted(ModalityAuthenticationI18nKeys.SupportViewNotPermittedError));
                return hasViewAsCustomerPermission(agentPersonId, entityStore)
                    .compose(permitted -> {
                        if (!permitted) {
                            // Person ids, not emails: this line lands in a rolling log file that
                            // ships to aggregation and backups, which neither the anonymiser nor the
                            // erasure tooling can reach. The durable audit record is the magic_link
                            // row, where the emails live inside the database and are covered by both.
                            Console.log("🚫 Support view refused: person %s lacks %s".formatted(
                                agentPrincipal.getUserPersonId(), VIEW_AS_CUSTOMER_OPERATION_CODE));
                            return Future.failedFuture("[%s] You are not permitted to open a support view".formatted(ModalityAuthenticationI18nKeys.SupportViewNotPermittedError));
                        }
                        return loadSupportViewTarget(targetPersonId, agentPersonId, entityStore)
                            .compose(targetUsername -> MagicLinkService.createSupportViewLink(
                                    targetUsername,
                                    agentUsername,
                                    agentRunId,
                                    // The row records the grant; the absolute origin is the back
                                    // office's business, so only the path is stored here.
                                    "/support-view",
                                    SUPPORT_VIEW_LANDING_PATH,
                                    null,
                                    MagicLinkType.SUPPORT_VIEW,
                                    dataSourceModel)
                                .map(magicLink -> {
                                    Console.log("🔎 Support view granted: person %s → person %s (magicLinkId=%s)".formatted(
                                        agentPrincipal.getUserPersonId(), targetPersonId, magicLink.getPrimaryKey()));
                                    return magicLink.getToken();
                                }));
                    });
            });
    }

    /**
     * Resolves the account a support view may be opened on, or fails.
     *
     * <p>A back-office account is a target for a super admin and nobody else, and the bar is that
     * high for a concrete reason rather than a cautious one. The session's authorizations are
     * computed from the TARGET's person: {@code ModalityAuthorizationServerServiceProvider}
     * resolves every other grant against the session's {@code backoffice} flag, but its
     * super-admin branch is keyed on the person alone and REPLACES the rest with
     * {@code operation:*} + {@code route:*}. So a support view aimed at a super admin hands the
     * agent the full grant set, front office or not. {@code ViewAsCustomer} is delegable to
     * roles; super-admin membership is not (see {@link #isSuperAdmin}), which makes it the only
     * safe key for the caller — the same one {@link #requestBackOfficeView} uses, and safe for the
     * reason given there: a super admin holds every grant already, so there is nothing left to
     * escalate to.
     *
     * <p>Be precise about what this closes, because the two keys are not the same key. The check
     * below is on the TARGET's {@code FrontendAccount.backoffice} flag, while the wildcard grant
     * above is on the target's membership of {@code authorization_super_admin}. Nothing in the
     * schema ties one to the other, so a super admin whose account is NOT flagged remains openable
     * by any {@code ViewAsCustomer} holder, exactly as before this method learned to say yes. That
     * gap predates this rule and is not narrowed by it; closing it would mean keying on the
     * target's membership as well as on the flag, at the cost of a super-admin lookup on the
     * ordinary customer path too. Left as it stands deliberately, and recorded here so the next
     * reader does not mistake the flag check for a membership check.
     *
     * <p>Do not relax this on the grounds that the {@code ViewAsCustomer} operation row is never
     * seeded. That row is created by hand per environment, so it is a deployment fact and not an
     * invariant of this code; an environment that has seeded it would get the escalation above
     * the moment this check went unconditional.
     *
     * <p>The refusal keeps {@code SupportViewInvalidTargetError} rather than the not-permitted
     * key: the caller may well hold {@code ViewAsCustomer} and be perfectly entitled to open
     * customers, so "you do not have permission to view customer accounts" would be a lie. It is
     * this account that cannot be opened by them, which is what the invalid-target message says.
     *
     * <p>This replaced a flat refusal of back-office accounts. Note what that flat rule was also
     * silently providing, and what now has to stand on its own: the SUPPORT_VIEW / BACKOFFICE_VIEW
     * separation in {@code ModalityPasswordAuthenticationGateway.queryModalityUserPerson} used to
     * have the {@code backoffice} column as a second line of defence, because a front-office
     * target could never hold the flag. It can now, so that separation rests on the live
     * {@code magic_link} row's type alone — see the comment there.
     *
     * @param agentPersonId the caller's person id, from the principal, which is what super-admin
     *                      membership is keyed on
     * @return the target account's username
     */
    private Future<String> loadSupportViewTarget(Object targetPersonId, Object agentPersonId, EntityStore entityStore) {
        return entityStore.<Person>executeQuery(
                "select frontendAccount.(username, backoffice, disabled), removed from Person where id=$1 limit 1", targetPersonId)
            .map(Collections::first)
            .compose(person -> {
                FrontendAccount account = person == null ? null : person.getFrontendAccount();
                if (account == null)
                    return refuseSupportViewTarget(targetPersonId, "person has no front-office account",
                        "[%s] This person has no front-office account".formatted(ModalityAuthenticationI18nKeys.SupportViewInvalidTargetError));
                if (Boolean.TRUE.equals(person.isRemoved()) || Boolean.TRUE.equals(account.isDisabled()))
                    return refuseSupportViewTarget(targetPersonId, "person removed or account disabled",
                        "[%s] This account is disabled".formatted(ModalityAuthenticationI18nKeys.SupportViewInvalidTargetError));
                String username = account.getUsername();
                // Ahead of the back-office branch, where it used to sit behind it: a nameless
                // account is refused either way, and this spares the extra query below.
                if (Strings.isEmpty(username))
                    return refuseSupportViewTarget(targetPersonId, "account has no username",
                        "[%s] This account has no username".formatted(ModalityAuthenticationI18nKeys.SupportViewInvalidTargetError));
                if (!Boolean.TRUE.equals(account.isBackoffice()))
                    return Future.succeededFuture(username);
                // Only reached for a staff account — 28 of them on production against ~49k accounts —
                // so the ordinary customer path costs exactly what it did before.
                return isSuperAdmin(agentPersonId, entityStore)
                    .compose(superAdmin -> {
                        if (!Boolean.TRUE.equals(superAdmin))
                            return refuseSupportViewTarget(targetPersonId, "back-office target, caller is not a super admin",
                                "[%s] Only a super admin can open a back-office account in support view".formatted(ModalityAuthenticationI18nKeys.SupportViewInvalidTargetError));
                        // Worth being able to find afterwards: one member of staff looked at another
                        // member of staff's front office, rather than at a customer's.
                        Console.log("🔎 Support view target is a back-office account: person %s (caller is a super admin)".formatted(targetPersonId));
                        return Future.succeededFuture(username);
                    });
            });
    }

    /**
     * Refuses a support-view target, saying in the log which rule fired.
     *
     * <p>A target refusal used to log nothing at all. Every one of them reaches the agent as the
     * same single sentence ("This account cannot be opened in support view"), so the one fact an
     * operator needs — WHICH rule fired — was the one fact recorded nowhere, and answering it
     * meant reading this method.
     *
     * <p>Person ids, not emails, and no username: these lines land in a rolling log file that
     * ships to aggregation and backups, which neither the anonymiser nor the erasure tooling can
     * reach. Same discipline as {@link #requestSupportView}; the durable audit record stays the
     * {@code magic_link} row, inside the database, where both tools do reach it.
     */
    private static Future<String> refuseSupportViewTarget(Object targetPersonId, String reason, String failureMessage) {
        Console.log("🚫 Support view refused: target person %s — %s".formatted(targetPersonId, reason));
        return Future.failedFuture(failureMessage);
    }

    /**
     * Whether this staff member may open support views, asked of the database rather than the client.
     *
     * <p>Deliberately mirrors what {@code ModalityAuthorizationServerServiceProvider} pushes to the
     * client — a super admin holds everything, and otherwise the operation must be granted to one of
     * the member's roles, directly or through the operation's group (RoleOperationMembership).
     */
    private Future<Boolean> hasViewAsCustomerPermission(Object agentPersonId, EntityStore entityStore) {
        // Grants are keyed on the principal's person id and nothing else, exactly as the authorization
        // provider resolves them. Not on Person.email, as they once were: a client can write that field,
        // so a match on it was a match on whatever the caller had typed there.
        return Future.all(
            isSuperAdmin(agentPersonId, entityStore),
            RoleOperationMembership.holdsThroughRole(agentPersonId, VIEW_AS_CUSTOMER_OPERATION_CODE, entityStore)
        ).map(compositeFuture -> Boolean.TRUE.equals(compositeFuture.resultAt(0))
                                 || Boolean.TRUE.equals(compositeFuture.resultAt(1)));
    }

    /**
     * Whether this person is a super admin — the same row the authorization provider keys the
     * {@code operation:*} wildcard on, asked of the database rather than the client.
     *
     * <p>Delegates to {@link SuperAdminMembership}, the one definition of "is super admin" across
     * the gateways (the passkey approval queue asks the same question). It is deliberately the
     * ONLY check behind the back-office view: an operation code would make the ability delegable
     * to roles, and a free-text {@code AuthorizationRule} could forge the matching grant string —
     * whereas membership of {@code authorization_super_admin} can only be conferred by someone
     * who can already write that table.
     */
    private static Future<Boolean> isSuperAdmin(Object personId, EntityStore entityStore) {
        return SuperAdminMembership.isSuperAdminPerson(personId, entityStore);
    }

    /**
     * Redeems a support-view pass, opening the customer's front office read-only.
     *
     * <p>Note what is deliberately absent compared with {@link #authenticateWithMagicLink}: no
     * {@code GuestPersonLinker}. Looking at someone's account must not alter it, and that call
     * re-parents guest Person rows as a side effect of logging in.
     */
    private Future<String> authenticateWithSupportView(AuthenticateWithSupportViewCredentials credentials) {
        String usageRunId = ThreadLocalStateHolder.getRunId();
        // Always false here — the guard below refuses a back-office caller outright — but captured and
        // passed rather than hard-coded, so the tier keeps coming from one rule. A support view is
        // recognised by its principal in any case, and lands on the 30-minute tier either way.
        boolean backofficeSession = ThreadLocalStateHolder.isBackoffice();
        // A support view belongs in the front office. The back office would hand the session the
        // customer's own back-office grants, which is not what "see what the customer sees" means.
        // (A super admin who wants the back-office equivalent has RequestBackOfficeViewCredentials,
        // whose BACKOFFICE_VIEW-typed pass this call refuses just below by requiring SUPPORT_VIEW.)
        if (ThreadLocalStateHolder.isBackoffice())
            return Future.failedFuture("[%s] A support view can only be opened in the front office".formatted(ModalityAuthenticationI18nKeys.SupportViewLinkInvalidError));
        return MagicLinkService.loadSupportViewLinkAndMarkAsUsed(credentials.token(), MagicLinkType.SUPPORT_VIEW, dataSourceModel)
            .compose(magicLink -> {
                String targetUsername = magicLink.getEmail();
                String agentUsername = magicLink.getOldEmail();
                EntityStore entityStore = EntityStore.create(dataSourceModel);
                return Future.all(
                    // Same ordering as MagicLinkService.loadUserPersonFromMagicLink, and for the same
                    // reason. Both of these used to say `order by p.id`, under the belief -- written
                    // here as fact -- that "the account owner is the first person recorded against
                    // the account". It is not: on prod (2026-08-27) 133 accounts had a non-owner or
                    // a removed duplicate holding the lowest id.
                    //
                    // Wrong in this method twice over, and both matter more here than at a login:
                    //   target -- the agent opens somebody OTHER than the customer they asked for,
                    //             while the audit row and the on-screen name both say otherwise.
                    //   agent  -- the person recorded as having looked is not the person who looked.
                    //             The permission was checked against the real principal upstream
                    //             (requestSupportView), so this never granted anything it should not
                    //             have -- it misattributed it, which is the failure this mechanism
                    //             exists to prevent.
                    entityStore.<Person>executeQuery("select frontendAccount.(id, backoffice, disabled), removed from Person p where lower(frontendAccount.username)=lower($1) order by p.removed, p.owner desc, p.id limit 1", targetUsername),
                    entityStore.<Person>executeQuery("select id from Person p where lower(frontendAccount.username)=lower($1) order by p.removed, p.owner desc, p.id limit 1", agentUsername)
                ).compose(compositeFuture -> {
                    EntityList<Person> targets = compositeFuture.resultAt(0);
                    EntityList<Person> agents = compositeFuture.resultAt(1);
                    Person targetPerson = Collections.first(targets);
                    Person agentPerson = Collections.first(agents);
                    if (targetPerson == null || agentPerson == null)
                        return Future.failedFuture("[%s] Invalid support view pass".formatted(ModalityAuthenticationI18nKeys.SupportViewLinkInvalidError));
                    // Re-vet what was vetted at mint time, as the back-office flavour already does:
                    // the two minutes between mint and redeem are exactly when an admin revoking
                    // someone's access expects it to take effect. That matters more now that a
                    // back-office target is legal, because the rule it has to satisfy depends on
                    // WHO is looking: a pass minted by a mere ViewAsCustomer holder onto an ordinary
                    // customer must not open a session just because the toggle on that same screen
                    // granted the customer back-office access in between.
                    FrontendAccount targetAccount = targetPerson.getFrontendAccount();
                    if (targetAccount == null
                        || Boolean.TRUE.equals(targetPerson.isRemoved())
                        || Boolean.TRUE.equals(targetAccount.isDisabled())) {
                        Console.log("🚫 Support view pass refused at redeem: target account no longer eligible (magicLinkId=%s)".formatted(magicLink.getPrimaryKey()));
                        return Future.failedFuture("[%s] Invalid support view pass".formatted(ModalityAuthenticationI18nKeys.SupportViewLinkInvalidError));
                    }
                    // Re-asked of the agent account's first person, which is the person a password sign-in
                    // gets. The pass records the agent's username, not the person minted against, so an
                    // agent who signed in as a different person of their account, holding the membership
                    // there, is refused here. That errs the safe way.
                    Future<Boolean> targetStillAllowed = Boolean.TRUE.equals(targetAccount.isBackoffice())
                        ? isSuperAdmin(agentPerson.getPrimaryKey(), entityStore) // the mint-time rule, re-asked
                        : Future.succeededFuture(true);
                    return targetStillAllowed.compose(allowed -> {
                        if (!Boolean.TRUE.equals(allowed)) {
                            Console.log("🚫 Support view pass refused at redeem: target became a back-office account and the agent is not a super admin (magicLinkId=%s)".formatted(magicLink.getPrimaryKey()));
                            return Future.failedFuture("[%s] Invalid support view pass".formatted(ModalityAuthenticationI18nKeys.SupportViewLinkInvalidError));
                        }
                        ModalityUserPrincipal userId = new ModalityUserPrincipal(
                            targetPerson.getPrimaryKey(), targetAccount.getPrimaryKey(), agentPerson.getPrimaryKey());
                        Console.log("🔎 Support view opened: person %s → person %s".formatted(
                            agentPerson.getPrimaryKey(), targetPerson.getPrimaryKey()));
                        return AuthenticatedState.createFor(userId, backofficeSession)
                            .compose(authenticatedState -> PushServerService.pushState(authenticatedState, usageRunId))
                            .map(ignored -> Strings.toSafeString(magicLink.getRequestedPath()));
                    });
                });
            });
    }

    // ====================================== BACK-OFFICE VIEW ======================================

    /** Where the back office lands once a back-office view pass is redeemed. */
    private static final String BACKOFFICE_VIEW_LANDING_PATH = "/dashboard";

    /**
     * Issues a super admin a one-time pass to open the back office as another back-office user.
     *
     * <p>The mirror image of {@link #requestSupportView}, with two deliberate inversions. The
     * permission is NOT an operation code: {@link #isSuperAdmin} membership is the only key, so the
     * ability can never be delegated to a role (see that method for why). And the target must BE a
     * back-office account rather than must not be one: the session inherits the target's grants,
     * which here is the point — a super admin verifying what another staff member can see holds
     * every grant already, so there is nothing to escalate to, and the write path stays closed by
     * the same read-only guard as the front-office flavour.
     *
     * @return the token the super admin's browser will redeem at the back office's own
     *         {@code /support-view/<token>} route
     */
    private Future<String> requestBackOfficeView(RequestBackOfficeViewCredentials credentials) {
        // Capture the client state before the first async hop wipes the thread local.
        String agentRunId = ThreadLocalStateHolder.getRunId();
        Object callerUserId = ThreadLocalStateHolder.getUserId();

        if (!(callerUserId instanceof ModalityUserPrincipal agentPrincipal))
            return Future.failedFuture("[%s] Only a signed-in staff member can open a back-office view".formatted(ModalityAuthenticationI18nKeys.SupportViewNotPermittedError));
        // No nesting, for the same audit-chain reason as the front-office flavour — and doubly so
        // here, since the borrowed session's target could itself be a super admin.
        if (agentPrincipal.isSupportView())
            return Future.failedFuture("[%s] A support view cannot open another support view".formatted(ModalityAuthenticationI18nKeys.SupportViewNotPermittedError));

        Object targetPersonId = normaliseId(credentials.targetPersonId());
        if (targetPersonId == null)
            return Future.failedFuture("[%s] No user specified".formatted(ModalityAuthenticationI18nKeys.SupportViewInvalidTargetError));

        EntityStore entityStore = EntityStore.create(dataSourceModel);
        // Same person-vs-username split as requestSupportView: grants (including super-admin
        // membership) are keyed on the principal's person id; the grant row records account usernames.
        Object agentPersonId = agentPrincipal.getUserPersonId();
        return entityStore.<Person>executeQuery(
                "select frontendAccount.username from Person where id=$1 limit 1", agentPersonId)
            .map(Collections::first)
            .compose(agentPerson -> {
                String agentUsername = agentPerson == null ? null : agentPerson.evaluate("frontendAccount.username");
                if (Strings.isEmpty(agentUsername))
                    return Future.failedFuture("[%s] Your account could not be identified".formatted(ModalityAuthenticationI18nKeys.SupportViewNotPermittedError));
                return isSuperAdmin(agentPersonId, entityStore)
                    .compose(superAdmin -> {
                        if (!Boolean.TRUE.equals(superAdmin)) {
                            // Person ids, not emails — same log-file discipline as requestSupportView.
                            Console.log("🚫 Back-office view refused: person %s is not a super admin".formatted(
                                agentPrincipal.getUserPersonId()));
                            return Future.failedFuture("[%s] Only a super admin can open a back-office view".formatted(ModalityAuthenticationI18nKeys.SupportViewNotPermittedError));
                        }
                        return loadBackOfficeViewTarget(targetPersonId, entityStore)
                            .compose(targetUsername -> MagicLinkService.createSupportViewLink(
                                    targetUsername,
                                    agentUsername,
                                    agentRunId,
                                    "/support-view",
                                    BACKOFFICE_VIEW_LANDING_PATH,
                                    null,
                                    MagicLinkType.BACKOFFICE_VIEW,
                                    dataSourceModel)
                                .map(magicLink -> {
                                    Console.log("🔎 Back-office view granted: person %s → person %s (magicLinkId=%s)".formatted(
                                        agentPrincipal.getUserPersonId(), targetPersonId, magicLink.getPrimaryKey()));
                                    return magicLink.getToken();
                                }));
                    });
            });
    }

    /**
     * Resolves the account a back-office view may be opened on, or fails.
     *
     * <p>Stricter than {@link #loadSupportViewTarget} on the same flag, and no longer its mirror:
     * there, a back-office account is one permitted kind of target; here it is the ONLY kind. The
     * target MUST have back-office access, because "see what this staff member sees" is meaningless
     * for an account the back office would refuse to sign in anyway (the login and re-verification
     * queries both filter on the {@code backoffice} flag). Disabled accounts and removed persons
     * are refused for the same reason as the front-office flavour: a pass onto a dead account is
     * only ever a mistake.
     *
     * @return the target account's username
     */
    private Future<String> loadBackOfficeViewTarget(Object targetPersonId, EntityStore entityStore) {
        return entityStore.<Person>executeQuery(
                "select frontendAccount.(username, backoffice, disabled), removed from Person where id=$1 limit 1", targetPersonId)
            .map(Collections::first)
            .compose(person -> {
                FrontendAccount account = person == null ? null : person.getFrontendAccount();
                if (account == null)
                    return Future.failedFuture("[%s] This person has no account".formatted(ModalityAuthenticationI18nKeys.SupportViewInvalidTargetError));
                if (Boolean.TRUE.equals(person.isRemoved()) || Boolean.TRUE.equals(account.isDisabled()))
                    return Future.failedFuture("[%s] This account is disabled".formatted(ModalityAuthenticationI18nKeys.SupportViewInvalidTargetError));
                if (!Boolean.TRUE.equals(account.isBackoffice()))
                    return Future.failedFuture("[%s] This account has no back-office access".formatted(ModalityAuthenticationI18nKeys.SupportViewInvalidTargetError));
                String username = account.getUsername();
                if (Strings.isEmpty(username))
                    return Future.failedFuture("[%s] This account has no username".formatted(ModalityAuthenticationI18nKeys.SupportViewInvalidTargetError));
                return Future.succeededFuture(username);
            });
    }

    /**
     * Redeems a back-office view pass, opening the back office read-only as the target user.
     *
     * <p>Mirror of {@link #authenticateWithSupportView} with the context check inverted, and the
     * same deliberate absence of {@code GuestPersonLinker}: looking must not alter. The principal is
     * the same {@code ModalityUserPrincipal(target, targetAccount, agent)} shape, so the SQL-layer
     * write guard, the credential-change refusals and the audit {@code toString()} all apply
     * unchanged; which application the session belongs to is told apart later solely by the
     * link type on the live {@code magic_link} row (see the password gateway's liveness check).
     */
    private Future<String> authenticateWithBackOfficeView(AuthenticateWithBackOfficeViewCredentials credentials) {
        String usageRunId = ThreadLocalStateHolder.getRunId();
        // Read on THIS thread, before the first async hop: ThreadLocalStateHolder is restored when the
        // synchronous part of the call returns, and this decides the session's lifetime tier for good.
        boolean backofficeSession = ThreadLocalStateHolder.isBackoffice();
        // A back-office view belongs in the back office. The `backoffice` flag is client-claimed
        // (the identity-binding spec's signed token is the planned hardening), so this refusal is
        // hygiene rather than the gate: the gate is that the pass was minted by a super admin, is
        // single-use, and expires in minutes — and a front-office session presenting the resulting
        // principal WITHOUT the flag dies on the liveness check, which finds no live SUPPORT_VIEW
        // row for it.
        if (!ThreadLocalStateHolder.isBackoffice())
            return Future.failedFuture("[%s] A back-office view can only be opened in the back office".formatted(ModalityAuthenticationI18nKeys.SupportViewLinkInvalidError));
        return MagicLinkService.loadSupportViewLinkAndMarkAsUsed(credentials.token(), MagicLinkType.BACKOFFICE_VIEW, dataSourceModel)
            .compose(magicLink -> {
                String targetUsername = magicLink.getEmail();
                String agentUsername = magicLink.getOldEmail();
                EntityStore entityStore = EntityStore.create(dataSourceModel);
                return Future.all(
                    // Owner first, then lowest id — same rule as the front-office redeem and the
                    // support view, plus the fields to re-vet the account below. NOT `order by p.id`:
                    // the comment that used to stand here said the account owner is the first person
                    // recorded against the account, and that is simply untrue — on prod (2026-08-27)
                    // 133 accounts had a non-owner or a removed duplicate holding the lowest id.
                    //
                    // It matters more on this path than at a login, and in two different ways:
                    //   target -- a super admin opens a DIFFERENT customer's back office from the one
                    //             they asked for, while the screen and the audit row both name the one
                    //             they asked for.
                    //   agent  -- the person recorded as having looked is not the person who looked.
                    // Neither grants anything unearned: the pass was already minted against the real
                    // principal. Both misattribute it, which is the one thing an audit trail exists
                    // to prevent.
                    entityStore.<Person>executeQuery("select frontendAccount.(id, backoffice, disabled), removed from Person p where lower(frontendAccount.username)=lower($1) order by p.removed, p.owner desc, p.id limit 1", targetUsername),
                    entityStore.<Person>executeQuery("select id from Person p where lower(frontendAccount.username)=lower($1) order by p.removed, p.owner desc, p.id limit 1", agentUsername)
                ).compose(compositeFuture -> {
                    EntityList<Person> targets = compositeFuture.resultAt(0);
                    EntityList<Person> agents = compositeFuture.resultAt(1);
                    Person targetPerson = Collections.first(targets);
                    Person agentPerson = Collections.first(agents);
                    if (targetPerson == null || agentPerson == null)
                        return Future.failedFuture("[%s] Invalid support view pass".formatted(ModalityAuthenticationI18nKeys.SupportViewLinkInvalidError));
                    // Re-vet what was vetted at mint time: the two minutes between the two are
                    // exactly when an admin revoking someone's access expects it to take effect,
                    // so a pass minted just before the toggle must not open a session just after.
                    FrontendAccount targetAccount = targetPerson.getFrontendAccount();
                    if (targetAccount == null
                        || Boolean.TRUE.equals(targetPerson.isRemoved())
                        || Boolean.TRUE.equals(targetAccount.isDisabled())
                        || !Boolean.TRUE.equals(targetAccount.isBackoffice())) {
                        Console.log("🚫 Back-office view pass refused at redeem: target account no longer eligible (magicLinkId=%s)".formatted(magicLink.getPrimaryKey()));
                        return Future.failedFuture("[%s] Invalid support view pass".formatted(ModalityAuthenticationI18nKeys.SupportViewLinkInvalidError));
                    }
                    ModalityUserPrincipal userId = new ModalityUserPrincipal(
                        targetPerson.getPrimaryKey(), targetAccount.getPrimaryKey(), agentPerson.getPrimaryKey());
                    Console.log("🔎 Back-office view opened: person %s → person %s".formatted(
                        agentPerson.getPrimaryKey(), targetPerson.getPrimaryKey()));
                    return AuthenticatedState.createFor(userId, backofficeSession)
                        .compose(authenticatedState -> PushServerService.pushState(authenticatedState, usageRunId))
                        .map(ignored -> Strings.toSafeString(magicLink.getRequestedPath()));
                });
            });
    }

    /**
     * JSON numbers reach the server as Integer or Double depending on how the client wrote them,
     * and a Double reaching a DQL parameter fails server-side coercion against an integer column.
     * Returns null for anything that is not a usable id, so callers fail with a clear message
     * rather than a coercion error.
     */
    private static Object normaliseId(Object id) {
        if (id instanceof Number number)
            return number.longValue();
        if (id instanceof String string) {
            try {
                return Long.parseLong(string.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    @Override
    public boolean acceptsUserId() {
        return false;
    }

    @Override
    public Future<?> verifyAuthenticated() {
        return Future.failedFuture("%s.verifyAuthenticated() is not supported".formatted(getClass().getSimpleName()));
    }

    @Override
    public Future<UserClaims> getUserClaims() {
        return Future.failedFuture("%s.getUserClaims() is not supported".formatted(getClass().getSimpleName()));
    }

    @Override
    public boolean acceptsUpdateCredentialsArgument(Object updateCredentialsArgument) {
        return updateCredentialsArgument instanceof UpdatePasswordFromMagicLinkCredentials;
    }

    @Override
    public Future<?> updateCredentials(Object updateCredentialsArgument) {
        if (!(updateCredentialsArgument instanceof UpdatePasswordFromMagicLinkCredentials update)) {
            return Future.failedFuture("%s.updateCredentials() requires a %s argument".formatted(getClass().getSimpleName(), UpdatePasswordFromMagicLinkCredentials.class.getSimpleName()));
        }
        // A support view is refused with its own answer, so the agent is told why rather than that a link is
        // missing. RecoveryWindow refuses it too; this is only the clearer message. Checked on this thread,
        // before the async hop, while the calling principal is still known.
        if (ThreadLocalStateHolder.getUserId() instanceof ModalityUserPrincipal callerPrincipal && callerPrincipal.isSupportView())
            return Future.failedFuture("[%s] A support view cannot change this account's password".formatted(ModalityAuthenticationI18nKeys.SupportViewLinkInvalidError));
        // Every other refusal — no link redeemed by this tab, one redeemed too long ago, one already used to
        // set a password, one for another account — is the same answer, so the caller learns nothing about
        // which it was, and the client falls back to asking for the current password. See RecoveryWindow.
        return RecoveryWindow.findForCaller(dataSourceModel)
            .compose(open -> {
                if (open == null || !RecoveryWindow.claim(open.magicLink()))
                    return Future.failedFuture("[%s] Magic link not found!".formatted(ModalityAuthenticationI18nKeys.LoginLinkUnrecognisedError));
                // No old password: redeeming the emailed link IS the proof of identity. Said with a type of
                // its own rather than a null in UpdatePasswordCredentials, because a session change MUST name
                // the current password — and a null there is exactly what a stolen session would send to skip
                // that. This type has no serial codec, so no client message can produce it; only this line
                // can. See SetPasswordAfterRecoveryCredentials.
                SetPasswordAfterRecoveryCredentials updatePasswordCredentials =
                    new SetPasswordAfterRecoveryCredentials(update.newPassword());
                Promise<Void> promise = Promise.promise();
                ThreadLocalStateHolder.runAsUser(open.target(),
                    () -> promise.handle(AuthenticationService.updateCredentials(updatePasswordCredentials).mapEmpty())
                );
                // Spent only if the password was actually set: a refusal (a closed password, a weak one, the
                // database) leaves the person free to try again within the same window.
                return promise.future()
                    .onFailure(e -> RecoveryWindow.release(open.magicLink()));
            });
    }

    @Override
    public Future<Void> logout() {
        return LogoutPush.pushLogoutMessageToClient();
    }

}
