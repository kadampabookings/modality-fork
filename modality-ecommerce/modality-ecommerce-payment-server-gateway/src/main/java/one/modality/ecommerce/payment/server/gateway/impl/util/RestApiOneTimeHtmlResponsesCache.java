package one.modality.ecommerce.payment.server.gateway.impl.util;

import dev.webfx.platform.console.Console;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Holds the payment form HTML that a gateway has just built, for the few seconds between
 * initiatePayment() and the payer's browser fetching it from the gateway's paymentForm endpoint (which it does when
 * the form can't be passed as direct HTML content — typically when it's loaded in an iFrame through https).
 *
 * The content is sensitive: it embeds the gateway's client secret for that payment, and the payer's billing name,
 * email, phone and address. So the cache is one-time (the entry goes on the first fetch), short-lived (an entry
 * nobody fetched is dropped, see ENTRY_LIFETIME_MILLIS), and the key that names it is a secret in its own right
 * (see generateKey()) — it is the only thing protecting the content, as the endpoint serving it has no login.
 *
 * It is deliberately in memory only. It used to write each entry to ~/.modality/payment-cache as well, which was
 * added in July 2025 while investigating forms failing to load on AWS (the cause turned out to be client-side, and
 * was fixed there). That file cache could not do what it looked like it did: on ECS a server restart replaces the
 * container, so the directory comes back empty, and the one case where the in-memory map does miss — a payment
 * initiated on the blue task whose form is fetched after the swap to green — is a different container too. What it
 * did do is leave client secrets and payer details on disk, out of reach of the anonymisation and erasure tooling.
 *
 * @author Bruno Salmon
 */
public final class RestApiOneTimeHtmlResponsesCache {

    private static final Map<String, CacheEntry> ONE_TIME_HTML_RESPONSES = new ConcurrentHashMap<>();

    // The browser fetches the form seconds after it is registered. Anything still here long after that was abandoned
    // (closed tab, blocked iFrame, or a gateway flow that redirected instead of embedding). Past this age an entry is
    // never served again, and it is dropped from memory by the next registration — there is no timer, so on a quiet
    // server the last abandoned entry of the day stays in the heap until the next payment (or the next deployment).
    private static final long ENTRY_LIFETIME_MILLIS = 10 * 60 * 1000; // 10 minutes

    // The keys are minted by generateKey(), and the gateways hand back what we gave them, so a key can only ever be
    // one 22-char url-safe base64 segment. Checking that here keeps malformed input out of the map and out of the
    // logs, and it is what a file path (were one ever reintroduced here) would need before being built from a key
    // that reaches us straight from the request path.
    private static final Pattern VALID_KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]{22}");

    // The key is the ONLY thing protecting the cached content. Uuid.randomUuid(), which the gateways used to mint it
    // with, is Math.random(): a 48-bit LCG on the JVM, whose whole output stream follows from a couple of observed
    // values — so an attacker could start a payment of their own, read their key, recover the state and predict the
    // keys minted for everybody else. This is the same reasoning that removed that helper from the magic-link token
    // (see GuestBookingAccessService). These gateways are server-only, so SecureRandom is available here.
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private record CacheEntry(String html, long expiryTime) {
        boolean hasExpired(long now) {
            return now > expiryTime;
        }
    }

    public static String generateKey() {
        byte[] bytes = new byte[16]; // 128 bits, encoded as 22 url-safe base64 chars
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean isValidKey(String key) {
        return key != null && VALID_KEY_PATTERN.matcher(key).matches();
    }

    // For the logs: enough to correlate a set with its get, far too little to reconstruct the key
    private static String logRef(String key) {
        return key == null ? "null" : key.length() < 6 ? "(malformed)" : key.substring(0, 6) + "…";
    }

    public static void registerOneTimeHtmlResponse(String key, String value) {
        Console.log("[PAYMENT] Cache set with key: " + logRef(key));
        if (!isValidKey(key)) { // can't happen with generateKey(), but a caller minting its own would otherwise store
            Console.log("[PAYMENT] Cache set with an invalid key -> refused (keys must come from generateKey())");
            return; // an entry the endpoint could never fetch back, showing up as a payment form that won't load
        }
        long now = System.currentTimeMillis();
        // Dropping whatever nobody came back for. Done on registration rather than on a timer: these entries arrive
        // one per payment, so this runs rarely and on a map that holds the payments currently in flight.
        ONE_TIME_HTML_RESPONSES.values().removeIf(entry -> entry.hasExpired(now));
        ONE_TIME_HTML_RESPONSES.put(key, new CacheEntry(value, now + ENTRY_LIFETIME_MILLIS));
    }

    public static String getOneTimeHtmlResponse(String key) {
        // The key comes from the request path, so it is checked before being used, and never logged in full: it is a
        // secret (see generateKey), and an invalid one could otherwise carry line breaks into the logs.
        if (!isValidKey(key)) {
            Console.log("[PAYMENT] Cache requested with an invalid key -> rejected");
            return null;
        }
        CacheEntry entry = ONE_TIME_HTML_RESPONSES.remove(key); // removed on request (one-time cache)
        String result = entry == null || entry.hasExpired(System.currentTimeMillis()) ? null : entry.html();
        Console.log("[PAYMENT] Cache requested with key: " + logRef(key) + " -> " + (result != null ? "found" : "not found"));
        return result;
    }
}
