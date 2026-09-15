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
import one.modality.base.shared.entities.FrontendAccount;
import one.modality.base.shared.entities.Person;
import one.modality.crm.server.authn.gateway.shared.LoginPersonResolver;
import one.modality.crm.server.authn.gateway.shared.PendingSecondFactor;
import one.modality.crm.server.authn.gateway.shared.PendingSecondFactorStore;
import one.modality.crm.server.authn.gateway.shared.SecondFactorAttemptLimiter;
import one.modality.crm.server.authn.gateway.shared.SecondFactorMethod;
import one.modality.crm.server.authn.gateway.shared.SuperAdminMembership;
import one.modality.crm.shared.services.authn.ApprovePasskeyCredentials;
import one.modality.crm.shared.services.authn.AuthenticateWithPasskeyCredentials;
import one.modality.crm.shared.services.authn.FinalisePasskeyRegistrationCredentials;
import one.modality.crm.shared.services.authn.ListAccountPasskeysCredentials;
import one.modality.crm.shared.services.authn.ListPasskeysCredentials;
import one.modality.crm.shared.services.authn.ListPendingPasskeysCredentials;
import one.modality.crm.shared.services.authn.ModalityAuthenticationI18nKeys;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;
import one.modality.crm.shared.services.authn.RejectPasskeyCredentials;
import one.modality.crm.shared.services.authn.RemovePasskeyCredentials;
import one.modality.crm.shared.services.authn.RenamePasskeyCredentials;
import one.modality.crm.shared.services.authn.RevokeApprovedPasskeyCredentials;
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
 * the back office.
 *
 * <p><b>Passkeys are a BACK-OFFICE feature (product decision, 2026-09-14).</b> The front office
 * keeps password, magic link and verification code, and shows no passkey UI at all. The rpId still
 * spans both origins and must stay that way — it is forever, and every passkey already registered
 * is bound to its current value — so the enforcement is the ORIGIN allowlist, not the rpId: with
 * {@code WEBAUTHN_FRONTOFFICE_ORIGINS} unset, a registration or an assertion signed by a
 * front-office origin fails webauthn4j's signed-origin check and is refused. That is a server-side
 * refusal, not a hidden button. The boot log states which of the two states the server is in.
 *
 * <p>Verification is delegated to webauthn4j (never hand-rolled): challenge (single-use, from
 * {@link WebAuthnChallengeStore}), origin ∈ configured allowlist, rpId hash, user presence + user
 * verification, and the assertion signature against the stored public key. The login's back-office
 * context is then derived from the BROWSER-VERIFIED clientDataJSON origin — strictly stronger than
 * the client-asserted state the password gateway has to rely on — and the same account fences are
 * applied (corporation, !disabled, backoffice flag for BO origins, owner-first person resolution
 * per the magic-link owner fix).
 *
 * <p>Super-administrator approval of a passkey for back-office use (migration V0089's status
 * column and queue) is a switch, {@link WebAuthnConfig#isBackofficeApprovalRequired()} from
 * {@code WEBAUTHN_BACKOFFICE_APPROVAL}. Off in phase 1: the account's {@code backoffice} flag
 * alone decides who enters, every passkey is usable at once and the queue lies dormant. On once
 * the passkey is the factor that matters (phase 2). The queue and its operations work either way;
 * the switch only changes the status a new passkey is stored with and whether PENDING blocks a
 * back-office login.
 *
 * <p><b>Two ways an assertion signs somebody in.</b> On its own it is the whole login — user
 * verification makes it possession and the person in one gesture. On a connection whose password
 * step is waiting ({@code PendingSecondFactorStore} holds an entry for this runId) the very same
 * assertion is instead the SECOND step of that login: it completes it, for the account both halves
 * agree on, and nothing else about it changes. {@link PasskeySecondFactorVerifier} is the other half
 * of that — it is what makes the password step ask for a passkey at all.
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
    // Written once by the config-loaded callback, read by every ceremony — volatile is the contract.
    // STATIC because it has a second reader that holds no gateway: PasskeySecondFactorVerifier is a
    // separate ServiceLoader instance and must answer from the SAME switch this gateway enforces,
    // never from a configuration read of its own. The value is global anyway — ConfigLoader hands
    // every instance of this class the same one — so sharing it adds no state, only an address.
    private static volatile WebAuthnConfig config = WebAuthnConfig.unconfigured();

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

    /**
     * The configuration this gateway loaded, for {@link PasskeySecondFactorVerifier} — which decides
     * "is this account enrolled in a passkey?" and must decide it from the same approval switch the
     * assertion path enforces. {@link WebAuthnConfig#unconfigured()} until {@link #boot()}'s callback
     * has run, which is the fail-closed reading: unconfigured means no assertion can succeed, and the
     * verifier says so loudly rather than reporting "no factor".
     */
    static WebAuthnConfig currentConfig() {
        return config;
    }

    @Override
    public void boot() {
        ConfigLoader.onConfigLoaded(CONFIG_PATH, loadedConfig -> {
            config = WebAuthnConfig.fromConfig(loadedConfig);
            if (config.isConfigured()) {
                Console.log(LOG_PREFIX + "Passkey gateway enabled (rpId=" + config.getRpId()
                            + ", " + config.getFrontofficeOriginCount() + " front-office + "
                            + config.getBackofficeOriginCount() + " back-office origin(s), approval="
                            + (config.isBackofficeApprovalRequired() ? "on" : "off — every passkey is usable at once") + ")");
                if (config.getBackofficeOriginCount() == 0)
                    // Loud: with no back-office origins every BO passkey login fails with the
                    // generic error, which reads as broken passkeys rather than missing config
                    Console.log(LOG_PREFIX + "⚠️ WEBAUTHN_BACKOFFICE_ORIGINS is empty — back-office passkey login will be refused");
                // Both front-office states are stated, because since 2026-09-14 the EMPTY one is
                // the policy rather than a mistake, and silence would leave the operator unable to
                // tell which of the two a given boot is in. Empty is not a warning: the origin
                // allowlist is the union of both lists (WebAuthnConfig.fromConfig), so with no
                // front-office origin a ceremony signed by one fails webauthn4j's signed-origin
                // check — registration included — which is exactly what enforces the decision.
                if (config.getFrontofficeOriginCount() == 0)
                    Console.log(LOG_PREFIX + "WEBAUTHN_FRONTOFFICE_ORIGINS is empty — front-office passkey sign-in AND enrolment are disabled (refused at the signed-origin check). This is the EXPECTED state: passkeys and the TOTP second factor are back-office only (product decision 2026-09-14)");
                else
                    Console.log(LOG_PREFIX + "⚠️ WEBAUTHN_FRONTOFFICE_ORIGINS is set — this server still ACCEPTS front-office passkey sign-in and enrolment, which the back-office-only policy of 2026-09-14 says it should not. The front office shows no passkey UI, so nothing reaches it today, but only clearing this variable enforces the decision server-side. Do NOT narrow WEBAUTHN_RP_ID instead: rpId is forever and every registered passkey is bound to its current value");
            } else
                // Loud, because the consequence is silent: the login button in the apps would just
                // return errors. Unset WEBAUTHN_* means "this environment has no passkeys", on purpose.
                Console.log(LOG_PREFIX + "Passkey gateway disabled — WEBAUTHN_RP_ID unset, both WEBAUTHN_FRONTOFFICE_ORIGINS and WEBAUTHN_BACKOFFICE_ORIGINS empty, or an origin malformed (check public-variables.properties)");
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

    /**
     * An assertion: either a login in its own right, or the SECOND step of one whose password has
     * already passed — the difference is whether {@link PendingSecondFactorStore} holds an entry for
     * this runId, and nothing else about the request.
     *
     * <p><b>Why the ordinary path is unchanged.</b> A passkey assertion carries user verification, so
     * it is possession AND the person in one gesture: an assertion that arrives with no pending entry
     * is already both factors and mints exactly as it always has. The step-up arm adds no strength to
     * it — it only settles WHICH account the resulting session is for when a password step is waiting,
     * and marks that step complete so no second credential can also claim it.
     *
     * <p><b>And a pending entry weakens nothing.</b> Every fence below runs in the order it always
     * did — the challenge, the signature, the user handle, the verified-origin cross-check,
     * {@code LoginPersonResolver}'s account fence, the REJECTED refusal and the approval gate — and a
     * fence that refuses leaves the entry in place (minus the attempt it charged), so its owner can
     * still finish with their other factor.
     */
    private Future<?> authenticateWithPasskey(AuthenticateWithPasskeyCredentials credentials) {
        // Capturing the required client state info from thread local (before it will be wiped out by the async call)
        String runId = ThreadLocalStateHolder.getRunId();
        boolean claimedBackoffice = ThreadLocalStateHolder.isBackoffice();
        WebAuthnConfig cfg = config;
        if (runId == null)
            return genericFailure();
        // THE ATTEMPT IS CHARGED FIRST, before a single byte is verified — the TOTP gateway's rule,
        // and it has to be this gateway's too or the five-attempt budget a password step opens would
        // be spendable for free through this door. recordAttempt is also the LOOKUP: the store has no
        // peek by design, so one call both finds the pending login and spends one of its attempts.
        //
        // Null therefore means three things at once — no entry for this runId, one that expired, or
        // one whose attempts are gone — and all three take the ordinary path below. That is safe
        // precisely because the ordinary path is a full login: the assertion still has to pass every
        // fence, and what it mints is a session for the account the KEY proves, never for the one the
        // spent entry named. A stale entry left behind is removed by the store's own sweep.
        PendingSecondFactor pendingSecondFactor = PendingSecondFactorStore.getInstance().recordAttempt(runId);
        if (pendingSecondFactor != null) {
            // THE FACTOR MUST BE ONE THIS LOGIN ADVERTISED — the mirror of the TOTP gateway's guard.
            // The password step asked every registered verifier what the account holds and stored the
            // answer; this gateway may satisfy the step only when its own method is in that list.
            // Without it, an account enrolled in TOTP alone would have its step completed by a passkey
            // nothing asked it for. The entry is NOT consumed here: this is a malformed second step,
            // not a failed one, and its owner must still be able to answer with the factor that WAS
            // advertised. The attempt above is spent either way.
            List<String> advertisedMethods = pendingSecondFactor.methods();
            if (advertisedMethods == null || !advertisedMethods.contains(SecondFactorMethod.PASSKEY)) {
                Console.log(LOG_PREFIX + "Second-step assertion refused for account " + pendingSecondFactor.accountId()
                            + ": this login advertised [" + (advertisedMethods == null ? "" : String.join(",", advertisedMethods))
                            + "] and " + SecondFactorMethod.PASSKEY + " was attempted");
                return genericFailure();
            }
        }
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
                } catch (RuntimeException | LinkageError e) {
                    // LinkageError for the same reason as the registration site above: this is the
                    // same webauthn4j/Jackson code path, so a classpath mismatch breaks passkey
                    // SIGN-IN identically, and must not escape as a raw JVM message either.
                    Console.log(LOG_PREFIX + "Assertion verification failed for credential row " + row.id()
                                + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
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
                        // Back-office trust (docs/security/backoffice-second-factor.md, decisions 2
                        // and 4). WHO may enter the back office was settled by the account fence
                        // above (the backoffice flag, on the verified origin); this is whether THIS
                        // credential is trusted for it, behind the approval switch. With the switch
                        // on, a passkey enrolled behind a weak password must not be a free upgrade to
                        // a strong credential, so back-office sign-in needs a super administrator's
                        // approval of the credential; front-office sign-in never does (it grants
                        // nothing the password did not already). With the switch off (phase 1) a
                        // PENDING row — e.g. one enrolled before the switch existed — is as usable as
                        // an APPROVED one. A rejected passkey signs in nowhere, switch or no switch:
                        // a rejection is a decision on record, not a queue state. Checked after the
                        // signature AND after the account fence: only a caller who holds the key
                        // learns anything, what they learn is their own status, and an account that
                        // can never enter the back office gets the same generic refusal as before
                        // rather than a promise that approval would change that.
                        if (WebAuthnCredentialStore.STATUS_REJECTED.equals(row.status())) {
                            Console.log(LOG_PREFIX + "Assertion with a rejected credential refused (row " + row.id() + ")");
                            return genericFailure();
                        }
                        // The status rule itself lives in the store, as ONE method, because
                        // PasskeySecondFactorVerifier has to answer "is this account enrolled?" by
                        // exactly the same rule: a verifier that said yes where this says no would
                        // ask an owner for a factor this line then refuses.
                        if (originIsBackoffice && !WebAuthnCredentialStore.opensBackofficeLogin(row.status(), cfg.isBackofficeApprovalRequired()))
                            return notApprovedFailure();
                        if (newSignCount != 0 && row.signCount() != 0 && newSignCount <= row.signCount())
                            // Warn-and-accept — see the class javadoc for why this is not a hard fail
                            Console.log(LOG_PREFIX + "Sign count regression on credential row " + row.id()
                                        + " (stored=" + row.signCount() + ", received=" + newSignCount + ") — accepted, monitor if repeated");
                        // Usage stamp is fire-and-forget: a metrics write must never fail a login
                        credentialStore.updateUsage(row.id(), newSignCount)
                            .onFailure(e -> Console.log(LOG_PREFIX + "Usage update failed for credential row " + row.id() + ": " + e.getMessage()));
                        ModalityUserPrincipal principal = new ModalityUserPrincipal(userPerson.getPrimaryKey(), row.accountId());
                        if (pendingSecondFactor != null)
                            // This assertion is the second step of a password login: it completes
                            // that one rather than opening one of its own.
                            return completeSecondFactorStep(runId, principal, row.accountId(), originIsBackoffice);
                        // The session TIER (which lifetime policy applies, and what auth_session records)
                        // is decided by the flag passed here, and it must be passed explicitly: by now
                        // the thread-local has been restored by the database round trips above, so
                        // reading it where the token is minted would answer "front office" for every
                        // login and hand every staff session the front office's year-long cap. The
                        // other gateways pass the client-asserted flag they captured at method entry;
                        // this one can do better — originIsBackoffice was proven by the signature over
                        // clientDataJSON, and the cross-check above already forced the two to agree.
                        return AuthenticatedState.createFor(principal, originIsBackoffice)
                            .compose(authenticatedState -> PushServerService.pushState(authenticatedState, runId));
                    });
            });
    }

    /**
     * Claims the pending login this assertion has just satisfied and mints from it — password plus
     * passkey, which would be {@code pwd,pk} once {@code $amr} exists (design milestone M1). TODAY
     * THE MINTED SESSION RECORDS NEITHER: {@code AuthenticatedState.createFor} takes a principal and
     * a back-office flag and no method argument, so what this produces is indistinguishable from a
     * passkey-only or a password-only session. The strength is in having required both, not in
     * anything the token says.
     *
     * <p>The consume is the gate, not a tidy-up ({@code ModalityTotpAuthenticationGateway} says the
     * same of its own): {@link PendingSecondFactorStore#consumeAfterSuccess} releases the entry to
     * exactly ONE caller, so one password plus one assertion is worth exactly one session however
     * many requests carry it, and the OTHER advertised factor — a TOTP code typed in the same
     * seconds — finds nothing left to complete and is refused cleanly.
     *
     * <p><b>And the entry released must be the same login the key proved.</b> {@code runId} is the
     * client's own string and {@code put} REPLACES on it, so a password step for a different account
     * can land on this runId while the fences above are in their database round trips. Minting
     * whatever the store holds at that instant would sign the caller into that other account on a
     * factor it never presented — the step inverted. So the account is compared, and a mismatch mints
     * nothing; the entry is spent all the same, because a released entry is single-use whatever the
     * caller then decides. Both logins restart from their password, which is the safe end for both.
     *
     * <p>The mismatch is not only a race, and the 🛑 in its log line should not send anybody hunting
     * for one: two people sharing a machine reach it with no concurrency at all — A types a password
     * and is asked for a factor, B picks their own passkey from the browser's account chooser (the
     * assertion is discoverable, so every passkey on the device is offered), and B's login is refused
     * once while A's pending step is spent. B succeeds on a retry and A types their password again.
     *
     * <p>The back-office flag comes from the ENTRY — the one the password step captured before its
     * first async hop — and is required to agree with the origin the signature proved. They can only
     * disagree if a client reused one runId across the two apps, since a runId is one running page;
     * refusing that is what keeps "mint with the entry's flag" from ever minting a back-office tier
     * for a session whose person fence was resolved for the front office.
     *
     * @param assertedAccountId the account the credential belongs to — proven by the signature
     * @param originIsBackoffice the app context proven by the verified origin, already cross-checked
     *                           against the connection's claim
     */
    private Future<Void> completeSecondFactorStep(String runId, ModalityUserPrincipal principal,
                                                  Object assertedAccountId, boolean originIsBackoffice) {
        PendingSecondFactor released = PendingSecondFactorStore.getInstance().consumeAfterSuccess(runId);
        if (released == null) {
            // Nothing left to mint FOR: another request already completed this login, the user
            // cancelled it, or it expired while the assertion was being verified.
            Console.log(LOG_PREFIX + "Verified assertion for account " + assertedAccountId
                        + " arrived with no pending login left (already completed, cancelled or expired) — not minting");
            return genericFailure();
        }
        Object releasedAccountId = WebAuthnCredentialStore.normaliseId(released.accountId());
        if (!Objects.equals(releasedAccountId, WebAuthnCredentialStore.normaliseId(assertedAccountId))) {
            // Account ids only — never a username, never a credential id.
            Console.log(LOG_PREFIX + "🛑 The pending login on this runId is for another account (asserted "
                        + assertedAccountId + ", found " + releasedAccountId + ") — not minting");
            return genericFailure();
        }
        // ...and the same PERSON. The account is what the key proves, but an account can hold
        // several persons, and the two halves of this login resolved one independently: the password
        // gateway's own login query named `released.personId()`, and `LoginPersonResolver` named the
        // one in `principal` a moment ago. Both end in `order by owner desc, id limit 1` over the
        // same fences, so today they always agree — which is exactly why a disagreement means one of
        // those queries has drifted from the other, or the account's person rows changed under the
        // login, and neither is a thing to mint through. The ASSERTION's person is the one kept when
        // they do agree: it was resolved after the signature, against the live account, so it is the
        // fresher of the two fences rather than one up to five minutes old.
        if (!Objects.equals(WebAuthnCredentialStore.normaliseId(released.personId()),
                            WebAuthnCredentialStore.normaliseId(principal.getUserPersonId()))) {
            Console.log(LOG_PREFIX + "🛑 The pending login on this runId resolved a different person (password step "
                        + released.personId() + ", assertion " + principal.getUserPersonId() + ", account "
                        + releasedAccountId + ") — not minting");
            return genericFailure();
        }
        if (released.backoffice() != originIsBackoffice) {
            Console.log(LOG_PREFIX + "🛑 The pending login on this runId was taken in the "
                        + (released.backoffice() ? "back" : "front") + " office and the assertion was signed by a "
                        + (originIsBackoffice ? "back" : "front") + "-office origin (account " + releasedAccountId
                        + ") — not minting");
            return genericFailure();
        }
        // A factor was ACCEPTED, so the account's failure budget is forgiven — the limiter's own
        // contract ("a bad day costs nothing later"), and what ModalityTotpAuthenticationGateway
        // does on its own success. Without this, somebody who fumbles three codes and then signs in
        // with their passkey carries those three failures toward a lockout they did nothing to earn.
        // Safe from here because reaching this line took a signature over a server-issued single-use
        // challenge for a credential of this very account — a stronger proof than the codes the
        // budget counts, and one a guesser of codes cannot produce. Keyed by the normalised id, which
        // is what the TOTP gateway counts under, so the two spell the same account the same way.
        SecondFactorAttemptLimiter.clear(releasedAccountId);
        return AuthenticatedState.createFor(principal, released.backoffice())
            .compose(authenticatedState -> PushServerService.pushState(authenticatedState, runId));
    }

    // ===== updateCredentials path (logged-in registration + management) ==========================

    @Override
    public boolean acceptsUpdateCredentialsArgument(Object updateCredentialsArgument) {
        return updateCredentialsArgument instanceof StartPasskeyRegistrationCredentials
               || updateCredentialsArgument instanceof FinalisePasskeyRegistrationCredentials
               || updateCredentialsArgument instanceof ListPasskeysCredentials
               || updateCredentialsArgument instanceof RemovePasskeyCredentials
               || updateCredentialsArgument instanceof RenamePasskeyCredentials
               || isSuperAdminOperation(updateCredentialsArgument);
    }

    /**
     * The operations a super administrator sends about SOMEBODY ELSE's credentials — the approval
     * queue, its two decisions, the withdrawal, and the per-account listing the rescue screen reads.
     *
     * <p>They are pure database decisions: none of them verifies a signature, so none of them needs
     * an rpId or an origin, which is why {@link #updateCredentials} lets them through on an
     * unconfigured gateway. Membership is still re-checked on every one of them.
     */
    private static boolean isSuperAdminOperation(Object updateCredentialsArgument) {
        return updateCredentialsArgument instanceof ListPendingPasskeysCredentials
               || updateCredentialsArgument instanceof ApprovePasskeyCredentials
               || updateCredentialsArgument instanceof RejectPasskeyCredentials
               || updateCredentialsArgument instanceof RevokeApprovedPasskeyCredentials
               || updateCredentialsArgument instanceof ListAccountPasskeysCredentials;
    }

    @Override
    public Future<?> updateCredentials(Object updateCredentialsArgument) {
        if (!acceptsUpdateCredentialsArgument(updateCredentialsArgument))
            return Future.failedFuture(getClass().getSimpleName() + ".updateCredentials() requires a passkey credentials argument");
        // A CEREMONY needs the relying-party identity; a database decision does not. The
        // super-administrator operations are decisions — they read and write the credential table
        // and verify no signature — so they are allowed through on an unconfigured gateway, which is
        // exactly when they are needed: PasskeySecondFactorVerifier answers ENROLLED for an account
        // holding a usable row on a gateway that cannot assert anything, so that account's
        // back-office login is refused and the only way back in is to withdraw those rows. Refusing
        // the withdrawal here as well would turn one missing variable into a lockout with no exit.
        if (!config.isConfigured() && !isSuperAdminOperation(updateCredentialsArgument))
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
        // Approval and revocation operations: re-checked as super administrator on EVERY call,
        // never trusted from the client's grant push, and never delegable through operation codes.
        // The approver's own passkeys are neither listed, decidable nor revocable (store WHERE
        // clauses).
        if (updateCredentialsArgument instanceof ListPendingPasskeysCredentials)
            return requireSuperAdmin(principal).compose(ignored -> listPendingPasskeysJson(principal));
        if (updateCredentialsArgument instanceof ApprovePasskeyCredentials cred)
            return requireSuperAdmin(principal).compose(ignored ->
                decidePasskey(principal, cred.passkeyId(), WebAuthnCredentialStore.STATUS_APPROVED));
        if (updateCredentialsArgument instanceof RejectPasskeyCredentials cred)
            return requireSuperAdmin(principal).compose(ignored ->
                decidePasskey(principal, cred.passkeyId(), WebAuthnCredentialStore.STATUS_REJECTED));
        if (updateCredentialsArgument instanceof RevokeApprovedPasskeyCredentials cred)
            return requireSuperAdmin(principal).compose(ignored ->
                revokePasskey(principal, cred.passkeyId(), cred.note()));
        if (updateCredentialsArgument instanceof ListAccountPasskeysCredentials cred)
            return requireSuperAdmin(principal).compose(ignored ->
                listAccountPasskeysJson(principal, cred.accountId()));
        // Unreachable while acceptsUpdateCredentialsArgument and this chain list the same types —
        // this arm is what keeps a future eleventh type from becoming a ClassCastException
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
        } catch (RuntimeException | LinkageError e) {
            // LinkageError as well as RuntimeException, because a Jackson/webauthn4j version
            // mismatch surfaces as NoSuchMethodError - an Error, NOT a RuntimeException. Uncaught,
            // it escaped this method altogether: nothing was written under [webauthn], so the log
            // said the ceremony had never happened, and the raw JVM message (an internal class
            // signature) was handed straight to the browser. Production did exactly that on every
            // passkey registration until the CBOR pin came back - see webfx.xml. Catching it here
            // keeps a classpath fault loud in the log and generic on the wire.
            Console.log(LOG_PREFIX + "Registration verification failed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
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
        // The initial status is decided here, from the approval switch as it stands when THIS row
        // is enrolled: PENDING for the super-administrator queue while the gate is on, APPROVED
        // otherwise. Turning the switch on later gates new enrolments, not rows already APPROVED.
        String status = cfg.isBackofficeApprovalRequired()
            ? WebAuthnCredentialStore.STATUS_PENDING : WebAuthnCredentialStore.STATUS_APPROVED;
        return credentialStore.insert(accountId, credentialIdB64, publicKeyCoseB64, signCount,
                pending.userHandleB64(), sanitizeTransports(credentials.transports()), aaguid, sanitizeLabel(credentials.label()), status)
            .recover(e -> {
                // Most likely the unique credential_id index (credential already registered) —
                // same generic answer either way, details go to the log only
                Console.log(LOG_PREFIX + "Credential insert failed: " + e.getMessage());
                return registrationFailure();
            })
            .compose(ignored -> listPasskeysJson(accountId));
    }

    /**
     * The management list, also returned by a successful registration so the client refreshes in
     * one call. Shaped {@code {"approvalRequired": bool, "passkeys": [...]}}: the flag is true
     * only when the approval switch is on AND the account can enter the back office — the one
     * case in which a status means anything to the owner — so the client shows status badges and
     * the "awaiting approval" hint when it is true and nothing of the sort otherwise (members
     * never, staff only while the gate is on).
     */
    private Future<String> listPasskeysJson(Object accountId) {
        WebAuthnConfig cfg = config;
        // The account's backoffice flag only feeds approvalRequired, so with the switch off the
        // query is not run at all rather than run and ignored
        Future<Boolean> backofficeAccountFuture = !cfg.isBackofficeApprovalRequired() ? Future.succeededFuture(false)
            : EntityStore.create(dataSourceModel)
                .<FrontendAccount>executeQuery("select backoffice from FrontendAccount where id=$1", accountId)
                .map(accounts -> !accounts.isEmpty() && Boolean.TRUE.equals(accounts.get(0).isBackoffice()));
        return Future.all(
            backofficeAccountFuture,
            credentialStore.findByAccount(accountId)
        ).map(compositeFuture -> {
            boolean approvalRequired = Boolean.TRUE.equals(compositeFuture.resultAt(0));
            List<WebAuthnCredentialStore.CredentialSummary> credentials = compositeFuture.resultAt(1);
            AstObject response = AST.createObject();
            response.set("approvalRequired", approvalRequired);
            response.setArray("passkeys", passkeysJson(credentials));
            return Json.formatObject(response);
        });
    }

    /**
     * The {@code passkeys} array, in the ONE per-ROW shape both listings emit — the owner's own and
     * the super administrator's — so a single client row parser serves both and the two cannot drift
     * apart into two subtly different rows. The ENVELOPES still differ: the owner's listing wraps
     * this in {@code {approvalRequired, passkeys}} and the administrator's in {@code {passkeys}},
     * because the approval switch is something only the owner's UI has anything to say about.
     *
     * <p>{@code id} and {@code status} are always present; every other field is omitted when the row
     * has no value for it, rather than sent as null. Never the credential id, never the public key:
     * what is here is what a person needs to recognise a device, and nothing that authenticates one.
     */
    private static AstArray passkeysJson(List<WebAuthnCredentialStore.CredentialSummary> credentials) {
        AstArray array = AST.createArray();
        for (WebAuthnCredentialStore.CredentialSummary summary : credentials) {
            AstObject entry = AST.createObject();
            entry.set("id", summary.id());
            entry.set("status", summary.status());
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
        return array;
    }

    // ===== super-administrator approval queue ====================================================

    /** Fails with the admin-not-permitted key unless the principal's person is a super administrator. */
    private Future<Void> requireSuperAdmin(ModalityUserPrincipal principal) {
        return SuperAdminMembership.isSuperAdmin(principal, dataSourceModel)
            .compose(isSuperAdmin -> {
                if (!Boolean.TRUE.equals(isSuperAdmin)) {
                    // Person id, not email: this lands in a log that ships to aggregation
                    Console.log(LOG_PREFIX + "Super-administrator passkey operation refused: person "
                                + principal.getUserPersonId() + " is not a super administrator");
                    return adminNotPermittedFailure();
                }
                return Future.succeededFuture();
            });
    }

    /**
     * The approval queue as JSON, shaped {@code {"approvalEnabled": bool, "pending": [...]}}: the
     * pending credentials with the account (username) each belongs to, plus the switch state so
     * the queue page can say the gate is off rather than show an empty queue. The queue is served
     * either way, but only rows that ARE pending can be decided — those enrolled while the switch
     * was on, or before it existed. On them a decision still lands with the switch off: a rejection
     * refuses that passkey everywhere at once, an approval clears it for the day the switch is
     * turned on. Rows enrolled while the switch is off are APPROVED from birth and never queue —
     * they are reached by {@link #revokePasskey} instead, which is the administrator
     * revocation this queue used to lack.
     */
    private Future<String> listPendingPasskeysJson(ModalityUserPrincipal approver) {
        WebAuthnConfig cfg = config;
        return credentialStore.findPending(accountIdOf(approver)).map(pending -> {
            AstArray array = AST.createArray();
            for (WebAuthnCredentialStore.PendingSummary summary : pending) {
                AstObject entry = AST.createObject();
                entry.set("id", summary.id());
                if (summary.username() != null)
                    entry.set("username", summary.username());
                if (summary.label() != null)
                    entry.set("label", summary.label());
                if (summary.aaguid() != null)
                    entry.set("aaguid", summary.aaguid());
                if (summary.transports() != null)
                    entry.set("transports", summary.transports());
                if (summary.createdAt() != null)
                    entry.set("createdAt", summary.createdAt().toString());
                array.push(entry);
            }
            AstObject response = AST.createObject();
            response.set("approvalEnabled", cfg.isBackofficeApprovalRequired());
            response.setArray("pending", array);
            return Json.formatObject(response);
        });
    }

    /**
     * Another account's passkeys, for the rescue screen: the read that tells a super administrator
     * which credentials a locked-out member of staff is still carrying, and therefore which rows to
     * withdraw before the account has no usable factor left.
     *
     * <p>The account id is the one the TOTP gateway's lookup returned, and membership has already
     * been re-checked on this very call ({@link #requireSuperAdmin}) — an ordinary account reaches
     * this method never, and gets the same generic management error whether the id exists or not.
     * Shaped {@code {"passkeys": [...]}} with the per-row shape of {@link #passkeysJson}, so the
     * client reuses the parser it already has for the owner's own listing.
     *
     * <p>The approver's OWN account is not excluded here, unlike in every decision below: looking is
     * not ruling, and a super administrator can already list their own passkeys from their profile.
     * The refusals stay where they belong — on the decisions, which still match no row of their own
     * account.
     *
     * <p>Nothing personal is logged: the row count and the account id, never a label (a person's
     * device name) and never a username.
     */
    private Future<String> listAccountPasskeysJson(ModalityUserPrincipal approver, Object accountIdArg) {
        Long accountId = Numbers.toLong(accountIdArg);
        if (accountId == null)
            return managementFailure();
        return credentialStore.findByAccount(accountId).map(credentials -> {
            Console.log(LOG_PREFIX + "Passkeys of account " + accountId + " listed by person "
                        + approver.getUserPersonId() + " (" + credentials.size() + " row(s))");
            AstObject response = AST.createObject();
            response.setArray("passkeys", passkeysJson(credentials));
            return Json.formatObject(response);
        });
    }

    /**
     * Records the approver's decision; a row that is no longer pending — or that belongs to the
     * approver's own account — yields the generic management error.
     */
    private Future<?> decidePasskey(ModalityUserPrincipal approver, Object passkeyIdArg, String newStatus) {
        Long passkeyId = Numbers.toLong(passkeyIdArg);
        if (passkeyId == null)
            return managementFailure();
        return credentialStore.decidePending(passkeyId, newStatus, Numbers.toLong(approver.getUserPersonId()), accountIdOf(approver))
            .compose(decided -> {
                if (!Boolean.TRUE.equals(decided))
                    return managementFailure();
                Console.log(LOG_PREFIX + "Passkey row " + passkeyId + " " + newStatus + " by person " + approver.getUserPersonId());
                return Future.succeededFuture();
            });
    }

    /**
     * Withdraws a passkey: the decision revisited, which approval alone left no way to do. Same
     * guards as {@link #decidePasskey} — super administrator re-checked on this call, never the
     * approver's own account — and the same generic management error for a row that does not exist,
     * is theirs, or is already REJECTED and so has nothing left to withdraw.
     *
     * <p><b>It withdraws a PENDING row as well as an APPROVED one</b> (the record keeps its wire
     * name, which is now narrower than what it does). The reason is
     * {@link WebAuthnCredentialStore#opensBackofficeLogin}: while
     * {@code WEBAUTHN_BACKOFFICE_APPROVAL} is off — the shipped default — a PENDING passkey signs
     * its owner into the back office exactly as an APPROVED one does, so an APPROVED-only
     * withdrawal could not clear every usable factor from an account, and clearing every usable
     * factor is precisely what rescuing a locked-out member of staff requires. The queue's own
     * {@link RejectPasskeyCredentials} still exists and is unchanged; this is the path that also
     * works when the account has since been disabled or demoted, which the queue's decision refuses.
     *
     * <p>The note is the approver's record of WHY, and it does not reach the log: a withdrawal is
     * written about a person ("shared their laptop with X", "left on 3 March"), so the log carries
     * only whether one was given. The row ids and the deciding person id are what an operator needs
     * to reconstruct the decision.
     */
    private Future<?> revokePasskey(ModalityUserPrincipal approver, Object passkeyIdArg, String note) {
        Long passkeyId = Numbers.toLong(passkeyIdArg);
        if (passkeyId == null)
            return managementFailure();
        boolean noteSupplied = !Strings.isEmpty(Strings.toSafeString(note).trim());
        return credentialStore.revoke(passkeyId, Numbers.toLong(approver.getUserPersonId()), accountIdOf(approver))
            .compose(revoked -> {
                if (!Boolean.TRUE.equals(revoked))
                    return managementFailure();
                Console.log(LOG_PREFIX + "Passkey row " + passkeyId + " revoked ("
                            + WebAuthnCredentialStore.STATUS_REJECTED + ") by person " + approver.getUserPersonId()
                            + (noteSupplied ? ", note supplied" : ", NO note supplied"));
                return Future.succeededFuture();
            });
    }

    private Future<?> removePasskey(ModalityUserPrincipal principal, RemovePasskeyCredentials credentials) {
        Long passkeyId = Numbers.toLong(credentials.passkeyId());
        if (passkeyId == null)
            return managementFailure();
        // Ownership is in the DELETE's WHERE clause; a valid id belonging to another account
        // matches zero rows and gets the same generic error as a nonexistent one — as does a
        // REJECTED row, which stays on record (the owner's UI offers no remove for it)
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

    private static <T> Future<T> notApprovedFailure() {
        // Only reachable with the approval switch on. Specific on purpose: the caller has just
        // proven possession of the key, so telling them their own credential awaits approval
        // discloses nothing to anyone else
        return Future.failedFuture("[%s] This passkey has not been approved for back-office use yet".formatted(ModalityAuthenticationI18nKeys.AuthnPasskeyNotApprovedError));
    }

    private static <T> Future<T> adminNotPermittedFailure() {
        return Future.failedFuture("[%s] Only a super administrator can approve or reject passkeys".formatted(ModalityAuthenticationI18nKeys.AuthnPasskeyAdminNotPermittedError));
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
        return WebAuthnCredentialStore.normaliseId(principal.getUserAccountId());
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
