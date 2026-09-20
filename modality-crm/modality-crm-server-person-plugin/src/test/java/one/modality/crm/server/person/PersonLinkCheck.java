package one.modality.crm.server.person;

import java.util.List;

/**
 * Check for the link rules' security properties, which are properties of the SQL text.
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from main() and exits non-zero on
 * failure, like the other checks here.
 *
 * <p><b>What is pinned is what an edit could quietly undo.</b> Every rule these operations enforce rides
 * in the WHERE of the statement that acts on it — a condition checked beforehand instead would let
 * anything committed in between through, and a row count cannot be used to notice, because
 * {@code SubmitResult.getRowCount()} counts result sets rather than rows. So a statement that lost its
 * guard would still look right, still report success, and start writing rows it must not. These
 * assertions are the thing that would notice.
 */
public class PersonLinkCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) {
        System.out.println("PersonLinkCheck");
        List<String> writes = PersonLinkRules.writeStatements();

        // --- nothing writes the link without saying which rows ---
        for (String sql : writes) {
            boolean touchesLink = sql.contains("account_person_id = $")
                                  && (sql.startsWith("update") || sql.startsWith("insert"));
            if (!touchesLink) continue;
            check("guarded: " + sql.substring(0, Math.min(46, sql.length())),
                sql.contains(" where ") && (sql.contains("not exists") || sql.contains("frontend_account_id = $")));
        }

        // --- withdrawing must never confiscate ---
        String revoke = writes.stream().filter(s -> s.contains("account_person_revoked_date = now()")).findFirst().orElse("");
        check("revoking sets the withdrawal date", !revoke.isEmpty());
        check("revoking does NOT clear the link", !revoke.contains("account_person_id = null"));
        check("revoking names both parties", revoke.contains("frontend_account_id = $2") && revoke.contains("account_person_id = $3"));
        check("revoking only acts on a live link", revoke.contains("account_person_revoked_date is null"));

        // --- an invitation is spent once ---
        String use = writes.stream().filter(s -> s.startsWith("update invitation")).findFirst().orElse("");
        check("the invitation is marked used", use.contains("pending = false") && use.contains("accepted = true"));
        check("only a pending invitation can be spent", use.contains("and pending = true"));

        // --- linking an existing member cannot steal one already linked elsewhere ---
        String link = writes.stream().filter(s -> s.startsWith("update person set account_person_id")).findFirst().orElse("");
        check("linking skips a row linked to somebody else",
            link.contains("account_person_id is null or account_person_id = $1"));
        check("linking never touches an owner", link.contains("owner = false"));
        check("linking never touches a removed row", link.contains("removed = false"));

        // Both directions must be able to re-link after a withdrawal, or "ask again" works one way only.
        // A row this service created carries no email, so the address-keyed statement cannot find it.
        // Keyed on the LINK, not the address: a row this service created carries no email, so the
        // address-keyed statement above cannot find it after a withdrawal. One such statement per
        // direction, or "ask again" works one way round only and burns the invitation.
        long relinks = writes.stream()
            .filter(s -> s.startsWith("update person set account_person_revoked_date = null"))
            .filter(s -> s.contains("account_person_id = $1"))
            .count();
        check("both directions can re-link a withdrawn row", relinks == 2);

        // --- inserts are idempotent rather than duplicating a person per click ---
        long guardedInserts = writes.stream().filter(s -> s.startsWith("insert into person")).filter(s -> s.contains("not exists")).count();
        check("both person inserts are guarded by not-exists", guardedInserts == 2);

        // --- ids are compared as numbers, since the driver's type is not ours to assume ---
        check("sameId matches across numeric types", PersonLinkRules.sameId(42, 42L));
        check("sameId is false for a mismatch", !PersonLinkRules.sameId(42, 43));
        check("sameId is false for null", !PersonLinkRules.sameId(null, null) && !PersonLinkRules.sameId(42, null));

        // --- what the endpoints are registered ON ---
        // These were held back while a client could still author an invitation: approving trusts
        // invitation.inviter, so registering them then would have made this service the escalation it
        // prevents. ClientWriteSecrets now denies a client `token` (NOT NULL, so no client can insert
        // one at all), `inviter_id` and `invitee_id`. That deny list is the precondition for these two
        // being registered, which is why it is asserted HERE rather than only beside itself.
        // Found by walking up from wherever this was started, because it is run both from the plugin
        // and from the repository root.
        String secrets = readClientWriteSecrets();
        check("the invitation deny rules were found", secrets.contains("denyColumn(\"invitation\""));
        // The exact list, not each name on its own: "token" also occurs in pwdreset_token, so a
        // per-column contains() could not fail for the one column that matters most.
        check("clients may not write invitation token or authorship",
            secrets.contains("\"token\", \"inviter_id\", \"invitee_id\""));

        String services = "";
        try (java.io.InputStream in = PersonLinkCheck.class.getClassLoader()
                .getResourceAsStream("META-INF/services/dev.webfx.stack.com.bus.call.spi.BusCallEndpoint")) {
            if (in != null)
                services = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            services = "(unreadable: " + e + ")";
        }
        check("the services file was read", !services.isEmpty() && !services.startsWith("(unreadable"));
        check("CreateInvitationEndpoint IS registered", services.contains("CreateInvitationEndpoint"));
        check("ApproveInvitationEndpoint IS registered", services.contains("ApproveInvitationEndpoint"));
        check("RevokeLinkEndpoint IS registered", services.contains("RevokeLinkEndpoint"));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }

    /**
     * The deny-list declaration, found by walking up from wherever this check was launched.
     *
     * <p>Read from a sibling module's source rather than from its runtime state, because nothing in a
     * check JVM runs the boot job that would populate the list. Textual, and therefore weaker than
     * asking the list itself — but the assertion it supports is about a PRECONDITION holding at the
     * time these endpoints were switched on, which a reader of this file needs to be told about at all.
     */
    static String readClientWriteSecrets() {
        String relative = "modality-fork/modality-crm/modality-crm-server-authz-required-plugin/src/main/java/"
                          + "one/modality/crm/server/services/authz/ClientWriteSecrets.java";
        java.nio.file.Path here = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (java.nio.file.Path at = here; at != null; at = at.getParent()) {
            java.nio.file.Path candidate = at.resolve(relative);
            if (java.nio.file.Files.exists(candidate)) {
                try {
                    return java.nio.file.Files.readString(candidate);
                } catch (Exception e) {
                    return "(unreadable: " + e + ")";
                }
            }
        }
        return "(not found from " + here + ")";
    }
}
