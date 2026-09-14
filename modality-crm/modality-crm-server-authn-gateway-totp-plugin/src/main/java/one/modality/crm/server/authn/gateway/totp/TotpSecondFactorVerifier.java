package one.modality.crm.server.authn.gateway.totp;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import one.modality.crm.server.authn.gateway.shared.SecondFactorMethod;
import one.modality.crm.server.authn.gateway.shared.SecondFactorVerifier;

/**
 * Answers the second-factor policy's one question about TOTP: is this account enrolled?
 *
 * <p>There is deliberately no {@code verify} here. The policy decides whether a factor is owed; the
 * plugin that owns the credential completes its own step, so the shared module never handles a
 * secret and a future factor can be added without widening a shared interface.
 *
 * <p><b>Fail closed.</b> "Enrolled" normally means a CONFIRMED row — a factor is a factor only once
 * a first correct code has proved the phone holds the secret. But when no encryption key is
 * configured the honest answer is not "not enrolled": the row is there, its owner believes they are
 * protected by two factors, and answering false would let exactly those accounts in on the password
 * alone. So a row with no usable key answers TRUE, loudly, and the login is refused instead. An
 * unconfirmed row counts too in that case, because enrolment cannot write a row without a key: a
 * row with no key is a row from a time when there WAS one, which makes "the key is gone" the
 * reading, not "somebody half-enrolled".
 *
 * <p>A failed query answers TRUE for the same reason — a database hiccup must not be a route to a
 * single-factor login.
 *
 * @author Claude Code
 */
public final class TotpSecondFactorVerifier implements SecondFactorVerifier {

    private static final String LOG_PREFIX = "[totp] ";

    private final TotpCredentialStore credentialStore = new TotpCredentialStore();

    @Override
    public String methodCode() {
        return SecondFactorMethod.TOTP;
    }

    @Override
    public boolean isStrongAlone() {
        // A code proves possession of the secret, not of the password: TOTP is always the SECOND
        // step, never a login on its own.
        return false;
    }

    @Override
    public Future<Boolean> isEnrolled(Object accountId) {
        Object normalisedAccountId = TotpCredentialStore.normaliseId(accountId);
        return credentialStore.findByAccount(normalisedAccountId)
            .map(row -> {
                if (row == null)
                    return false;
                if (!TotpKeys.isUsable()) {
                    // Account id only — never the username, never the secret. Loud because the cure
                    // is an operator setting TOTP_ENCRYPTION_KEY, and nothing else will say so.
                    Console.log(LOG_PREFIX + "🛑 Account " + normalisedAccountId + " holds a TOTP row but no"
                                + " encryption key is configured — treating it as ENROLLED and refusing the login"
                                + " rather than downgrading it to password-only. Set TOTP_ENCRYPTION_KEY.");
                    return true;
                }
                return row.isConfirmed();
            })
            .otherwise(e -> {
                Console.log(LOG_PREFIX + "🛑 Enrolment lookup failed for account " + normalisedAccountId
                            + " (" + e.getMessage() + ") — answering ENROLLED so the login is refused rather than"
                            + " downgraded");
                return true;
            });
    }
}
