package one.modality.crm.server.authn.gateway.shared;

/**
 * The short codes naming the credential checks a session can rest on.
 *
 * <p>They are a wire format, not a label: they travel to the client inside the pending marker, and
 * (once the claims land) inside the session token's {@code $amr}. A token minted today must still be
 * readable by the instance that reads it tomorrow, so these strings are FIXED — renaming one
 * silently invalidates every live session that carries it, and changes what the policy sees.
 *
 * <p>Kept here, in the module every Modality gateway already shares, so that the password gateway,
 * the WebAuthn plugin and the TOTP plugin all spell them the same way. A factor's own gateway is the
 * only thing that may declare its code satisfied — this class just names them.
 *
 * @author Claude Code
 */
public final class SecondFactorMethod {

    /** A username/password check. One factor, and the one an attacker can obtain from a hash dump. */
    public static final String PASSWORD = "pwd";

    /** A code from an authenticator app (RFC 6238). */
    public static final String TOTP = "totp";

    /** One of the printed backup codes. Same strength class as {@link #TOTP}, single-use. */
    public static final String TOTP_BACKUP = "totp-backup";

    /** A passkey (WebAuthn) assertion with user verification — both factors in one gesture. */
    public static final String PASSKEY = "pk";

    private SecondFactorMethod() {
    }
}
