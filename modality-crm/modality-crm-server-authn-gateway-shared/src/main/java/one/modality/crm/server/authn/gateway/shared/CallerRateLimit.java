package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.console.Console;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * How many times a door that anyone may knock on answers, per caller address and in total.
 *
 * <p>For the endpoints that must work for somebody NOT signed in and that each answer says something about a
 * person: "does this address have an account?", "send a recovery mail to this address". Nothing else on the bus
 * throttles (see {@link MagicLinkService}'s guessing cap), so without this each was an oracle at line speed — the
 * first lets a caller test a list of addresses for membership, the second lets one fill a stranger's inbox.
 *
 * <p>Two budgets, for two attackers. The per-address budget stops one machine; it is keyed on the address the
 * bus bridge stamps ({@code ThreadLocalStateHolder.getClientAddress()}), which the client cannot choose. The
 * global budget stops many machines, at the price that a large enough botnet can spend it and refuse everybody
 * for the rest of the window — the trade taken knowingly: a door that closes for ten minutes beats an oracle that
 * never does. A call refused on its address does NOT spend the global budget, or one machine could spend it.
 *
 * <p>Fixed windows, kept in memory: the server runs as a single task, and a restart merely forgets counts that
 * expire within minutes anyway. The per-address map cannot outgrow the global budget, since only admitted calls
 * add to it. Addresses are personal data, so they are counted and never logged.
 *
 * @author Claude Code
 */
public final class CallerRateLimit {

    /** "Does this address have an account?" — the booking and sign-up forms ask once per address typed. */
    public static final CallerRateLimit EMAIL_LOOKUP = new CallerRateLimit("email lookup", Duration.ofMinutes(10), 30, 1000, Clock.systemUTC());

    /**
     * Password recovery: each admitted call sends a mail, to an address the caller typed. Also spent by the
     * public-talk form's code sign-in and by staff recovery, which share the gateway — so the global ceiling is set
     * well above real traffic, leaving the per-address budget as the abuse control; the ceiling only stops a crowd.
     */
    public static final CallerRateLimit RECOVERY_MAIL = new CallerRateLimit("recovery mail", Duration.ofMinutes(10), 5, 1000, Clock.systemUTC());

    /** The address a call is counted under when the bridge could not read one: all such calls share it. */
    static final String UNKNOWN_ADDRESS = "";

    private final String name;
    private final Duration window;
    private final int perAddress;
    private final int global;
    private final Clock clock;

    private final Map<String, Integer> counts = new HashMap<>();
    private Instant windowStart;
    private int globalCount;
    private boolean globalRefusalLogged;

    /** Public for the checks, which need their own clock; the server uses the two instances above. */
    public CallerRateLimit(String name, Duration window, int perAddress, int global, Clock clock) {
        this.name = name;
        this.window = window;
        this.perAddress = perAddress;
        this.global = global;
        this.clock = clock;
    }

    /**
     * Counts one call from {@code clientAddress} and says whether it may go ahead.
     *
     * @param clientAddress the caller's address as the bridge stamped it; null shares one budget with every other
     *                      unknown caller, rather than escaping the count
     */
    public synchronized boolean tryAcquire(String clientAddress) {
        Instant now = clock.instant();
        if (windowStart == null || !now.isBefore(windowStart.plus(window))) {
            windowStart = now;
            counts.clear();
            globalCount = 0;
            globalRefusalLogged = false;
        }
        String key = keyOf(clientAddress);
        int used = counts.getOrDefault(key, 0);
        if (used >= perAddress)
            return false;
        if (globalCount >= global) {
            if (!globalRefusalLogged) { // once per window: the number that matters is that it happened
                globalRefusalLogged = true;
                try {
                    Console.log("🛡 Rate limit reached for " + name + ": " + global + " calls in " + window.toMinutes()
                                + " min from all callers; refusing everybody until the window ends");
                } catch (RuntimeException ignored) { // a log that cannot be written must not turn a refusal into a crash
                }
            }
            return false;
        }
        counts.put(key, used + 1);
        globalCount++;
        return true;
    }

    /**
     * What a caller is counted under: an IPv4 address as it is, an IPv6 address by its first four groups (its /64).
     * One machine is normally handed a whole /64 and may pick a fresh address in it for every call, so counting
     * full IPv6 addresses would give it an unlimited per-address budget. Pure.
     */
    public static String keyOf(String clientAddress) {
        if (clientAddress == null || clientAddress.isEmpty())
            return UNKNOWN_ADDRESS;
        if (clientAddress.indexOf(':') < 0)
            return clientAddress;
        String address = clientAddress.toLowerCase();
        if (address.startsWith("[") && address.endsWith("]"))
            address = address.substring(1, address.length() - 1);
        int zone = address.indexOf('%');
        if (zone >= 0)
            address = address.substring(0, zone);
        // Expand "::" so the first four groups are the real ones, whatever was compressed.
        String[] groups;
        int gap = address.indexOf("::");
        if (gap >= 0) {
            String[] head = address.substring(0, gap).isEmpty() ? new String[0] : address.substring(0, gap).split(":");
            String tailText = address.substring(gap + 2);
            String[] tail = tailText.isEmpty() ? new String[0] : tailText.split(":");
            groups = new String[8];
            java.util.Arrays.fill(groups, "0");
            System.arraycopy(head, 0, groups, 0, Math.min(head.length, 8));
            for (int i = 0; i < tail.length && 8 - tail.length + i >= 0; i++)
                groups[8 - tail.length + i] = tail[i];
        } else
            groups = address.split(":");
        if (groups.length < 4)
            return address;
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            String group = groups[i].replaceFirst("^0+(?=.)", "");
            prefix.append(group).append(':');
        }
        return prefix.append(":/64").toString();
    }
}
