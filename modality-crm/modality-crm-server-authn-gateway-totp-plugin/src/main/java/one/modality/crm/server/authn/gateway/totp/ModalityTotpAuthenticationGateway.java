package one.modality.crm.server.authn.gateway.totp;

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
import one.modality.crm.server.authn.gateway.shared.PendingSecondFactor;
import one.modality.crm.server.authn.gateway.shared.PendingSecondFactorStore;
import one.modality.crm.server.authn.gateway.shared.SecondFactorAttemptLimiter;
import one.modality.crm.server.authn.gateway.shared.SecondFactorMethod;
import one.modality.crm.server.authn.gateway.shared.SuperAdminMembership;
import one.modality.crm.shared.services.authn.AuthenticateWithTotpBackupCodeCredentials;
import one.modality.crm.shared.services.authn.AuthenticateWithTotpCredentials;
import one.modality.crm.shared.services.authn.CancelSecondFactorCredentials;
import one.modality.crm.shared.services.authn.ConfirmTotpEnrolmentCredentials;
import one.modality.crm.shared.services.authn.ListSecondFactorsCredentials;
import one.modality.crm.shared.services.authn.ModalityAuthenticationI18nKeys;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;
import one.modality.crm.shared.services.authn.RegenerateTotpBackupCodesCredentials;
import one.modality.crm.shared.services.authn.RemoveTotpCredentials;
import one.modality.crm.shared.services.authn.ResetSecondFactorCredentials;
import one.modality.crm.shared.services.authn.StartTotpEnrolmentCredentials;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The TOTP second factor: the code step of a back-office login, and the self-service enrolment and
 * management that stand behind it.
 *
 * <p><b>This gateway never logs anybody in on its own.</b> Every login path here starts from a
 * pending entry that the password step left in {@link PendingSecondFactorStore} under this page's
 * {@code runId} — the password has already been proved. A code with no pending entry is refused
 * like any other failure, so nothing here is a way into an account without the password. And the
 * entry must have advertised {@link SecondFactorMethod#TOTP}: this gateway satisfies the step only
 * for logins that were asked for ITS factor, never for one enrolled in somebody else's.
 *
 * <p><b>Two generic errors on the login path, and neither names an account.</b> Wrong code, replayed
 * code, an account with no confirmed factor and a secret that will not decrypt answer
 * {@code AuthnSecondFactorCodeError}; a runId with no entry — absent, expired, or out of attempts —
 * answers {@code AuthnSecondFactorAttemptsExceededError}, because the store cannot tell those three
 * apart and "start again from the password" is the true instruction for all of them. An account
 * inside its {@link SecondFactorAttemptLimiter} lockout answers with the same key, where that
 * instruction is incomplete rather than untrue — starting again is exactly right, once the window
 * has passed. Telling any of them apart would disclose nothing anyway: the request names no account,
 * and a runId is the caller's own. The management path has its own generic {@code AuthnTotpEnrolmentError}. Reasons go to the
 * log, and the log carries row, person and account ids ONLY: never a code, never a secret, never a
 * username or an email.
 *
 * <p><b>Abuse controls.</b> The per-{@code runId} attempt is spent BEFORE anything is verified, so a
 * caller who floods still pays for every try; the per-account cap
 * ({@link SecondFactorAttemptLimiter}) spans runIds, so restarting from the password does not
 * refresh the budget; {@code last_used_step} refuses a step that was already accepted, which is
 * what closes the ±1 drift window against replay; and the pending entry is released to exactly one
 * caller ({@link PendingSecondFactorStore#consumeAfterSuccess}), so one password plus one correct
 * code is worth exactly one session however many requests carry it.
 *
 * <p><b>Inert without a key.</b> {@link TotpKeys} decides that. Enrolment is then refused, and
 * {@link TotpSecondFactorVerifier} reports accounts that hold a row as ENROLLED so their logins are
 * refused rather than downgraded. Backup codes keep working in that state — they are hashed, not
 * encrypted — which is deliberate: the people locked out by a lost key are exactly the ones holding
 * a sheet of codes.
 *
 * @author Claude Code
 */
public final class ModalityTotpAuthenticationGateway implements ServerAuthenticationGateway, HasDataSourceModel {

    private static final String CONFIG_PATH = "modality.crm.server.authn.totp";
    private static final String LOG_PREFIX = "[totp] ";
    /** What the authenticator app shows above the code, and the {@code otpauth://} issuer. */
    private static final String ISSUER = "KBS3";
    private static final int MAX_LABEL_LENGTH = 64;   // totp_credential.label
    private static final int MAX_NOTE_LENGTH = 256;   // second_factor_reset.note
    /** What a super-administrator reset may clear — {@code PASSKEY} belongs to the WebAuthn gateway. */
    private static final String WHAT_TOTP = "TOTP";
    private static final String WHAT_BACKUP_CODES = "BACKUP_CODES";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final DataSourceModel dataSourceModel;
    private final TotpCredentialStore credentialStore = new TotpCredentialStore();

    public ModalityTotpAuthenticationGateway() {
        this(DataSourceModelService.getDefaultDataSourceModel());
    }

    public ModalityTotpAuthenticationGateway(DataSourceModel dataSourceModel) {
        this.dataSourceModel = dataSourceModel;
    }

    @Override
    public DataSourceModel getDataSourceModel() {
        return dataSourceModel;
    }

    @Override
    public void boot() {
        ConfigLoader.onConfigLoaded(CONFIG_PATH, TotpKeys::initFromConfig);
    }

    // ===== authenticate path (the second step of a login whose password already passed) ==========

    @Override
    public boolean acceptsUserCredentials(Object userCredentials) {
        return userCredentials instanceof AuthenticateWithTotpCredentials
               || userCredentials instanceof AuthenticateWithTotpBackupCodeCredentials
               || userCredentials instanceof CancelSecondFactorCredentials;
    }

    @Override
    public Future<?> authenticate(Object credentials) {
        // Read at method entry, before the first async hop wipes the thread local. runId is the key
        // of the pending entry the password step left — per page, not per socket, so a reconnect
        // finishes the login and a reload simply starts it again.
        String runId = ThreadLocalStateHolder.getRunId();
        if (credentials instanceof CancelSecondFactorCredentials) {
            // The user backed out. Nothing to report: the entry either existed and is gone, or never did.
            if (runId != null)
                PendingSecondFactorStore.getInstance().drop(runId);
            return Future.succeededFuture();
        }
        if (runId == null)
            return codeFailure();
        // The attempt is spent FIRST, before a single byte is verified: a caller who hangs up
        // mid-verification, or who floods, still pays for the try. Null means there is no entry for
        // this runId, it has expired, or its five attempts are gone — one answer for all three, so
        // none of them confirms the others.
        //
        // The entry to verify against is the one recordAttempt HANDS BACK. The store keeps a spent
        // entry rather than deleting it as it charges the last attempt, so a CORRECT fifth code is
        // still completed ("five attempts means five") and the zero balance is what refuses a sixth.
        PendingSecondFactor entry = PendingSecondFactorStore.getInstance().recordAttempt(runId);
        if (entry == null)
            return attemptsExceededFailure();
        // The per-account cap is consulted BEFORE the code is looked at, which is the only place it
        // can bound anything: the runId budget is the caller's own to reset (new runId, password
        // step again, five more guesses), so without this a guesser who holds the password is
        // limited by the network rather than by us — and three chances in a million per try is a
        // back-office session within hours at that rate. Refused unlooked-at, for a correct code as
        // well as a wrong one; the attempt above is still charged, so this cannot be probed for free.
        // Normalised here and everywhere else this class touches the limiter, so the key it counts a
        // failure under is the key it is later asked about.
        Object cappedAccountId = TotpCredentialStore.normaliseId(entry.accountId());
        if (SecondFactorAttemptLimiter.isLockedOut(cappedAccountId)) {
            Console.log(LOG_PREFIX + "Attempt refused for account " + cappedAccountId
                        + ": too many recent failures on this account");
            return attemptsExceededFailure();
        }
        // THE FACTOR MUST BE ONE THIS LOGIN ADVERTISED. The password step asked every registered
        // verifier what the account holds and stored the answer in the entry; this gateway may only
        // satisfy the step when its own method is in that list. Inert today — TOTP is the only
        // registered verifier, so the list is ["totp"] or empty — and the whole of the cross-factor
        // guard the day a second one exists: without it, an account enrolled in that other factor
        // would be signed in by a TOTP row nothing asked it for, which is the step inverted.
        // A backup code is checked against "totp" rather than a code of its own: it is the TOTP
        // factor's recovery, so it is offered exactly when that factor is, and an account
        // advertising some other factor must not be able to fall back onto this one's sheet.
        // Not charged to the per-account budget — this is a malformed login, not a guess at a code,
        // and charging it would let one client lock an account out of signing in. The runId attempt
        // above is still spent.
        String attemptedMethod = credentials instanceof AuthenticateWithTotpBackupCodeCredentials
            ? SecondFactorMethod.TOTP_BACKUP : SecondFactorMethod.TOTP;
        List<String> advertisedMethods = entry.methods();
        if (advertisedMethods == null || !advertisedMethods.contains(SecondFactorMethod.TOTP)) {
            Console.log(LOG_PREFIX + "Attempt refused for account " + cappedAccountId + ": this login advertised ["
                        + (advertisedMethods == null ? "" : String.join(",", advertisedMethods))
                        + "] and " + attemptedMethod + " was attempted");
            return codeFailure();
        }
        if (credentials instanceof AuthenticateWithTotpCredentials cred)
            return authenticateWithTotp(runId, entry, cred.code());
        if (credentials instanceof AuthenticateWithTotpBackupCodeCredentials cred)
            return authenticateWithBackupCode(runId, entry, cred.code());
        return Future.failedFuture("[%s] requires a TOTP credentials argument".formatted(getClass().getSimpleName()));
    }

    private Future<?> authenticateWithTotp(String runId, PendingSecondFactor entry, String code) {
        Object accountId = TotpCredentialStore.normaliseId(entry.accountId());
        if (Strings.isEmpty(code))
            return failedAttempt(accountId);
        TotpSecretCipher cipher = TotpKeys.cipher();
        if (cipher == null) {
            // Loud, because nothing else will say why every code in the building stopped working.
            // Not an attempt against the account, so the per-account budget is left alone.
            Console.log(LOG_PREFIX + "🛑 Code refused for account " + accountId + ": no encryption key configured");
            return codeFailure();
        }
        return credentialStore.findByAccount(accountId).compose(row -> {
            if (row == null || !row.isConfirmed()) {
                Console.log(LOG_PREFIX + "Code refused for account " + accountId + ": no confirmed TOTP factor");
                return failedAttempt(accountId);
            }
            byte[] secret = cipher.decrypt(row.secretEnc(), accountId);
            if (secret == null) {
                Console.log(LOG_PREFIX + "🛑 TOTP row " + row.id() + " will not decrypt (written by key " + row.keyId()
                            + ") — wrong key, or a row moved between accounts");
                // NOT charged to the account, for the same reason as the missing key above: this is
                // the server's fault, not a guess, and the per-account budget now LOCKS the account
                // out for fifteen minutes when it runs out. Charging here would mean a key rotation
                // that left rows behind also shut the owner out of the backup codes — the one path
                // that is supposed to survive exactly this. The runId attempt is still spent.
                return codeFailure();
            }
            long step = Totp.matchingStep(secret, code, System.currentTimeMillis() / 1000);
            if (step < 0)
                return failedAttempt(accountId);
            // The replay guard is part of verifying, not an afterthought: the ±1 drift window is
            // exactly the room a relay proxy has, and a step already accepted must never be a login.
            return credentialStore.recordUse(row.id(), step).compose(accepted -> {
                if (!Boolean.TRUE.equals(accepted)) {
                    Console.log(LOG_PREFIX + "Replayed step refused on TOTP row " + row.id());
                    return failedAttempt(accountId);
                }
                return completeLogin(runId, entry, accountId);
            });
        });
    }

    private Future<?> authenticateWithBackupCode(String runId, PendingSecondFactor entry, String code) {
        Object accountId = TotpCredentialStore.normaliseId(entry.accountId());
        if (Strings.isEmpty(code))
            return failedAttempt(accountId);
        // No key needed: codes are salted hashes, so the everyday recovery path survives a lost
        // encryption key — which is when it is needed most.
        return credentialStore.consumeUnused(accountId, code).compose(consumed -> {
            if (!Boolean.TRUE.equals(consumed))
                return failedAttempt(accountId);
            Console.log(LOG_PREFIX + "Backup code consumed for account " + accountId);
            // The code is spent at this point whether or not the mint below goes through: if the
            // pending login was consumed, dropped or expired while this ran, the sheet has lost a
            // code and the login is refused. That is the fail-closed direction and it costs one of
            // ten codes; the alternative — minting on a login the store no longer holds — is a
            // session nothing released.
            return completeLogin(runId, entry, accountId)
                // The identity has been pushed by this point, so the login has HAPPENED. A failure
                // of the count below must not turn it into a reported failure — the caller would
                // show a sign-in error to somebody who is signed in. Unknown drops the field.
                .compose(ignored -> credentialStore.countUnused(accountId)
                    .otherwise(e -> {
                        Console.log(LOG_PREFIX + "Backup code count failed after a successful login for account "
                                    + accountId + " (" + e.getMessage() + ")");
                        return -1;
                    }))
                .map(left -> {
                    // Tells the owner how many codes are left, so they can regenerate before
                    // running out.
                    AstObject response = AST.createObject();
                    if (left >= 0)
                        response.set("backupCodesLeft", left);
                    return Json.formatObject(response);
                });
        });
    }

    /**
     * Consumes the pending entry and mints the session — the only place in this plugin where an
     * identity comes into existence, and only ever with the account the PASSWORD step named.
     *
     * <p>The consume is the gate, not a tidy-up: the store hands the entry to exactly ONE caller, so
     * a correct code that arrives twice — a double-submit, a replayed request, the same still-valid
     * code inside one 30-second step — mints once and is refused the second time.
     *
     * <p><b>And the released entry must be the same login that was verified.</b> {@code runId} is the
     * client's own string and {@link PendingSecondFactorStore#put} REPLACES on it, so a password step
     * for a different account can land on this runId while the code above is in its database round
     * trips. Minting whatever the store holds at that instant would sign the caller in as that other
     * account on a factor it never presented — the whole point of the step, inverted — so the account
     * is compared and a mismatch mints nothing. What is then minted is {@code verified}: the login
     * whose factor actually passed, never the one that merely arrived last.
     *
     * @param verified  the pending login {@code recordAttempt} handed back and whose factor has just
     *                  been proved
     * @param accountId {@code verified}'s account, normalised — what the verification ran against
     */
    private Future<Void> completeLogin(String runId, PendingSecondFactor verified, Object accountId) {
        PendingSecondFactor released = PendingSecondFactorStore.getInstance().consumeAfterSuccess(runId);
        if (released == null) {
            // Nothing left to mint FOR: another request already completed this login, the user
            // cancelled it, or it expired while the code was being verified. Not counted against the
            // account — the code was right; it simply arrived second.
            Console.log(LOG_PREFIX + "Correct factor for account " + accountId
                        + " arrived with no pending login left (already completed, cancelled or expired) — not minting");
            return codeFailure();
        }
        Object releasedAccountId = TotpCredentialStore.normaliseId(released.accountId());
        if (!Objects.equals(releasedAccountId, accountId)) {
            // Account ids only. Both logins now restart from their password, which is the safe end
            // for both: nothing is minted for either.
            Console.log(LOG_PREFIX + "🛑 The pending login on this runId changed account between the attempt and its"
                        + " completion (verified " + accountId + ", found " + releasedAccountId + ") — not minting");
            return codeFailure();
        }
        SecondFactorAttemptLimiter.clear(accountId);
        // The session tier comes from the flag the PASSWORD step captured and stored in the entry:
        // by now the thread-local has been restored by the database round trips above, so reading it
        // here would answer "front office" for every login (the webauthn gateway's note).
        ModalityUserPrincipal principal = new ModalityUserPrincipal(verified.personId(), verified.accountId());
        return AuthenticatedState.createFor(principal, verified.backoffice())
            .compose(authenticatedState -> PushServerService.pushState(authenticatedState, runId));
    }

    /**
     * One failed try: spends the per-account budget and answers the generic code error, or
     * AttemptsExceeded once the budget is gone. The cap spans runIds, so restarting the login from
     * the password does not refresh it.
     */
    private static <T> Future<T> failedAttempt(Object accountId) {
        return SecondFactorAttemptLimiter.tryConsumeFailure(accountId) ? codeFailure() : attemptsExceededFailure();
    }

    // ===== updateCredentials path (enrolment, management, super-administrator reset) ==============

    @Override
    public boolean acceptsUpdateCredentialsArgument(Object updateCredentialsArgument) {
        return updateCredentialsArgument instanceof StartTotpEnrolmentCredentials
               || updateCredentialsArgument instanceof ConfirmTotpEnrolmentCredentials
               || updateCredentialsArgument instanceof ListSecondFactorsCredentials
               || updateCredentialsArgument instanceof RemoveTotpCredentials
               || updateCredentialsArgument instanceof RegenerateTotpBackupCodesCredentials
               || updateCredentialsArgument instanceof ResetSecondFactorCredentials;
    }

    @Override
    public Future<?> updateCredentials(Object updateCredentialsArgument) {
        if (!acceptsUpdateCredentialsArgument(updateCredentialsArgument))
            return Future.failedFuture(getClass().getSimpleName() + ".updateCredentials() requires a TOTP credentials argument");
        // Enrolment and management require a real logged-in account. A support view is a member of
        // staff looking at a customer's account: letting one plant its OWN second factor there — or
        // list, remove or regenerate the customer's — is exactly the transferable-credential hole the
        // support view mechanism exists to close, so the refusal is blanket. Guests carry a different
        // principal type and are refused by the same check.
        Object userId = ThreadLocalStateHolder.getUserId();
        if (!(userId instanceof ModalityUserPrincipal principal) || principal.isSupportView())
            return enrolmentFailure();
        Object accountId = TotpCredentialStore.normaliseId(principal.getUserAccountId());
        if (updateCredentialsArgument instanceof StartTotpEnrolmentCredentials cred)
            return startEnrolment(principal, accountId, cred.label());
        if (updateCredentialsArgument instanceof ConfirmTotpEnrolmentCredentials cred)
            return confirmEnrolment(accountId, cred.code());
        if (updateCredentialsArgument instanceof ListSecondFactorsCredentials)
            return listSecondFactorsJson(accountId);
        if (updateCredentialsArgument instanceof RemoveTotpCredentials cred)
            return removeTotp(accountId, cred.totpId(), cred.code());
        if (updateCredentialsArgument instanceof RegenerateTotpBackupCodesCredentials cred)
            return regenerateBackupCodes(accountId, cred.code());
        // Re-checked as a super administrator on EVERY call, never trusted from the client's grant
        // push, and never delegable through an operation code.
        if (updateCredentialsArgument instanceof ResetSecondFactorCredentials cred)
            return requireSuperAdmin(principal).compose(ignored ->
                resetSecondFactor(principal, accountId, cred.accountId(), cred.what(), cred.note()));
        // Unreachable while acceptsUpdateCredentialsArgument and this chain list the same types —
        // this arm is what keeps a future seventh type from becoming a ClassCastException
        return enrolmentFailure();
    }

    /**
     * Mints a secret, stores it encrypted and UNCONFIRMED, and hands back the URI and the base32
     * text. Unconfirmed is the whole point: a mistyped secret must fail at the next step, not at
     * the owner's next login.
     */
    private Future<?> startEnrolment(ModalityUserPrincipal principal, Object accountId, String label) {
        TotpSecretCipher cipher = TotpKeys.cipher();
        if (cipher == null) {
            Console.log(LOG_PREFIX + "🛑 Enrolment refused for account " + accountId + ": no encryption key configured");
            return enrolmentFailure();
        }
        // Captured before the async hop, like every other gateway does
        boolean isBackoffice = ThreadLocalStateHolder.isBackoffice();
        // Independent reads (both key on ids already at hand) — run them together. The person query
        // carries the same fences as getUserClaims: the session's person must still exist on that
        // account, and the account must still be allowed on this app.
        return Future.all(
            EntityStore.create(dataSourceModel)
                .<Person>executeQuery("select frontendAccount.username from Person where id=$1 and frontendAccount.(id=$2 and !disabled and ($3=false or backoffice))",
                    principal.getUserPersonId(), accountId, isBackoffice),
            credentialStore.findByAccount(accountId)
        ).compose(compositeFuture -> {
            List<Person> persons = compositeFuture.resultAt(0);
            TotpCredentialStore.TotpRow existing = compositeFuture.resultAt(1);
            if (persons.size() != 1)
                return enrolmentFailure();
            if (existing != null && existing.isConfirmed()) {
                // One TOTP per account. Replacing a confirmed factor goes through remove — which
                // asks for a fresh code — so a stolen session cannot quietly swap the factor out.
                Console.log(LOG_PREFIX + "Enrolment refused for account " + accountId + ": a confirmed factor already exists");
                return enrolmentFailure();
            }
            String username = persons.get(0).evaluate("frontendAccount.username");
            byte[] secret = new byte[Totp.SECRET_BYTES];
            SECURE_RANDOM.nextBytes(secret);
            String secretEnc = cipher.encrypt(secret, accountId);
            if (secretEnc == null)
                return enrolmentFailure();
            // The secret is in the reply by necessity — the owner has to type or scan it — so
            // wiping the array here would be theatre. What matters is that it is encrypted at rest
            // and that nothing on this path logs it.
            String base32Secret = Totp.base32Encode(secret);
            return credentialStore.insertUnconfirmed(accountId, secretEnc, cipher.keyId(), sanitizeLabel(label))
                .map(ignored -> {
                    AstObject response = AST.createObject();
                    response.set("otpauthUri", otpauthUri(username, base32Secret));
                    response.set("secret", base32Secret);
                    response.set("issuer", ISSUER);
                    response.set("username", username);
                    return Json.formatObject(response);
                });
        });
    }

    /**
     * Turns an unconfirmed row into a factor: one correct code, then the backup codes, shown once
     * and never again.
     *
     * <p>The order is confirm → generate → reply, and every step after the confirm is defensive by
     * design. Once the row is confirmed the enrolment HAS happened and its owner's next back-office
     * login will ask for the code, so no later step may report it as a failure; and the ten codes
     * live in this one reply and nowhere else in readable form, so no later step may lose them
     * either. A failed enrolment must therefore fail BEFORE the confirm, never after it.
     */
    private Future<?> confirmEnrolment(Object accountId, String code) {
        TotpSecretCipher cipher = TotpKeys.cipher();
        if (cipher == null) {
            Console.log(LOG_PREFIX + "🛑 Confirmation refused for account " + accountId + ": no encryption key configured");
            return enrolmentFailure();
        }
        return credentialStore.findByAccount(accountId).compose(row -> {
            if (row == null || row.isConfirmed())
                return enrolmentFailure();
            return verifyFreshCode(row, accountId, cipher, code).compose(verified -> {
                if (!Boolean.TRUE.equals(verified))
                    return enrolmentFailure();
                return credentialStore.confirm(row.id(), accountId).compose(confirmed -> {
                    if (!Boolean.TRUE.equals(confirmed))
                        return enrolmentFailure();
                    Console.log(LOG_PREFIX + "TOTP row " + row.id() + " confirmed for account " + accountId);
                    List<String> codes = BackupCodes.generate();
                    return credentialStore.insertGeneration(accountId, codes)
                        .map(generation -> codes)
                        // The factor is ALREADY confirmed by the time this runs, so a failure here
                        // must not be reported as a failed enrolment: its owner would believe they
                        // had not enrolled while their next back-office login asks for the code.
                        // Answer with the factor and no codes — the profile page's regenerate is
                        // the way back — and say so in the log.
                        .otherwise(e -> {
                            Console.log(LOG_PREFIX + "🛑 Backup codes could not be stored for account " + accountId
                                        + " (" + e.getMessage() + ") — the factor IS confirmed; its owner must regenerate");
                            return List.<String>of();
                        })
                        // Re-read so the reply carries the confirmed timestamp the database wrote —
                        // and guarded for the same reason as the insert above, which is the stronger
                        // one here: THE CODES ARE IN THIS REPLY AND ARE SHOWN EXACTLY ONCE. An
                        // unguarded echo would fail the whole future on a pool timeout, so its owner
                        // would read "could not be set up" for a factor that IS set up and would
                        // never see the ten codes that already exist in the database. Null drops the
                        // key (totpJson below), and the client tolerates its absence — the factor is
                        // the profile page's next listing away, the codes are not.
                        .compose(shownCodes -> credentialStore.findByAccount(accountId)
                            .otherwise(e -> {
                                Console.log(LOG_PREFIX + "🛑 The confirmed TOTP row could not be re-read for account "
                                            + accountId + " (" + e.getMessage() + ") — the factor IS confirmed;"
                                            + " replying with the backup codes and no factor detail");
                                return null;
                            })
                            .map(confirmedRow -> {
                                AstObject response = AST.createObject();
                                AstArray codesArray = AST.createArray();
                                for (String plainCode : shownCodes)
                                    codesArray.push(BackupCodes.format(plainCode));
                                response.setArray("codes", codesArray);
                                AstObject totp = totpJson(confirmedRow);
                                if (totp != null)
                                    response.setObject("totp", totp);
                                response.set("backupCodesLeft", shownCodes.size());
                                return Json.formatObject(response);
                            }));
                });
            });
        });
    }

    /** What the profile page shows: the factor (or none) and how many backup codes are left. */
    private Future<String> listSecondFactorsJson(Object accountId) {
        return Future.all(
            credentialStore.findByAccount(accountId),
            credentialStore.countUnused(accountId)
        ).map(compositeFuture -> {
            TotpCredentialStore.TotpRow row = compositeFuture.resultAt(0);
            Integer backupCodesLeft = compositeFuture.resultAt(1);
            AstObject response = AST.createObject();
            AstObject totp = totpJson(row);
            // Absent when there is no factor — which the client reads exactly as null, and which
            // keeps this off the AST's null-value behaviour across its four platform backends.
            if (totp != null)
                response.setObject("totp", totp);
            response.set("backupCodesLeft", backupCodesLeft == null ? 0 : backupCodesLeft);
            return Json.formatObject(response);
        });
    }

    /** Removes the factor and its codes — behind a fresh code, so a stolen session alone cannot. */
    private Future<?> removeTotp(Object accountId, Object totpIdArgument, String code) {
        Long requestedId = Numbers.toLong(totpIdArgument);
        TotpSecretCipher cipher = TotpKeys.cipher();
        if (cipher == null) {
            // Without the key no fresh code can be verified, so removal is a super-administrator
            // reset until the key is back. Said out loud for the operator, generic for the caller.
            Console.log(LOG_PREFIX + "🛑 Removal refused for account " + accountId + ": no encryption key configured");
            return enrolmentFailure();
        }
        return credentialStore.findByAccount(accountId).compose(row -> {
            // The id from the wire never selects the row — the account does. It is checked only so
            // that a client acting on a stale listing is told no instead of removing something else.
            if (row == null || requestedId == null || requestedId != row.id())
                return enrolmentFailure();
            return verifyFreshCode(row, accountId, cipher, code).compose(verified -> {
                if (!Boolean.TRUE.equals(verified))
                    return enrolmentFailure();
                return credentialStore.deleteOwned(accountId)
                    // The codes go with the factor: a sheet that outlived its TOTP would be a
                    // password-plus-paper login nobody remembers enabling.
                    .compose(deleted -> credentialStore.deleteAllFor(accountId))
                    .compose(ignored -> {
                        Console.log(LOG_PREFIX + "TOTP row " + row.id() + " removed by its owner (account " + accountId + ")");
                        return listSecondFactorsJson(accountId);
                    });
            });
        });
    }

    /** A new generation of backup codes, shown once; the previous generation stops working. */
    private Future<?> regenerateBackupCodes(Object accountId, String code) {
        TotpSecretCipher cipher = TotpKeys.cipher();
        if (cipher == null) {
            Console.log(LOG_PREFIX + "🛑 Regeneration refused for account " + accountId + ": no encryption key configured");
            return enrolmentFailure();
        }
        return credentialStore.findByAccount(accountId).compose(row -> {
            if (row == null || !row.isConfirmed())
                return enrolmentFailure();
            return verifyFreshCode(row, accountId, cipher, code).compose(verified -> {
                if (!Boolean.TRUE.equals(verified))
                    return enrolmentFailure();
                List<String> codes = BackupCodes.generate();
                return credentialStore.insertGeneration(accountId, codes).map(generation -> {
                    Console.log(LOG_PREFIX + "Backup codes regenerated for account " + accountId + " (generation " + generation + ")");
                    AstObject response = AST.createObject();
                    AstArray codesArray = AST.createArray();
                    for (String plainCode : codes)
                        codesArray.push(BackupCodes.format(plainCode));
                    response.setArray("codes", codesArray);
                    response.set("backupCodesLeft", codes.size());
                    return Json.formatObject(response);
                });
            });
        });
    }

    /**
     * Verifies a code against a row and consumes its step, so "fresh" is literal: a code already
     * used — for a login or for another management change — is not fresh and authorises nothing.
     *
     * <p>NOTE, and it is a real gap rather than an oversight: unlike the login path this has no
     * per-account attempt cap, because the cap is the LOGIN budget and spending it here would let a
     * mistyped code on a profile page lock its owner out of signing in. Guessing here needs an
     * already-hijacked session, which can do most of what this protects anyway — but if the cap is
     * ever split into a login budget and a management budget, this is the call site to wire up.
     */
    private Future<Boolean> verifyFreshCode(TotpCredentialStore.TotpRow row, Object accountId,
                                            TotpSecretCipher cipher, String code) {
        if (Strings.isEmpty(code))
            return Future.succeededFuture(false);
        byte[] secret = cipher.decrypt(row.secretEnc(), accountId);
        if (secret == null) {
            Console.log(LOG_PREFIX + "🛑 TOTP row " + row.id() + " will not decrypt (written by key " + row.keyId() + ")");
            return Future.succeededFuture(false);
        }
        long step = Totp.matchingStep(secret, code, System.currentTimeMillis() / 1000);
        if (step < 0)
            return Future.succeededFuture(false);
        return credentialStore.recordUse(row.id(), step);
    }

    // ===== super-administrator reset =============================================================

    /** Fails with the enrolment key unless the principal's person is a super administrator. */
    private Future<Void> requireSuperAdmin(ModalityUserPrincipal principal) {
        return SuperAdminMembership.isSuperAdmin(principal, dataSourceModel)
            .compose(isSuperAdmin -> {
                if (!Boolean.TRUE.equals(isSuperAdmin)) {
                    // Person id, not email: this lands in a log that ships to aggregation
                    Console.log(LOG_PREFIX + "Second-factor reset refused: person " + principal.getUserPersonId()
                                + " is not a super administrator");
                    return enrolmentFailure();
                }
                return Future.succeededFuture();
            });
    }

    /**
     * Clears somebody else's factor after an out-of-band identity check, ends their live sessions,
     * and writes what was done and on whose word.
     *
     * <p>Never on the approver's own account: a reset one could perform on oneself is a
     * self-service reset wearing a hat, and the factor exists precisely so that a cracked password
     * is not enough. The sessions go too — a reset happens because somebody lost control of a
     * factor, so a session opened by whoever caused it must not outlive it.
     */
    private Future<?> resetSecondFactor(ModalityUserPrincipal approver, Object approverAccountId,
                                        Object targetAccountIdArgument, String what, String note) {
        Object targetAccountId = TotpCredentialStore.normaliseId(targetAccountIdArgument);
        if (targetAccountId == null)
            return enrolmentFailure();
        if (Objects.equals(approverAccountId, targetAccountId)) {
            Console.log(LOG_PREFIX + "Second-factor reset refused: person " + approver.getUserPersonId()
                        + " targeted their own account");
            return enrolmentFailure();
        }
        String resetWhat = WHAT_TOTP.equalsIgnoreCase(Strings.toSafeString(what).trim()) ? WHAT_TOTP
            : WHAT_BACKUP_CODES.equalsIgnoreCase(Strings.toSafeString(what).trim()) ? WHAT_BACKUP_CODES : null;
        if (resetWhat == null)
            return enrolmentFailure();
        String sanitizedNote = sanitizeNote(note);
        // TOTP takes the codes with it (a sheet without a factor is a login nobody remembers
        // enabling); BACKUP_CODES clears only the sheet, for a person who mislaid it but still has
        // the phone.
        Future<Void> cleared = WHAT_TOTP.equals(resetWhat)
            ? credentialStore.deleteOwned(targetAccountId).compose(ignored -> credentialStore.deleteAllFor(targetAccountId))
            : credentialStore.deleteAllFor(targetAccountId);
        return cleared
            .compose(ignored -> EntityStore.create(dataSourceModel)
                // Every person on the account, not just the login one: a session belongs to a
                // person, and the account's other persons hold sessions of their own.
                .<Person>executeQuery("select id from Person where frontendAccount=$1", targetAccountId))
            .compose(persons -> revokeSessionsOf(persons, 0, 0))
            .compose(sessionsRevoked -> credentialStore
                .insertReset(targetAccountId, resetWhat, TotpCredentialStore.normaliseId(approver.getUserPersonId()),
                    sanitizedNote, sessionsRevoked)
                .map(ignored -> {
                    Console.log(LOG_PREFIX + resetWhat + " reset on account " + targetAccountId + " by person "
                                + approver.getUserPersonId() + " — " + sessionsRevoked + " session(s) revoked");
                    AstObject response = AST.createObject();
                    response.set("sessionsRevoked", sessionsRevoked);
                    return Json.formatObject(response);
                }));
    }

    /**
     * Revokes each person's sessions in turn, carrying the running total.
     *
     * <p>Sequential rather than parallel on purpose: an account has a handful of persons, and one
     * statement at a time keeps the count honest and the failure mode obvious.
     */
    private Future<Integer> revokeSessionsOf(List<Person> persons, int index, int revokedSoFar) {
        if (persons == null || index >= persons.size())
            return Future.succeededFuture(revokedSoFar);
        return credentialStore.revokeLiveSessions(TotpCredentialStore.normaliseId(persons.get(index).getPrimaryKey()))
            .compose(revoked -> revokeSessionsOf(persons, index + 1, revokedSoFar + revoked));
    }

    // ===== the rest of the SPI (delegated elsewhere, webauthn/magiclink pattern) ==================

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

    /** The factor as its owner's UI shows it — never the secret, never the key id. */
    private static AstObject totpJson(TotpCredentialStore.TotpRow row) {
        if (row == null)
            return null;
        AstObject totp = AST.createObject();
        totp.set("id", row.id());
        if (row.label() != null)
            totp.set("label", row.label());
        if (row.createdAt() != null)
            totp.set("createdAt", row.createdAt().toString());
        // Null while an enrolment is half-finished — the client shows "finish setting this up"
        if (row.confirmedAt() != null)
            totp.set("confirmedAt", row.confirmedAt().toString());
        if (row.lastUsedAt() != null)
            totp.set("lastUsedAt", row.lastUsedAt().toString());
        return totp;
    }

    /**
     * {@code otpauth://totp/KBS3:<username>?secret=…&issuer=KBS3&algorithm=SHA1&digits=6&period=30}
     * — the label carries the username so a person with several accounts can tell them apart in the
     * app, and the parameters are spelled out rather than left to each app's defaults.
     */
    private static String otpauthUri(String username, String base32Secret) {
        return "otpauth://totp/" + percentEncode(ISSUER + ":" + Strings.toSafeString(username))
               + "?secret=" + base32Secret
               + "&issuer=" + percentEncode(ISSUER)
               + "&algorithm=SHA1&digits=" + Totp.DIGITS + "&period=" + Totp.STEP_SECONDS;
    }

    /**
     * Percent-encodes one URI component.
     *
     * <p>Hand-rolled rather than {@code URLEncoder}, which encodes a space as {@code +} — correct
     * for a form body, wrong inside a path segment, and the label here is an email address that may
     * contain characters the app would otherwise read as part of the URI.
     */
    private static String percentEncode(String value) {
        StringBuilder encoded = new StringBuilder(value.length());
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                || c == '-' || c == '.' || c == '_' || c == '~')
                encoded.append(c);
            else
                encoded.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xFF));
        }
        return encoded.toString();
    }

    /** User-supplied device name: trimmed, capped to the column, empty collapsed to null. */
    private static String sanitizeLabel(String label) {
        return capped(label, MAX_LABEL_LENGTH);
    }

    /** The approver's record of the out-of-band check: trimmed, capped to the column. */
    private static String sanitizeNote(String note) {
        return capped(note, MAX_NOTE_LENGTH);
    }

    private static String capped(String value, int maxLength) {
        if (value == null)
            return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty())
            return null;
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private static <T> Future<T> codeFailure() {
        // ONE error for wrong, replayed, expired and no-pending — anything else would tell a
        // caller which of those it was, and each of those answers is a fact about the account.
        return Future.failedFuture("[%s] That code was not accepted".formatted(ModalityAuthenticationI18nKeys.AuthnSecondFactorCodeError));
    }

    private static <T> Future<T> attemptsExceededFailure() {
        return Future.failedFuture("[%s] Too many attempts — sign in again".formatted(ModalityAuthenticationI18nKeys.AuthnSecondFactorAttemptsExceededError));
    }

    private static <T> Future<T> enrolmentFailure() {
        // The management path's generic error: "sign-in failed" on a profile page where nobody is
        // signing in was worse (the webauthn gateway's note).
        return Future.failedFuture("[%s] The second-factor change could not be completed".formatted(ModalityAuthenticationI18nKeys.AuthnTotpEnrolmentError));
    }
}
