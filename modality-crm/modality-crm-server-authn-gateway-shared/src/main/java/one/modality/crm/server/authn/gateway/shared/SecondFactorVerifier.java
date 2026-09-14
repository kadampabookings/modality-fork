package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;

/**
 * What one kind of second factor tells the login orchestration about an account: which code it
 * answers to, whether it is strong enough on its own, and whether this account has one.
 *
 * <p>Discovered with {@code ServiceLoader} through {@link SecondFactorVerifiers}, so a factor is
 * present exactly when its plugin is in the build. A deployment without the TOTP plugin has nobody
 * enrolled in TOTP, which is true, rather than half a feature.
 *
 * <p><b>There is deliberately no {@code verify()} method.</b> Each factor's own gateway completes its
 * own step — the TOTP plugin owns the code path and the encryption key, the WebAuthn plugin owns the
 * assertion — so this module never sees a code, a secret or a signature, and never grows the ability
 * to. What crosses this interface is a yes/no about enrolment and a constant string; nothing here can
 * leak a credential, and nothing here can be tricked into accepting one.
 *
 * @author Claude Code
 */
public interface SecondFactorVerifier {

    /**
     * The wire code for this factor, from {@link SecondFactorMethod} — what the pending marker
     * advertises to the client and what the session records as the method it rests on.
     */
    String methodCode();

    /**
     * Whether holding this factor alone proves both factors (a passkey assertion with user
     * verification does; a TOTP code does not — it is the second half of password + code).
     */
    boolean isStrongAlone();

    /**
     * Whether this account holds a CONFIRMED credential of this kind — one that has already proved
     * itself with a first correct code or assertion. An unconfirmed row is not a factor: asking for
     * one would lock its owner out with a secret their app never actually received.
     *
     * <p>Called with the frontend account id resolved from a proven password, never with anything
     * the client sent.
     */
    Future<Boolean> isEnrolled(Object accountId);
}
