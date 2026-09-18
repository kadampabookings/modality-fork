package one.modality.crm.server.authn.gateway;

import dev.webfx.platform.async.Future;
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
import dev.webfx.stack.orm.entity.UpdateStore;
import dev.webfx.stack.push.server.PushServerService;
import dev.webfx.stack.session.state.AuditActorRegistry;
import dev.webfx.stack.session.state.RestrictedPrincipalRegistry;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import dev.webfx.stack.session.state.TransactionPreambleRegistry;
import dev.webfx.stack.session.token.AuthenticatedState;
import one.modality.base.shared.entities.FrontendAccount;
import one.modality.base.shared.entities.MagicLink;
import one.modality.base.shared.entities.MagicLinkType;
import one.modality.base.shared.entities.Person;
import one.modality.crm.server.authn.gateway.magiclink.ModalityMagicLinkAuthenticationGateway;
import one.modality.crm.server.authn.gateway.shared.AccountSignInRestrictionStore;
import one.modality.crm.server.authn.gateway.shared.CredentialChangeProof;
import one.modality.crm.server.authn.gateway.shared.EmailChangeNotice;
import one.modality.crm.server.authn.gateway.shared.GuestPersonLinker;
import one.modality.crm.server.authn.gateway.shared.LocalizedMailTemplate;
import one.modality.crm.server.authn.gateway.shared.MagicLinkService;
import one.modality.crm.server.authn.gateway.shared.SetPasswordAfterRecoveryCredentials;
import one.modality.crm.server.authn.gateway.shared.StoredPasswords;
import one.modality.crm.server.authn.gateway.shared.PendingSecondFactor;
import one.modality.crm.server.authn.gateway.shared.PendingSecondFactorStore;
import one.modality.crm.server.authn.gateway.shared.SecondFactorMarker;
import one.modality.crm.server.authn.gateway.shared.SecondFactorPolicy;
import one.modality.crm.server.authn.gateway.shared.SecondFactorVerifiers;
import one.modality.crm.shared.services.authn.ModalityAuthenticationI18nKeys;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.time.Duration;
import java.util.List;
import java.util.Objects;


/**
 * @author Bruno Salmon
 */
public final class ModalityPasswordAuthenticationGateway implements ServerAuthenticationGateway, HasDataSourceModel {

    /** The shortest new password the server accepts — the front office's own minimum, so no form it offers is refused. */
    private static final int MIN_NEW_PASSWORD_LENGTH = 8;

    /**
     * Teaches the stack how to read an actor id out of a Modality principal.
     *
     * <p>The SQL submit provider stamps that id onto every write transaction so the person and
     * account audit triggers can record WHO made a change. It cannot work this out for itself:
     * ThreadLocalStateHolder.getUserId() is a generic Object, and only the side that
     * authenticated it knows that a ModalityUserPrincipal's person is the actor. A gateway is
     * exactly the component that knows, having created the principal in the first place.
     *
     * <p>Registered from boot() rather than a static initialiser so it happens when the gateway
     * is actually loaded. Any gateway would do — the mapping is about the principal TYPE, not
     * about how someone logged in — and registering it twice is harmless. Guests return null and
     * are simply not recorded as an actor.
     */
    @Override
    public void boot() {
        AuditActorRegistry.registerResolver(ModalityUserPrincipal::getUserPersonId);
        RestrictedPrincipalRegistry.setRegisteredUserPredicate(userId -> userId instanceof ModalityUserPrincipal);
        // Tells the SQL submit provider which sessions may only read. A support view is a member of
        // staff looking at a customer's front office; it must not be able to change anything, and
        // the only place that can be guaranteed is the layer every write passes through. Registered
        // here for the same reason as the resolver above: the gateway is what creates the principal,
        // so it is the component that can interpret one.
        RestrictedPrincipalRegistry.registerRestrictedUserPredicate(userId ->
            userId instanceof ModalityUserPrincipal mup && mup.isSupportView());
        // Tells the SQL submit provider what a transaction preamble says, so that a client asking for
        // one no longer gets to write it. Sending the SQL was how a caller chose the privileges its own
        // transaction ran under: set_transaction_parameters(true) is what makes the document triggers
        // skip EVENT_ON_HOLD, EVENT_CLOSED and DOUBLEBOOKING, and makes the allocator ignore capacity
        // and room eligibility. Now the client only says THAT it needs a preamble; this says what it is.
        //
        // NOT YET A CLOSED DOOR: isBackoffice() reads the session state the client sends, so the value
        // is still influenced by the caller — one source instead of two, and the single place a verified
        // principal plugs into once identity binding lands. Until then this removes the raw-SQL route,
        // not the choice. See docs/design/server-side-authorization-spec.md.
        //
        // Here rather than in the datasource plugin, whose module does not (yet) require
        // webfx.stack.session.state and whose module-info is WebFX-generated. The cost of sharing this
        // gateway's boot is that a deployment without it registers nothing — which fails loudly and
        // closed at the submit provider, never silently open, so it is a visible misconfiguration
        // rather than a hole.
        TransactionPreambleRegistry.registerResolver(() ->
            ThreadLocalStateHolder.isBackoffice() ? BACK_OFFICE_TRANSACTION_SQL : FRONT_OFFICE_TRANSACTION_SQL);
        // Starts the back-office second-factor policy listening for its configuration. It lives in
        // gateway-shared, which provides no ApplicationModuleBooter of its own; this gateway owns the
        // mint site the policy guards, so wherever the policy can matter this boot() has run. Idempotent,
        // and a deployment without this gateway reads no configuration and stays off — what it was before.
        SecondFactorPolicy.boot();
    }

