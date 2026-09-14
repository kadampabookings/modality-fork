package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.service.MultipleServiceProviders;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Asks every registered {@link SecondFactorVerifier} which factors an account holds.
 *
 * <p>Providers are discovered the way the rest of the stack discovers a multi-provider SPI
 * ({@code ServerAuthenticationPortalProvider}, {@code ServerPaymentServiceProvider}): through
 * {@link MultipleServiceProviders}, so the answer follows the build.
 *
 * <p><b>"Nobody is enrolled" and "I could not find out" are different answers, and only the first
 * is permissive.</b>
 *
 * <ul>
 * <li><b>No verifier registered at all</b> — the plugin is simply not in this build, so nothing can
 *     be enrolled in a factor the deployment does not have. Permissive, and true.</li>
 * <li><b>A registered verifier could not answer</b> — an unapplied migration, a rotated-away
 *     encryption key, a pool timeout. {@link SecondFactorEnrolment#unavailable()} says so and the
 *     caller must FAIL CLOSED: refuse the login with
 *     {@code AuthnSecondFactorUnavailableError}, never mint on the password alone.</li>
 * </ul>
 *
 * <p>This reverses the earlier rule, which counted a failed verifier as "not enrolled" and argued
 * that the permissive direction was safe because an enrolled factor can only ADD a requirement. The
 * argument is true and the conclusion was still wrong: it made one broken query — one migration not
 * run, one key not mounted — silently downgrade EVERY account with a confirmed factor to a
 * password-only back-office login, organisation-wide, with a single log line as the only signal.
 * Refusing instead turns the same fault into an outage, which is loud, bounded and recoverable, and
 * which nobody mistakes for a working control. A verifier that knows its own answer is safe — the
 * TOTP plugin reports accounts holding a row it cannot decrypt as ENROLLED so their logins are
 * refused rather than downgraded — is unaffected: it answers, and this class believes it.
 *
 * @author Claude Code
 */
public final class SecondFactorVerifiers {

    private static final String LOG_PREFIX = "[2fa] ";

    /**
     * What the account holds, and whether anything failed to say.
     *
     * @param methods            the method codes of the factors this account holds confirmed, in
     *                           verifier order. Empty means "password only", and the policy decides
     *                           whether that is allowed — but ONLY when {@code unavailableMethods}
     *                           is also empty
     * @param unavailableMethods the method codes of the verifiers that could not answer. Non-empty
     *                           means the login must be refused: the true answer is unknown, and an
     *                           unknown answer minted is a one-factor back-office session
     */
    public record SecondFactorEnrolment(List<String> methods, List<String> unavailableMethods) {

        /** Whether at least one registered verifier failed to answer — the fail-closed signal. */
        public boolean unavailable() {
            return !unavailableMethods.isEmpty();
        }

        /** The failing method codes for the refusal log line, e.g. {@code totp}. */
        public String unavailableMethodsText() {
            return String.join(",", unavailableMethods);
        }
    }

    private SecondFactorVerifiers() {
    }

    private static List<SecondFactorVerifier> getVerifiers() {
        return MultipleServiceProviders.getProviders(SecondFactorVerifier.class, () -> ServiceLoader.load(SecondFactorVerifier.class));
    }

    /** One verifier's three possible answers — the middle one is what used to be lost. */
    private enum Answer {ENROLLED, NOT_ENROLLED, UNAVAILABLE}

    /**
     * What this account holds, asked of every registered verifier.
     *
     * <p>Never fails: a verifier's failure is reported INSIDE the result, so a caller cannot
     * accidentally handle it as "no factor" by composing a {@code .otherwise}.
     */
    public static Future<SecondFactorEnrolment> enrolmentOf(Object accountId) {
        List<SecondFactorVerifier> verifiers = getVerifiers();
        if (verifiers.isEmpty()) // no plugin in this build: nobody can be enrolled, and that is an answer
            return Future.succeededFuture(new SecondFactorEnrolment(List.of(), List.of()));
        List<Future<Answer>> answers = new ArrayList<>(verifiers.size());
        for (SecondFactorVerifier verifier : verifiers)
            answers.add(askEnrolled(verifier, accountId));
        return Future.all(answers).map(composite -> {
            List<String> methods = new ArrayList<>(verifiers.size());
            List<String> unavailable = new ArrayList<>(verifiers.size());
            for (int i = 0; i < verifiers.size(); i++) {
                Answer answer = composite.resultAt(i);
                if (answer == Answer.ENROLLED)
                    methods.add(verifiers.get(i).methodCode());
                else if (answer != Answer.NOT_ENROLLED) // UNAVAILABLE, or a null nobody expected
                    unavailable.add(verifiers.get(i).methodCode());
            }
            return new SecondFactorEnrolment(methods, unavailable);
        });
    }

    /** One verifier's answer, with both of its failure modes (thrown and failed future) reported as UNAVAILABLE. */
    private static Future<Answer> askEnrolled(SecondFactorVerifier verifier, Object accountId) {
        try {
            return verifier.isEnrolled(accountId)
                .map(enrolled -> Boolean.TRUE.equals(enrolled) ? Answer.ENROLLED : Answer.NOT_ENROLLED)
                .otherwise(error -> {
                    logVerifierFailure(verifier, accountId, error);
                    return Answer.UNAVAILABLE;
                });
        } catch (RuntimeException e) {
            logVerifierFailure(verifier, accountId, e);
            return Future.succeededFuture(Answer.UNAVAILABLE);
        }
    }

    /**
     * Account id only — never a username, and never anything the factor holds.
     *
     * <p>The message, or the class name when there is none; never the throwable itself, whose
     * {@code toString()} prepends the class to that same message and whose cause chain a logger may
     * walk. That is a narrowing, not a guarantee: a JDBC driver is free to put the statement and its
     * bound parameters in the MESSAGE, so a verifier whose query binds anything personal must fail
     * with a message of its own rather than let the driver's reach this line. Today's only verifier
     * binds an account id.</p>
     */
    private static void logVerifierFailure(SecondFactorVerifier verifier, Object accountId, Throwable error) {
        Console.log(LOG_PREFIX + "🛑 Verifier " + verifier.methodCode() + " could not answer for account " + accountId
                    + " — this login will be REFUSED rather than downgraded to password-only: " + describe(error));
    }

    /** The message when there is one, the class name otherwise. Nothing else off the throwable. */
    private static String describe(Throwable error) {
        if (error == null)
            return "no error reported";
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getName() : message;
    }
}
