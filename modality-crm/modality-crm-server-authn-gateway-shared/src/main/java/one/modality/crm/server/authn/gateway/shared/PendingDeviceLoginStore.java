package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.scheduler.Scheduler;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory store of device logins waiting for somebody to approve them in a browser, keyed by the short
 * code the operator reads off their terminal and types into the approval page.
 *
 * <p>Sibling of {@link PendingSecondFactorStore} and deliberately the same shape: a {@link ConcurrentHashMap},
 * a five-minute TTL, a cap, and <b>refusing</b> a new entry when full rather than evicting one. Evicting
 * would let a flood of forged requests cancel real logins, turning a memory guard into an availability hole.
 *
 * <h3>What a pending entry is NOT</h3>
 *
 * <p>It is coordination, not a record. It carries <b>no identity</b>: an attacker who can create entries must
 * not be able to choose who they become, so who the session belongs to is decided entirely by the session that
 * APPROVES, and arrives here only as the approval's own business. The durable half is the
 * {@code device_login_attempt} row (V0181), which is append-only and the opposite of this in every respect.
 *
 * <h3>Why the owning session is held, and why there is no collection secret</h3>
 *
 * <p>The minted session is PUSHED to the requester's {@code runId}; it is never collected. That run id is
 * chosen by the client, arrives in a message, and is authenticated by nothing — {@code Uuid.randomUuid} is
 * built on {@code Math.random}, and the bus layer says so in as many words where it refuses a second
 * session's claim over a connected client's family. At an ordinary login the gap between minting and
 * delivery is milliseconds; here it is five minutes, waiting for a person, which is the whole of the
 * difference.
 *
 * <p>So the entry remembers the bus session that ASKED, and the gateway releases the minted session only
 * while that same session still owns the run id — the binding {@code ownerSessionId} already performs for
 * prompt revocation. A collection secret would answer the same threat, but only for a client that comes back
 * to collect, and this design deliberately has no polling: there is no second call to present a secret on.
 * Binding what exists beats inventing a round trip to protect.
 *
 * <h3>The code</h3>
 *
 * <p>Eight characters of {@link SecureRandom} from an alphabet with the look-alikes removed, shown as
 * {@code XXXX-XXXX} and read back with anything outside the alphabet stripped, so a typed hyphen or space
 * costs nothing. Roughly 40 bits: far beyond guessing inside five minutes at any rate a person could sustain,
 * and short enough that typing it is the easy path. <b>Typed, never compared</b> — the review's point: a
 * comparison can be skipped by somebody in a hurry, and a framed page cannot supply a code the attacker does
 * not have.
 *
 * <p>It must never be derived from the run id, which is neither secret nor random enough to be one.
 *
 * <h3>Expired requests are counted, never stored</h3>
 *
 * <p>A request nobody acts on leaves through {@link #sweepExpired} and increments {@link #expiredCount()}.
 * It writes no audit row, because a refusal row is a row written for a caller who never signed in, and an
 * unauthenticated caller must not be able to fill a table by asking. A code typed after its entry lapsed is
 * a different thing — somebody did something — and that one is recorded.
 *
 * <p>SINGLE-INSTANCE ONLY, like its sibling: a request made on one server instance cannot be approved on
 * another. One task runs except during a blue/green deploy, and the people running the command line are the
 * people running the deploy, so the window is short and the failure is legible rather than silent.
 *
 * @author Claude Code
 */
public final class PendingDeviceLoginStore {

    /** How long a request waits for somebody to approve it. The same five minutes a factor step gets. */
    public static final long TTL_MILLIS = 300_000;
    private static final long SWEEP_INTERVAL_MILLIS = 60_000;
    /** Far below the factor store's: a device login is a developer's occasional act, not a login path. */
    private static final int MAX_PENDING = 2_000;

    /** Look-alikes removed: no 0/O, no 1/I/L. 31 characters, so eight of them are a shade under 40 bits. */
    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 8;
    /** Tries before giving up on finding an unused code — a collision at this cap is already vanishing. */
    private static final int CODE_ATTEMPTS = 8;

    private static final PendingDeviceLoginStore INSTANCE = new PendingDeviceLoginStore(true);

    public static PendingDeviceLoginStore getInstance() {
        return INSTANCE;
    }

    private final Map<String, Pending> pendingByCode = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private final AtomicLong expiredCount = new AtomicLong();

    /**
     * @param sweeping false in a check, which drives {@link #sweepExpired(long)} itself and has no scheduler
     *                 provider to schedule against
     */
    PendingDeviceLoginStore(boolean sweeping) {
        if (sweeping)
            Scheduler.schedulePeriodic(SWEEP_INTERVAL_MILLIS, () -> sweepExpired(System.currentTimeMillis()));
    }

    /**
     * A device login waiting to be approved.
     *
     * @param runId           where the minted session is pushed. Client-chosen and unauthenticated — see the
     *                        class comment for what {@code ownerSessionId} does about that
     * @param ownerSessionId  the bus session that asked, which must still own {@code runId} at approval
     * @param backofficeRequested which arm the caller SAYS it wants. Recorded so the gateway can tell the
     *                        approver what was asked for; the audience actually minted comes from the
     *                        approving session and never from here
     * @param expiresAtMillis when this entry stops being approvable
     */
    public record Pending(String runId, String ownerSessionId, boolean backofficeRequested, long expiresAtMillis) {

        public boolean isExpired(long nowMillis) {
            return nowMillis >= expiresAtMillis;
        }
    }

    /**
     * Registers a request and returns the code to print, or null when the store is full or no free code
     * came up. Null is a refusal the caller reports as such, having created nothing.
     */
    public String register(String runId, String ownerSessionId, boolean backofficeRequested, long nowMillis) {
        if (runId == null || ownerSessionId == null)
            return null; // a request we could not deliver to, or could not bind: not worth holding
        if (pendingByCode.size() >= MAX_PENDING)
            return null;
        Pending entry = new Pending(runId, ownerSessionId, backofficeRequested, nowMillis + TTL_MILLIS);
        for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
            String code = newCode();
            // putIfAbsent, so two requests racing on the same code cannot both believe they hold it.
            if (pendingByCode.putIfAbsent(code, entry) == null)
                return code;
        }
        return null;
    }

    /** Why a code did not yield a request to mint from, or that it did. */
    public enum Outcome {
        /** The entry was live and is now this caller's alone. */
        READY,
        /** No such code, or one already used. */
        UNKNOWN,
        /** The code was real and its entry had lapsed — somebody typed it too late. */
        EXPIRED
    }

    /** What {@link #consume} found. {@code entry} is non-null only for {@link Outcome#READY}. */
    public record Consumed(Outcome outcome, Pending entry) {}

    /**
     * Atomically removes the entry for this code and says what it was.
     *
     * <p>Single-use is a property of this method rather than of the caller remembering to clean up:
     * {@code remove} is atomic, so exactly one approval can ever be told "you may mint for this request".
     *
     * <p><b>One call, because two could not tell the truth.</b> An earlier draft removed the entry here and
     * offered a separate "had it expired?" — which, called afterwards, always answered no, since this method
     * had just taken the entry away. Every too-late approval would have been recorded as a code that never
     * existed. The distinction exists only for the audit row, so it has to come out of the same atomic step
     * that resolves it.
     *
     * <p>{@link Outcome#EXPIRED} is for the record, <b>never for the reply</b>: the page must say the same
     * thing as for an unknown code, or it confirms to a guesser that a code was once real.
     */
    public Consumed consume(String typedCode, long nowMillis) {
        String code = normalise(typedCode);
        if (code == null)
            return new Consumed(Outcome.UNKNOWN, null);
        Pending entry = pendingByCode.remove(code);
        if (entry == null)
            return new Consumed(Outcome.UNKNOWN, null);
        // Not counted as an expiry: expiredCount is requests nobody acted on, and somebody acted on this one.
        return entry.isExpired(nowMillis) ? new Consumed(Outcome.EXPIRED, null)
                                          : new Consumed(Outcome.READY, entry);
    }

    /**
     * The live entry for this code WITHOUT consuming it, so the approval page can say what is being asked
     * for before anybody approves it. Null when unknown or lapsed — the page says the same thing for both.
     *
     * <p>It is not an oracle worth having: learning that a code is live costs exactly as much work as
     * guessing it, and the entry holds no identity to leak. Its caller refuses unauthenticated callers
     * anyway, so even that costs a working login first.
     */
    public Pending peek(String typedCode, long nowMillis) {
        String code = normalise(typedCode);
        if (code == null)
            return null;
        Pending entry = pendingByCode.get(code);
        return entry == null || entry.isExpired(nowMillis) ? null : entry;
    }

    /** Drops the entry for a request whose caller has gone away. Nothing is recorded: nobody decided anything. */
    public void drop(String typedCode) {
        String code = normalise(typedCode);
        if (code != null)
            pendingByCode.remove(code);
    }

    /** How many requests have lapsed unapproved since this server started — the number, never a row each. */
    public long expiredCount() {
        return expiredCount.get();
    }

    public int size() {
        return pendingByCode.size();
    }

    /** Removes what has lapsed, counting it. Returns how many went, for the check. */
    public int sweepExpired(long nowMillis) {
        int swept = 0;
        for (Map.Entry<String, Pending> entry : pendingByCode.entrySet()) {
            if (entry.getValue().isExpired(nowMillis) && pendingByCode.remove(entry.getKey(), entry.getValue())) {
                expiredCount.incrementAndGet();
                swept++;
            }
        }
        return swept;
    }

    /** {@code XXXX-XXXX} — the hyphen is for the eye only, and {@link #normalise} throws it away. */
    public static String format(String code) {
        return code == null || code.length() != CODE_LENGTH ? code
               : code.substring(0, 4) + "-" + code.substring(4);
    }

    /**
     * What the operator typed, reduced to the alphabet: upper-cased, with hyphens, spaces and anything else
     * removed. A wrong length is null rather than a lookup, so a stray character is refused cleanly instead
     * of silently shifting the rest into some other account's code.
     */
    static String normalise(String typedCode) {
        if (typedCode == null)
            return null;
        StringBuilder cleaned = new StringBuilder(CODE_LENGTH);
        for (char c : typedCode.toUpperCase().toCharArray())
            if (ALPHABET.indexOf(c) >= 0) {
                if (cleaned.length() == CODE_LENGTH)
                    return null; // too long: refuse rather than truncate to somebody else's code
                cleaned.append(c);
            }
        return cleaned.length() == CODE_LENGTH ? cleaned.toString() : null;
    }

    private String newCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++)
            code.append(ALPHABET.charAt(secureRandom.nextInt(ALPHABET.length())));
        return code.toString();
    }
}