    // The canonical home of these two statements now that the server, not the caller, decides which one
    // runs. Triggers.frontOfficeTransaction/backOfficeTransaction still carry copies for the callers that
    // pass a preamble explicitly; those go away as each migrates to the marker.
    private static final String FRONT_OFFICE_TRANSACTION_SQL = "select set_transaction_parameters(false)";
    private static final String BACK_OFFICE_TRANSACTION_SQL = "select set_transaction_parameters(true)";

    /** Guesses allowed at the second factor on one runId, advertised to the client in the marker. */
    private static final int MAX_SECOND_FACTOR_ATTEMPTS = 5;

    /** Shared by every second-factor line this gateway logs, so one grep finds the whole step-up. */
    private static final String LOG_PREFIX = "[2fa] ";

    private static final String CREATE_ACCOUNT_ACTIVITY_PATH_PREFIX = "/create-account";
    private static final String CREATE_ACCOUNT_ACTIVITY_PATH_FULL = CREATE_ACCOUNT_ACTIVITY_PATH_PREFIX + "/:token";

    // Temporarily hardcoded (to replace with database letters)
    private static final String CREATE_ACCOUNT_MAIL_FROM = "kbs@kadampa.net";
    // Display name shown next to the sender address in the recipient's inbox.
    // Kept alongside the flow-specific `from` constants because the brand is
    // context-sensitive (other email flows may send as a different persona).
    private static final String MAIL_FROM_NAME = "Kadampa Booking System";

    // Account-creation emails — dictionary-driven localization. Each template pairs a
    // per-language HTML body (baseName.html + baseName_<lang>.html) with a .properties
    // file holding the translated subject. See LocalizedMailTemplate for the wiring details.
    private static final LocalizedMailTemplate CREATE_ACCOUNT_MAIL = LocalizedMailTemplate.load(
        "AccountCreationMailBody", "AccountCreationMailMessages", ModalityPasswordAuthenticationGateway.class);
    private static final LocalizedMailTemplate CREATE_ACCOUNT_WITH_VERIFICATION_CODE_MAIL = LocalizedMailTemplate.load(
        "AccountCreationWithVerificationCodeMailBody", "AccountCreationWithVerificationCodeMailMessages", ModalityPasswordAuthenticationGateway.class);
    private static final LocalizedMailTemplate CREATE_ACCOUNT_ALREADY_EXISTS_MAIL = LocalizedMailTemplate.load(
        "AccountCreationAlreadyExistsMailBody", "AccountCreationAlreadyExistsMailMessages", ModalityPasswordAuthenticationGateway.class);

    private static final String CREATE_ACCOUNT_ALREADY_EXISTS_MAIL_FROM = "kbs@kadampa.net";

    private static final String UPDATE_EMAIL_ACTIVITY_PATH_PREFIX = "/user-profile/email-update";
    private static final String UPDATE_EMAIL_ACTIVITY_PATH_FULL = UPDATE_EMAIL_ACTIVITY_PATH_PREFIX + "/:token";

    private static final String UPDATE_EMAIL_MAIL_FROM = "kbs@kadampa.net";
    private static final LocalizedMailTemplate UPDATE_EMAIL_MAIL = LocalizedMailTemplate.load(
        "AccountEmailUpdateMailBody", "AccountEmailUpdateMailMessages", ModalityPasswordAuthenticationGateway.class);

    private final DataSourceModel dataSourceModel;

    public ModalityPasswordAuthenticationGateway() {
        this(DataSourceModelService.getDefaultDataSourceModel());
    }

    public ModalityPasswordAuthenticationGateway(DataSourceModel dataSourceModel) {
        this.dataSourceModel = dataSourceModel;
    }

    @Override
    public DataSourceModel getDataSourceModel() {
        return dataSourceModel;
    }

    @Override
    public boolean acceptsUserCredentials(Object userCredentials) {
        return userCredentials instanceof AuthenticateWithUsernamePasswordCredentials
               || userCredentials instanceof InitiateAccountCreationCredentials
               || userCredentials instanceof ContinueAccountCreationCredentials
               || userCredentials instanceof FinaliseAccountCreationCredentials
               || userCredentials instanceof InitiateEmailUpdateCredentials
               || userCredentials instanceof FinaliseEmailUpdateCredentials
            ;
    }

    /**
     * Credentials a support view is allowed to send: none of them.
     *
     * <p>Everything this gateway accepts either signs someone in or changes an account — creating it,
     * moving its email, setting its password. A member of staff looking at a customer's front office
     * has no business doing any of that, and the danger is not hypothetical: the front-office profile
     * page offers "change email" as a button, so without this guard one click from inside a support
     * view would send a confirmation link to an address of the agent's choosing and, once clicked,
     * reassign the customer's login. That last step runs in a fresh token-only session where no
     * support-view restriction could apply, so refusing to MINT the link is the only point where it
     * can be stopped.
     *
     * <p>A blanket refusal rather than a list of the dangerous ones: the next credential added here
     * should have to argue its way in, not be permitted by omission.
     */
    private Future<?> refuseIfSupportView() {
        if (ThreadLocalStateHolder.getUserId() instanceof ModalityUserPrincipal mup && mup.isSupportView())
            return Future.failedFuture("[%s] A support view cannot change this account".formatted(ModalityAuthenticationI18nKeys.SupportViewLinkInvalidError));
        return null;
    }

