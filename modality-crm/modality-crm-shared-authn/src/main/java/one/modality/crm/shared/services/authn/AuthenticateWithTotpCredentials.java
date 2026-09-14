package one.modality.crm.shared.services.authn;

/**
 * Completes a back-office login step-up with a code from the authenticator app.
 *
 * <p>Sent on the {@code authenticate} path, on the same {@code runId} as the password step that
 * returned the pending marker. The server consumes that pending entry — which names the account
 * whose password was already proven — verifies the code against that account and no other, and only
 * then mints the session, with {@code pwd,totp} as the methods it rests on. The identity reaches the
 * client by the same push as any other login; nothing was pushed by the password step.
 *
 * <p>There is no login by TOTP alone: without a pending entry for this {@code runId} this credential
 * proves nothing about the password and is refused. The code is checked against a replay guard, so a
 * step already accepted is never accepted twice, and the per-{@code runId} attempt budget is spent
 * BEFORE the code is verified, so a failed guess costs an attempt whatever it was.
 *
 * @param code the current 6-digit code from the authenticator app
 *
 * @author Claude Code
 */
public record AuthenticateWithTotpCredentials(String code) {
}
