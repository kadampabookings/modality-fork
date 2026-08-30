package one.modality.crm.server.authn.gateway.webauthn;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecord;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticationRequest;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.RegistrationRequest;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.AstArray;
import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.json.Json;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.conf.ConfigLoader;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Numbers;
import dev.webfx.platform.util.Strings;
import dev.webfx.stack.authn.UserClaims;
import dev.webfx.stack.authn.logout.server.LogoutPush;
import dev.webfx.stack.authn.server.gateway.spi.ServerAuthenticationGateway;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.domainmodel.HasDataSourceModel;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.push.server.PushServerService;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import dev.webfx.stack.session.token.AuthenticatedState;
import one.modality.base.shared.entities.Person;
import one.modality.crm.server.authn.gateway.shared.LoginPersonResolver;
import one.modality.crm.shared.services.authn.AuthenticateWithPasskeyCredentials;
import one.modality.crm.shared.services.authn.FinalisePasskeyRegistrationCredentials;
import one.modality.crm.shared.services.authn.ListPasskeysCredentials;
import one.modality.crm.shared.services.authn.ModalityAuthenticationI18nKeys;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;
import one.modality.crm.shared.services.authn.RemovePasskeyCredentials;
import one.modality.crm.shared.services.authn.RenamePasskeyCredentials;
import one.modality.crm.shared.services.authn.StartPasskeyAssertionCredentials;
import one.modality.crm.shared.services.authn.StartPasskeyRegistrationCredentials;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Passkey (WebAuthn) authentication gateway: registration + login ceremonies and self-service
 * credential management, all keyed to the {@code frontend_account} shared by the front office and
 * the back office — which is what lets ONE passkey sign a person into both apps (the rpId spans
 * both origins, see {@link WebAuthnConfig}).
 *
 * <p>Verification is delegated to webauthn4j (never hand-rolled): challenge (single-use, from
 * {@link WebAuthnChallengeStore}), origin ∈ configured allowlist, rpId hash, user presence + user
 * verification, and the assertion signature against the stored public key. The login's back-office
 * context is then derived from the BROWSER-VERIFIED clientDataJSON origin — strictly stronger than
 * the client-asserted state the password gateway has to rely on — and the same account fences are
 * applied (corporation, !disabled, backoffice flag for BO origins, owner-first person resolution
 * per the magic-link owner fix).
 *
 * <p>What "accepting a principal" means here (the modality-base-server-max-plugin house rule): a
 * login is granted only after a cryptographic proof of possession of a key whose public half this
 * server stored during an authenticated registration — nothing is taken on the client's word.
 *
 * <p>Deliberate absences, so a future reader doesn't "fix" them:
 * <ul>
 * <li>No {@code GuestPersonLinker} call on login — linking keys on a typed email and a passkey
 *     login types none; guests who later log in with a password still get linked there.</li>
 * <li>Sign-count regressions WARN and accept rather than lock out: synced passkeys (iCloud
 *     Keychain, Google Password Manager) legitimately report 0 or regress across devices, so a
 *     hard fail would lock out real users to catch an attack (a cloned authenticator) that
 *     credential syncing already makes undetectable by counters. The stored value still only
 *     ever grows ({@code GREATEST}), and every regression is logged with the row id.</li>
 * </ul>
 *
 * @author Claude Code
 */
public final class ModalityWebAuthnAuthenticationGateway implements ServerAuthenticationGateway, HasDataSourceModel {

    private static final String CONFIG_PATH = "modality.crm.server.authn.webauthn";
    private static final String LOG_PREFIX = "[webauthn] ";
    /** Advertised to the browser dialog; the challenge store's TTL gives 60s slack on top. */
    private static final int CEREMONY_TIMEOUT_MILLIS = 120_000;
    private static final int MAX_LABEL_LENGTH = 64;
    private static final int MAX_TRANSPORTS_LENGTH = 128;
    private static final String REGISTRATION_PURPOSE = ":reg";
    private static final String ASSERTION_PURPOSE = ":auth";