    @Override
    public Future<?> authenticate(Object credentials) {
        Future<?> refusal = refuseIfSupportView();
        if (refusal != null)
            return refusal;
        if (credentials instanceof AuthenticateWithUsernamePasswordCredentials cred) {
            return authenticateWithUsernamePassword(cred);
        }
        if (credentials instanceof InitiateAccountCreationCredentials cred) {
            return sendAccountCreationLink(cred);
        }
        if (credentials instanceof ContinueAccountCreationCredentials cred) {
            return continueAccountCreation(cred);
        }
        if (credentials instanceof FinaliseAccountCreationCredentials cred) {
            return finaliseAccountCreation(cred);
        }
        if (credentials instanceof InitiateEmailUpdateCredentials cred) {
            return sendEmailUpdateLink(cred);
        }
        if (credentials instanceof FinaliseEmailUpdateCredentials cred) {
            return finaliseEmailUpdate(cred);
        }
        return Future.failedFuture("[%s] requires a %s argument".formatted(getClass().getSimpleName(), AuthenticateWithUsernamePasswordCredentials.class.getSimpleName()));
    }

    private Future<?> authenticateWithUsernamePassword(AuthenticateWithUsernamePasswordCredentials credentials) {
        // Capturing the required client state info from thread local (before it will be wiped out by the async call)
        String runId = ThreadLocalStateHolder.getRunId();
        boolean isBackofficeAuthentication = ThreadLocalStateHolder.isBackoffice();
        // Capturing the parameters from the credentials
        String username = credentials.username();
        String password = credentials.password();
        if (Strings.isEmpty(username) || Strings.isEmpty(password))
            return Future.failedFuture("[%s] Username and password must not be empty".formatted(ModalityAuthenticationI18nKeys.AuthnUserOrPasswordEmptyError));
        username = username.trim(); // Ignoring leading and tailing spaces in username
        if (username.contains("@")) // If the username is an email address, it shouldn't be case-sensitive
            username = username.toLowerCase(); // emails are stored in lowercase in the database
        final String normalizedUsername = username; // effectively-final copy for lambda capture
        return EntityStore.create(dataSourceModel)
            // Note: accounts are created from the front-office and can first be used only to log in in the front-office,
            // but an admin or superadmin can mark them as back-office and can then be used to log in in the back-office as well.
            .<Person>executeQuery("select id,frontendAccount.(password, salt) from Person where frontendAccount.(corporation=$1 and lower(username)=lower($2) and !removed and !disabled and ($3=false or backoffice)) order by owner desc, id limit 1", 1, username, isBackofficeAuthentication)
            .compose(persons -> {
                if (persons.size() != 1)
                    return Future.failedFuture("[%s] Wrong user or password".formatted(ModalityAuthenticationI18nKeys.AuthnWrongUserOrPasswordError));
                Person userPerson = persons.get(0);
                FrontendAccount fa = userPerson.getFrontendAccount();
                Object accountId = Entities.getPrimaryKey(userPerson.getForeignEntityId("frontendAccount"));
                // "Stop my password working" (V0096). Asked BEFORE the password is checked, and answered
                // with the SAME error as a wrong one, which is the whole of the design here: a distinct
                // message after a successful check would tell whoever typed it that the password is
                // CORRECT — and a password that is right here is very often the same password somewhere
                // else, so confirming it hands an attacker the one thing this control was supposed to take
                // away. The owner's confusion is handled where it belongs, on the login page and on the
                // Security page that shows them what they turned on; it is not worth an oracle.
                //
                // It does widen an existing timing difference, and that is accepted rather than unnoticed:
                // an unknown username already returned above without so much as an MD5, and a known one now
                // costs a database round trip before anything else, which is easier to measure than the
                // hash was. Equalising it would mean running this query for every unknown username too —
                // one database call per attempt in a credential-stuffing flood, to hide a distinction the
                // recovery flow does not hide either. The cheaper leak is the one we keep.
                return AccountSignInRestrictionStore.isPasswordClosed(accountId)
                    .compose(passwordClosed -> {
                        if (passwordClosed) {
                            // Logged without the username: this is a refusal an operator may want to see,
                            // and naming the person would put an account under a security restriction into
                            // logs/server.log, which travels further than the table it came from.
                            Console.log("🛡 Password sign-in refused — the account's owner has closed it");
                            return Future.failedFuture("[%s] Wrong user or password".formatted(ModalityAuthenticationI18nKeys.AuthnWrongUserOrPasswordError));
                        }
                        return continueAfterPasswordRestrictionCheck(userPerson, fa, accountId, password, normalizedUsername, runId, isBackofficeAuthentication);
                    });
            });
    }

