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
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.EntityList;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.push.server.PushServerService;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.token.AuthenticatedState;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.base.shared.entities.FrontendAccount;
import one.modality.base.shared.entities.MagicLink;
import one.modality.base.shared.entities.MagicLinkType;
import one.modality.base.shared.entities.Operation;
import one.modality.base.shared.entities.Person;
import one.modality.base.shared.util.ActivityHashUtil;
import one.modality.crm.server.authn.gateway.shared.GuestPersonLinker;
import one.modality.crm.server.authn.gateway.shared.LocalizedMailTemplate;
import one.modality.crm.server.authn.gateway.shared.MagicLinkService;
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

    @Override
    public Future<?> authenticate(Object userCredentials) {
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
                return MagicLinkService.createAndSendMagicLink(
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
                );
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
        // 1) Checking the existence of the magic link in the database, and if so, loading it with required info
        return MagicLinkService.loadMagicLinkFromTokenOrVerificationCode(tokenOrVerificationCode, true, dataSourceModel)
            .compose(magicLink -> {
                // 2) The magic link is valid, so we check if the request comes from
                // a registered or unregistered user (with or without an account)
                return MagicLinkService.loadUserPersonFromMagicLink(magicLink)
                    .compose(userPerson -> {
                        // 3) Preparing the userId = ModalityUserPrincipal for registered users, ModalityGuestPrincipal for unregistered users
                        Object userId;
                        if (userPerson != null) {
                            Object accountId = userPerson.getForeignEntity("frontendAccount").getPrimaryKey();
                            userId = new ModalityUserPrincipal(userPerson.getPrimaryKey(), accountId);
                            // Link any guest Person records with the same email. Fire-and-forget.
                            GuestPersonLinker.linkGuestPersonsToAccount(magicLink.getEmail(), accountId, dataSourceModel)
                                .onFailure(err -> Console.log("GuestPersonLinker failed on magic-link login for " + magicLink.getEmail() + ": " + err));
                        } else {
                            userId = new ModalityGuestPrincipal(magicLink.getEmail());
                        }
                        // 4) Pushing the userId to the magic link client which is identified by runId = usageRunId.
                        // Pushing the userId will cause a login, and subsequently a push of the authorizations.
                        return PushServerService.pushState(AuthenticatedState.createFor(userId), usageRunId)
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
                                        if (MagicLinkService.isWithinRequesterPushWindow(magicLink))
                                            PushServerService.pushState(AuthenticatedState.createFor(userId), magicLink.getLoginRunId());
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
        //  - email is what the authorization system keys grants on (AuthorizationSuperAdmin.superAdmin.email,
        //    AuthorizationOrganizationUserAccess.user.email — and ModalityAuthorizationServerServiceProvider
        //    reaches them via getUserClaims().email(), which returns Person.email).
        //  - frontendAccount.username is what identifies the ACCOUNT, and is what the grant row records.
        // People routinely have a different Person.email from their account username, so checking the
        // permission against the username would silently deny staff the authorization push had granted.
        return entityStore.<Person>executeQuery(
                "select email, frontendAccount.username from Person where id=$1 limit 1", agentPrincipal.getUserPersonId())
            .map(Collections::first)
            .compose(agentPerson -> {
                String agentUsername = agentPerson == null ? null : agentPerson.evaluate("frontendAccount.username");
                String agentEmail = agentPerson == null ? null : agentPerson.getEmail();
                if (Strings.isEmpty(agentUsername))
                    return Future.failedFuture("[%s] Your account could not be identified".formatted(ModalityAuthenticationI18nKeys.SupportViewNotPermittedError));
                return hasViewAsCustomerPermission(agentEmail, agentUsername, entityStore)
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
                        return loadSupportViewTarget(targetPersonId, entityStore)
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
     * <p>Refusing back-office accounts is the important one. A support view is read-only, but the
     * account it opens is also the account whose authorizations the session inherits — so allowing
     * a staff account as the target would let a junior member borrow a senior member's grants. The
     * mechanism is for looking at customers.
     *
     * @return the target account's username
     */
    private Future<String> loadSupportViewTarget(Object targetPersonId, EntityStore entityStore) {
        return entityStore.<Person>executeQuery(
                "select frontendAccount.(username, backoffice, disabled), removed from Person where id=$1 limit 1", targetPersonId)
            .map(Collections::first)
            .compose(person -> {
                FrontendAccount account = person == null ? null : person.getFrontendAccount();
                if (account == null)
                    return Future.failedFuture("[%s] This person has no front-office account".formatted(ModalityAuthenticationI18nKeys.SupportViewInvalidTargetError));
                if (Boolean.TRUE.equals(person.isRemoved()) || Boolean.TRUE.equals(account.isDisabled()))
                    return Future.failedFuture("[%s] This account is disabled".formatted(ModalityAuthenticationI18nKeys.SupportViewInvalidTargetError));
                if (Boolean.TRUE.equals(account.isBackoffice()))
                    return Future.failedFuture("[%s] Back-office accounts cannot be opened in support view".formatted(ModalityAuthenticationI18nKeys.SupportViewInvalidTargetError));
                String username = account.getUsername();
                if (Strings.isEmpty(username))
                    return Future.failedFuture("[%s] This account has no username".formatted(ModalityAuthenticationI18nKeys.SupportViewInvalidTargetError));
                return Future.succeededFuture(username);
            });
    }

    /**
     * Whether this staff member may open support views, asked of the database rather than the client.
     *
     * <p>Deliberately mirrors what {@code ModalityAuthorizationServerServiceProvider} pushes to the
     * client — a super admin holds everything, and otherwise the operation must be granted to one of
     * the member's roles, directly or through the operation's group. Resolving the operation first
     * keeps both branches flat: a correlated {@code exists} is a shape the DQL parser is known to
     * handle (the authorization provider uses the same one), nested ones are not.
     */
    private Future<Boolean> hasViewAsCustomerPermission(String agentEmail, String agentUsername, EntityStore entityStore) {
        // Grants are keyed on Person.email and nothing else, exactly as the authorization provider
        // resolves them. No fallback to the account username: that asks a DIFFERENT question — it
        // would match a grant held by whatever person happens to have this account's username as
        // their email, which on a shared account is a different human. When the caller's person
        // record has no email the authorization system grants them nothing, so neither do we.
        if (Strings.isEmpty(agentEmail)) {
            Console.log("🚫 Support view refused: caller has no Person.email to resolve grants against");
            return Future.succeededFuture(false);
        }
        String grantEmail = agentEmail;
        return Future.all(
            isSuperAdmin(grantEmail, entityStore),
            entityStore.<Operation>executeQuery("select group.id from Operation where operationCode=$1 limit 1", VIEW_AS_CUSTOMER_OPERATION_CODE)
        ).compose(compositeFuture -> {
            Boolean superAdmin = compositeFuture.resultAt(0);
            if (Boolean.TRUE.equals(superAdmin))
                return Future.succeededFuture(true);
            EntityList<Operation> operations = compositeFuture.resultAt(1);
            Operation operation = Collections.first(operations);
            if (operation == null) // the operation has not been seeded yet => nobody holds it
                return Future.succeededFuture(false);
            Object operationGroupId = Entities.getPrimaryKey(operation.getGroupId());
            return entityStore.executeQuery(
                    "select AuthorizationRoleOperation ro where (ro.operation=$1 or ro.operationGroup=$2)"
                    + " and exists(select AuthorizationOrganizationUserAccess ua where ua.role=ro.role and ua.user.email=$3) limit 1",
                    operation.getPrimaryKey(), operationGroupId, grantEmail)
                .map(roleOperations -> !roleOperations.isEmpty());
        });
    }

    /**
     * Whether this email belongs to a super admin — the same row the authorization provider keys the
     * {@code operation:*} wildcard on, asked of the database rather than the client.
     *
     * <p>The one definition of "is super admin" in this gateway. It is deliberately the ONLY check
     * behind the back-office view: an operation code would make the ability delegable to roles, and
     * a free-text {@code AuthorizationRule} could forge the matching grant string — whereas
     * membership of {@code authorization_super_admin} can only be conferred by someone who can
     * already write that table.
     */
    private static Future<Boolean> isSuperAdmin(String email, EntityStore entityStore) {
        if (Strings.isEmpty(email))
            return Future.succeededFuture(false);
        return entityStore.executeQuery("select AuthorizationSuperAdmin where superAdmin.email=$1 limit 1", email)
            .map(superAdmins -> !superAdmins.isEmpty());
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
                    entityStore.<Person>executeQuery("select frontendAccount.id from Person p where lower(frontendAccount.username)=lower($1) order by p.removed, p.owner desc, p.id limit 1", targetUsername),
                    entityStore.<Person>executeQuery("select id from Person p where lower(frontendAccount.username)=lower($1) order by p.removed, p.owner desc, p.id limit 1", agentUsername)
                ).compose(compositeFuture -> {
                    EntityList<Person> targets = compositeFuture.resultAt(0);
                    EntityList<Person> agents = compositeFuture.resultAt(1);
                    Person targetPerson = Collections.first(targets);
                    Person agentPerson = Collections.first(agents);
                    if (targetPerson == null || agentPerson == null)
                        return Future.failedFuture("[%s] Invalid support view pass".formatted(ModalityAuthenticationI18nKeys.SupportViewLinkInvalidError));
                    Object accountId = Entities.getPrimaryKey(targetPerson.getForeignEntityId("frontendAccount"));
                    ModalityUserPrincipal userId = new ModalityUserPrincipal(
                        targetPerson.getPrimaryKey(), accountId, agentPerson.getPrimaryKey());
                    Console.log("🔎 Support view opened: person %s → person %s".formatted(
                        agentPerson.getPrimaryKey(), targetPerson.getPrimaryKey()));
                    return PushServerService.pushState(AuthenticatedState.createFor(userId), usageRunId)
                        .map(ignored -> Strings.toSafeString(magicLink.getRequestedPath()));
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
        // Same email-vs-username split as requestSupportView: grants (including super-admin
        // membership) are keyed on Person.email; the grant row records account usernames.
        return entityStore.<Person>executeQuery(
                "select email, frontendAccount.username from Person where id=$1 limit 1", agentPrincipal.getUserPersonId())
            .map(Collections::first)
            .compose(agentPerson -> {
                String agentUsername = agentPerson == null ? null : agentPerson.evaluate("frontendAccount.username");
                String agentEmail = agentPerson == null ? null : agentPerson.getEmail();
                if (Strings.isEmpty(agentUsername))
                    return Future.failedFuture("[%s] Your account could not be identified".formatted(ModalityAuthenticationI18nKeys.SupportViewNotPermittedError));
                return isSuperAdmin(agentEmail, entityStore)
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
     * <p>The inversion of {@link #loadSupportViewTarget}: the target MUST have back-office access,
     * because "see what this staff member sees" is meaningless for an account the back office would
     * refuse to sign in anyway (the login and re-verification queries both filter on the
     * {@code backoffice} flag). Disabled accounts and removed persons are refused for the same
     * reason as the front-office flavour: a pass onto a dead account is only ever a mistake.
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
                    return PushServerService.pushState(AuthenticatedState.createFor(userId), usageRunId)
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
        String usageRunId = ThreadLocalStateHolder.getRunId();
        // A support view must not reach this path at all. It redeems a grant of its own, which stamps
        // this same usageRunId onto that row — so without this guard the agent's tab satisfies the
        // "you recently redeemed a link" precondition below, on a row whose email is the CUSTOMER's,
        // and could reset the customer's password from inside a session that is supposed to be
        // read-only. Checked here, before the async hop, while the calling principal is still known.
        if (ThreadLocalStateHolder.getUserId() instanceof ModalityUserPrincipal callerPrincipal && callerPrincipal.isSupportView())
            return Future.failedFuture("[%s] A support view cannot change this account's password".formatted(ModalityAuthenticationI18nKeys.SupportViewLinkInvalidError));
        // 1) Loading the email for the magic link normally associated with this magic link app userId from the database
        // This will be used to identify the account we need to change the password for.
        //
        // Scoped to LOGIN rows IN THE QUERY, not just in the Java check below: one tab (one runId)
        // can legitimately stamp usageRunId onto more than one row — the account-creation-from-
        // booking flows mark BOOKING_ACCESS links as used with the same runId — and an unordered
        // `limit 1` over that set is a coin toss. Before the type check tightened to LOGIN-only
        // the coin toss was invisible (either row passed); with it, drawing the BOOKING_ACCESS row
        // would refuse a perfectly legitimate password change. The column is NOT NULL DEFAULT
        // 'LOGIN', so the SQL filter cannot miss legacy rows, and `order by id desc` keeps the
        // answer deterministic even so.
        return EntityStore.create(dataSourceModel)
            .<MagicLink>executeQuery("select email,linkType from MagicLink where usageRunId=$1 and linkType=$2 order by id desc limit 1", usageRunId, MagicLinkType.LOGIN.name())
            .compose(magicLinks -> {
                if (magicLinks.isEmpty())
                    return Future.failedFuture("[%s] Magic link not found!".formatted(ModalityAuthenticationI18nKeys.LoginLinkUnrecognisedError));
                MagicLink magicLink = magicLinks.get(0);
                // Belt and braces for the query filter above and the principal guard before it:
                // only a LOGIN link authorises a password change — stated as an allowlist so a
                // support pass of either flavour (or any future type) is refused without this line
                // needing to know about it. linkType is selected explicitly because an unselected
                // field reads as null, which getLinkType() would charitably interpret as LOGIN.
                if (magicLink.getLinkType() != MagicLinkType.LOGIN)
                    return Future.failedFuture("[%s] Magic link not found!".formatted(ModalityAuthenticationI18nKeys.LoginLinkUnrecognisedError));
                // 3) Reading the user person
                return MagicLinkService.loadUserPersonFromMagicLink(magicLink)
                    .compose(userPerson -> {
                        if (userPerson == null)
                            return Future.failedFuture("[%s] No such user account".formatted(ModalityAuthenticationI18nKeys.AuthnNoSuchUserAccountError));
                        // 4) Preparing the userId = ModalityUserPrincipal for registered users, ModalityGuestPrincipal for unregistered users
                        ModalityUserPrincipal targetUserId = new ModalityUserPrincipal(userPerson.getPrimaryKey(), userPerson.getForeignEntity("frontendAccount").getPrimaryKey());
                        // 5) Pushing the userId to the original client from which the magic link request was made.
                        // The original client is identified by runId. Pushing the userId will cause a login, and
                        // subsequently a push of the authorizations.
                        UpdatePasswordCredentials updatePasswordCredentials = new UpdatePasswordCredentials(
                            // No old password: redeeming the emailed link IS the proof of identity, exactly as
                            // the React front-office profile page relies on the authenticated session.
                            //
                            // This used to pass the stored hash, which only worked because the password check
                            // accepted a stored hash as if it were the password typed — the same branch that let
                            // support log in as a customer with a copied hash. That branch is gone, so this had
                            // to stop depending on it. Passing null is not a weakening: updateCredentials already
                            // resolves the account from the session it was invoked under (runAsUser below), and
                            // has always skipped the check for a null old password.
                            null,
                            update.newPassword() // new password
                        );
                        Promise<Void> promise = Promise.promise();
                        ThreadLocalStateHolder.runAsUser(targetUserId,
                            () -> promise.handle(AuthenticationService.updateCredentials(updatePasswordCredentials).mapEmpty())
                        );
                        return promise.future();
                    });
            });
    }

    @Override
    public Future<Void> logout() {
        return LogoutPush.pushLogoutMessageToClient();
    }

}
