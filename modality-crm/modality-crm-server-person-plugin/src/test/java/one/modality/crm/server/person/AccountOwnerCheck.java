package one.modality.crm.server.person;

import java.util.ArrayList;
import java.util.List;

/**
 * Check for the one operation in this package that runs with nobody signed in.
 *
 * <p>No test framework: runs from main() and exits non-zero on failure, like the other checks here.
 *
 * <p>What it pins is the boundary between what the caller says and what the server decides. Signing up
 * used to insert the owner person from the browser, so the browser chose the account the row landed in,
 * its email and whether it was the owner. Those three now come from the magic link, and the only way to
 * be sure they still do is to read the statement that runs: a builder edit that let one of them back
 * into the client's field list would produce SQL that works perfectly and puts an owner row wherever it
 * is told.
 */
public class AccountOwnerCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    /** A source file, found by walking up from wherever this was invoked — as PersonLinkCheck does. */
    static String readSource(String relative) {
        java.nio.file.Path here = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (java.nio.file.Path at = here; at != null; at = at.getParent()) {
            java.nio.file.Path candidate = at.resolve(relative);
            if (java.nio.file.Files.exists(candidate)) {
                try {
                    return java.nio.file.Files.readString(candidate);
                } catch (Exception e) {
                    return "";
                }
            }
        }
        return "";
    }

    /** The text between two markers, or empty when either is missing. */
    static String between(String source, String from, String to) {
        int start = source.indexOf(from);
        int end = start < 0 ? -1 : source.indexOf(to, start);
        return start < 0 || end < 0 ? "" : source.substring(start, end);
    }

    /** What the endpoint would refuse these pairs for, or null. */
    static String rejection(Object... namesAndValues) {
        return AccountOwnerRules.rejectionFor(namesAndValues, new ArrayList<>(), new ArrayList<>());
    }

    public static void main(String[] args) {
        System.out.println("AccountOwnerCheck");

        // --- the three the client no longer decides ---
        String sql = AccountOwnerRules.insertStatementFor(List.of("firstName", "lastName"));
        check("the account is a parameter the endpoint supplies, first",
            sql.startsWith("insert into person (frontend_account_id, owner, email") && sql.contains("select $1, "));
        check("owner is written true as a literal, so no caller can choose it",
            sql.contains("select $1, true, $2"));
        check("the email is the second parameter, which is the link's",
            sql.contains("select $1, true, $2,"));
        check("the profile columns start at $3, after the three that are not the client's",
            sql.contains("first_name") && sql.contains("$3") && sql.contains("last_name") && sql.contains("$4"));
        check("the insert returns its row, so a miss is not reported as a save",
            sql.endsWith(" returning id"));

        // --- once per account, and inside the statement rather than before it ---
        // A separate read then an unconditional insert is check-then-write: two overlapping calls both
        // see "no owner" and both insert. There is no unique index to fall back on, because production
        // already holds accounts with several owner rows.
        check("the once-per-account test travels inside the statement",
            sql.contains("where not exists (select 1 from person where frontend_account_id = $1 and owner = true)"));
        check("a REMOVED owner still blocks — the row is evidence this already ran",
            !sql.contains("owner = true and removed"));

        // --- and cannot smuggle them in as fields ---
        check("sending an email is refused, not ignored",
            AccountOwnerRules.EMAIL_NOT_YOURS_KEY.equals(rejection("email", "thief@evil.example")));
        for (String structural : new String[] { "owner", "frontendAccount", "accountPerson" })
            check("sending " + structural + " is refused by the field allowlist",
                PersonDetailsRules.FIELD_NOT_EDITABLE_KEY.equals(rejection(structural, 1)));
        // THE ONE THIS CHECK MISSED FIRST TIME. `removed` is an ordinary editable field everywhere
        // else, and an owner row inserted with removed = true is an owner the guard above still counts
        // — but only because the guard was ALSO fixed. Either alone would leave the credential able to
        // mint owner rows without limit, each carrying a name and address of the caller's choosing.
        for (String membership : new String[] { "removed", "occasional" })
            check("sending " + membership + " is refused: it is membership state, not a sign-up profile",
                PersonDetailsRules.FIELD_NOT_EDITABLE_KEY.equals(rejection(membership, true)));
        check("and it really is editable elsewhere, so the refusal above is this endpoint's own",
            PersonFields.isEditable("removed") && PersonFields.isEditable("occasional"));

        // --- the ordinary checks the other person writes make ---
        check("a profile field list is accepted", rejection("firstName", "Alice", "lastName", "Smith") == null);
        check("an over-long value is caught by name",
            PersonDetailsRules.VALUE_TOO_LONG_KEY.equals(rejection("firstName", "x".repeat(46))));
        check("an entity sent where an id belongs is refused",
            PersonDetailsRules.NOT_AN_ID_KEY.equals(rejection("country", new Object())));
        check("an unpaired field is refused", rejection("firstName") != null);
        check("nothing at all is accepted — the three server columns are a complete row",
            rejection() == null);

        // --- the freshness markers are still the server's value, as everywhere else ---
        String stamped = AccountOwnerRules.insertStatementFor(List.of("detailsConfirmedDate"));
        check("a marker is stamped by the server here too",
            stamped.contains("then current_date") && !stamped.contains("::date"));

        // --- the credential's LIFECYCLE, which this check did not look at the first time ---
        // It asserted the shape of the generated SQL and nothing about what the endpoint asks the magic
        // link for — so it stayed green over a version that refused EVERY six-digit sign-up. The code
        // route is the booking wizard and the public-talk card; the failure would have left a password
        // account with no person row, and no way to sign in to it or start again.
        String rules = readSource("modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/"
                                  + "one/modality/crm/server/person/AccountOwnerRules.java");
        check("the endpoint asks for a link this session REDEEMED",
            rules.contains("loadMagicLinkRedeemedByThisSession"));
        check("and not for an unspent one, which a code can never be by the time it gets here",
            !rules.contains("loadMagicLinkForAccountCreation("));

        String magicLink = readSource("modality-fork/modality-crm/modality-crm-server-authn-gateway-shared/src/main/java/"
                                      + "one/modality/crm/server/authn/gateway/shared/MagicLinkService.java");
        String loader = between(magicLink, "loadMagicLinkRedeemedByThisSession(String", "private static <T> Future<T> notFound");
        check("that loader was found", !loader.isEmpty());
        check("it proves the session by usageRunId, which means \"redeemed by me\"",
            loader.contains("usageRunId=$3"));
        check("it does NOT filter out a spent link, which is the whole point",
            !loader.contains("usageDate is null") && !loader.contains("usageDate=null"));
        check("it stays on LOGIN links, so a BOOKING_ACCESS row sharing six digits cannot shadow one",
            loader.contains("linkType=$2") && loader.contains("MagicLinkType.LOGIN.name()"));
        check("it matches one column rather than an OR across two, which would match neither index",
            loader.contains("looksLikeVerificationCode(tokenOrVerificationCode) ? \"verificationCode\" : \"token\""));

        // The log line fires on every miss, and the loader's own messages end in "(token: <credential>)".
        check("a refusal does not log the credential",
            !rules.contains("link: \" + error") && rules.contains("error.getClass().getSimpleName()"));

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
