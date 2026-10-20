package one.modality.event.server.rest.bunnyaudio;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Check for the upload gate: which bytes are accepted as audio, how a ticket is spent and lapses, which
 * hosts a zone answers for — and that neither a ticket nor a zone ever prints its secret.
 *
 * <p>No test framework, as {@link AudioStorageNamesCheck} explains. Run by
 * {@code scripts/checks/bunny-audio/run.sh}.
 */
public class AudioUploadChecks {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) b[i] = (byte) values[i];
        return b;
    }

    static byte[] ftyp(String brand) {
        byte[] b = new byte[12];
        byte[] box = ("....ftyp" + brand).getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(box, 0, b, 0, 12);
        return b;
    }

    public static void main(String[] args) {
        System.out.println("AudioUploadChecks");

        // --- the sniffer ---
        check("an ID3v2 tag is MP3", "mp3".equals(AudioFileSniffer.detect("ID3\u0004\u0000".getBytes(StandardCharsets.ISO_8859_1), 5)));
        check("a bare MPEG-1 layer III frame is MP3", "mp3".equals(AudioFileSniffer.detect(bytes(0xFF, 0xFB, 0x90, 0x64), 4)));
        check("a frame sync with the reserved layer is not", AudioFileSniffer.detect(bytes(0xFF, 0xE0, 0x00, 0x00), 4) == null);
        check("an M4A brand is M4A", "m4a".equals(AudioFileSniffer.detect(ftyp("M4A "), 12)));
        check("an mp42 brand is M4A", "m4a".equals(AudioFileSniffer.detect(ftyp("mp42"), 12)));
        check("a QuickTime brand is not", AudioFileSniffer.detect(ftyp("qt  "), 12) == null);
        check("a WAV file is not", AudioFileSniffer.detect("RIFF....WAVE".getBytes(StandardCharsets.ISO_8859_1), 12) == null);
        check("a PDF renamed .mp3 is not", AudioFileSniffer.detect("%PDF-1.7\n...".getBytes(StandardCharsets.ISO_8859_1), 12) == null);
        check("an empty file is not", AudioFileSniffer.detect(new byte[12], 0) == null);
        check("one byte is not", AudioFileSniffer.detect(bytes(0xFF), 1) == null);

        // --- tickets ---
        Instant start = Instant.parse("2026-10-20T10:00:00Z");
        AudioUploadTickets.useClock(Clock.fixed(start, ZoneOffset.UTC));
        BunnyStorageClient.Zone zone = new BunnyStorageClient.Zone("test-zone", "ZONE-PASSWORD-SECRET", "storage.bunnycdn.com", "test-zone.b-cdn.net",
            List.of("test-zone.b-cdn.net"));
        String token = AudioUploadTickets.issue(42, 1857, 1, "ittp/2026/eng/01.mp3", "mp3", zone, "ACCOUNT-KEY-SECRET");
        check("a ticket is long and URL-safe", token.length() >= 43 && token.matches("[A-Za-z0-9_-]+"));
        AudioUploadTickets.Ticket ticket = AudioUploadTickets.consume(token);
        check("it is spent once", ticket != null && ticket.slotId() == 42 && "ittp/2026/eng/01.mp3".equals(ticket.storagePath()));
        check("and only once", AudioUploadTickets.consume(token) == null);
        check("an unknown ticket is nothing", AudioUploadTickets.consume("not-a-ticket") == null && AudioUploadTickets.consume(null) == null);

        String late = AudioUploadTickets.issue(43, 1857, 1, "ittp/2026/eng/02.mp3", "mp3", zone, "ACCOUNT-KEY-SECRET");
        AudioUploadTickets.useClock(Clock.fixed(start.plus(AudioUploadTickets.LIFETIME).plus(Duration.ofSeconds(1)), ZoneOffset.UTC));
        check("a lapsed ticket cannot be spent", AudioUploadTickets.consume(late) == null);

        check("a ticket never prints the account key or the zone password",
            !ticket.toString().contains("SECRET"));
        check("a zone never prints its password", !zone.toString().contains("SECRET"));
        check("a zone's public address is encoded",
            "https://test-zone.b-cdn.net/sttp/a%20b/01.mp3".equals(zone.cdnUrl("sttp/a b/01.mp3")));
        check("a zone's storage address names the zone",
            "https://storage.bunnycdn.com/test-zone/sttp/01.mp3".equals(zone.storageUrl("sttp/01.mp3")));
        check("a zone serves its hostnames, whatever their case", zone.servesHost("Test-Zone.B-CDN.net") && !zone.servesHost("other.b-cdn.net"));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}
