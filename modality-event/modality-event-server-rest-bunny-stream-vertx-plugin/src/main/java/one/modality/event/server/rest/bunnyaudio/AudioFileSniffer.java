package one.modality.event.server.rest.bunnyaudio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What an uploaded file IS, from its first bytes — never from the name or the content type the browser sent,
 * both of which are the caller's to choose ({@code code-review-checklist.md} § New Surfaces: uploads are
 * validated on the server).
 *
 * <p>Two formats are accepted, the ones the front-office player and download link handle everywhere:
 * <ul>
 *   <li><b>MP3</b> — an ID3v2 tag ({@code "ID3"}), or no tag and an MPEG audio frame straight away (eleven sync
 *       bits set, then a layer field that is not the reserved {@code 00});</li>
 *   <li><b>M4A</b> — an ISO base-media file whose {@code ftyp} box names an audio-capable brand.</li>
 * </ul>
 * Anything else — a WAV, a video, a document renamed {@code .mp3} — is refused.
 */
public final class AudioFileSniffer {

    private AudioFileSniffer() {
    }

    /** Bytes read from the start of the file; enough for the ftyp box's major brand. */
    static final int HEAD_LENGTH = 12;

    private static final String[] M4A_BRANDS = { "M4A ", "M4B ", "mp42", "mp41", "isom", "iso2", "dash" };

    /** The format of the file at {@code path}: {@code "mp3"}, {@code "m4a"}, or null when it is neither. */
    public static String detect(Path path) throws IOException {
        byte[] head = new byte[HEAD_LENGTH];
        int read;
        try (InputStream in = Files.newInputStream(path)) {
            read = in.readNBytes(head, 0, HEAD_LENGTH);
        }
        return detect(head, read);
    }

    /** The format of a file starting with the first {@code length} bytes of {@code head}. */
    public static String detect(byte[] head, int length) {
        if (length >= 3 && head[0] == 'I' && head[1] == 'D' && head[2] == '3')
            return "mp3";
        if (length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0 && (head[1] & 0x06) != 0)
            return "mp3";
        if (length >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            String brand = new String(head, 8, 4, StandardCharsets.ISO_8859_1);
            for (String accepted : M4A_BRANDS)
                if (accepted.equals(brand))
                    return "m4a";
        }
        return null;
    }
}
