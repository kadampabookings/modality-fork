package one.modality.crm.server.person;

import java.util.List;

/**
 * Check for the merge dialogs' operation: which rows move, what they are linked to, and which accounts
 * are deleted.
 *
 * <p>No test framework: runs from main() and exits non-zero on failure, like the other checks here.
 *
 * <p>The account delete is the one worth reading. Both dialogs computed "accounts that are now empty"
 * IN THE BROWSER and sent those ids. An account named wrongly — or named deliberately — took its
 * remaining people's sign-in with it, and nothing on the server would have questioned the list. The
 * emptiness test now lives inside the DELETE, so the statement refuses what the caller believed.
 */
public class AccountMergeCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    /** The rules' own source, for the decisions that are not visible in a statement string. */
    static String rules() {
        return AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/"
            + "one/modality/crm/server/person/AccountMergeRules.java");
    }

    public static void main(String[] args) {
        System.out.println("AccountMergeCheck");

        // --- the move ---
        String move = AccountMergeRules.moveSql(3, false);
        check("everybody lands on the destination, which is the first parameter",
            move.startsWith("update person set frontend_account_id = $1"));
        check("owner is cleared as a literal on every moved row",
            move.contains(", owner = false"));
        check("and the people are parameters, never interpolated ids",
            move.contains(" where id in ($2, $3, $4)"));
        // Naming the destination's own owner among the movers cleared their owner flag and left that
        // account with nobody speaking for it — and the delete would not remove it either, since it
        // still holds people. A merge must not be able to un-own the account it merges ONTO.
        check("and never touches somebody already on the destination",
            move.contains(" and frontend_account_id is distinct from $1"));
        check("no link means no accountPerson in the statement at all",
            !move.contains("account_person_id"));

        String linked = AccountMergeRules.moveSql(2, true);
        check("a link shifts the people along and writes accountPerson",
            linked.contains(", account_person_id = $2") && linked.contains(" where id in ($3, $4)"));
        // PersonLinkRules writes these two together; a pair only sometimes written is a pair that gets
        // read inconsistently one day, even where — as here — the odd state fails safe.
        check("and clears the revoked date with it, as every other link write does",
            linked.contains("account_person_revoked_date = null"));

        // --- the delete, which is the point ---
        String delete = AccountMergeRules.deleteEmptyAccountsSql(2);
        check("only the accounts named are candidates",
            delete.startsWith("delete from frontend_account where id in ($1, $2)"));
        // THE ONE THE CLIENT USED TO DECIDE. Without it, a caller naming a populated account deletes it
        // and takes its remaining people's sign-in with it.
        check("and each has to be EMPTY, tested in the statement rather than believed",
            delete.contains(" and not exists (select 1 from person p"
                            + " where p.frontend_account_id = frontend_account.id)"));

        // --- the soft delete ---
        String remove = AccountMergeRules.removeCustomersSql(2);
        check("removing duplicates is one statement for all of them",
            remove.startsWith("update person set removed = true where id in ($1, $2)"));
        check("and refuses an account owner, as every other person statement does",
            remove.contains(" and owner = false"));
        // NOT `returning id`: generated keys carry one value per RESULT SET and not per row, so the
        // array's length is 1 for any non-empty update however many rows matched. Counting from it
        // told the screen an owner it named had been removed when the statement had skipped them.
        check("and does NOT pretend the write can count its own rows",
            !remove.contains("returning"));
        check("the removed set is read back instead",
            rules().contains("and removed = true") && rules().contains("removedAmong"));

        // --- the delete candidates include the accounts the caller NAMED, not only the derived ones ---
        // A source whose people were all moved or deleted by somebody else between the dialog loading
        // and the merge contributes nobody to the moving set, so its orphaned row would have survived.
        // Safe to add precisely because the delete tests emptiness for itself.
        check("a named source is a delete candidate even if nobody moved off it",
            rules().contains("new LinkedHashSet<>(namedSources)"));
        check("and the destination never is",
            rules().contains("candidates.remove(destination)"));

        // --- the grant-holder rule fails CLOSED ---
        check("an unreadable answer refuses rather than allows",
            rules().contains("holders == null || holders > 0 ? GRANT_HOLDER_KEY")
            && rules().contains(".otherwise(GRANT_HOLDER_KEY)"));

        // --- placeholders are generated, so an id is never text in a statement ---
        check("placeholders count from where they are told",
            "$4, $5, $6".equals(AccountMergeRules.placeholders(4, 3)));
        check("and one is not a list", "$1".equals(AccountMergeRules.placeholders(1, 1)));

        // --- the refusals a screen has to tell apart ---
        check("a grant holder in the moving set has its own key",
            !AccountMergeRules.GRANT_HOLDER_KEY.equals(AccountMergeRules.TARGET_KEY)
            && !AccountMergeRules.GRANT_HOLDER_KEY.equals(AccountMergeRules.NOTHING_KEY));

        // --- and the rule that is re-asked here because the policy cannot see these writes ---
        check("moving somebody who holds authorizations needs a super admin",
            rules().contains("if (callerIsSuperAdmin)") && rules().contains("GRANT_HOLDER_KEY"));
        check("and all three grant tables are asked, as PersonAccountMovePolicy asks them",
            rules().contains("authorization_super_admin")
            && rules().contains("authorization_organization_admin")
            && rules().contains("authorization_organization_user_access"));
        check("a link must be a live OWNER of the destination, not merely a person",
            rules().contains("frontend_account_id = $2 and owner = true and removed = false"));
        // The whole-account merge moves removed rows too: one left behind would point at an account the
        // delete is about to remove, and the foreign key would refuse it.
        check("the whole-account merge takes removed rows with it",
            rules().contains("select id, frontend_account_id from person where frontend_account_id in ("));

        String endpoint = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/"
            + "one/modality/crm/server/person/MergeIntoAccountEndpoint.java");
        check("the merge is under the customers grant, like the screen it serves",
            endpoint.contains("whenCallerMayReachAnywhere(UpdateCustomerEndpoint.CUSTOMERS_ROUTE"));
        check("and reads super-admin membership itself, for the question the guard does not ask",
            endpoint.contains("SuperAdminMembership"));

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