    /** The rest of the password login, once the account is known not to have closed this way in. */
    private Future<?> continueAfterPasswordRestrictionCheck(Person userPerson, FrontendAccount fa, Object accountId,
                                                            String password, String normalizedUsername, String runId,
                                                            boolean isBackofficeAuthentication) {
        if (!StoredPasswords.matches(password, fa.getPassword(), fa.getSalt())) {
            // Deliberately not logging what was typed. A failed attempt is very often a real
            // password — the one for another account, or the right one with a typo — and
            // logging it wrote live credentials into logs/server.log in clear, where they
            // outlive the session, get shipped to log aggregation, and sit in backups.
            return Future.failedFuture("[%s] Wrong user or password".formatted(ModalityAuthenticationI18nKeys.AuthnWrongUserOrPasswordError));
        }
        // This tab has just proved the password: for a few minutes it may add a passkey or change the email
        // without typing it again — what lets the back office offer a passkey straight after this sign-in.
        // Usable only once a session for this account exists in this tab. See CredentialChangeProof.
        CredentialChangeProof.notePasswordProved(runId, accountId);
        Object personId = userPerson.getPrimaryKey();
        ModalityUserPrincipal modalityUserPrincipal = new ModalityUserPrincipal(personId, accountId);
        // Both linker calls below stay on this side of the second-factor step: they are a data
        // fix-up keyed on a password that has just been proven, not a session — they push nothing
        // and grant nothing, and a login that then stops at the step-up leaves them correctly done.
        // Link any guest Person records with the same email, in case the user booked
        // as a guest before logging in. Fire-and-forget.
        GuestPersonLinker.linkGuestPersonsToAccount(normalizedUsername, accountId, dataSourceModel)
            .onFailure(err -> Console.log("GuestPersonLinker failed on login for " + normalizedUsername + ": " + err));
        // Attach the person-less guest bookings the React front office makes under this
        // address to the owner Person — only if THIS session verified the address by
        // redeeming a link or code for it (a password proves the account, not the
        // address; see the linker). Awaited before the login push so the first /orders
        // load already sees them, but a failure must never keep anyone from signing in.
        return GuestPersonLinker.linkGuestDocumentsToPerson(normalizedUsername, personId, false, runId, dataSourceModel)
            .otherwise(err -> {
                Console.log("GuestPersonLinker (documents) failed on login for " + normalizedUsername + ": " + err);
                return null;
            })
            // isBackofficeAuthentication was captured at the top of authenticateWithUsernamePassword,
            // before any async hop, which is the only place it can be read — see AuthenticatedState.createFor,
            // and why it is threaded through here rather than re-read.
            .compose(ignored -> mintOrAskForSecondFactor(modalityUserPrincipal, personId, accountId, isBackofficeAuthentication, runId));
    }

    /**
     * The password was right. Either this signs the user in, or it stops one step short and asks for a
     * second factor.
     *
     * <p>Everything before this point is unchanged, and everything after it is the login that always
     * existed. What sits between is the whole step-up: under a policy that asks for a factor on a
     * back-office login, an account that HOLDS one gets the pending marker instead of a session —
     * nothing is minted, nothing is pushed, no {@code auth_session} family is opened, and the client
     * stays logged out until it answers on the same runId. A session for someone who has proved one
     * factor would be a session; a "restricted" principal would be one too, and the client renders the
     * app for any principal at all.
     *
     * <p>The four answers, in the order they are decided:
     * <ul>
     * <li>policy off, or not a back-office login — mint exactly as before. The requirement attaches to
     *     back-office authentication, not to the account.</li>
     * <li>a verifier could not answer — refused, with an honest "temporarily unavailable". An
     *     unapplied migration, a rotated-away key or a pool timeout must not become a silent,
     *     organisation-wide downgrade to password-only; see {@link SecondFactorVerifiers}.</li>
     * <li>a confirmed factor — hold the proven login in {@link PendingSecondFactorStore} and return the
     *     marker. If the store is full the login fails with the ordinary credentials error rather than
     *     minting: refusing a login is recoverable, minting a one-factor back-office session is not.</li>
     * <li>no confirmed factor — refused under {@code required}, minted otherwise. Naming the
     *     requirement discloses nothing here: the caller has already proved the password.</li>
     * </ul>
     */
    private Future<Object> mintOrAskForSecondFactor(ModalityUserPrincipal principal, Object personId, Object accountId,
                                                    boolean isBackofficeAuthentication, String runId) {
        if (!SecondFactorPolicy.get().asksForFactor(isBackofficeAuthentication))
            return mintAndPush(principal, isBackofficeAuthentication, runId);
        return SecondFactorVerifiers.enrolmentOf(accountId)
            .compose(enrolment -> {
                if (enrolment.unavailable()) {
                    // FAIL CLOSED. The account may well hold a factor; nothing here can tell, and
                    // "cannot tell" minted is a one-factor back-office session. The account id and
                    // the failing method code go to the log — the operator needs both to find the
                    // cause — while the caller is told the truth and nothing more.
                    Console.log(LOG_PREFIX + "🛑 Back-office login REFUSED for account " + accountId
                                + ": second-factor verifier(s) " + enrolment.unavailableMethodsText()
                                + " could not answer (see the verifier's own line above for why)");
                    return Future.failedFuture("[%s] Second-factor verification is temporarily unavailable".formatted(ModalityAuthenticationI18nKeys.AuthnSecondFactorUnavailableError));
                }
                List<String> methods = enrolment.methods();
                if (methods.isEmpty()) {
                    if (SecondFactorPolicy.get().isRequiredNow())
                        return Future.failedFuture("[%s] This account has no second factor for the back office".formatted(ModalityAuthenticationI18nKeys.AuthnSecondFactorNotEnrolledError));
                    return mintAndPush(principal, isBackofficeAuthentication, runId);
                }
                return holdForSecondFactor(personId, accountId, isBackofficeAuthentication, runId, methods);
            });
    }

