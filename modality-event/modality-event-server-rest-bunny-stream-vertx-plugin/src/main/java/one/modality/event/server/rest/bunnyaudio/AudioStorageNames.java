package one.modality.event.server.rest.bunnyaudio;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The names and folders an uploaded recording may be given in the Bunny storage zone — the rules, with no I/O,
 * so they can be checked on their own ({@code AudioStorageNamesCheck}).
 *
 * <p><b>The client proposes, this decides.</b> Staff keep the recording team's own file names (David's choice,
 * 2026-10-20), so the name a file lands under comes from the browser. What the browser cannot do is choose
 * WHERE: the folder is the event's, read from the database, and the name is reduced here to a single safe
 * segment — so a name can neither climb out of that folder nor collide with something it should not by way of
 * a character the CDN treats differently. The back office shows the name this returns (the prepare reply
 * carries it); it only keeps a renamed file's own extension ({@code audio-file-name.ts}), since the format is
 * read from the name.
 *
 * <p>The safe set is what the zone already holds: letters, digits, space and {@code . _ - ( )}. Spaces stay
 * because existing names use them ({@code 01_2025_MKMC-EC_..._01. Introduction.mp3}); accents are folded to
 * their base letter; apostrophes are dropped so {@code Heart's} reads {@code Hearts} rather than
 * {@code Heart_s}; anything else becomes an underscore.
 */
public final class AudioStorageNames {

    private AudioStorageNames() {
    }

    /** The language placeholder of a folder: replaced per file by {@link #languageFolder}. */
    public static final String LANG_TOKEN = "{lang}";
    /** Pattern tokens resolved from the event when its folder is first set. */
    public static final String YEAR_TOKEN = "{year}", MONTH_TOKEN = "{month}", EVENT_TOKEN = "{event}";

    /** Longest folder path accepted — the column is varchar(512). */
    public static final int MAX_FOLDER_LENGTH = 512;
    /** Deepest folder accepted; the zone's deepest real path is four levels. */
    public static final int MAX_FOLDER_DEPTH = 10;
    /** Longest file name kept, extension included. */
    public static final int MAX_FILE_NAME_LENGTH = 160;

    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9 ._()\\-]+");
    private static final Pattern TOKEN = Pattern.compile("\\{(year|month|event|lang)}");
    private static final Pattern NOT_SAFE = Pattern.compile("[^A-Za-z0-9 ._()\\-]");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern APOSTROPHES = Pattern.compile("['’‘`]");
    private static final Pattern REPEATED_UNDERSCORES = Pattern.compile("_{2,}");
    private static final Pattern REPEATED_SPACES = Pattern.compile(" {2,}");
    private static final Pattern AUDIO_EXTENSION = Pattern.compile("(?i)\\.(mp3|m4a|mp4|aac|m4b)$");
    private static final Pattern ISO_639_3 = Pattern.compile("[a-z]{3}");
    private static final Pattern CODE_SUFFIX = Pattern.compile("[a-z]{2,8}");

    // ─── File names ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The name a file is stored under: {@code proposed} reduced to the safe set, its audio extension replaced
     * by {@code extension} (the one its bytes were recognised as), never empty and never a hidden or dot name.
     *
     * @param proposed  the name the browser sent — the original file name, or what staff renamed it to
     * @param extension {@code "mp3"} or {@code "m4a"}
     */
    public static String cleanFileName(String proposed, String extension) {
        String stem = proposed == null ? "" : proposed.trim();
        stem = AUDIO_EXTENSION.matcher(stem).replaceAll("");
        stem = cleanText(stem);
        String suffix = "." + extension.toLowerCase(Locale.ROOT);
        int maxStem = MAX_FILE_NAME_LENGTH - suffix.length();
        if (stem.length() > maxStem)
            stem = trimEnds(stem.substring(0, maxStem));
        if (stem.isEmpty())
            stem = "recording";
        return stem + suffix;
    }

    /** The extension a proposed name declares, lower case, or null when it declares none we accept. */
    public static String declaredExtension(String proposed) {
        if (proposed == null)
            return null;
        String lower = proposed.trim().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp3"))
            return "mp3";
        if (lower.endsWith(".m4a"))
            return "m4a";
        return null;
    }

    /** Text reduced to the safe set — shared by file names and the {@code {event}} token. */
    static String cleanText(String text) {
        String folded = COMBINING_MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        folded = APOSTROPHES.matcher(folded).replaceAll("");
        folded = NOT_SAFE.matcher(folded).replaceAll("_");
        folded = REPEATED_UNDERSCORES.matcher(folded).replaceAll("_");
        folded = REPEATED_SPACES.matcher(folded).replaceAll(" ");
        return trimEnds(folded);
    }

    /** No leading or trailing space, dot or underscore — a leading dot would make a hidden or relative name. */
    private static String trimEnds(String s) {
        int start = 0, end = s.length();
        while (start < end && " ._".indexOf(s.charAt(start)) >= 0) start++;
        while (end > start && " ._".indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(start, end);
    }

    // ─── Folders ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * A folder or pattern as staff typed or picked it, normalised — or null when it is not acceptable.
     *
     * <p>Relative (leading and trailing slashes dropped, backslashes read as slashes), no empty, {@code .} or
     * {@code ..} segment, at most {@link #MAX_FOLDER_DEPTH} deep and {@link #MAX_FOLDER_LENGTH} long, and every
     * segment either of the safe set or containing only the tokens a folder may carry. {@code allowPatternTokens}
     * is false for an event's own folder, where only {@code {lang}} may remain.
     */
    public static String normaliseFolder(String folder, boolean allowPatternTokens) {
        if (folder == null)
            return null;
        String path = folder.trim().replace('\\', '/');
        while (path.startsWith("/")) path = path.substring(1);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isEmpty() || path.length() > MAX_FOLDER_LENGTH)
            return null;
        String[] segments = path.split("/", -1);
        if (segments.length > MAX_FOLDER_DEPTH)
            return null;
        List<String> kept = new ArrayList<>(segments.length);
        for (String raw : segments) {
            String segment = raw.trim();
            if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
                return null;
            String withoutTokens = TOKEN.matcher(segment).replaceAll(m ->
                allowPatternTokens || m.group(1).equals("lang") ? "x" : "\u0000");
            if (!SAFE_SEGMENT.matcher(withoutTokens).matches() || withoutTokens.equals(".") || withoutTokens.equals(".."))
                return null;
            kept.add(segment);
        }
        return String.join("/", kept);
    }

    /**
     * An event's folder from its type's pattern: {@code {year}}, {@code {month}} and {@code {event}} filled in,
     * {@code {lang}} left for {@link #folderForLanguage}. Null when the result is not a valid folder (an event
     * name made only of unsafe characters, say).
     */
    public static String resolvePattern(String pattern, int year, int month, String eventName) {
        if (pattern == null)
            return null;
        String eventSegment = cleanText(eventName == null ? "" : eventName);
        String resolved = pattern
            .replace(YEAR_TOKEN, String.valueOf(year))
            .replace(MONTH_TOKEN, month < 10 ? "0" + month : String.valueOf(month))
            .replace(EVENT_TOKEN, eventSegment.isEmpty() ? "event" : eventSegment);
        return normaliseFolder(resolved, false);
    }

    /** Whether files are split into one subfolder per language. */
    public static boolean hasLanguageSubfolder(String folder) {
        return folder != null && folder.contains(LANG_TOKEN);
    }

    /** The folder one language's files go in: {@code {lang}} replaced by that language's folder. */
    public static String folderForLanguage(String eventFolder, String languageFolder) {
        return eventFolder.replace(LANG_TOKEN, languageFolder);
    }

    /**
     * The folder name of a recording language, as the zone already spells them: ISO 639-3 ({@code eng fra deu
     * spa por ita vie ell jpn}).
     *
     * <p>Mandarin and Cantonese are named from the item code, not the language row: Mandarin's item has no
     * language codes at all, and Cantonese's points at generic Chinese ({@code zho}), while the zone holds
     * {@code cmn} and {@code yue}. Otherwise the language's ISO 639-3 code, else the item code's suffix
     * ({@code audio-xx}), else {@code audio}.
     */
    public static String languageFolder(String itemCode, String iso6393) {
        String code = itemCode == null ? "" : itemCode.trim().toLowerCase(Locale.ROOT);
        if (code.endsWith("zhca"))
            return "yue";
        if (code.endsWith("zhma"))
            return "cmn";
        String iso = iso6393 == null ? "" : iso6393.trim().toLowerCase(Locale.ROOT);
        if (ISO_639_3.matcher(iso).matches())
            return iso;
        int dash = code.lastIndexOf('-');
        String suffix = dash >= 0 ? code.substring(dash + 1) : code;
        return CODE_SUFFIX.matcher(suffix).matches() ? suffix : "audio";
    }

    /**
     * A pattern learnt from where an earlier event of the same type put its files: its year segment becomes
     * {@code {year}} and a trailing three-letter language folder {@code {lang}}.
     * {@code international-festivals/2025/summer/eng} gives {@code international-festivals/{year}/summer/{lang}}.
     * Only a suggestion — the back office shows it for staff to accept or correct.
     */
    public static String patternFromEarlierFolder(String folder, int earlierEventYear) {
        String normalised = normaliseFolder(folder, false);
        if (normalised == null)
            return null;
        String[] segments = normalised.split("/");
        String year = String.valueOf(earlierEventYear);
        for (int i = 0; i < segments.length; i++)
            if (segments[i].equals(year))
                segments[i] = YEAR_TOKEN;
        int last = segments.length - 1;
        if (last > 0 && ISO_639_3.matcher(segments[last]).matches())
            segments[last] = LANG_TOKEN;
        return String.join("/", segments);
    }

    // ─── URLs ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * A storage path as it appears in a URL: each segment percent-encoded, slashes kept. Only the space needs
     * it in the safe set, but anything else is encoded too, so a folder read back from the zone cannot break
     * out of its segment.
     */
    public static String encodePath(String path) {
        StringBuilder sb = new StringBuilder(path.length() + 16);
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '/' || c == '-' || c == '_' || c == '.' || c == '~' || c == '(' || c == ')';
            if (plain)
                sb.append((char) c);
            else
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                    .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
        }
        return sb.toString();
    }

    private static final Pattern URL_PARTS = Pattern.compile("^[A-Za-z][A-Za-z0-9+.\\-]*://([^/\\\\?#]+)(.*)$");

    /**
     * The path in the zone that {@code url} names, its case kept — or null when the address is not on one of
     * the zone's {@code hostnames} (lower case), or names no file at all.
     *
     * <p>Every spelling a hand-typed address can give one file comes to the same path, read the way a browser
     * reads it before the request goes out: the scheme, any user info and port are dropped once the host is
     * known to serve the zone; the query and fragment end the path where they are written (an encoded {@code ?}
     * is part of a name); the path is percent-decoded, backslashes read as slashes, empty segments dropped and
     * {@code .}/{@code ..} resolved. Comparing these paths — not the address text — is what lets "another
     * recording still plays this file" be answered whatever way that recording's address was written. A
     * segment's spaces are kept: {@code "folder /a.mp3"} is another file than {@code "folder/a.mp3"}.
     */
    public static String zonePath(String url, Collection<String> hostnames) {
        if (url == null)
            return null;
        Matcher parts = URL_PARTS.matcher(url.trim());
        if (!parts.matches())
            return null;
        String host = decodeUrl(parts.group(1)).toLowerCase(Locale.ROOT);
        int at = host.lastIndexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        if (!hostnames.contains(host))
            return null;
        String rest = parts.group(2);
        for (char end : new char[] { '?', '#' }) {
            int cut = rest.indexOf(end);
            if (cut >= 0) rest = rest.substring(0, cut);
        }
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : decodeUrl(rest).replace('\\', '/').split("/")) {
            if (segment.isEmpty() || segment.equals("."))
                continue;
            if (segment.equals("..")) {
                segments.pollLast();
                continue;
            }
            segments.addLast(segment);
        }
        return segments.isEmpty() ? null : String.join("/", segments);
    }

    /** {@link #zonePath} folded for comparison; null under the same conditions. */
    public static String zonePathKey(String url, Collection<String> hostnames) {
        String path = zonePath(url, hostnames);
        return path == null ? null : path.toLowerCase(Locale.ROOT);
    }

    /**
     * A URL with its percent-encoding undone, for comparing addresses that were typed by hand ({@code %20} in
     * one, a raw space or {@code %28} in another) with the ones built here. {@code +} is kept as a plus: it is
     * a literal in a path, not a space.
     */
    public static String decodeUrl(String url) {
        if (url == null)
            return null;
        try {
            return URLDecoder.decode(url.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return url;
        }
    }
}
