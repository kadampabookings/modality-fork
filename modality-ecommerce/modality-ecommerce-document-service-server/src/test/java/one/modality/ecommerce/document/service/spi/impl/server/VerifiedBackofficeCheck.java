package one.modality.ecommerce.document.service.spi.impl.server;

import dev.webfx.stack.session.state.StateAccessor;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Check for {@link VerifiedBackoffice} — the document service grants back-office treatment (any
 * booking loadable, front-office booking rules lifted) only on the VERIFIED audience.
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from main() and exits non-zero
 * on failure, following MateLinkRulesCheck.
 *
 * <p>The cases that matter are the ones where the client's claim and the token DISAGREE. A fixture in
 * which they agree passes whether the guard reads the claim or the token, which is how three of four
 * guard tests on the audience arc passed with the guard deleted.
 */
public class VerifiedBackofficeCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    /** A message state as the server sees it: the client's claim, and what a verified token said (or null). */
    static Object state(Boolean claimedBackoffice, Boolean verifiedBackoffice) {
        Object state = StateAccessor.createEmptyState();
        if (claimedBackoffice != null)
            state = StateAccessor.setBackoffice(state, claimedBackoffice);
        if (verifiedBackoffice != null)
            state = StateAccessor.setVerifiedBackoffice(state, verifiedBackoffice);
        return state;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("VerifiedBackofficeCheck");

        // ── The hole: the client claims the back office and nothing verified it ──
        check("a claimed back office with no token is NOT the back office (any booking loadable before)",
            !VerifiedBackoffice.of(state(true, null)));
        check("a claimed back office over a FRONT-office token is NOT the back office (claim and token disagree)",
            !VerifiedBackoffice.of(state(true, false)));

        // ── The token decides, whatever the claim ──
        check("a back-office token is the back office even when the client claims the front office",
            VerifiedBackoffice.of(state(false, true)));
        check("a back-office token with a matching claim is the back office",
            VerifiedBackoffice.of(state(true, true)));

        // ── No answer is not the back office ──
        check("nothing claimed, nothing verified: not the back office", !VerifiedBackoffice.of(state(null, null)));
        check("no state at all (read after an async hop): not the back office", !VerifiedBackoffice.of(null));

        // ── Both grants go through it ──
        // Asserted on the CALL, not on the absence of the old one: the helper's own javadoc names the old
        // call, and an absence check would be broken by the file explaining what it replaced.
        Path dir = Path.of("src/main/java/one/modality/ecommerce/document/service/spi/impl/server");
        String submit = Files.readString(dir.resolve("DocumentSubmitRequest.java"));
        String provider = Files.readString(dir.resolve("ServerDocumentServiceProvider.java"));
        check("the submit's back-office transaction is chosen from the verified audience",
            submit.contains("boolean backoffice = VerifiedBackoffice.of(ThreadLocalStateHolder.getThreadLocalState());"));
        check("the booking load's access check is skipped only for a verified back office",
            provider.contains("!VerifiedBackoffice.of(ThreadLocalStateHolder.getThreadLocalState())"));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}
