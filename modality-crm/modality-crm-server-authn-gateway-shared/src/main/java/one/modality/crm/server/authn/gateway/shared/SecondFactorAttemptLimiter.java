package one.modality.crm.server.authn.gateway.shared;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caps how often ONE ACCOUNT may fail its second factor, across every runId.
 *
 * <p>Why this exists alongside the per-runId budget in {@link PendingSecondFactorStore}: runIds are
 * chosen by the client. A caller who holds the password can take five guesses, open a new runId, do
 * the password step again and take five more, for as long as it likes — so a per-runId cap alone
 * bounds nothing. The account is the thing being attacked, so the account is what gets counted.
 *
 * <p><b>What "capped" has to mean.</b> Counting alone is not a cap: until the caller consults
 * {@link #isLockedOut} BEFORE verifying, an over-budget account is still verified against, and a
 * six-digit code with a ±1 step window is roughly three chances in a million per try — which an
 * unbounded guesser converts into a back-office session in hours. So once
 * {@value #FAILURES_BEFORE_LOCKOUT} failures land inside {@link #WINDOW}, every further attempt on
 * that account is refused unlooked-at until the window ages out, and the guessing rate is bounded to
 * that budget per window rather than to whatever the network allows.
 *
 * <p>This is a lockout, and it is meant as one. An earlier note here promised that "a correct code
 * is never refused by this class" — kinder, and unimplementable alongside the cap it claimed to be:
 * the class is only ever called after a code has already been rejected, so refusing nothing left
 * guessing unbounded. The cost is real and accepted: somebody whose phone clock has drifted far
 * enough to fail ten codes waits out the window or uses a backup code (which the same lockout
 * covers — a printed sheet is the recovery path for a lost factor, not a way around the cap).
 * {@link #clear} on a successful factor means a bad day costs nothing later.
 *
 * <p><b>Differs from the magic-link code budget it is modelled on</b> ({@code MagicLinkService}:
 * {@code MAX_CODE_ATTEMPTS} attempts allowed, the next one refused before the lookup). There, an
 * attempt is counted before the code is checked, so the Nth attempt is still verified and can
 * succeed. Here the count is spent only on a FAILURE — by the time this class hears about an
 * attempt the code has already been rejected — so the constant counts failures, not attempts, and
 * the name says so.
 *
 * <p>The map cannot be grown by an anonymous flood: a key only ever arrives from a pending entry,
 * and nothing is pending until a password matched. Entries age out with the window and are pruned on
 * every write. In memory on purpose, like the magic-link code budget — the server runs single-task
 * with single-active deploys, and a restart merely resets a counter that would have expired anyway.
 *
 * @author Claude Code
 */
public final class SecondFactorAttemptLimiter {

    /** Failures one account may accumulate inside {@link #WINDOW} before every attempt is refused. */
    private static final int FAILURES_BEFORE_LOCKOUT = 10;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Map<Object, Failures> FAILURES_BY_ACCOUNT = new ConcurrentHashMap<>();

    private record Failures(int count, Instant since) {
    }

    private SecondFactorAttemptLimiter() {
    }

    /**
     * Whether this account is out of budget and must be refused WITHOUT its factor being looked at.
     *
     * <p>Call this before verifying anything, with the same account id the failures are counted
     * under. Read-only: consulting it costs nothing, so a refused attempt cannot extend its own
     * lockout.
     */
    public static boolean isLockedOut(Object accountId) {
        if (accountId == null)
            return false; // nothing to key on; the per-runId budget is still charged
        Failures failures = FAILURES_BY_ACCOUNT.get(accountId);
        return failures != null
               && failures.count() >= FAILURES_BEFORE_LOCKOUT
               && !failures.since().isBefore(Instant.now().minus(WINDOW));
    }

    /**
     * Counts one failed attempt for the account and says whether it may keep trying: false once
     * {@value #FAILURES_BEFORE_LOCKOUT} failures have landed inside the window, including the one
     * just counted. The caller answers a false with the attempts-exceeded error, logged with the
     * account id only.
     */
    public static boolean tryConsumeFailure(Object accountId) {
        if (accountId == null)
            return true; // nothing to key on; the per-runId budget is still charged
        prune();
        Instant now = Instant.now();
        int count = FAILURES_BY_ACCOUNT.merge(accountId, new Failures(1, now),
            (previous, one) -> previous.since().isBefore(now.minus(WINDOW))
                ? new Failures(1, now)              // the old window is over: start a fresh one
                : new Failures(previous.count() + 1, previous.since())).count();
        return count < FAILURES_BEFORE_LOCKOUT;
    }

    /** Forgets an account's failures — called when a factor is accepted, so a bad day costs nothing later. */
    public static void clear(Object accountId) {
        if (accountId != null)
            FAILURES_BY_ACCOUNT.remove(accountId);
    }

    private static void prune() {
        Instant cutoff = Instant.now().minus(WINDOW);
        FAILURES_BY_ACCOUNT.values().removeIf(failures -> failures.since().isBefore(cutoff));
    }
}
