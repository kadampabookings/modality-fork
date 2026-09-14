package one.modality.crm.server.authn.gateway.webauthn;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import one.modality.crm.server.authn.gateway.shared.SecondFactorMethod;
import one.modality.crm.server.authn.gateway.shared.SecondFactorVerifier;

import java.util.List;

/**
 * Answers the second-factor policy's one question about passkeys: is this account enrolled?
 *
 * <p><b>Why this class exists.</b> Until it did, the TOTP plugin was the only registered
 * {@link SecondFactorVerifier}, so an account whose only strong factor was a passkey held, as far as
 * the policy could tell, no factor at all — and a back-office password login for it was never asked
 * for anything. "MFA when there is either the passkey or the code" is exactly this verifier plus the
 * step-up arm in {@link ModalityWebAuthnAuthenticationGateway#authenticateWithPasskey}.
 *
 * <p>There is deliberately no {@code verify} here, for the reason {@link SecondFactorVerifier} gives:
 * the plugin that owns the credential completes its own step, so the shared module never sees a
 * signature.
 *
 * <p><b>Enrolled means "could actually complete a back-office assertion TODAY"</b>, not "has a row".
 * The rule is {@link WebAuthnCredentialStore#opensBackofficeLogin}, which the gateway's own approval
 * gate reads too — one rule, so this answer and the login path cannot disagree. It depends on the
 * approval switch, and the switch is read from the configuration the GATEWAY loaded
 * ({@link ModalityWebAuthnAuthenticationGateway#currentConfig()}), never from a second read of our
 * own: one switch with two readers is two answers waiting to drift.
 *
 * <p><b>Fail closed</b>, like {@code TotpSecondFactorVerifier}:
 * <ul>
 * <li>A failed query answers TRUE. A pool timeout, an unapplied migration or a dropped index must
 *     not be a route to a one-factor back-office session: the caller ({@code SecondFactorVerifiers})
 *     turns "enrolled" into a held login and, failing that, a refusal — loud, bounded, recoverable —
 *     whereas "not enrolled" would silently downgrade exactly the staff accounts that enrolled.</li>
 * <li>A usable row on an UNCONFIGURED gateway also answers TRUE, and says so loudly. With no rpId or
 *     no origin nothing can be asserted, so the honest answer is not "no factor" — the row is there,
 *     its owner believes they are protected by it, and answering false would let precisely those
 *     accounts in on the password alone. The login is refused instead, and the cure is a variable.</li>
 * </ul>
 *
 * @author Claude Code
 */
public final class PasskeySecondFactorVerifier implements SecondFactorVerifier {

    private static final String LOG_PREFIX = "[webauthn] ";

    private final WebAuthnCredentialStore credentialStore = new WebAuthnCredentialStore();

    @Override
    public String methodCode() {
        return SecondFactorMethod.PASSKEY;
    }

    @Override
    public boolean isStrongAlone() {
        // A user-verified passkey is possession (the key) plus verification (the PIN or biometric)
        // in one gesture — which is why it is also allowed to BE the whole login. Registration
        // requires userVerification=required, and so does every assertion the gateway starts.
        //
        // DECLARED BY THE SPI AND CONSULTED NOWHERE YET — don't go looking for the caller. What
        // actually makes a passkey a whole login is the gateway's ordinary assertion path minting
        // without asking for anything else; nothing reads this flag to decide it. An account whose
        // only factor is a passkey is still HELD at the password step and asked for the passkey,
        // which is the intended behaviour and not a contradiction: the password login owes a second
        // factor, and this is the one it owes.
        return true;
    }

    @Override
    public Future<Boolean> isEnrolled(Object accountId) {
        Object normalisedAccountId = WebAuthnCredentialStore.normaliseId(accountId);
        // Read once, before the async hop, exactly as every ceremony in the gateway reads it: the
        // switch must not be able to change under one account's answer.
        WebAuthnConfig cfg = ModalityWebAuthnAuthenticationGateway.currentConfig();
        boolean approvalRequired = cfg.isBackofficeApprovalRequired();
        return credentialStore.findByAccount(normalisedAccountId)
            .map(credentials -> {
                boolean enrolled = holdsUsableCredential(credentials, approvalRequired);
                if (enrolled && !cfg.isConfigured())
                    // Account id only — never the username, never a credential id. Loud because the
                    // cure is an operator setting WEBAUTHN_RP_ID / WEBAUTHN_BACKOFFICE_ORIGINS, and
                    // nothing else will say why these logins stopped.
                    Console.log(LOG_PREFIX + "🛑 Account " + normalisedAccountId + " holds a usable passkey but the"
                                + " gateway is NOT configured — treating it as ENROLLED and refusing the login rather"
                                + " than downgrading it to password-only. Check WEBAUTHN_RP_ID and WEBAUTHN_*_ORIGINS.");
                return enrolled;
            })
            .otherwise(e -> {
                Console.log(LOG_PREFIX + "🛑 Passkey enrolment lookup failed for account " + normalisedAccountId
                            + " (" + e.getMessage() + ") — answering ENROLLED so the login is refused rather than"
                            + " downgraded");
                return true;
            });
    }

    /**
     * Whether any ONE of the account's credentials would be accepted by a back-office assertion
     * today — an account with three rejected passkeys and one approved one is enrolled, and an
     * account with three pending ones is not while the gate is on.
     *
     * <p>Package-private for {@code PasskeySecondFactorVerifierCheck}, which pins that table.
     */
    static boolean holdsUsableCredential(List<WebAuthnCredentialStore.CredentialSummary> credentials,
                                         boolean approvalRequired) {
        if (credentials == null)
            return false;
        for (WebAuthnCredentialStore.CredentialSummary credential : credentials)
            if (WebAuthnCredentialStore.opensBackofficeLogin(credential.status(), approvalRequired))
                return true;
        return false;
    }
}
