package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.stack.hash.md5.Md5;

import java.util.Objects;

/**
 * How a password is stored ({@code frontend_account.password}) and how a typed one is checked against it.
 *
 * <p>Here rather than in the password gateway because the password is no longer only a way to sign in: it
 * is also what a session shows before it changes how the account is reached (see
 * {@link CredentialChangeProof}), and that is asked by the passkey gateway as well.
 *
 * <p>The scheme is KBS2's — MD5 of the salt (the account's email) and the MD5 of the password — and is
 * weaker than the passwords it protects; replacing it is its own piece of work.
 *
 * @author Claude Code
 */
public final class StoredPasswords {

    private StoredPasswords() {}

    /** What {@code frontend_account.password} holds for this password and salt. */
    public static String encrypt(String password, String salt) {
        String toEncrypt = salt + ":" + Md5.hash(password);
        return Md5.hash(toEncrypt);
    }

    /**
     * Whether the password typed by the user matches the one stored for the account. A null typed password
     * matches nothing (the hash would throw on it).
     *
     * <p>This used to have a second branch accepting the <em>stored hash itself</em> as a valid password, so
     * that support could copy a hash out of the back office and sign in as the customer. That made the hash a
     * permanent, transferable credential and turned any dump of {@code frontend_account} into a plaintext
     * password list — inverting the entire point of storing passwords hashed — while leaving no record that
     * support had signed in at all. Support now uses {@code RequestSupportViewCredentials}, which issues a
     * pass of its own: short-lived, single-use, read-only, tied to one named customer and one named member of
     * staff, and recorded.
     */
    public static boolean matches(String typedPassword, String storedEncryptedPassword, String salt) {
        if (typedPassword == null || storedEncryptedPassword == null)
            return false;
        return Objects.equals(encrypt(typedPassword, salt), storedEncryptedPassword);
    }
}
