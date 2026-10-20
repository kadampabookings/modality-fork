package one.modality.event.server.rest.bunnyaudio;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The permission to upload ONE file to ONE place, handed out by {@code prepareAudioUploads} and spent by
 * {@code POST /rest/audio/upload}.
 *
 * <p><b>Why a ticket.</b> The upload is an HTTP request, because the file can be hundreds of megabytes and the
 * event bus is no place for it — and no {@code /rest} route here can tell who is calling (the bus session is
 * not on the HTTP path). So the authorisation happens where it can: on the bus, where
 * {@code RouteAccessGuard} checks that the caller may reach {@code /medias} for THAT event's organisation.
 * What it authorises is written into the ticket — the slot, the storage path, the zone — and the upload
 * request can do nothing else with it. The same shape as the TUS signature the video upload hands out, minus
 * the third party.
 *
 * <p>A ticket is 256 random bits, travels in a header (never a URL, where it would reach logs and history),
 * is spent on first use whatever the outcome, and lapses after {@link #LIFETIME}. Held in memory: this server
 * runs one instance, and a restart costs an in-flight upload no more than a retry.
 */
public final class AudioUploadTickets {

    /** Long enough for a queue of bulk uploads on a slow venue connection to reach its last file. */
    static final Duration LIFETIME = Duration.ofHours(8);

    /** What a ticket lets its holder do. */
    public record Ticket(
        long slotId,
        Object eventId,
        Object organizationId,
        /** Folder and name in the zone, unencoded: {@code international-festivals/2026/summer/fra/01_Intro.mp3}. */
        String storagePath,
        /** The format the name declared, which the bytes must turn out to be. */
        String extension,
        BunnyStorageClient.Zone zone,
        String accountApiKey,
        Instant expiresAt) {

        /** The generated record string would print the account key. */
        @Override
        public String toString() {
            return "Ticket[slot=" + slotId + ", path=" + storagePath + "]";
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Ticket> TICKETS = new ConcurrentHashMap<>();
    private static Clock clock = Clock.systemUTC();

    private AudioUploadTickets() {
    }

    /** A new ticket for this upload; the returned string is what the browser sends back. */
    static String issue(long slotId, Object eventId, Object organizationId, String storagePath, String extension,
                        BunnyStorageClient.Zone zone, String accountApiKey) {
        dropExpired();
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        TICKETS.put(token, new Ticket(slotId, eventId, organizationId, storagePath, extension, zone, accountApiKey,
            clock.instant().plus(LIFETIME)));
        return token;
    }

    /**
     * The ticket, spent: a second request with the same token finds nothing. Null when unknown or lapsed. Asked
     * before the body is read, so a request without a valid ticket is turned away before it can fill the disk.
     */
    static Ticket consume(String token) {
        if (token == null)
            return null;
        Ticket ticket = TICKETS.remove(token);
        return ticket != null && clock.instant().isBefore(ticket.expiresAt()) ? ticket : null;
    }

    private static void dropExpired() {
        Instant now = clock.instant();
        TICKETS.values().removeIf(ticket -> !now.isBefore(ticket.expiresAt()));
    }

    /** For {@code AudioUploadTicketsCheck}: move time without waiting for it. */
    static void useClock(Clock testClock) {
        clock = testClock;
    }
}