    /** The login as it has always been: mint the session from the captured back-office flag, then push it. */
    private Future<Object> mintAndPush(ModalityUserPrincipal principal, boolean isBackofficeAuthentication, String runId) {
        return AuthenticatedState.createFor(principal, isBackofficeAuthentication)
            .compose(authenticatedState -> PushServerService.pushState(authenticatedState, runId))
            .map(ignored -> null);
    }

    /** Parks the proven login against its runId and tells the client what it may answer with. */
    private Future<Object> holdForSecondFactor(Object personId, Object accountId, boolean isBackofficeAuthentication,
                                               String runId, List<String> methods) {
        PendingSecondFactor pending = new PendingSecondFactor(personId, accountId, isBackofficeAuthentication, methods,
            System.currentTimeMillis() + PendingSecondFactorStore.TTL_MILLIS, MAX_SECOND_FACTOR_ATTEMPTS);
        if (!PendingSecondFactorStore.getInstance().put(runId, pending))
            // Store full (or no runId to key on): the same answer as a wrong password, because the
            // alternative — minting anyway — is a back-office session on one factor.
            return Future.failedFuture("[%s] Wrong user or password".formatted(ModalityAuthenticationI18nKeys.AuthnWrongUserOrPasswordError));
        // The person id and the factor kinds, never the username and never a code: this line says
        // "somebody's back-office login is waiting", not who typed what.
        Console.log(LOG_PREFIX + "Second factor required for person " + personId + " (" + String.join(",", methods) + ")");
        return Future.succeededFuture(SecondFactorMarker.json(methods,
            PendingSecondFactorStore.TTL_MILLIS / 1000, MAX_SECOND_FACTOR_ATTEMPTS));
    }

    private Future<Void> sendAccountCreationLink(InitiateAccountCreationCredentials credentials) {
        // We check that the requested account doesn't exist in the database. If it doesn't exist, we send an
        // "Account creation" email as requested. But if it exists, we send an "Account already exists" email instead.
        // For this later case, we still create a login link that will actually act as a magic link.
        String loginRunId = ThreadLocalStateHolder.getRunId(); // Capturing the loginRunId before async operation
        boolean verificationCodeOnly = credentials.isVerificationCodeOnly();
        // The user's chosen UI language flows in via InitiateAccountCreationCredentials so the
        // email matches what they saw on the sign-up page.
        String lang = Strings.toSafeString(credentials.getLanguage());
        return EntityStore.create(dataSourceModel)
            .<FrontendAccount>executeQuery("select FrontendAccount where corporation=$1 and lower(username)=lower($2) limit 1", 1, credentials.getEmail())
            .compose(accounts -> {
                    boolean doesntExists = accounts.isEmpty();
                    // Pick the right template for the branch, then render body + subject in the user's language.
                    LocalizedMailTemplate template = doesntExists
                        ? (verificationCodeOnly ? CREATE_ACCOUNT_WITH_VERIFICATION_CODE_MAIL : CREATE_ACCOUNT_MAIL)
                        : CREATE_ACCOUNT_ALREADY_EXISTS_MAIL;
                    return MagicLinkService.createAndSendMagicLink(
                        loginRunId,
                        credentials,
                        null,
                        doesntExists ? CREATE_ACCOUNT_ACTIVITY_PATH_FULL : ModalityMagicLinkAuthenticationGateway.MAGIC_LINK_ACTIVITY_PATH_FULL,
                        MAIL_FROM_NAME,
                        doesntExists ? CREATE_ACCOUNT_MAIL_FROM : CREATE_ACCOUNT_ALREADY_EXISTS_MAIL_FROM,
                        template.renderSubject(lang),
                        template.renderBody(lang),
                        dataSourceModel
                    );
                }
            );
    }

    private Future<String> continueAccountCreation(ContinueAccountCreationCredentials credentials) {
        // Marks the token as used by THIS session; finaliseAccountCreation() below then accepts it
        // from the same session only (see MagicLinkService.loadMagicLinkForAccountCreation).
        return MagicLinkService.loadMagicLinkFromTokenAndMarkAsUsed(credentials.magicLinkTokenOrVerificationCode(), dataSourceModel)
            .compose(magicLink -> magicLink.getLinkType() == MagicLinkType.LOGIN
                ? Future.succeededFuture(magicLink)
                // Only account-creation / login links may open the sign-up form. Reported as
                // unrecognised: to whoever pasted a booking-access token here, that is the truth.
                : Future.<MagicLink>failedFuture("[%s] Magic link not found (token: %s)".formatted(ModalityAuthenticationI18nKeys.LoginLinkUnrecognisedError, credentials.magicLinkTokenOrVerificationCode())))
            .compose(magicLink -> MagicLinkService.loadUserPersonFromMagicLink(magicLink)
                .compose(userPerson -> {
                    String email = magicLink.getEmail();
                    if (userPerson != null)
                        return Future.succeededFuture("[%s] There is already an account associated with %s".formatted(ModalityAuthenticationI18nKeys.CreateAccountAlreadyExistsError, email));
                    return Future.succeededFuture(email);
                })
            );
    }

