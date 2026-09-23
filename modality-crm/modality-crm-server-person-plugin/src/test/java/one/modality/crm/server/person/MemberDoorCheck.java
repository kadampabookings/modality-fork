package one.modality.crm.server.person;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Check that every front-office member endpoint goes through the FENCED door.
 *
 * <p>Cross-cutting on purpose. The per-endpoint checks each pin their own statement text, and none of
 * them can see the property that matters here: that no endpoint in this package accepts a caller whose
 * identity this server never verified. A new endpoint added next month is exactly the thing that would
 * slip past a per-endpoint check, because nobody writes a check for a door they did not notice.
 *
 * <p>The plain door was REMOVED on 2026-09-23 rather than deprecated, so the first assertion is about
 * absence: if {@code whenCallerIsMember} comes back as an entry point, the judgement that retired it
 * (see {@link MemberSessionGuard}) is being reopened without saying so.
 *
 * <p><b>A scan that matches nothing must fail, not pass.</b> Hence the minimum-count assertion: the
 * cheapest way for this check to become worthless is for the directory walk to quietly find no files.
 */
public class MemberDoorCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    /** The package directory, found the same way AccountOwnerCheck.readSource finds a file. */
    private static Path packageDir() {
        String relative = "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/"
                          + "one/modality/crm/server/person";
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path at = here; at != null; at = at.getParent()) {
            Path candidate = at.resolve(relative);
            if (Files.isDirectory(candidate))
                return candidate;
        }
        return null;
    }

    public static void main(String[] args) {
        System.out.println("MemberDoorCheck");
        Path dir = packageDir();
        check("the package directory was found", dir != null);
        if (dir == null) {
            System.out.println(pass + " passed, " + (fail + 1) + " failed");
            System.exit(1);
        }

        // --- the unfenced door is gone, not merely unused ---
        String guard = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/"
            + "one/modality/crm/server/person/MemberSessionGuard.java");
        check("the guard source was found", !guard.isEmpty());
        check("there is no unfenced entry point",
            !guard.contains("whenCallerIsMember("));
        check("the fenced one tests the session family, which only a verified token sets",
            guard.contains("StateAccessor.getSessionFamilyId(state) != null"));
        check("a support view is still refused, whatever the session",
            guard.contains("principal.isSupportView()"));
        check("and a refusal still says nothing about which test failed",
            guard.contains("\"This operation is not available to you\""));

        // --- every endpoint that guards at all, guards with the fence ---
        List<String> plainDoor = new ArrayList<>();
        List<String> fenced = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String name = f.getFileName().toString();
                if (!name.endsWith(".java") || name.equals("MemberSessionGuard.java"))
                    continue;
                String source = Files.readString(f);
                if (source.contains("MemberSessionGuard.whenCallerIsMember("))
                    plainDoor.add(name);
                if (source.contains("MemberSessionGuard.whenCallerIsVerifiedMember("))
                    fenced.add(name);
            }
        } catch (Exception e) {
            check("the package could be read: " + e, false);
        }

        check("no endpoint takes the unfenced door " + plainDoor, plainDoor.isEmpty());
        // The four that existed when this was written. A lower number means the scan broke; a higher
        // one is fine and expected as the mail arc adds endpoints.
        check("the fenced door is actually in use (found " + fenced.size() + ": " + fenced + ")",
            fenced.size() >= 4);

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}
