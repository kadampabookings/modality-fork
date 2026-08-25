package one.modality.crm.server.authn.gateway;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Strings;
import dev.webfx.platform.util.collection.Collections;
import dev.webfx.stack.authn.*;
import dev.webfx.stack.authn.logout.server.LogoutPush;
import dev.webfx.stack.authn.server.gateway.spi.ServerAuthenticationGateway;
import dev.webfx.stack.hash.md5.Md5;
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
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import dev.webfx.stack.session.state.TransactionPreambleRegistry;
import one.modality.base.shared.entities.FrontendAccount;
import one.modality.base.shared.entities.Person;
import one.modality.crm.server.authn.gateway.magiclink.ModalityMagicLinkAuthenticationGateway;
import one.modality.crm.server.authn.gateway.shared.GuestPersonLinker;
import one.modality.crm.server.authn.gateway.shared.LocalizedMailTemplate;
import one.modality.crm.server.authn.gateway.shared.MagicLinkService;
import one.modality.crm.shared.services.authn.ModalityAuthenticationI18nKeys;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.time.Duration;
import java.util.Objects;


/**
 * @author Bruno Salmon
 */
public final class ModalityPasswordAuthenticationGateway implements ServerAuthenticationGateway, HasDataSourceModel {

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
    }

    // The canonical home of these two statements now that the server, not the caller, decides which one
    // runs. Triggers.frontOfficeTransaction/backOfficeTransaction still carry copies for the callers that
    // pass a preamble explicitly; those go away as each migrates to the marker.
    private static final String FRONT_OFFICE_TRANSACTION_SQL = "select set_transaction_parameters(false)";
    private static final String BACK_OFFICE_TRANSACTION_SQL = "select set_transaction_parameters(true)";

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

    private Future<Void> authenticateWithUsernamePassword(AuthenticateWithUsernamePasswordCredentials credentials) {
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
                if (!isTypedPasswordCorrect(password, fa.getPassword(), fa.getSalt())) {
                    // Deliberately not logging what was typed. A failed attempt is very often a real
                    // password — the one for another account, or the right one with a typo — and
                    // logging it wrote live credentials into logs/server.log in clear, where they
                    // outlive the session, get shipped to log aggregation, and sit in backups.
                    return Future.failedFuture("[%s] Wrong user or password".formatted(ModalityAuthenticationI18nKeys.AuthnWrongUserOrPasswordError));
                }
                Object personId = userPerson.getPrimaryKey();
                Object accountId = Entities.getPrimaryKey(userPerson.getForeignEntityId("frontendAccount"));
                ModalityUserPrincipal modalityUserPrincipal = new ModalityUserPrincipal(personId, accountId);
                // Link any guest Person records with the same email, in case the user booked
                // as a guest before logging in. Fire-and-forget.
                GuestPersonLinker.linkGuestPersonsToAccount(normalizedUsername, accountId, dataSourceModel)
                    .onFailure(err -> Console.log("GuestPersonLinker failed on login for " + normalizedUsername + ": " + err));
                return PushServerService.pushState(StateAccessor.createUserIdState(modalityUserPrincipal), runId);
            });
    }

    /**
     * Whether the password typed by the user matches the one stored for the account.
     *
     * <p>This used to have a second branch accepting the <em>stored hash itself</em> as a valid
     * password, so that support could copy a hash out of the back office and sign in as the
     * customer. That made the hash a permanent, transferable credential and turned any dump of
     * {@code frontend_account} into a plaintext password list — inverting the entire point of
     * storing passwords hashed — while leaving no record that support had signed in at all.
     *
     * <p>Support now uses {@code RequestSupportViewCredentials}, which issues a pass of its own:
     * short-lived, single-use, read-only, tied to one named customer and one named member of staff,
     * and recorded. See {@code ModalityMagicLinkAuthenticationGateway}.
     */
    private boolean isTypedPasswordCorrect(String typedPassword, String storedEncryptedPassword, String salt) {
        // The typed password is not encrypted, so we encrypt it to compare it with the stored one
        String typedEncryptedPassword = encryptPassword(typedPassword, salt);
        return Objects.equals(typedEncryptedPassword, storedEncryptedPassword);
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
        return MagicLinkService.loadMagicLinkFromTokenAndMarkAsUsed(credentials.magicLinkTokenOrVerificationCode(), dataSourceModel)
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
        return MagicLinkService.loadMagicLinkFromTokenOrVerificationCode(credentials.magicLinkTokenOrVerificationCode(), false, dataSourceModel)
            .compose(magicLink -> {
                UpdateStore updateStore = UpdateStore.create(dataSourceModel);
                FrontendAccount fa = updateStore.insertEntity(FrontendAccount.class);
                String email = magicLink.getEmail();
                String salt = email; // like KBS2 for now
                fa.setUsername(email);
                fa.setSalt(salt);
                fa.setPassword(encryptPassword(credentials.password(), salt));
                fa.setCorporation(1);
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
        return getUserClaims() // to get the old email (the passed credential contains the new email)
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
                    // We change the email in both the account, and the user person
                    UpdateStore updateStore = UpdateStore.create(dataSourceModel);
                    updateStore.updateEntity(userPerson.getFrontendAccount()).setUsername(magicLink.getEmail());
                    updateStore.updateEntity(userPerson).setEmail(magicLink.getEmail());
                    return updateStore.submitChanges()
                        .map(ignored -> null);
                }));
    }

    private String encryptPassword(String password, String salt) {
        // KBS2 way of encrypting the password
        String toEncrypt = salt + ":" + Md5.hash(password);
        return Md5.hash(toEncrypt); // encrypted
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
        // A support view is a front-office affordance. Letting one identify itself to the back office
        // would hand the session whatever back-office grants the CUSTOMER holds, which is neither
        // what support asked for nor something the customer consented to.
        if (modalityUserPrincipal.isSupportView() && isBackofficeAuthentication)
            return Future.failedFuture("[%s] A support view cannot be used in the back office".formatted(ModalityAuthenticationI18nKeys.SupportViewLinkInvalidError));
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
                // simply leaves the tab open does not keep the customer's account open indefinitely.
                return checkSupportViewStillLive(modalityUserPrincipal)
                    .map(ignored -> userPerson);
            });
    }

    /** How long a support member may keep a customer's account open before the pass has to be re-issued. */
    private static final Duration SUPPORT_VIEW_SESSION_DURATION = Duration.ofMinutes(30);

    /**
     * Fails when the grant behind a support-view session has run out.
     *
     * <p>Resolves both usernames rather than trusting the principal alone: the grant is recorded
     * against the pair (customer viewed, member of staff viewing), and matching on both is what
     * stops one agent's fresh pass from silently extending another's expired session.
     */
    private Future<Void> checkSupportViewStillLive(ModalityUserPrincipal principal) {
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
                    dataSourceModel)
                .mapEmpty();
        });
    }

    @Override
    public boolean acceptsUpdateCredentialsArgument(Object updateCredentialsArgument) {
        return updateCredentialsArgument instanceof UpdatePasswordCredentials;
    }

    @Override
    public Future<?> updateCredentials(Object updateCredentialsArgument) {
        if (!acceptsUpdateCredentialsArgument(updateCredentialsArgument))
            return Future.failedFuture(getClass().getSimpleName() + ".updateCredentials() requires a " + UpdatePasswordCredentials.class.getSimpleName() + " argument");
        UpdatePasswordCredentials passwordUpdate = (UpdatePasswordCredentials) updateCredentialsArgument;
        // A support view may look, not change. Checked here, synchronously, while the calling
        // principal is still on the thread — see refuseIfSupportView().
        Future<?> refusal = refuseIfSupportView();
        if (refusal != null)
            return refusal;
        // 1) We first check that the passed old password matches with the one in the database
        return queryModalityUserPerson("frontendAccount.(username, password, salt)")
            .compose(userPerson -> {
                String oldPassword = passwordUpdate.oldPassword();
                // Note: in case of resetting the password from a magic link, the old password is not typed by the user
                // but loaded again from the database (by the MagicLink gateway) and is therefore already encrypted.
                // When the password change originates from the legacy JavaFX user profile, the old password is in clear
                // (what the user directly typed). The React front-office profile page omits the old password entirely
                // (oldPassword == null) because the active authenticated session is sufficient proof of identity —
                // queryModalityUserPerson() above already resolved the user via the session, so we can skip the check.
                FrontendAccount fa = userPerson.getFrontendAccount();
                // isTypedPasswordCorrect() is handling both cases (oldPassword in clear or already encrypted)
                if (oldPassword != null && !isTypedPasswordCorrect(oldPassword, fa.getPassword(), fa.getSalt()))
                    return Future.failedFuture("[%s] The old password is not matching".formatted(ModalityAuthenticationI18nKeys.AuthnOldPasswordNotMatchingError));
                // 2) We update the password in the database
                String storedEncryptedPassword = encryptPassword(passwordUpdate.newPassword(), fa.getSalt());
                UpdateStore updateStore = UpdateStore.createAbove(fa.getStore());
                FrontendAccount ufa = updateStore.updateEntity(fa);
                ufa.setPassword(storedEncryptedPassword);
                // Map the SubmitChangesResult to null — the client only needs success/failure,
                // and SubmitChangesResult has no registered SerialCodec so returning it would
                // cause the bus-call reply to throw IllegalArgumentException during encode,
                // leaving the client's Promise un-resolved until its 30 s timeout.
                return updateStore.submitChanges().map(ignored -> null);
            });
    }

    @Override
    public Future<Void> logout() {
        return LogoutPush.pushLogoutMessageToClient();
    }

}
