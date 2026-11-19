package one.modality.crm.server.authn.gateway.shared;

/**
 * {@link EmailAddresses#canonical}: @googlemail.com folds into @gmail.com, and nothing else changes beyond the trim
 * and the lower-casing the password login always applied to addresses (2026-11-19).
 *
 * <p>No test framework; main() exits non-zero on failure, like the checks next door.
 */
public class EmailAddressesCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    static boolean is(String expected, String input) {
        return expected == null ? EmailAddresses.canonical(input) == null : expected.equals(EmailAddresses.canonical(input));
    }

    public static void main(String[] args) {
        System.out.println("EmailAddressesCheck");
        check("googlemail folds into gmail", is("jo.smith@gmail.com", "jo.smith@googlemail.com"));
        check("whatever its case", is("jo.smith@gmail.com", "Jo.Smith@GoogleMail.COM"));
        check("and around blanks", is("jo@gmail.com", "  jo@googlemail.com \t"));
        check("gmail stays gmail", is("jo@gmail.com", "jo@gmail.com"));
        check("dots in the local part are kept", is("j.o@gmail.com", "j.o@googlemail.com"));
        check("+tags are kept", is("jo+kmc@gmail.com", "jo+kmc@googlemail.com"));
        check("another domain is only lower-cased", is("jo@example.org", "Jo@Example.ORG"));
        check("a subdomain is not folded", is("jo@mail.googlemail.com.au", "jo@mail.googlemail.com.au"));
        check("googlemail.com in the local part is not folded", is("googlemail.com@example.org", "googlemail.com@example.org"));
        check("a domain merely ending in googlemail.com is not folded", is("jo@notgooglemail.com", "jo@notgooglemail.com"));
        check("a name without @ is only trimmed, case kept", is("Admin", " Admin "));
        check("null stays null", is(null, null));
        check("blank becomes empty", is("", "   "));
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}