    // webauthn4j entry points are thread-safe and shared. "Non-strict" = attestation statements are
    // not chain-verified, matching the "attestation":"none" we request — we authenticate users, we
    // don't certify authenticator models.
    private static final WebAuthnManager WEBAUTHN_MANAGER = WebAuthnManager.createNonStrictWebAuthnManager();
    private static final AttestedCredentialDataConverter ATTESTED_CREDENTIAL_DATA_CONVERTER =
        new AttestedCredentialDataConverter(new ObjectConverter());
    private static final List<PublicKeyCredentialParameters> PUB_KEY_CRED_PARAMS = List.of(
        new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
        new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256));

    private static final Base64.Encoder B64URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DECODER = Base64.getUrlDecoder();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int USER_HANDLE_BYTES = 32;

    private final DataSourceModel dataSourceModel;
    private final WebAuthnChallengeStore challengeStore = new WebAuthnChallengeStore();
    private final WebAuthnCredentialStore credentialStore = new WebAuthnCredentialStore();
    // Written once by the config-loaded callback, read by every ceremony — volatile is the contract
    private volatile WebAuthnConfig config = WebAuthnConfig.unconfigured();

    public ModalityWebAuthnAuthenticationGateway() {
        this(DataSourceModelService.getDefaultDataSourceModel());
    }

    public ModalityWebAuthnAuthenticationGateway(DataSourceModel dataSourceModel) {
        this.dataSourceModel = dataSourceModel;
    }

    @Override
    public DataSourceModel getDataSourceModel() {
        return dataSourceModel;
    }

    @Override
    public void boot() {
        ConfigLoader.onConfigLoaded(CONFIG_PATH, loadedConfig -> {
            config = WebAuthnConfig.fromConfig(loadedConfig);
            if (config.isConfigured()) {
                Console.log(LOG_PREFIX + "Passkey gateway enabled (rpId=" + config.getRpId()
                            + ", " + config.getFrontofficeOriginCount() + " front-office + "
                            + config.getBackofficeOriginCount() + " back-office origin(s))");
                if (config.getBackofficeOriginCount() == 0)
                    // Loud: with no back-office origins every BO passkey login fails with the
                    // generic error, which reads as broken passkeys rather than missing config
                    Console.log(LOG_PREFIX + "⚠️ WEBAUTHN_BACKOFFICE_ORIGINS is empty — back-office passkey login will be refused");
            } else
                // Loud, because the consequence is silent: the login button in the apps would just
                // return errors. Unset WEBAUTHN_* means "this environment has no passkeys", on purpose.
                Console.log(LOG_PREFIX + "Passkey gateway disabled — WEBAUTHN_RP_ID / WEBAUTHN_ALLOWED_ORIGINS not set");
        });
    }

    // ===== authenticate path (anonymous ceremonies) ==============================================

    @Override
    public boolean acceptsUserCredentials(Object userCredentials) {
        return userCredentials instanceof StartPasskeyAssertionCredentials
               || userCredentials instanceof AuthenticateWithPasskeyCredentials;
    }

    @Override
    public Future<?> authenticate(Object credentials) {
        if (!config.isConfigured())
            return notConfiguredFailure();
        if (credentials instanceof StartPasskeyAssertionCredentials)
            return startPasskeyAssertion();
        if (credentials instanceof AuthenticateWithPasskeyCredentials cred)
            return authenticateWithPasskey(cred);
        return Future.failedFuture("[%s] requires a passkey credentials argument".formatted(getClass().getSimpleName()));
    }

    private Future<String> startPasskeyAssertion() {
        String runId = ThreadLocalStateHolder.getRunId();
        if (runId == null)
            return genericFailure();
        WebAuthnConfig cfg = config;
        WebAuthnChallengeStore.Pending pending = challengeStore.create(runId + ASSERTION_PURPOSE, null, null);
        if (pending == null) // store full (anonymous start-flood) — new ceremonies refused, pending ones untouched
            return genericFailure();
        AstObject options = AST.createObject();
        options.set("challenge", B64URL_ENCODER.encodeToString(pending.challenge()));
        options.set("rpId", cfg.getRpId());
        options.set("timeout", CEREMONY_TIMEOUT_MILLIS);
        options.set("userVerification", "required");
        // Empty on purpose: the discoverable-credential flow. Naming credentials here would require
        // naming an account first, which is exactly the enumeration surface we refuse to open.
        options.setArray("allowCredentials", AST.createArray());
        return Future.succeededFuture(Json.formatObject(options));
    }

    private Future<?> authenticateWithPasskey(AuthenticateWithPasskeyCredentials credentials) {
        // Capturing the required client state info from thread local (before it will be wiped out by the async call)
        String runId = ThreadLocalStateHolder.getRunId();
        boolean claimedBackoffice = ThreadLocalStateHolder.isBackoffice();
        WebAuthnConfig cfg = config;
        if (runId == null)
            return genericFailure();
        WebAuthnChallengeStore.Pending pending = challengeStore.consume(runId + ASSERTION_PURPOSE);
        if (pending == null)
            return genericFailure();
        byte[] credentialId, authenticatorData, clientDataJSON, signature, userHandle;
        try {
            credentialId = B64URL_DECODER.decode(credentials.credentialId());
            authenticatorData = B64URL_DECODER.decode(credentials.authenticatorData());
            clientDataJSON = B64URL_DECODER.decode(credentials.clientDataJSON());
            signature = B64URL_DECODER.decode(credentials.signature());
            userHandle = Strings.isEmpty(credentials.userHandle()) ? null : B64URL_DECODER.decode(credentials.userHandle());
        } catch (RuntimeException e) {
            return genericFailure();
        }
        String credentialIdB64 = B64URL_ENCODER.encodeToString(credentialId);
        return credentialStore.findByCredentialId(credentialIdB64)
            .compose(row -> {
                if (row == null) {
                    // Unknown credential id: same generic error as every other failure — no oracle
                    Console.log(LOG_PREFIX + "Assertion with unknown credential id refused");
                    return genericFailure();
                }
                AuthenticationData authenticationData;
                try {
                    // Stored counter is fed as 0 so webauthn4j's own regression check (which only
                    // fires when both values are nonzero) stays out of the way of our
                    // warn-and-accept policy — see the class javadoc.
                    AttestedCredentialData attested = ATTESTED_CREDENTIAL_DATA_CONVERTER.convert(B64URL_DECODER.decode(row.publicKeyCose()));
                    CredentialRecord credentialRecord = new CredentialRecordImpl(
                        new NoneAttestationStatement(), null, null, null, 0L, attested, null, null, null, null);
                    AuthenticationRequest request = new AuthenticationRequest(
                        credentialId, userHandle, authenticatorData, clientDataJSON, signature);
                    AuthenticationParameters parameters = new AuthenticationParameters(
                        serverProperty(cfg, pending.challenge()), credentialRecord, null, true, true);
                    authenticationData = WEBAUTHN_MANAGER.verify(request, parameters);
                } catch (RuntimeException e) {
                    Console.log(LOG_PREFIX + "Assertion verification failed for credential row " + row.id() + ": " + e.getMessage());
                    return genericFailure();
                }
                // The authenticator's user handle must be the one registration stored on this row
                if (userHandle != null && !B64URL_ENCODER.encodeToString(userHandle).equals(row.userHandle())) {
                    Console.log(LOG_PREFIX + "Assertion user handle mismatch for credential row " + row.id());
                    return genericFailure();
                }
                // The back-office context comes from the VERIFIED origin (signature-covered), and must
                // agree with what the session claims — a mismatch is tampering or misconfiguration.
                Origin verifiedOrigin = authenticationData.getCollectedClientData() == null ? null
                    : authenticationData.getCollectedClientData().getOrigin();
                boolean originIsBackoffice = cfg.isBackofficeOrigin(verifiedOrigin);
                if (originIsBackoffice != claimedBackoffice) {
                    Console.log(LOG_PREFIX + "Verified origin " + verifiedOrigin + " does not match the session's claimed app context");
                    return genericFailure();
                }
                long newSignCount = authenticationData.getAuthenticatorData() == null ? 0
                    : authenticationData.getAuthenticatorData().getSignCount();
                return LoginPersonResolver.loadLiveLoginPersonForAccount(row.accountId(), originIsBackoffice, dataSourceModel)
                    .compose(userPerson -> {
                        if (userPerson == null)
                            return genericFailure();
                        if (newSignCount != 0 && row.signCount() != 0 && newSignCount <= row.signCount())
                            // Warn-and-accept — see the class javadoc for why this is not a hard fail
                            Console.log(LOG_PREFIX + "Sign count regression on credential row " + row.id()
                                        + " (stored=" + row.signCount() + ", received=" + newSignCount + ") — accepted, monitor if repeated");
                        // Usage stamp is fire-and-forget: a metrics write must never fail a login
                        credentialStore.updateUsage(row.id(), newSignCount)
                            .onFailure(e -> Console.log(LOG_PREFIX + "Usage update failed for credential row " + row.id() + ": " + e.getMessage()));
                        ModalityUserPrincipal principal = new ModalityUserPrincipal(userPerson.getPrimaryKey(), row.accountId());
                        return PushServerService.pushState(AuthenticatedState.createFor(principal), runId);
                    });
            });
    }

    // ===== updateCredentials path (logged-in registration + management) ==========================

    @Override
    public boolean acceptsUpdateCredentialsArgument(Object updateCredentialsArgument) {
        return updateCredentialsArgument instanceof StartPasskeyRegistrationCredentials
               || updateCredentialsArgument instanceof FinalisePasskeyRegistrationCredentials
               || updateCredentialsArgument instanceof ListPasskeysCredentials
               || updateCredentialsArgument instanceof RemovePasskeyCredentials
               || updateCredentialsArgument instanceof RenamePasskeyCredentials;
    }

    @Override
    public Future<?> updateCredentials(Object updateCredentialsArgument) {
        if (!acceptsUpdateCredentialsArgument(updateCredentialsArgument))
            return Future.failedFuture(getClass().getSimpleName() + ".updateCredentials() requires a passkey credentials argument");
        if (!config.isConfigured())
            return notConfiguredFailure();
        // Registration and management require a real logged-in account. A support view is a member
        // of staff looking at a customer's account: letting one plant its OWN authenticator there —
        // or list/remove the customer's — is exactly the transferable-credential hole the support
        // view mechanism exists to close, so the refusal is blanket (password gateway rationale).
        // Guests carry a different principal type and are refused by the same check.
        Object userId = ThreadLocalStateHolder.getUserId();
        if (!(userId instanceof ModalityUserPrincipal principal) || principal.isSupportView())
            return managementFailure();
        if (updateCredentialsArgument instanceof StartPasskeyRegistrationCredentials)
            return startPasskeyRegistration(principal);
        if (updateCredentialsArgument instanceof FinalisePasskeyRegistrationCredentials cred)
            return finalisePasskeyRegistration(principal, cred);
        if (updateCredentialsArgument instanceof ListPasskeysCredentials)
            return listPasskeysJson(accountIdOf(principal));
        if (updateCredentialsArgument instanceof RemovePasskeyCredentials cred)
            return removePasskey(principal, cred);
        if (updateCredentialsArgument instanceof RenamePasskeyCredentials cred)
            return renamePasskey(principal, cred);
        // Unreachable while acceptsUpdateCredentialsArgument and this chain list the same types —
        // this arm is what keeps a future sixth type from becoming a ClassCastException
        return managementFailure();
    }

    private Future<String> startPasskeyRegistration(ModalityUserPrincipal principal) {
        // Capturing the required client state info from thread local (before it will be wiped out by the async call)
        String runId = ThreadLocalStateHolder.getRunId();
        boolean isBackoffice = ThreadLocalStateHolder.isBackoffice();
        WebAuthnConfig cfg = config;
        if (runId == null)
            return registrationFailure();
        Object accountId = accountIdOf(principal);
        // The two reads are independent (both key on ids already at hand) — run them in parallel.
        // The person query carries the same fences as getUserClaims: the session's person must
        // still exist on that account.
        return Future.all(
            EntityStore.create(dataSourceModel)
                .<Person>executeQuery("select frontendAccount.username from Person where id=$1 and frontendAccount.(id=$2 and !disabled and ($3=false or backoffice))",
                    principal.getUserPersonId(), accountId, isBackoffice),
            credentialStore.findByAccount(accountId)
        ).compose(compositeFuture -> {
            List<Person> persons = compositeFuture.resultAt(0);
            List<WebAuthnCredentialStore.CredentialSummary> existingCredentials = compositeFuture.resultAt(1);
            if (persons.size() != 1)
                return registrationFailure();
            String username = persons.get(0).evaluate("frontendAccount.username");
            // WebAuthn wants ONE user handle per account: reuse the one the account's other
            // passkeys carry, mint 32 random bytes for a first registration (random, never
            // identity-derived — the spec forbids PII in it)
            String userHandleB64 = existingCredentials.stream()
                .map(WebAuthnCredentialStore.CredentialSummary::userHandle)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseGet(() -> {
                    byte[] handle = new byte[USER_HANDLE_BYTES];
                    SECURE_RANDOM.nextBytes(handle);
                    return B64URL_ENCODER.encodeToString(handle);
                });
            WebAuthnChallengeStore.Pending pending =
                challengeStore.create(runId + REGISTRATION_PURPOSE, accountId, userHandleB64);
            if (pending == null) // store full — see WebAuthnChallengeStore's overload policy
                return registrationFailure();
            return Future.succeededFuture(buildCreationOptionsJson(cfg, username, userHandleB64, pending.challenge(), existingCredentials));
        });
    }

    private String buildCreationOptionsJson(WebAuthnConfig cfg, String username, String userHandleB64,
                                            byte[] challenge, List<WebAuthnCredentialStore.CredentialSummary> existingCredentials) {
        AstObject options = AST.createObject();
        AstObject rp = AST.createObject();
        rp.set("id", cfg.getRpId());
        rp.set("name", cfg.getRpName());
        options.setObject("rp", rp);
        AstObject user = AST.createObject();
        user.set("id", userHandleB64);
        user.set("name", username);
        user.set("displayName", username);
        options.setObject("user", user);
        options.set("challenge", B64URL_ENCODER.encodeToString(challenge));
        options.set("timeout", CEREMONY_TIMEOUT_MILLIS);
        AstArray pubKeyCredParams = AST.createArray();
        for (PublicKeyCredentialParameters params : PUB_KEY_CRED_PARAMS) {
            AstObject param = AST.createObject();
            param.set("type", "public-key");
            param.set("alg", (int) params.getAlg().getValue());
            pubKeyCredParams.push(param);
        }
        options.setArray("pubKeyCredParams", pubKeyCredParams);
        // Excluding the account's existing credentials makes a double registration of the same
        // authenticator fail in the browser dialog instead of at the unique index
        AstArray excludeCredentials = AST.createArray();
        for (WebAuthnCredentialStore.CredentialSummary existing : existingCredentials) {
            AstObject descriptor = AST.createObject();
            descriptor.set("type", "public-key");
            descriptor.set("id", existing.credentialId());
            excludeCredentials.push(descriptor);
        }
        options.setArray("excludeCredentials", excludeCredentials);
        AstObject authenticatorSelection = AST.createObject();
        // residentKey=required: only discoverable credentials can serve the username-less login
        // flow; userVerification=required: the passkey alone signs the user in, so it must be
        // possession AND presence-of-the-person (PIN/biometric), not possession alone
        authenticatorSelection.set("residentKey", "required");
        authenticatorSelection.set("requireResidentKey", Boolean.TRUE);
        authenticatorSelection.set("userVerification", "required");
        options.setObject("authenticatorSelection", authenticatorSelection);
        options.set("attestation", "none");
        return Json.formatObject(options);
    }

    private Future<String> finalisePasskeyRegistration(ModalityUserPrincipal principal, FinalisePasskeyRegistrationCredentials credentials) {
        String runId = ThreadLocalStateHolder.getRunId();
        WebAuthnConfig cfg = config;
        if (runId == null)
            return registrationFailure();
        WebAuthnChallengeStore.Pending pending = challengeStore.consume(runId + REGISTRATION_PURPOSE);
        // The pending ceremony must exist AND have been started by this same account — a challenge
        // minted for one session can never finalise onto another's account
        if (pending == null || !Objects.equals(pending.accountId(), accountIdOf(principal)))
            return registrationFailure();
        RegistrationData registrationData;
        try {
            RegistrationRequest request = new RegistrationRequest(
                B64URL_DECODER.decode(credentials.attestationObject()),
                B64URL_DECODER.decode(credentials.clientDataJSON()),
                parseTransports(credentials.transports()));
            RegistrationParameters parameters = new RegistrationParameters(
                serverProperty(cfg, pending.challenge()), PUB_KEY_CRED_PARAMS, true, true);
            registrationData = WEBAUTHN_MANAGER.verify(request, parameters);
        } catch (RuntimeException e) {
            Console.log(LOG_PREFIX + "Registration verification failed: " + e.getMessage());
            return registrationFailure();
        }
        AttestedCredentialData attested = registrationData.getAttestationObject() == null ? null
            : registrationData.getAttestationObject().getAuthenticatorData().getAttestedCredentialData();
        if (attested == null)
            return registrationFailure();
        String credentialIdB64 = B64URL_ENCODER.encodeToString(attested.getCredentialId());
        // The stored blob is the full attested credential data (aaguid + id + COSE key): it
        // round-trips losslessly into the CredentialRecord the assertion verification needs
        String publicKeyCoseB64 = B64URL_ENCODER.encodeToString(ATTESTED_CREDENTIAL_DATA_CONVERTER.convert(attested));
        long signCount = registrationData.getAttestationObject().getAuthenticatorData().getSignCount();
        String aaguid = String.valueOf(attested.getAaguid());
        Object accountId = accountIdOf(principal);
        return credentialStore.insert(accountId, credentialIdB64, publicKeyCoseB64, signCount,
                pending.userHandleB64(), sanitizeTransports(credentials.transports()), aaguid, sanitizeLabel(credentials.label()))
            .recover(e -> {
                // Most likely the unique credential_id index (credential already registered) —
                // same generic answer either way, details go to the log only
                Console.log(LOG_PREFIX + "Credential insert failed: " + e.getMessage());
                return registrationFailure();
            })
            .compose(ignored -> listPasskeysJson(accountId));
    }

    /** The management list, also returned by a successful registration so the client refreshes in one call. */
    private Future<String> listPasskeysJson(Object accountId) {
        return credentialStore.findByAccount(accountId).map(credentials -> {
            AstArray array = AST.createArray();
            for (WebAuthnCredentialStore.CredentialSummary summary : credentials) {
                AstObject entry = AST.createObject();
                entry.set("id", summary.id());
                if (summary.label() != null)
                    entry.set("label", summary.label());
                if (summary.aaguid() != null)
                    entry.set("aaguid", summary.aaguid());
                if (summary.transports() != null)
                    entry.set("transports", summary.transports());
                if (summary.createdAt() != null)
                    entry.set("createdAt", summary.createdAt().toString());
                if (summary.lastUsedAt() != null)
                    entry.set("lastUsedAt", summary.lastUsedAt().toString());
                array.push(entry);
            }
            return Json.formatArray(array);
        });
    }

    private Future<?> removePasskey(ModalityUserPrincipal principal, RemovePasskeyCredentials credentials) {
        Long passkeyId = Numbers.toLong(credentials.passkeyId());
        if (passkeyId == null)
            return managementFailure();
        // Ownership is in the DELETE's WHERE clause; a valid id belonging to another account
        // matches zero rows and gets the same generic error as a nonexistent one
        return credentialStore.deleteOwned(passkeyId, accountIdOf(principal))
            .compose(deleted -> Boolean.TRUE.equals(deleted) ? Future.succeededFuture() : managementFailure());
    }

    private Future<?> renamePasskey(ModalityUserPrincipal principal, RenamePasskeyCredentials credentials) {
        Long passkeyId = Numbers.toLong(credentials.passkeyId());
        String label = sanitizeLabel(credentials.label());
        if (passkeyId == null || label == null)
            return managementFailure();
        return credentialStore.renameOwned(passkeyId, accountIdOf(principal), label)
            .compose(renamed -> Boolean.TRUE.equals(renamed) ? Future.succeededFuture() : managementFailure());
    }

    // ===== the rest of the SPI (delegated elsewhere, magiclink pattern) ==========================

    @Override
    public boolean acceptsUserId() {
        return false; // verify/claims/logout stay with the password gateway
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
    public Future<Void> logout() {
        return LogoutPush.pushLogoutMessageToClient();
    }

    // ===== helpers ===============================================================================

    private static ServerProperty serverProperty(WebAuthnConfig cfg, byte[] challenge) {
        return new ServerProperty(cfg.getAllowedOrigins(), cfg.getRpId(), new DefaultChallenge(challenge));
    }

    private static <T> Future<T> genericFailure() {
        // One error for every login/management failure mode — no wrong-vs-unknown oracle
        return Future.failedFuture("[%s] Passkey sign-in failed".formatted(ModalityAuthenticationI18nKeys.AuthnPasskeyError));
    }

    private static <T> Future<T> registrationFailure() {
        return Future.failedFuture("[%s] The passkey could not be registered".formatted(ModalityAuthenticationI18nKeys.AuthnPasskeyRegistrationError));
    }

    private static <T> Future<T> notConfiguredFailure() {
        return Future.failedFuture("[%s] Passkey sign-in is not configured on this server".formatted(ModalityAuthenticationI18nKeys.AuthnPasskeyNotConfiguredError));
    }

    private static <T> Future<T> managementFailure() {
        // Management operations (list/rename/remove, and their principal guards) fail with their
        // own key — "sign-in failed" on a profile page where nobody is signing in was worse
        return Future.failedFuture("[%s] The passkey change could not be completed".formatted(ModalityAuthenticationI18nKeys.AuthnPasskeyManagementError));
    }

    /**
     * The principal's account id, normalised to Long for raw-SQL binding: the principal's own
     * javadoc warns that ids deserialized from the session token can come back as Byte/Short for
     * small values, which DQL coerces but the pg driver's raw Tuple binding refuses.
     */
    private static Object accountIdOf(ModalityUserPrincipal principal) {
        Long normalised = Numbers.toLong(principal.getUserAccountId());
        return normalised != null ? normalised : principal.getUserAccountId();
    }

    /** User-supplied display name: trimmed, capped, empty collapsed to null. */
    private static String sanitizeLabel(String label) {
        if (label == null)
            return null;
        String trimmed = label.trim();
        if (trimmed.isEmpty())
            return null;
        return trimmed.length() <= MAX_LABEL_LENGTH ? trimmed : trimmed.substring(0, MAX_LABEL_LENGTH);
    }

    /** Browser-reported transports ("internal,hybrid"): normalised for storage, capped to the column. */
    private static String sanitizeTransports(String transports) {
        Set<String> parsed = parseTransports(transports);
        if (parsed.isEmpty())
            return null;
        String joined = String.join(",", parsed);
        return joined.length() <= MAX_TRANSPORTS_LENGTH ? joined : joined.substring(0, MAX_TRANSPORTS_LENGTH);
    }

    private static Set<String> parseTransports(String transports) {
        Set<String> parsed = new LinkedHashSet<>();
        if (transports != null)
            for (String token : transports.split(",")) {
                // Transport names are a small known alphabet ("internal", "hybrid", "usb", "nfc",
                // "ble", ...) — anything else the browser sends is dropped rather than stored
                String trimmed = token.trim().toLowerCase();
                if (!trimmed.isEmpty() && trimmed.matches("[a-z-]{1,16}"))
                    parsed.add(trimmed);
            }
        return parsed;
    }
}
