package one.modality.crm.server.person;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.orm.entity.EntityStore;
import one.modality.base.shared.entities.Document;

/**
 * The parts every booking-scoped mail endpoint needs, in one place because there are going to be nine
 * of them.
 *
 * <p>The mail arc replaces each client-composed mail with a server endpoint, and each one answers the
 * same two questions before it writes anything: may this caller ask about this booking, and where
 * should the centre reply. {@link RefundRequestRules} answered them first and well; what is here is
 * the part that must not be answered TWICE, differently.
 *
 * <h3>Why the access tests are constants rather than a copied string</h3>
 *
 * <p>{@link #MEMBER_TEST} spans two person rows — the document's person, and that person's
 * accountPerson link — so the version somebody writes from memory ({@code person.frontendAccount = $2})
 * looks right, passes a test with an ordinary booking, and quietly refuses every member who reaches
 * their bookings through a link. That is a bug you find from a support ticket, months later, and it is
 * exactly the kind that arrives by copy-paste into flow number six.
 *
 * <p>Refund still holds its own copies, because it is deployed and its check pins the exact strings.
 * {@code ContactRequestCheck} asserts the two are EQUAL to these, so the moment either drifts a check
 * fails rather than a member's booking quietly becoming invisible to one endpoint and not the other.
 *
 * @author Claude Code
 */
final class BookingMail {

    private BookingMail() {
    }

    /**
     * A member may ask about a booking their account can see on the orders page.
     *
     * <p>The domain model's own inline function, shared with the orders query, the legacy orders
     * activity and the server document service. Reused rather than re-derived — see the class note.
     */
    static final String MEMBER_TEST = "accountCanAccessPersonOrders($2, person)";

    /** A guest may ask about a booking carrying the address their principal proved. */
    static final String GUEST_TEST = "lower(person_email)=lower($2)";

    /** The caller's own sign-in address, for the reply a member without their own address still needs. */
    private static final String OWNER_ADDRESS_DQL = "select username from FrontendAccount where id=$1";

    /** What {@code mail.from_name} holds; the two document columns feeding it are 45 characters each. */
    private static final int MAX_FROM_NAME = 64;

    /**
     * Where the centre should reply.
     *
     * <p>The booking's own address first. A member whose person carries none is reached through their
     * account owner — {@code person_email} is NULL for most of them — so the caller's own sign-in
     * address stands in. A guest has only ever had the one address, and it is the one they proved.
     */
    static Future<String> replyAddress(Document booking, Object callerScope, boolean guest,
                                       EntityStore entityStore) {
        String own = booking.getStringFieldValue("person_email");
        if (own != null && !own.isBlank())
            return Future.succeededFuture(own);
        if (guest)
            return Future.succeededFuture(String.valueOf(callerScope));
        return ServerWrite.asServer(() -> entityStore.executeQuery(OWNER_ADDRESS_DQL, callerScope))
            .map(accounts -> accounts == null || accounts.isEmpty() ? null
                : accounts.get(0).getStringFieldValue("username"));
    }

    /**
     * The booker's name for the mail's display name, cut to what the column holds.
     *
     * <p>A long name used to fail the whole request on a PG 22001 rather than sending it. The browser
     * built the same string into the same column and had the same problem; the difference is that the
     * server is somewhere it can be cut.
     */
    static String name(String first, String last) {
        String joined = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        if (joined.isEmpty())
            return null;
        return joined.length() <= MAX_FROM_NAME ? joined : joined.substring(0, MAX_FROM_NAME).trim();
    }

    /**
     * The caller's own words, made safe to put inside an HTML mail body.
     *
     * <p><b>The browser did not do this.</b> It sent
     * {@code <html>${message.replace(/\r?\n/g, '<br/>')}</html>} — the member's text straight into
     * markup — so anything typed into the message box arrived at the centre as HTML: a link pointing
     * somewhere else, markup that hides the rest of the message, a forged-looking quoted reply. It
     * reaches staff from a Kadampa-verified sender, which is exactly the context in which a reader
     * trusts what they are looking at. Escaping first, and only then turning newlines into breaks,
     * keeps the line breaks the member meant and nothing else they typed.
     */
    static String escapeHtml(String text) {
        if (text == null)
            return "";
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
