package one.modality.crm.server.authn.gateway.shared;

/**
 * The one spelling of an email address that KBS stores and looks accounts up by.
 *
 * <p>{@code @googlemail.com} and {@code @gmail.com} are the same Gmail mailbox: Google gave German and UK users
 * the googlemail domain for years, and either spelling still delivers to the same inbox. People type whichever
 * they remember, and until 2026-11 each spelling opened its own account — 383 logins on prod were googlemail
 * ones, 35 of them with a separate gmail account beside them, each holding part of one person's bookings.
 *
 * <p>So every address a caller types is passed through {@link #canonical} before it is used to find or create an
 * account, and the database rewrites the same domain on write (V0173's trigger) as a backstop for any path that
 * stores an address without coming through here. Only the domain is folded: Gmail also ignores dots and
 * {@code +tags} in the local part, but folding those would merge addresses that other providers keep distinct,
 * and is not done.
 *
 * <p>Must stay identical to {@code canonical_email()} in V0173: a lookup made with one spelling and a row stored
 * with the other would miss each other.
 */
public final class EmailAddresses {

    private static final String GOOGLEMAIL = "@googlemail.com";
    private static final String GMAIL = "@gmail.com";

    private EmailAddresses() {}

    /**
     * Trimmed, lower-cased, with {@code @googlemail.com} replaced by {@code @gmail.com}. {@code null} stays
     * {@code null}; a value without {@code @} (a back-office login such as a shiro.ini name) is only trimmed, as
     * the password login has always treated it.
     */
    public static String canonical(String address) {
        if (address == null)
            return null;
        String a = address.trim();
        if (a.indexOf('@') < 0)
            return a;
        a = a.toLowerCase(java.util.Locale.ROOT);
        if (a.endsWith(GOOGLEMAIL))
            a = a.substring(0, a.length() - GOOGLEMAIL.length()) + GMAIL;
        return a;
    }
}
