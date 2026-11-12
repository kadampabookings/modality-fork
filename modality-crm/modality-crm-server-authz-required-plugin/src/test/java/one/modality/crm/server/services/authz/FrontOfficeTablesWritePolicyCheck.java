package one.modality.crm.server.services.authz;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Check for {@link FrontOfficeTablesWritePolicy}: the back office writes anything; without a verified back-office
 * token a client writes only the front office's tables; and that list still covers every table the React front
 * office's code writes.
 *
 * <p>The last part is what keeps a list of what is allowed from going stale. It scans {@code kbs3-react/frontoffice/src}
 * and {@code kbs3-react/shared/src} (when run from the aggregate) for writes through a change set and for DQL write
 * statements, the way the list was first drawn. A front-office write added later to a table not on the list fails
 * here, before it fails for every member once the rule enforces.
 *
 * <p>No test framework: runs from main() and exits non-zero on failure, following ChatWritePolicyCheck.
 */
public class FrontOfficeTablesWritePolicyCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    /** Tables the front office's code names but reaches some other way, so they are rightly NOT on the list. */
    static final Set<String> REACHED_OTHERWISE = Set.of(
        "Document", "DocumentLine", "Attendance", // booking service; BackofficeOnlyWritePolicy
        "Person");                                // person endpoints; denied outright by ClientWriteSecrets

    static final Pattern CHANGE_SET_WRITE = Pattern.compile(
        "\\b(?:insertEntity|updateEntity|insert|update|delete)(?:<[A-Za-z]+>)?\\(\\s*['\"]([A-Z][A-Za-z]+)['\"]");
    static final Pattern DQL_WRITE = Pattern.compile(
        "['\"`]\\s*(?:insert(?: into)?|update|delete(?: from)?)\\s+([A-Z][A-Za-z]+)\\s+(?:set|where|values|\\()");

    static Path kbs3React() {
        for (String root : new String[] { "kbs3-react", "../kbs3-react", "../../kbs3-react" }) {
            Path p = Path.of(root);
            if (Files.isDirectory(p.resolve("frontoffice/src")))
                return p;
        }
        return null;
    }

    /** Table → one file writing it, from the front office's sources. Comment lines are skipped: examples are not writes. */
    static TreeMap<String, String> frontOfficeWrites(Path kbs3React) throws IOException {
        TreeMap<String, String> found = new TreeMap<>();
        for (String dir : new String[] { "frontoffice/src", "shared/src" }) {
            try (Stream<Path> files = Files.walk(kbs3React.resolve(dir))) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    String name = file.toString();
                    if (!(name.endsWith(".ts") || name.endsWith(".tsx")) || name.contains("__tests__"))
                        continue;
                    StringBuilder code = new StringBuilder();
                    for (String line : Files.readAllLines(file)) {
                        String t = line.trim();
                        if (!t.startsWith("*") && !t.startsWith("//") && !t.startsWith("/*"))
                            code.append(line).append('\n');
                    }
                    for (Pattern p : new Pattern[] { CHANGE_SET_WRITE, DQL_WRITE }) {
                        Matcher m = p.matcher(code);
                        while (m.find())
                            found.putIfAbsent(m.group(1), kbs3React.relativize(file).toString());
                    }
                }
            }
        }
        return found;
    }

    public static void main(String[] args) throws IOException {
        System.out.println("the switch:");
        check("ships OBSERVING", !FrontOfficeTablesWritePolicy.ENFORCING);
        String job = ChatWritePolicyCheck.readJob();
        check("is registered among the row rules", job == null || job.contains("new FrontOfficeTablesWritePolicy()"));

        System.out.println("who may write what:");
        check("the back office writes a room", !FrontOfficeTablesWritePolicy.refuses("ResourceConfiguration", true));
        check("a member does not", FrontOfficeTablesWritePolicy.refuses("ResourceConfiguration", false));
        check("nor does a caller with no token", FrontOfficeTablesWritePolicy.refuses("ResourceConfiguration", null));
        check("nor a table nobody has heard of yet", FrontOfficeTablesWritePolicy.refuses("SomeFutureTable", null));
        check("a member writes chat", !FrontOfficeTablesWritePolicy.refuses("ChatMessage", false));
        check("an anonymous page reports an error", !FrontOfficeTablesWritePolicy.refuses("Error", null));
        check("a member records viewing", !FrontOfficeTablesWritePolicy.refuses("MediaConsumption", false));
        check("the booking tables are left to their own rule (no double log)",
            !FrontOfficeTablesWritePolicy.refuses("Document", false) && !FrontOfficeTablesWritePolicy.refuses("DocumentLine", null));
        check("an unreadable write is left to the rule that already refuses it",
            FrontOfficeTablesWritePolicy.judge(null, null, true) == null);

        System.out.println("the list still covers the front office's code:");
        Path kbs3React = kbs3React();
        if (kbs3React == null) {
            System.out.println("  (not checked: run from the aggregate root, where kbs3-react/ is)");
        } else {
            TreeMap<String, String> writes = frontOfficeWrites(kbs3React);
            check("the scan finds the front office's writes (" + writes.size() + " tables)", writes.size() >= 10);
            for (var entry : writes.entrySet()) {
                String table = entry.getKey();
                boolean covered = FrontOfficeTablesWritePolicy.FRONT_OFFICE_ENTITIES.contains(table)
                                  || REACHED_OTHERWISE.contains(table);
                check(table + " (" + entry.getValue() + ")" + (covered ? "" : " — written by the front office, NOT on the list"), covered);
            }
            // And the other way: a table on the list that no front-office code writes any more should come off it.
            Set<String> stale = new TreeSet<>(FrontOfficeTablesWritePolicy.FRONT_OFFICE_ENTITIES);
            stale.removeAll(writes.keySet());
            check("every listed table is still written by the front office" + (stale.isEmpty() ? "" : " — no longer: " + stale),
                stale.isEmpty());
        }

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}