    private Future<Object> finaliseAccountCreation(FinaliseAccountCreationCredentials credentials) {
        // Captured before the async chain: identifies the tab that typed the code / clicked the link.
        String runId = ThreadLocalStateHolder.getRunId();
        // The credential must be a LOGIN-type link that is unexpired and unused — or the token this
        // same session already claimed through ContinueAccountCreation. Until 2026-09 this loaded the
        // row with every check off: an unscoped, year-long BOOKING_ACCESS code (one per guest booking)
        // was accepted, and nothing was ever consumed, so guessing 6 digits could mint a password
        // account for somebody else's email.
        return MagicLinkService.loadMagicLinkForAccountCreation(credentials.magicLinkTokenOrVerificationCode(), runId, dataSourceModel)
            .compose(magicLink -> {
                // Above the link's store so the usage mark below can be staged next to the account insert.
                UpdateStore updateStore = UpdateStore.createAbove(magicLink.getStore());
                FrontendAccount fa = updateStore.insertEntity(FrontendAccount.class);
                String email = magicLink.getEmail();
                String salt = email; // like KBS2 for now
                fa.setUsername(email);
                fa.setSalt(salt);
                fa.setPassword(StoredPasswords.encrypt(credentials.password(), salt));
                fa.setCorporation(1);
                // Consume the credential in the same transaction as the account it creates: a code is
                // single-use from here on, and neither write can land without the other.
                if (magicLink.getUsageDate() == null)
                    MagicLinkService.stageMagicLinkUsage(updateStore, magicLink, runId);
                return updateStore.submitChanges()
                    .compose(ignored -> {
                        // Link any guest Person records (created during guest bookings) that share
                        // this email to the new account. Fire-and-forget: failures are logged but
                        // don't block the response.
                        GuestPersonLinker.linkGuestPersonsToAccount(email, fa.getPrimaryKey(), dataSourceModel)
                            .onFailure(err -> Console.log("GuestPersonLinker failed on account creation for " + email + ": " + err));
                        return Future.succeededFuture(fa.getPrimaryKey());
                    });
            });
    }

    private Future<Void> sendEmailUpdateLink(InitiateEmailUpdateCredentials credentials) {
        // Capturing the required client state info from thread local (before it will be wiped out by the async call)
        String runId = ThreadLocalStateHolder.getRunId();
        // Render the email in the user's current language — mirrors what they saw in the profile page.
        String lang = Strings.toSafeString(credentials.getLanguage());
        // Nothing is sent until the change is proved: the current password, or a recovery minutes old.
        // Changing the sign-in email changes who can recover the account — whoever holds the new address can
        // then use "forgot password" — so a session alone must not be able to do it. See CredentialChangeProof.
        // Both futures are started here, on the caller's thread, because both read the caller from it.
        Future<Void> proved = CredentialChangeProof.require(credentials.getCurrentPassword(), dataSourceModel);
        Future<UserClaims> claims = getUserClaims(); // to get the old email (the passed credential contains the new email)
        return proved
            .compose(ignored -> claims)
            .compose(userClaims -> MagicLinkService.createAndSendMagicLink(
                    runId,
                    credentials, // contains the new email (the one to send the link to)
                    userClaims.email(), // current email for this account (= old email)
                    UPDATE_EMAIL_ACTIVITY_PATH_FULL,
                MAIL_FROM_NAME,
                UPDATE_EMAIL_MAIL_FROM,
                UPDATE_EMAIL_MAIL.renderSubject(lang),
                UPDATE_EMAIL_MAIL.renderBody(lang),
                    dataSourceModel
                )
            );
    }

    private Future<Void> finaliseEmailUpdate(FinaliseEmailUpdateCredentials credentials) {
        // We check the validity of the token, and if valid, we load the user person
        return MagicLinkService.loadMagicLinkFromTokenAndMarkAsUsed(credentials.magicLinkTokenOrVerificationCode(), dataSourceModel)
            .compose(magicLink -> MagicLinkService.loadUserPersonFromMagicLink(magicLink)
                .compose(userPerson -> {
                    // No account at the link's old address any more — a second pending change clicked after
                    // the first took effect. A refusal, not an exception: an exception here leaves the bus
                    // call with no reply at all, and the person watching a spinner until it times out.
                    if (userPerson == null)
                        return Future.failedFuture("[%s] No such user account".formatted(ModalityAuthenticationI18nKeys.AuthnNoSuchUserAccountError));
                    // We change the email in both the account, and the user person
                    UpdateStore updateStore = UpdateStore.create(dataSourceModel);
                    updateStore.updateEntity(userPerson.getFrontendAccount()).setUsername(magicLink.getEmail());
                    updateStore.updateEntity(userPerson).setEmail(magicLink.getEmail());
                    // Once committed, the previous address is told: the confirmation link went to the NEW one,
                    // so otherwise the address the owner still reads hears nothing. Not awaited — the change is
                    // done whether or not the mail goes, and the notice logs its own failure (EmailChangeNotice).
                    return updateStore.submitChanges()
                        .onSuccess(ignored -> EmailChangeNotice.send(magicLink.getOldEmail(), magicLink.getEmail(), magicLink.getLang()))
                        .map(ignored -> null);
                }));
    }

    @Override
    public boolean acceptsUserId() {
        Object userId = ThreadLocalStateHolder.getUserId();
        return userId instanceof ModalityUserPrincipal;
    }

    @Override
    public Future<?> verifyAuthenticated() {
        Object userId = ThreadLocalStateHolder.getUserId();
        Console.log("👮 Checking userId=[%s]".formatted(userId));
        return queryModalityUserPerson("id")
            .map(ignoredQueryResult -> userId);
    }

    @Override
    public Future<UserClaims> getUserClaims() {
        return queryModalityUserPerson("frontendAccount.username,email,phone")
            .map(userPerson -> {
                String username = userPerson.evaluate("frontendAccount.username");
                String email = userPerson.getEmail();
                String phone = userPerson.getPhone();
                return new UserClaims(username, email, phone, null);
            });
    }

