package one.modality.ecommerce.payment.spi.impl.server;

import dev.webfx.platform.console.spi.ConsoleProvider;
import dev.webfx.platform.service.SingleServiceProvider;
import dev.webfx.stack.session.token.SignedToken;

import java.util.Collections;
import java.util.List;

/**
 * The token that authorizes the PUBLIC resume-payment page.
 *
 * <p>Everything here is a property somebody could reasonably get wrong, and two of them were got wrong while
 * this was being written: the payload has to be built identically from an id that arrives as a different Java
 * type on each side, and a verifier with no key has to refuse rather than skip the check.
 *
 * <p>No test framework — runs from main() and exits non-zero, like the checks elsewhere in this repo.
 *
 * @author Claude Code
 */
public class ResumePaymentTokenCheck {

    static int pass = 0, fail = 0;

    private static final byte[] KEY = "a-test-signing-key-of-at-least-32-bytes".getBytes();

    public static void main(String[] args) {
        // The no-key paths LOG before they refuse, and Console throws for want of a provider outside a
        // running server — which masks whatever is being asserted. A silent provider lets it through.
        SingleServiceProvider.registerServiceProvider(ConsoleProvider.class, new ConsoleProvider() {
            @Override public void log(String message) { }
        });

        // ── With no signing key: everything refuses, nothing is minted ──
        // First, because it is the state a misconfigured server is in, and a verifier that cannot verify
        // must never wave a caller through. This ordering also proves the later checks are not passing
        // because some earlier call left keys lying around.
        SignedToken.setKeys(Collections.emptyList());
        // Reported rather than thrown: SignedToken.mint THROWS without a key, so the guard that turns
        // that into a null is what this asserts, and a regression must read as a failure and not as a
        // stack trace that stops the rest of the checks from running.
        boolean mintedNothingWithoutAKey;
        try {
            mintedNothingWithoutAKey = ResumePaymentToken.mint(101001) == null;
        } catch (RuntimeException e) {
            mintedNothingWithoutAKey = false;
        }
        check("no key: nothing is minted", mintedNothingWithoutAKey);
        check("no key: every token is refused", !ResumePaymentToken.isValidFor("anything", 101001));

        SignedToken.setKeys(List.of(KEY));

        String token = ResumePaymentToken.mint(101001);
        check("a token is minted for a payment", token != null && !token.isEmpty());
        check("and it verifies for that payment", ResumePaymentToken.isValidFor(token, 101001));

        // THE property. A signature only says "we issued this"; it must also say "for this payment".
        check("a token for one payment does not verify for another",
              !ResumePaymentToken.isValidFor(token, 101002));

        // THE OTHER property, and the one that would have failed only in production. Minting reads the id
        // from the entity layer and verifying reads it off the wire, so the two sides see different Java
        // types for the same payment. A payload built from a raw toString() passes every test where both
        // are Integers and refuses every payer the day a number decodes as a Double.
        check("an id that arrives as a Long still verifies",
              ResumePaymentToken.isValidFor(token, 101001L));
        check("an id that arrives as a Double still verifies",
              ResumePaymentToken.isValidFor(token, 101001.0));
        check("a token minted from a Long verifies against an Integer",
              ResumePaymentToken.isValidFor(ResumePaymentToken.mint(101001L), 101001));

        // ── Namespacing: another kind of signed token is not a resume token ──
        // SignedToken is shared, so a token minted elsewhere with the same key must not open this page.
        // An ABSOLUTE deadline. Written as a duration first, which minted tokens that expired in 1970 —
        // so both of these passed because the token was stale, not because the namespace refused it. A
        // test that passes for the wrong reason is worse than no test: it reports a guard that is absent.
        long inAMinute = System.currentTimeMillis() + 60_000;
        String foreignToken = SignedToken.mint("some-other-purpose:101001", inAMinute);
        check("a signed token for another purpose is refused",
              !ResumePaymentToken.isValidFor(foreignToken, 101001));
        String bareIdToken = SignedToken.mint("101001", inAMinute);
        check("and so is one whose payload is just the id",
              !ResumePaymentToken.isValidFor(bareIdToken, 101001));

        // ── Rubbish in ──
        check("null token refused", !ResumePaymentToken.isValidFor(null, 101001));
        check("empty token refused", !ResumePaymentToken.isValidFor("", 101001));
        check("tampered token refused", !ResumePaymentToken.isValidFor(token + "x", 101001));
        check("null payment refused", !ResumePaymentToken.isValidFor(token, null));
        check("non-numeric payment refused", !ResumePaymentToken.isValidFor(token, "not-a-number"));
        check("nothing is minted for a null payment", ResumePaymentToken.mint(null) == null);
        check("nor for a payment that is not a number", ResumePaymentToken.mint("not-a-number") == null);

        System.out.println(fail == 0 ? "\nALL " + pass + " PASS" : "\n" + fail + " FAILED");
        if (fail > 0)
            throw new AssertionError(fail + " resume payment token checks failed");
    }

    private static void check(String what, boolean condition) {
        if (condition) {
            pass++;
            System.out.println("  ok   " + what);
        } else {
            fail++;
            System.out.println("  FAIL " + what);
        }
    }
}
