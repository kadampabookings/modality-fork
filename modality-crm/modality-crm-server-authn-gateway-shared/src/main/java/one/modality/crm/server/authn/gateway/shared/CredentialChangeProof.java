package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Numbers;
import dev.webfx.platform.util.Strings;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.base.shared.entities.FrontendAccount;
import one.modality.crm.shared.services.authn.ModalityAuthenticationI18nKeys;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a session must show before it changes how the account is reached — its sign-in email, a new passkey:
 * the current password, or a password sign-in or an emailed recovery minutes old.
 *
 * <h3>Why a session is not enough</h3>
 *
 * <p>A session proves who opened it, not who is holding it. A laptop taken unlocked carries a session that is
 * bounded — hours idle, a day at most, ended by "sign out my other devices" — and every change gated here is a
 * way to turn that bounded session into access that outlives it: point the sign-in email at an address the
 * holder controls and "Forgot password" hands them the account; register their own passkey and they simply
 * sign in. The password change itself is held to the same rule in the password gateway.
 *
 * <h3>The proofs</h3>
 *
 * <ul>
 *   <li><b>The current password</b>, checked against the stored hash. Not accepted when the owner has stopped
 *       their password working (V0096): a password that may be known is exactly what that control distrusts.
 *       Read fail-closed — a restriction that could not be read is not taken as absent.</li>
 *   <li><b>The password, proved at sign-in in this tab within {@link #RECENT_PASSWORD_SIGN_IN}</b> — what lets
 *       the back office offer a passkey right after a password sign-in without asking for the password it was
 *       just given. Noted by the password gateway ({@link #notePasswordProved}) the moment a typed password is
 *       found correct, keyed by the tab's run id and the account, and only ever accepted for a caller signed
 *       in as that account. Five minutes because it serves that one moment: a laptop taken later carries an
 *       older sign-in.</li>
 *   <li><b>A recovery window</b> ({@link RecoveryWindow}): this tab redeemed an emailed link or code for this
 *       account within the last fifteen minutes — the person who has forgotten their password has proved the
 *       mailbox instead. Not spent here; see RecoveryWindow on why only a password set spends it.</li>
 * </ul>
 *
 * <p>A support view is refused outright: it is staff looking at a customer's account, and must never be
 * able to change how that account is reached.
 *
 * <p>What this does NOT stop is somebody who also holds the mailbox — a laptop taken with the mail client
 * open. Every email-based proof, the recovery window included, falls to that; only an account whose password
 * and recovery are both closed resists it.
 *
 * @author Claude Code
 */
public final class CredentialChangeProof {

    /** How long after a password sign-in the same tab counts as having proved the password. */
    public static final Duration RECENT_PASSWORD_SIGN_IN = Duration.ofMinutes(5);

    /**
     * Tabs that proved the password at sign-in, by run id. In memory: a fact that lives five minutes, and
     * whose loss on a redeploy only means the password is asked for again.
     */
    private static final Map<String, PasswordProved> RECENT_PASSWORD_PROOFS = new ConcurrentHashMap<>();

    private record PasswordProved(long accountId, Instant at) {}

    private CredentialChangeProof() {}

    /**
     * Records that the tab {@code runId} has just typed the correct password for {@code accountId} — called by
     * the password gateway at sign-in, before any second factor (the proof is only usable once a session for
     * that account exists in that tab).
     */
    public static void notePasswordProved(String runId, Object accountId) {
        Long normalisedAccountId = accountId == null ? null : Numbers.toLong(accountId);
        if (runId == null || normalisedAccountId == null)
            return;
        Instant now = Instant.now();
        RECENT_PASSWORD_PROOFS.values().removeIf(proof -> proof.at().plus(RECENT_PASSWORD_SIGN_IN).isBefore(now));
        RECENT_PASSWORD_PROOFS.put(runId, new PasswordProved(normalisedAccountId, now));
    }

    private static boolean passwordProvedRecently(String runId, Object accountId) {
        PasswordProved proof = runId == null ? null : RECENT_PASSWORD_PROOFS.get(runId);
        return proof != null
               && Numbers.identicalObjectsOrNumberValues(proof.accountId(), accountId)
               && Instant.now().isBefore(proof.at().plus(RECENT_PASSWORD_SIGN_IN));
    }

    /**
     * Succeeds when the caller has proved the change; fails with {@code [AuthnOldPasswordNotMatchingError]}
     * when it has not — a wrong password, or no password and neither a recent password sign-in nor an open
     * recovery window, deliberately the same answer — or with {@code [AuthnPasswordSignInClosedError]} when the
     * proof offered is the password of an account whose password is closed (the recovery window is then the
     * only proof).
     *
     * <p>MUST be called on the caller's thread: it reads the principal and run id before its first async step,
     * because {@link ThreadLocalStateHolder} is restored once the synchronous part of the call returns.
     *
     * @param currentPassword the password as typed, or null/empty to rely on a recent password sign-in or a
     *                        recovery window
     * @param dataSourceModel the data source to read the account and the link from
     */
    public static Future<Void> require(String currentPassword, DataSourceModel dataSourceModel) {
        Object caller = ThreadLocalStateHolder.getUserId();
        String runId = ThreadLocalStateHolder.getRunId();
        if (!(caller instanceof ModalityUserPrincipal principal) || principal.isSupportView())
            return notProved();
        Object accountId = normaliseId(principal.getUserAccountId());
        if (accountId == null)
            return notProved();
        if (Strings.isEmpty(currentPassword)) {
            // Both started here, on the caller's thread: the recovery lookup reads the caller from it too
            Future<Void> byRecovery = RecoveryWindow.findForCaller(dataSourceModel)
                .compose(open -> open != null ? Future.succeededFuture() : notProved());
            if (!passwordProvedRecently(runId, accountId))
                return byRecovery;
            // A password proved at sign-in minutes ago still counts — unless it has since been closed, in which
            // case the recovery window is the proof left, as it is for a typed password
            return AccountSignInRestrictionStore.readPasswordClosed(accountId)
                .compose(closed -> Boolean.TRUE.equals(closed) ? byRecovery : Future.succeededFuture());
        }
        return Future.all(
            EntityStore.create(dataSourceModel)
                .<FrontendAccount>executeQuery("select password,salt from FrontendAccount where id=$1 and !disabled", accountId),
            AccountSignInRestrictionStore.readPasswordClosed(accountId)
        ).compose(results -> {
            List<FrontendAccount> accounts = results.resultAt(0);
            Boolean passwordClosed = results.resultAt(1);
            if (accounts.size() != 1)
                return notProved();
            // Checked before the password, so a closed account says nothing about whether the guess was right
            if (Boolean.TRUE.equals(passwordClosed))
                return passwordClosed();
            FrontendAccount account = accounts.get(0);
            return StoredPasswords.matches(currentPassword, account.getPassword(), account.getSalt())
                ? Future.succeededFuture()
                : notProved();
        });
    }

    private static Future<Void> passwordClosed() {
        return Future.failedFuture("[%s] Password sign-in is closed on this account".formatted(ModalityAuthenticationI18nKeys.AuthnPasswordSignInClosedError));
    }

    private static Future<Void> notProved() {
        return Future.failedFuture("[%s] The current password is not matching".formatted(ModalityAuthenticationI18nKeys.AuthnOldPasswordNotMatchingError));
    }

    /**
     * A principal decoded from a token carries small ids as Byte or Short, and a Double reaching a DQL
     * parameter fails coercion against an integer column; a Long is what every query here expects.
     */
    private static Object normaliseId(Object id) {
        return id == null ? null : Numbers.toLong(id);
    }
}