    private Future<Person> queryModalityUserPerson(String fields) {
        // Capturing the required client state info from thread local (before it will be wiped out by the async call)
        Object userId = ThreadLocalStateHolder.getUserId();
        boolean isBackofficeAuthentication = ThreadLocalStateHolder.isBackoffice();
        if (!(userId instanceof ModalityUserPrincipal modalityUserPrincipal))
            return Future.failedFuture("[%s] This userId object is not recognized by Modality".formatted(ModalityAuthenticationI18nKeys.AuthnUnrecognizedUserIdError));
        return EntityStore.create(dataSourceModel)
            .<Person>executeQuery("select " + fields + " from Person where id=$1 and frontendAccount.(id=$2 and !disabled and ($3=false or backoffice))", modalityUserPrincipal.getUserPersonId(), modalityUserPrincipal.getUserAccountId(), isBackofficeAuthentication)
            .compose(persons -> {
                if (persons.size() != 1)
                    return Future.failedFuture("[%s] No such user account".formatted(ModalityAuthenticationI18nKeys.AuthnNoSuchUserAccountError));
                Person userPerson = persons.get(0);
                if (!modalityUserPrincipal.isSupportView())
                    return Future.succeededFuture(userPerson);
                // Support views end on their own. This runs on the verification and claims round
                // trips — i.e. on reconnection and on every authorization refresh — so an agent who
                // simply leaves the tab open does not keep the borrowed account open indefinitely.
                //
                // The principal cannot say WHICH flavour of view it is — front-office SUPPORT_VIEW
                // or back-office BACKOFFICE_VIEW carry the same (target, account, agent) shape. The
                // live magic_link row's type is the only separator, so the context the session
                // presents under picks the type the row must have. That yields mutual exclusion in
                // both directions: a front-office pass presented under the back-office flag finds
                // no live BACKOFFICE_VIEW row and dies here, and a back-office pass presented
                // without the flag finds no live SUPPORT_VIEW row.
                // This replaced the earlier flat refusal of support views in the back office, which
                // predated the BACKOFFICE_VIEW flavour: a support view is no longer only a
                // front-office affordance, but each pass still opens exactly the door it was
                // minted for.
                //
                // The row type is now the ONLY separator, where it used to have the backoffice
                // column behind it: this comment said a front-office pass under the back-office
                // flag "also fails the backoffice filter in the person query above, since
                // front-office targets never hold the flag". They can hold it now — a super admin
                // may open a staff account's front office (loadSupportViewTarget in the magic-link
                // gateway), so that second line of defence is gone and only the type check stands.
                //
                // Known residual, accepted, and wider than it was: the old refusal was
                // unconditional, this one depends on DB state. If the SAME (target, agent) pair
                // holds live rows of BOTH flavours in one 30-minute window — previously that
                // needed the target's backoffice flag granted mid-window, now it needs only the
                // same super admin to mint both passes — a tampered front-office session claiming
                // the backoffice flag could ride the BO row's liveness. Still no privilege gained,
                // and for a sharper reason than before: the only caller who can reach this is a
                // super admin, who can mint a back-office view openly anyway (and writes stay
                // blocked either way). Closing it would need the principal to carry its flavour,
                // which the identity-binding token will eventually provide — milestone M1 of
                // docs/security/backoffice-second-factor-totp-design.md.
                return checkSupportViewStillLive(modalityUserPrincipal,
                        isBackofficeAuthentication ? MagicLinkType.BACKOFFICE_VIEW : MagicLinkType.SUPPORT_VIEW)
                    .map(ignored -> userPerson);
            });
    }

    /** How long a support member may keep a borrowed account open before the pass has to be re-issued. Shared by both flavours. */
    private static final Duration SUPPORT_VIEW_SESSION_DURATION = Duration.ofMinutes(30);

    /**
     * Fails when the grant behind a support-view session has run out.
     *
     * <p>Resolves both usernames rather than trusting the principal alone: the grant is recorded
     * against the pair (account viewed, member of staff viewing), and matching on both is what
     * stops one agent's fresh pass from silently extending another's expired session.
     *
     * @param linkType the flavour the session's context calls for — see the caller for how the
     *                 claimed {@code backoffice} flag maps to it and why that is safe
     */
    private Future<Void> checkSupportViewStillLive(ModalityUserPrincipal principal, MagicLinkType linkType) {
        EntityStore entityStore = EntityStore.create(dataSourceModel);
        return Future.all(
            entityStore.<Person>executeQuery("select frontendAccount.username from Person where id=$1 limit 1", principal.getUserPersonId()),
            entityStore.<Person>executeQuery("select frontendAccount.username from Person where id=$1 limit 1", principal.getSupportAgentPersonId())
        ).compose(compositeFuture -> {
            EntityList<Person> targets = compositeFuture.resultAt(0);
            EntityList<Person> agents = compositeFuture.resultAt(1);
            Person targetPerson = Collections.first(targets);
            Person agentPerson = Collections.first(agents);
            if (targetPerson == null || agentPerson == null)
                return Future.failedFuture("[%s] Support view session has ended".formatted(ModalityAuthenticationI18nKeys.SupportViewLinkInvalidError));
            return MagicLinkService.loadLiveSupportViewLink(
                    targetPerson.evaluate("frontendAccount.username"),
                    agentPerson.evaluate("frontendAccount.username"),
                    SUPPORT_VIEW_SESSION_DURATION,
                    linkType,
                    dataSourceModel)
                .mapEmpty();
        });
    }

