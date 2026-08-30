package one.modality.crm.server.authn.gateway.webauthn;

import dev.webfx.platform.scheduler.Scheduler;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory store of pending WebAuthn challenges, keyed by the client's runId plus the ceremony
 * purpose ("reg" / "auth"), so one tab's registration cannot answer another tab's login. A repeat
 * {@link #create} under the same key replaces the previous pending ceremony — one websocket can
 * therefore hold at most one pending login and one pending registration, whatever it floods.
 *
 * <p>Challenges are 32 bytes of {@link SecureRandom}, SINGLE-USE — {@link #consume} removes
 * atomically before any verification, so a replayed response finds nothing — and expire after
 * {@link #TTL_MILLIS} (the browser dialog advertises a 120s timeout; the store gives 60s slack
 * for slow account pickers).
 *
 * <p>Overload policy: at {@link #MAX_PENDING} entries (reachable only by forging many distinct
 * runIds, since the key collapses per-connection floods), {@link #create} REFUSES rather than
 * evicting — evicting would let an anonymous flood cancel real users' ceremonies mid-dialog,
 * turning a memory guard into an availability hole. Refusing bounds the damage to "new ceremonies
 * fail while the flood lasts", which the generic error already covers. Expired entries are swept
 * every {@link #SWEEP_INTERVAL_MILLIS} and opportunistically (rate-limited) when full.
 *
 * <p>SINGLE-INSTANCE ONLY, like KBS2's ServerNonceStore: a ceremony started on one server
 * instance cannot be finished on another (blue/green overlap), it fails cleanly and the user
 * retries. If the server ever becomes multi-instance this store must move to the database.
 *
 * @author Claude Code
 */
final class WebAuthnChallengeStore {

    static final long TTL_MILLIS = 180_000;
    private static final long SWEEP_INTERVAL_MILLIS = 60_000;
    /** Minimum interval between opportunistic full sweeps while at the cap — keeps a sustained flood from buying an O(n) scan per request. */
    private static final long FULL_SWEEP_MIN_INTERVAL_MILLIS = 5_000;
    private static final int MAX_PENDING = 10_000;
    private static final int CHALLENGE_BYTES = 32;

    /**
     * One pending ceremony. {@code accountId} and {@code userHandleB64} are set for registrations
     * only — they bind the finalise step to the account and handle the start step committed to.
     */
    record Pending(byte[] challenge, long expiresAtMillis, Object accountId, String userHandleB64) {
        boolean isExpired(long nowMillis) {
            return nowMillis > expiresAtMillis;
        }
    }

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Pending> pendingByKey = new ConcurrentHashMap<>();
    private final AtomicLong lastFullSweepMillis = new AtomicLong();

    WebAuthnChallengeStore() {
        Scheduler.schedulePeriodic(SWEEP_INTERVAL_MILLIS, this::sweepExpired);
    }

    /**
     * Mints and stores a fresh challenge for the given key, replacing any previous pending
     * ceremony of the same key (a user clicking the button twice keeps only the latest).
     * Returns null when the store is full — the caller answers with its generic error.
     */
    Pending create(String key, Object accountId, String userHandleB64) {
        if (pendingByKey.size() >= MAX_PENDING && !tryMakeRoom())
            return null;
        byte[] challenge = new byte[CHALLENGE_BYTES];
        secureRandom.nextBytes(challenge);
        Pending pending = new Pending(challenge, System.currentTimeMillis() + TTL_MILLIS, accountId, userHandleB64);
        pendingByKey.put(key, pending);
        return pending;
    }

    /**
     * Atomically removes and returns the pending ceremony for the key, or null when there is
     * none or it has expired. Removal-before-verification is what makes challenges single-use.
     */
    Pending consume(String key) {
        Pending pending = pendingByKey.remove(key);
        if (pending == null || pending.isExpired(System.currentTimeMillis()))
            return null;
        return pending;
    }

    private void sweepExpired() {
        long now = System.currentTimeMillis();
        pendingByKey.entrySet().removeIf(e -> e.getValue().isExpired(now));
    }

    /** At the cap: sweep expired entries, at most once per {@link #FULL_SWEEP_MIN_INTERVAL_MILLIS}. */
    private boolean tryMakeRoom() {
        long now = System.currentTimeMillis();
        long lastSweep = lastFullSweepMillis.get();
        if (now - lastSweep >= FULL_SWEEP_MIN_INTERVAL_MILLIS && lastFullSweepMillis.compareAndSet(lastSweep, now))
            sweepExpired();
        return pendingByKey.size() < MAX_PENDING;
    }
}
