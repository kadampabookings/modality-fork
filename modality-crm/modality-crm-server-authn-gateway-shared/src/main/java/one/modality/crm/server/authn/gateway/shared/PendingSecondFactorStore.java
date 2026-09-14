package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory store of logins that have proved their password and are waiting for a second factor,
 * keyed by the client's runId. A repeat password step for the same runId REPLACES the previous entry
 * — one browser tab therefore holds at most one pending login, whatever it floods.
 *
 * <p>Entries expire after {@link #TTL_MILLIS} (five minutes: long enough to unlock a phone and find
 * the app, short enough that an abandoned one costs nothing).
 *
 * <p><b>The two ways to touch an entry, and the invariant they exist to make provable.</b>
 * {@link #recordAttempt} spends one of the five attempts BEFORE any verification, so a guess costs an
 * attempt whether it was right, wrong or malformed, and it leaves the entry in place — even when it
 * has just spent the last attempt, where the zero balance is what refuses the next call.
 * {@link #consumeAfterSuccess} is the only way to RECEIVE an entry to mint from: it removes the entry
 * atomically before returning it, so exactly one caller can ever be told "you may mint for this
 * login". A caller that verified a code successfully and got null back lost the race (or waited past
 * the expiry) and must refuse. (Entries also leave without being released to anybody — {@link #drop},
 * the expiry sweep, and the per-account eviction below — and every one of those paths ends in a
 * refused login, never a mint.)
 *
 * <p>That is the whole contract, and it holds from this class alone rather than from a gateway
 * remembering to clean up: <b>one pending entry per runId, five charged attempts, removed on the
 * first success.</b> The earlier split — {@code recordAttempt} deleting the entry as it charged the
 * last attempt, plus a {@code consume} that charged nothing and whose result nobody checked — made
 * "single-use" a property of the caller instead, and one password plus one code inside the same
 * 30-second TOTP step could have been redeemed more than once had a caller forgotten the second call.
 *
 * <p><b>Overload policy</b>, inherited from {@code WebAuthnChallengeStore}: at {@link #MAX_PENDING}
 * entries {@link #put} REFUSES rather than evicting a stranger's login. Evicting would let a flood of
 * forged runIds cancel real users' logins mid-step — turning a memory guard into an availability hole
 * — whereas refusing bounds the damage to "new logins are refused while the flood lasts", which the
 * generic credentials error already covers.
 *
 * <p><b>Per account</b>, {@link #MAX_PENDING_PER_ACCOUNT} is the real bound, and it is the one that
 * matters: an account needs ONE pending login, so a caller holding one cracked password can no longer
 * fill the store with distinct runIds and refuse every other member of staff the back office. Over
 * the cap, that ACCOUNT's own oldest entry is evicted — never a stranger's, and never the entry just
 * written — so somebody retrying their own login is never blocked by themselves, while the global cap
 * now needs {@code MAX_PENDING / MAX_PENDING_PER_ACCOUNT} distinct cracked passwords to reach.
 *
 * <p>SINGLE-INSTANCE ONLY, like the challenge store: a password step taken on one server instance
 * cannot be completed on another (blue/green overlap) — the second step fails cleanly and the user
 * types the password again. If the server ever becomes multi-instance this store must move to the
 * database.
 *
 * @author Claude Code
 */
public final class PendingSecondFactorStore {

    /** How long a proven password waits for its factor. Also what the marker advertises to the client. */
    public static final long TTL_MILLIS = 300_000;
    private static final long SWEEP_INTERVAL_MILLIS = 60_000;
    /** Minimum interval between opportunistic full sweeps while at the cap — keeps a sustained flood from buying an O(n) scan per request. */
    private static final long FULL_SWEEP_MIN_INTERVAL_MILLIS = 5_000;
    private static final int MAX_PENDING = 10_000;
    /**
     * Pending logins one account may hold at once. Three, not one: a person who reloads the login
     * page, or signs in from a phone and a desktop within the same five minutes, legitimately holds
     * more than one — and the eviction below takes the oldest of THEIR OWN rather than refusing, so
     * the cap can never lock somebody out of their own login.
     */
    private static final int MAX_PENDING_PER_ACCOUNT = 3;

    private static final PendingSecondFactorStore INSTANCE = new PendingSecondFactorStore();

    public static PendingSecondFactorStore getInstance() {
        return INSTANCE;
    }

    private final Map<String, PendingSecondFactor> pendingByRunId = new ConcurrentHashMap<>();
    private final AtomicLong lastFullSweepMillis = new AtomicLong();

    private PendingSecondFactorStore() {
        Scheduler.schedulePeriodic(SWEEP_INTERVAL_MILLIS, this::sweepExpired);
    }

    /**
     * Stores the pending login for this runId, replacing any previous one, and drops this ACCOUNT's
     * oldest surplus entries if it now holds more than {@link #MAX_PENDING_PER_ACCOUNT}. Returns
     * false when the store is globally full — the caller answers with its generic credentials error,
     * having minted nothing.
     */
    public boolean put(String runId, PendingSecondFactor entry) {
        if (runId == null || entry == null)
            return false;
        if (pendingByRunId.size() >= MAX_PENDING && !pendingByRunId.containsKey(runId) && !tryMakeRoom())
            return false;
        pendingByRunId.put(runId, entry);
        evictAccountSurplus(entry.accountId(), runId);
        return true;
    }

    /**
     * Spends one attempt and returns the pending login to verify against, or null when there is
     * none, it has expired, or its attempts are gone.
     *
     * <p>Call this BEFORE verifying anything: the budget is what bounds guessing, so it must be
     * charged for an attempt that turns out to be malformed, replayed or right. The attempt that
     * takes the count to zero is still answered — five attempts means five — and the spent entry is
     * KEPT so that the next call finds a zero balance and refuses; it is removed then, or by the
     * sweep, or by {@link #consumeAfterSuccess} when the attempt it charged turns out to be the
     * right code.
     */
    public PendingSecondFactor recordAttempt(String runId) {
        if (runId == null)
            return null;
        long now = System.currentTimeMillis();
        AtomicReference<PendingSecondFactor> attempt = new AtomicReference<>();
        pendingByRunId.compute(runId, (key, entry) -> {
            if (entry == null || entry.isExpired(now) || entry.attemptsLeft() <= 0)
                return null; // absent, stale or spent: nothing to verify against, and the husk goes
            PendingSecondFactor charged = entry.withOneAttemptLess();
            attempt.set(charged);
            return charged; // kept even at zero — only a success removes a live entry
        });
        return attempt.get();
    }

    /**
     * Atomically removes the pending login and hands it back to the ONE caller that verified its
     * factor, or null when there is nothing left to consume — already consumed by a concurrent or
     * replayed request, dropped, or expired while the verification was in flight.
     *
     * <p><b>A null answer means "do not mint".</b> Not a formality: this is what makes one password
     * plus one correct code worth exactly one session, including for two requests carrying the same
     * still-valid code inside the same 30-second TOTP step. Call it only after the factor has
     * verified, and mint only if it answers non-null.
     */
    public PendingSecondFactor consumeAfterSuccess(String runId) {
        if (runId == null)
            return null;
        PendingSecondFactor entry = pendingByRunId.remove(runId);
        if (entry == null || entry.isExpired(System.currentTimeMillis()))
            return null;
        return entry;
    }

    /** Forgets the pending login (the user cancelled, or a step decided it must not be retried). */
    public void drop(String runId) {
        if (runId != null)
            pendingByRunId.remove(runId);
    }

    private void sweepExpired() {
        long now = System.currentTimeMillis();
        pendingByRunId.entrySet().removeIf(e -> e.getValue().isExpired(now));
    }

    /**
     * Keeps one account to {@link #MAX_PENDING_PER_ACCOUNT} entries by removing its OWN oldest,
     * never {@code keepRunId} and never another account's.
     *
     * <p>Oldest is read from {@code expiresAtMillis}: the TTL is a constant, so expiry orders entries
     * exactly as creation does, and the record needs no extra field. Removal is conditional on the
     * value still being the one that was scanned, so a login that replaced it between the scan and
     * here survives. A concurrent put for the same account may make this evict one entry more than
     * strictly needed, which costs its owner a retry of the password step — the direction that fails
     * safe.
     *
     * <p>O(n) over a map bounded by {@link #MAX_PENDING}, on a path that has already done a password
     * hash and two database round trips.
     */
    private void evictAccountSurplus(Object accountId, String keepRunId) {
        if (accountId == null)
            return;
        List<Map.Entry<String, PendingSecondFactor>> sameAccount = new ArrayList<>();
        for (Map.Entry<String, PendingSecondFactor> candidate : pendingByRunId.entrySet())
            if (!candidate.getKey().equals(keepRunId) && Objects.equals(candidate.getValue().accountId(), accountId))
                sameAccount.add(candidate);
        int surplus = sameAccount.size() - (MAX_PENDING_PER_ACCOUNT - 1); // the entry just written takes one slot
        if (surplus <= 0)
            return;
        sameAccount.sort(Comparator.comparingLong(candidate -> candidate.getValue().expiresAtMillis()));
        for (int i = 0; i < surplus; i++)
            pendingByRunId.remove(sameAccount.get(i).getKey(), sameAccount.get(i).getValue());
    }

    /** At the cap: sweep expired entries, at most once per {@link #FULL_SWEEP_MIN_INTERVAL_MILLIS}. */
    private boolean tryMakeRoom() {
        long now = System.currentTimeMillis();
        long lastSweep = lastFullSweepMillis.get();
        if (now - lastSweep >= FULL_SWEEP_MIN_INTERVAL_MILLIS && lastFullSweepMillis.compareAndSet(lastSweep, now))
            sweepExpired();
        return pendingByRunId.size() < MAX_PENDING;
    }
}
