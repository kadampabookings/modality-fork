package one.modality.event.server.rest.bunnyaudio;

import java.util.List;

/**
 * Check for the naming rules of uploaded recordings: what a proposed file name becomes, which folders are
 * accepted, how patterns resolve, and which folder each recording language gets — against the names and
 * folders the NKT audio zone actually holds (staging media rows and David's Bunny screenshots, 2026-10-20).
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from main() and exits non-zero on
 * failure, following {@code PersonMergeCheck}. Run by {@code scripts/checks/bunny-audio/run.sh}.
 */
public class AudioStorageNamesCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    static void same(String what, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        check(what + (ok ? "" : " — expected <" + expected + "> got <" + actual + ">"), ok);
    }

    public static void main(String[] args) {
        System.out.println("AudioStorageNamesCheck");

        // --- file names: the recording team's own names survive ---
        same("an NKT festival name is kept", "01_2026_Suf_w1_01.Intro_GenRabten.mp3",
            AudioStorageNames.cleanFileName("01_2026_Suf_w1_01.Intro_GenRabten.mp3", "mp3"));
        same("spaces and a dot-space are kept (mkmc-events names use them)",
            "01_2025_MKMC-EC_PureMind-PureWorld_GenlaDekyong_01. Introduction.mp3",
            AudioStorageNames.cleanFileName("01_2025_MKMC-EC_PureMind-PureWorld_GenlaDekyong_01. Introduction.mp3", "mp3"));
        same("accents are folded and the extension lower-cased", "Meditation guidee.mp3",
            AudioStorageNames.cleanFileName("Méditation guidée.MP3", "mp3"));
        same("an apostrophe is dropped, not turned into an underscore", "Hearts Jewel.m4a",
            AudioStorageNames.cleanFileName("Heart's Jewel.m4a", "m4a"));
        same("the extension follows what the bytes are", "teaching.mp3",
            AudioStorageNames.cleanFileName("teaching.m4a", "mp3"));
        same("a name without an extension gets one", "Commentary 3.mp3",
            AudioStorageNames.cleanFileName("Commentary 3", "mp3"));

        // --- file names: nothing can climb out of the folder or hide ---
        same("a path in the name stays one segment", "etc_passwd.mp3",
            AudioStorageNames.cleanFileName("../../etc/passwd.mp3", "mp3"));
        same("a backslash path too", "windows_system32.mp3",
            AudioStorageNames.cleanFileName("..\\windows\\system32.mp3", "mp3"));
        same("a dot name becomes a real name", "recording.mp3", AudioStorageNames.cleanFileName(".mp3", "mp3"));
        same("an empty name becomes a real name", "recording.mp3", AudioStorageNames.cleanFileName("  ", "mp3"));
        same("a null name becomes a real name", "recording.m4a", AudioStorageNames.cleanFileName(null, "m4a"));
        same("URL and shell characters are replaced", "a_b_c_d.mp3",
            AudioStorageNames.cleanFileName("a?b#c%d.mp3", "mp3"));
        String longName = AudioStorageNames.cleanFileName("x".repeat(400) + ".mp3", "mp3");
        check("a long name is capped at " + AudioStorageNames.MAX_FILE_NAME_LENGTH + " characters",
            longName.length() <= AudioStorageNames.MAX_FILE_NAME_LENGTH && longName.endsWith(".mp3"));

        same("declared extension mp3", "mp3", AudioStorageNames.declaredExtension("A.Mp3"));
        same("declared extension m4a", "m4a", AudioStorageNames.declaredExtension("a.m4a"));
        same("no accepted extension", null, AudioStorageNames.declaredExtension("a.wav"));

        // --- folders ---
        same("a festival folder with its language token", "international-festivals/2026/summer/{lang}",
            AudioStorageNames.normaliseFolder("international-festivals/2026/summer/{lang}", false));
        same("slashes trimmed, backslashes read as slashes", "ittp/2026",
            AudioStorageNames.normaliseFolder("  /ittp\\2026/ ", false));
        same("a hand-named folder with spaces", "mkmc-events/2025/2025-11 - Vajrasattva Empowerment - Pure Mind Pure World - Gen La Dekyong",
            AudioStorageNames.normaliseFolder("mkmc-events/2025/2025-11 - Vajrasattva Empowerment - Pure Mind Pure World - Gen La Dekyong", false));
        same("'..' refused", null, AudioStorageNames.normaliseFolder("sttp/../ittp", false));
        same("'.' refused", null, AudioStorageNames.normaliseFolder("./sttp", false));
        same("an empty segment refused", null, AudioStorageNames.normaliseFolder("sttp//course", false));
        same("an unsafe character refused", null, AudioStorageNames.normaliseFolder("sttp/a&b", false));
        same("an empty folder refused", null, AudioStorageNames.normaliseFolder(" / ", false));
        same("pattern tokens refused in an event's folder", null,
            AudioStorageNames.normaliseFolder("international-festivals/{year}/summer", false));
        same("pattern tokens accepted in a pattern", "international-festivals/{year}/summer/{lang}",
            AudioStorageNames.normaliseFolder("international-festivals/{year}/summer/{lang}", true));
        same("an unknown token refused", null, AudioStorageNames.normaliseFolder("a/{teacher}", true));
        same("too deep refused", null, AudioStorageNames.normaliseFolder("a/b/c/d/e/f/g/h/i/j/k", false));
        same("too long refused", null, AudioStorageNames.normaliseFolder("a".repeat(513), false));

        // --- patterns ---
        same("a festival pattern resolves", "international-festivals/2026/summer/{lang}",
            AudioStorageNames.resolvePattern("international-festivals/{year}/summer/{lang}", 2026, 7, "Summer Festival 2026"));
        same("an mkmc-events pattern resolves with month and name",
            "mkmc-events/2025/2025-11 - Vajrasattva Empowerment",
            AudioStorageNames.resolvePattern("mkmc-events/{year}/{year}-{month} - {event}", 2025, 11, "Vajrasattva Empowerment"));
        same("an event name with a slash stays in its segment", "sttp/Part 1_2",
            AudioStorageNames.resolvePattern("sttp/{event}", 2025, 9, "Part 1/2"));
        same("no pattern, no folder", null, AudioStorageNames.resolvePattern(null, 2026, 1, "x"));
        check("a language subfolder is detected", AudioStorageNames.hasLanguageSubfolder("ittp/2026/{lang}"));
        check("a flat folder is detected", !AudioStorageNames.hasLanguageSubfolder("sttp/course/part"));
        same("a language folder replaces the token", "ittp/2026/por",
            AudioStorageNames.folderForLanguage("ittp/2026/{lang}", "por"));
        same("a flat folder is the same for every language", "sttp/course",
            AudioStorageNames.folderForLanguage("sttp/course", "fra"));

        // --- language folders, as the zone spells them ---
        same("Cantonese is yue, though its language row says zho", "yue", AudioStorageNames.languageFolder("audio-zhca", "zho"));
        same("Mandarin is cmn, though its language row has no codes", "cmn", AudioStorageNames.languageFolder("audio-zhma", null));
        same("French", "fra", AudioStorageNames.languageFolder("audio-fr", "fra"));
        same("German", "deu", AudioStorageNames.languageFolder("audio-de", "deu"));
        same("Japanese from its language, not its code (audio-jp)", "jpn", AudioStorageNames.languageFolder("audio-jp", "jpn"));
        same("the 'mp3' item takes its language's code", "eng", AudioStorageNames.languageFolder("mp3", "eng"));
        same("no language row: the code's suffix", "xx", AudioStorageNames.languageFolder("audio-xx", null));
        same("nothing usable: a fixed name", "audio", AudioStorageNames.languageFolder(null, null));

        // --- patterns learnt from where earlier events put their files ---
        same("a festival folder becomes a pattern", "international-festivals/{year}/summer/{lang}",
            AudioStorageNames.patternFromEarlierFolder("international-festivals/2025/summer/eng", 2025));
        same("an ITTP folder becomes a pattern", "ittp/{year}/{lang}",
            AudioStorageNames.patternFromEarlierFolder("ittp/2025/por", 2025));
        same("an STTP course folder stays as it is", "sttp/new-eight-steps-to-happiness/2025-2026-part-2-gen-la-dekyong",
            AudioStorageNames.patternFromEarlierFolder("sttp/new-eight-steps-to-happiness/2025-2026-part-2-gen-la-dekyong", 2025));

        // --- URLs ---
        same("spaces encoded as the stored addresses already are",
            "mkmc-events/2025/2025-11%20-%20Vajrasattva/01.%20Intro.mp3",
            AudioStorageNames.encodePath("mkmc-events/2025/2025-11 - Vajrasattva/01. Intro.mp3"));
        same("a plain path is unchanged", "international-festivals/2026/summer/eng/01_a.mp3",
            AudioStorageNames.encodePath("international-festivals/2026/summer/eng/01_a.mp3"));
        same("decoding undoes it", "a b/c (1).mp3", AudioStorageNames.decodeUrl("a%20b/c%20%281%29.mp3"));
        same("a plus is a plus in a path", "a+b.mp3", AudioStorageNames.decodeUrl("a+b.mp3"));
        same("a malformed escape is left alone", "a%zz.mp3", AudioStorageNames.decodeUrl("a%zz.mp3"));

        // --- which file of the zone an address names (who plays it; what a delete may touch) ---
        List<String> hosts = List.of("nkt-audio-recordings.b-cdn.net", "audio.example.org");
        String cdn = "https://nkt-audio-recordings.b-cdn.net/";
        String mkmc = "mkmc-events/2025/2025-11 - Vajrasattva/01. Intro (1).mp3";
        same("an address built here names its own path", mkmc, AudioStorageNames.zonePath(cdn + AudioStorageNames.encodePath(mkmc), hosts));
        same("a raw space and %20 name the same file", AudioStorageNames.zonePath(cdn + "US%20Fest/01.mp3", hosts),
            AudioStorageNames.zonePath(cdn + "US Fest/01.mp3", hosts));
        same("http, an upper-case host and a port change nothing", "US Fest/01.mp3",
            AudioStorageNames.zonePath("HTTP://NKT-Audio-Recordings.b-cdn.net:443/US%20Fest/01.mp3", hosts));
        same("the pull zone's other hostname is the same zone", "ittp/2026/eng/01.mp3",
            AudioStorageNames.zonePath("https://audio.example.org/ittp/2026/eng/01.mp3", hosts));
        same("doubled slashes, backslashes and dots collapse", "ittp/2026/eng/01.mp3",
            AudioStorageNames.zonePath(cdn + "ittp//2026\\eng/./01.mp3", hosts));
        same("dot-dot is resolved as a browser would", "ittp/2026/eng/01.mp3",
            AudioStorageNames.zonePath(cdn + "ittp/2025/../2026/eng/01.mp3", hosts));
        same("an encoded dot-dot too", "ittp/2026/eng/01.mp3",
            AudioStorageNames.zonePath(cdn + "ittp/2025/%2E%2E/2026/eng/01.mp3", hosts));
        same("and it cannot climb out of the zone", "01.mp3", AudioStorageNames.zonePath(cdn + "../../01.mp3", hosts));
        same("the query and fragment are not part of the path", "ittp/2026/eng/01.mp3",
            AudioStorageNames.zonePath(cdn + "ittp/2026/eng/01.mp3?v=2#t=10", hosts));
        same("an encoded question mark is part of a name", "ittp/What?.mp3", AudioStorageNames.zonePath(cdn + "ittp/What%3F.mp3", hosts));
        same("UTF-8 escapes decode", "ittp/Méditation.mp3", AudioStorageNames.zonePath(cdn + "ittp/M%C3%A9ditation.mp3", hosts));
        same("a segment's spaces are kept: another file", "ittp /01.mp3", AudioStorageNames.zonePath(cdn + "ittp%20/01.mp3", hosts));
        same("a folder address names a folder path, which a delete then refuses", "international-festivals",
            AudioStorageNames.zonePath(cdn + "international-festivals/", hosts));
        same("another host is not the zone", null, AudioStorageNames.zonePath("https://other.b-cdn.net/ittp/01.mp3", hosts));
        same("a look-alike host is not the zone", null,
            AudioStorageNames.zonePath("https://nkt-audio-recordings.b-cdn.net.evil.example/01.mp3", hosts));
        same("user info cannot pass another host off as the zone", null,
            AudioStorageNames.zonePath("https://nkt-audio-recordings.b-cdn.net@evil.example/01.mp3", hosts));
        same("user info in front of the zone is still the zone", "01.mp3",
            AudioStorageNames.zonePath("https://someone@nkt-audio-recordings.b-cdn.net/01.mp3", hosts));
        same("the zone's root names no file", null, AudioStorageNames.zonePath(cdn, hosts));
        same("a path without a scheme is not an address", null, AudioStorageNames.zonePath("ittp/2026/eng/01.mp3", hosts));
        same("nothing names nothing", null, AudioStorageNames.zonePath(null, hosts));
        same("the comparison key folds case", "us fest/01.mp3", AudioStorageNames.zonePathKey(cdn + "US%20Fest/01.MP3", hosts));
        same("a key built here matches the key of its address", AudioStorageService.pathKey(mkmc),
            AudioStorageNames.zonePathKey(cdn + AudioStorageNames.encodePath(mkmc), hosts));

        // --- the bus argument for prepareAudioUploads ---
        List<AudioStorageService.ProposedFile> files = AudioStorageService.proposedFiles(
            new Object[] { 1857, 11, "a.mp3", 100, 12, "b.mp3", 200, 13, "dangling" });
        same("two complete triples, a dangling one ignored", 2, files.size());
        same("first file's slot", 11L, files.get(0).slotId());
        same("second file's size", 200L, files.get(1).size());

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}