    @Override
    public boolean acceptsUpdateCredentialsArgument(Object updateCredentialsArgument) {
        // The second type has no serial codec, so only server code — the magic-link gateway, after a
        // redeemed recovery link — can hand one over. See SetPasswordAfterRecoveryCredentials.
        return updateCredentialsArgument instanceof UpdatePasswordCredentials
               || updateCredentialsArgument instanceof SetPasswordAfterRecoveryCredentials;
    }

    @Override
    public Future<?> updateCredentials(Object updateCredentialsArgument) {
        if (!acceptsUpdateCredentialsArgument(updateCredentialsArgument))
            return Future.failedFuture(getClass().getSimpleName() + ".updateCredentials() requires a " + UpdatePasswordCredentials.class.getSimpleName() + " argument");
        // Which proof of identity came with this change. A recovery flow has already matched a redeemed
        // link to this runId; a session has proved only that it was opened by the owner, at some point.
        boolean afterRecovery = updateCredentialsArgument instanceof SetPasswordAfterRecoveryCredentials;
        String oldPassword = afterRecovery ? null : ((UpdatePasswordCredentials) updateCredentialsArgument).oldPassword();
        String newPassword = afterRecovery
            ? ((SetPasswordAfterRecoveryCredentials) updateCredentialsArgument).newPassword()
            : ((UpdatePasswordCredentials) updateCredentialsArgument).newPassword();
        // A support view may look, not change. Checked here, synchronously, while the calling
        // principal is still on the thread — see refuseIfSupportView().
        Future<?> refusal = refuseIfSupportView();
        if (refusal != null)
            return refusal;
        // A floor on the new password here, not only in the browser: the browser's rules are UX, and the
        // recovery route reaches this line with nothing else checked. An empty one would be stored — and then
        // accepted by every password sign-in — and a null would throw inside the hash. Only the length:
        // the fuller rules (a capital, a digit, a symbol) belong to the forms that can explain them.
        if (newPassword == null || newPassword.length() < MIN_NEW_PASSWORD_LENGTH)
            return Future.failedFuture("[%s] The new password is too short".formatted(ModalityAuthenticationI18nKeys.AuthnNewPasswordTooShortError));
        // A change from a SESSION must name the current password, and there is no exception for an
        // account that has none.
        //
        // The reason is the stolen laptop. A session is bounded — hours idle, a day at most, and ended by
        // "sign out my other devices" — while a password is not. Letting a session set one turns a taken
        // session into an account, which outlives every control on the Security page. That is equally true
        // of an account with no password yet: a thief holding its session could create one. So a session
        // proves the old password or changes nothing; somebody who has none sets their first through the
        // emailed link, where the mailbox is the proof. Refused here rather than handed to the hash, which
        // would throw on a null (Md5 calls getBytes on it) instead of refusing.
        if (!afterRecovery && Strings.isEmpty(oldPassword))
            return Future.failedFuture("[%s] The old password is not matching".formatted(ModalityAuthenticationI18nKeys.AuthnOldPasswordNotMatchingError));
        return queryModalityUserPerson("frontendAccount.(username, password, salt)")
            .compose(userPerson -> {
                FrontendAccount fa = userPerson.getFrontendAccount();
                Object accountId = Entities.getPrimaryKey(userPerson.getForeignEntityId("frontendAccount"));
                // Read with the FAIL-CLOSED variant, unlike the sign-in path: if the restriction cannot be
                // read, refuse. A person retries in a minute; a restricted account that got a password
                // back because the database was slow is the control quietly failing when it matters.
                return AccountSignInRestrictionStore.readPasswordClosed(accountId)
                    .compose(passwordClosed -> {
                        // "Stop my password working" (V0096) closes this too, from EITHER route. From a
                        // session that is the point of refusing; from recovery it is what closing recovery
                        // means in practice — the link can still be redeemed, but it cannot put a password
                        // back. The owner lifts it deliberately, on the Security page.
                        if (passwordClosed)
                            return Future.failedFuture("[%s] Password sign-in is closed on this account".formatted(ModalityAuthenticationI18nKeys.AuthnPasswordSignInClosedError));
                        if (!afterRecovery && !StoredPasswords.matches(oldPassword, fa.getPassword(), fa.getSalt()))
                            return Future.failedFuture("[%s] The old password is not matching".formatted(ModalityAuthenticationI18nKeys.AuthnOldPasswordNotMatchingError));
                        String storedEncryptedPassword = StoredPasswords.encrypt(newPassword, fa.getSalt());
                        UpdateStore updateStore = UpdateStore.createAbove(fa.getStore());
                        FrontendAccount ufa = updateStore.updateEntity(fa);
                        ufa.setPassword(storedEncryptedPassword);
                        // Map the SubmitChangesResult to null — the client only needs success/failure,
                        // and SubmitChangesResult has no registered SerialCodec so returning it would
                        // cause the bus-call reply to throw IllegalArgumentException during encode,
                        // leaving the client's Promise un-resolved until its 30 s timeout.
                        return updateStore.submitChanges().map(ignored -> null);
                    });
            });
    }

    @Override
    public Future<Void> logout() {
        return LogoutPush.pushLogoutMessageToClient();
    }

}
