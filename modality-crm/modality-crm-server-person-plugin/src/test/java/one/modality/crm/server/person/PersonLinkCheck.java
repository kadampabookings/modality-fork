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

        // --- accepting after all: only the invitee's own dated decline, and the inviter is told again ---
        String acceptDeclined = writes.stream().filter(s -> s.startsWith("update invitation")
            && s.contains("pending = false and accepted = false")).findFirst().orElse("");
        check("there is an accept-after-decline write", !acceptDeclined.isEmpty());
        check("only the invitee accepts after all", acceptDeclined.contains("invitee_id = $2")
            && !acceptDeclined.contains("inviter_id"));
        check("only a DATED decline is reopened, never a cancellation",
            acceptDeclined.contains("usage_date is not null"));
        check("never a pending invitation (that is approval's)", !acceptDeclined.contains("pending = true"));
        check("the acceptance is news to the inviter (seen date cleared)",
            acceptDeclined.contains("answer_seen_date = null"));
        check("accepting after all names one row", acceptDeclined.contains("where id = $1"));

        // --- linking an existing member cannot steal one already linked elsewhere ---
        String link = writes.stream().filter(s -> s.startsWith("update person set account_person_id")).findFirst().orElse("");
        check("linking skips a row linked to somebody else",
            link.contains("account_person_id is null or account_person_id = $1"));
        check("linking never touches an owner", link.contains("owner = false"));
        // A booker adding someone by address knows only their sign-in address (2026-11-09); some account
        // holders' own email differs. Either must find the row, or approval adds a twin.
        check("linking matches the member row on either address", link.contains("lower(email) in ($3, $4)"));
        String create = writes.stream().filter(s -> s.startsWith("insert into person (first_name, last_name, frontend_account_id, account_person_id, owner)")
            && s.contains("$5, $2, $1")).findFirst().orElse("");
        check("and creating one is skipped when either address already has a row", create.contains("lower(email) in ($3, $6)"));
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

        // The claim is only as safe as the table it reads. claimMembers takes no arguments and derives
        // its rows from the caller's own verified sign-in address — but that was never the whole story:
        // an ordinary client write could point a STRANGER's member row at the caller's address first,
        // and the claim would then link it entirely lawfully. Two steps, each one looking legitimate.
        // Shutting the table is what ends that, so the endpoint and this deny rule are safe together
        // and not separately. If somebody reopens person to clients, claimMembers has to be revisited
        // in the same breath — which is what this assertion is here to force.
        check("person is CLOSED to clients, which is what makes the claim safe",
            secrets.contains("denyTable(\"person\")"));
        // The exact list, not each name on its own: "token" also occurs in pwdreset_token, so a
        // per-column contains() could not fail for the one column that matters most.
        check("clients may not write invitation token or authorship",
            secrets.contains("\"token\", \"inviter_id\", \"invitee_id\""));

        // --- and the link itself, which is what all of this protects ---
        // Any client could set account_person_id on any row until 2b. That is the single worst thing a
        // client could do to a person: it hands one account the row's bookings and TAKES its media.
        // The gate is both halves together — the deny, and the endpoint that replaced the one client
        // that legitimately wrote it. Either alone is a broken state: without the endpoint the claim
        // stops working, and without the deny the endpoint's rules are bypassable by not calling it.
        check("clients may not write the link",
            secrets.contains("\"account_person_id\", \"account_person_revoked_date\""));
        // The un-revoke case is asserted THROUGH THE INSPECTOR in ClientWriteGuardCheck; a second
        // substring test here would be a substring of the one above and could never fail alone.

        String claim = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/"
            + "one/modality/crm/server/person/MemberClaimRules.java");
        // The proof is the caller's own sign-in address, read from frontend_account.username — which
        // only the server writes. The browser used to choose which rows to claim.
        check("the claim derives its rows from the caller's verified address",
            claim.contains("select username from frontend_account where id = $1")
            && claim.contains("lower(email) = lower($2)"));
        check("and repeats every condition in the write, not only the read",
            claim.contains("account_person_id is null and frontend_account_id is not null")
            && claim.contains("owner = false and removed = false"));
        check("never the caller's own account, which would claim nothing and mean nothing",
            claim.contains("frontend_account_id <> $3"));

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
        // The other half of the deny above: without this endpoint the claim simply stops working, and
        // without the deny its rules are bypassable by not calling it. Neither is safe alone.
        check("ClaimMembersEndpoint IS registered", services.contains("ClaimMembersEndpoint"));

        // --- the claim's READ half, and the managers' names (PersonReadScope, Nov 2026) ---
        // Scoping `Person` to the caller's own account closed the two client statements these replace.
        // Both reached across accounts, and the first did it on an address the CALLER supplied, which
        // answered "did anyone book for this address, and in whose account?" about anybody. The screens
        // went blank the moment that closed, which is the whole reason these exist: a rule that leaves
        // the feature broken does not survive contact with the next person who has to ship something.
        check("ClaimableMembersEndpoint IS registered", services.contains("ClaimableMembersEndpoint"));
        check("ManagerAccountOwnersEndpoint IS registered",
            services.contains("ManagerAccountOwnersEndpoint"));

        // The offer and the claim must agree about what is claimable. If they drift, a member is shown
        // a row that is then not claimed -- or worse, claims one they were never shown, because the
        // notice to that account's owner is sent by the screen from the list the screen was given.
        // Asserted as ONE shared constant rather than as two matching strings: two strings that happen
        // to agree today is exactly the state this is meant to prevent.
        check("the offer and the claim share one definition of claimable",
            claim.contains("private static final String CLAIMABLE_SQL = \"select id from person\" + CLAIMABLE_WHERE;")
            && claim.contains("frontend_account_id from person\" + CLAIMABLE_WHERE"));
        check("the offer takes the address from the caller, never from an argument",
            claim.contains("static Future<Object> offers(Object callerAccountId)")
            && !claim.contains("static Future<Object> offers(String"));
        // Data minimisation, and it is load-bearing rather than tidy: the rows' own addresses ARE the
        // proof the derivation runs on. Returning them would hand a caller the one value that decides
        // what they may claim, and no screen reads it -- the only address in the reply is the owner's,
        // which is where the notice goes.
        check("no claimable row's own email is sent back",
            claim.contains("select id, first_name, last_name, frontend_account_id from person"));

        String claimableEndpoint = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/"
            + "one/modality/crm/server/person/ClaimableMembersEndpoint.java");
        check("the offer is behind the fenced door, as the claim is",
            claimableEndpoint.contains("MemberSessionGuard.whenCallerIsVerifiedMember"));
        // A support view's principal carries the VIEWED member's person id, so an unfenced version
        // would hand a support agent the claimables of the member they are helping.
        check("and it passes the guard's account, not anything from the argument",
            claimableEndpoint.contains("MemberClaimRules.offers(callerAccountId)"));

        String managerOwners = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/"
            + "one/modality/crm/server/person/ManagerAccountOwnersEndpoint.java");
        check("the managers' owners are behind the same door",
            managerOwners.contains("MemberSessionGuard.whenCallerIsVerifiedMember"));
        // The point of the whole endpoint. Taking the accounts as an argument would answer "who owns
        // account N?" for any N a caller cares to send -- the question the read scope was written to
        // stop, reopened by the thing meant to replace it. They are derived from the live link itself.
        check("the manager accounts are derived from the link, not taken as ids",
            managerOwners.contains("where account_person_id = $1 and owner = false and removed = false")
            && managerOwners.contains("and account_person_revoked_date is null"));
        check("and the caller's own account is excluded, as the page's own query excludes it",
            managerOwners.contains("and frontend_account_id <> $2"));

        // --- the booking form's NEEDS_VALIDATION badge, the fourth read the scope closed ---
        // Found during review of the three above, in shared/ rather than frontoffice/, and it had
        // failed SILENTLY: zero rows reads exactly like "nobody here has their own account", so the
        // badge simply stopped appearing with nothing in any log.
        check("MembersWithOwnAccountEndpoint IS registered",
            services.contains("MembersWithOwnAccountEndpoint"));
        String ownAccount = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/"
            + "one/modality/crm/server/person/MembersWithOwnAccountEndpoint.java");
        check("it is behind the fenced door",
            ownAccount.contains("MemberSessionGuard.whenCallerIsVerifiedMember"));
        // Every row it names is on the caller's own account, which the read scope's first arm already
        // lets them read, and nothing about the matched account comes back. That bounds the REPLY.
        //
        // It does NOT make the endpoint safe the way its two siblings are, and this check must not be
        // read as saying so. Their derived input cannot be chosen; this one's is person.email on the
        // caller's own rows, which a member writes at will through updatePersonDetails / addMember,
        // neither metered. So it answers a membership question about addresses the caller picked, in
        // bulk, with no budget -- what EmailHasAccountEndpoint meters at 30 per ten minutes.
        //
        // It grants nothing today because FrontendAccount is itself read-unscoped (read-authorization
        // -plan.md records that). It survives the fix, though, because it runs under asServer on raw
        // SQL: metering or re-deriving it belongs in the change that scopes FrontendAccount.
        check("every row it returns is on the caller's own account",
            ownAccount.contains("where m.frontend_account_id = $1"));
        check("and nothing about the matched account is selected",
            ownAccount.contains("select distinct m.id from person m")
            && !ownAccount.contains("fa.username,")
            && !ownAccount.contains("select distinct m.id, "));
        // The googlemail fold the client used to do, kept rather than quietly dropped: an address
        // written @googlemail.com is the same mailbox as @gmail.com, and V0173 only folds NEW
        // writes -- the existing rows wait on scripts/fold-googlemail-accounts.sql. Both sides.
        // Both sides, schema-qualified, and btrim OUTSIDE the fold: the function trims whitespace only
        // on the branch that matches, so 'a@gmail.com  ' would otherwise never equal 'a@gmail.com' and
        // the member would silently lose the badge -- this endpoint's own failure mode.
        check("the googlemail fold is applied to both sides, as the client's canonicalEmail was",
            ownAccount.contains("lower(btrim(public.canonical_email_domain(fa.username)))")
            && ownAccount.contains("lower(btrim(public.canonical_email_domain(m.email)))"));
        // Measured, not guessed: the correlated exists form hashes all 49,650 owner rows joined to
        // all 49,664 accounts before testing anything (531 ms on staging's worst account), where the
        // join hashes the caller's own members -- one at the median, 17 at the worst -- and streams
        // frontend_account past it once (134 ms). A future edit back to `exists` is a 4x regression.
        check("written as a join rather than a correlated exists, which is 4x slower here",
            !ownAccount.contains("and exists (select"));

        // --- closing an invitation without a link (V0157) ---
        // Declining and cancelling were client writes nobody checked the party of. The table closes to
        // clients and three endpoints replace them; as with the claim, the deny and the endpoints are safe
        // together and not apart — with the deny alone, members could no longer decline or cancel.
        check("invitation is CLOSED to clients", secrets.contains("denyTable(\"invitation\")"));
        check("DeclineInvitationEndpoint IS registered", services.contains("DeclineInvitationEndpoint"));
        check("CancelInvitationEndpoint IS registered", services.contains("CancelInvitationEndpoint"));
        check("SeeInvitationAnswerEndpoint IS registered", services.contains("SeeInvitationAnswerEndpoint"));
        check("AcceptDeclinedInvitationEndpoint IS registered", services.contains("AcceptDeclinedInvitationEndpoint"));

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
