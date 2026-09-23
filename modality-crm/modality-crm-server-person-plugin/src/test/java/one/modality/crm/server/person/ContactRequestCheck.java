package one.modality.crm.server.person;

/**
 * Check for the contact form's security properties.
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from main() and exits non-zero
 * on failure, like the other checks here.
 *
 * <p><b>Three things here are load-bearing and all three look like details.</b> The access test rides
 * in the WHERE of the read, so a booking this caller may not see is never loaded. The absence of a
 * recipient row is what makes the database address the mail to the centre, so "fixing" the missing
 * recipient would be choosing an address. And the absence of {@code account_id} is what leaves the
 * {@code auto_account} trigger to derive the sending account from the booking — that trigger fires
 * only {@code WHEN (new.account_id IS NULL)}, so a value written here would win, and would decide
 * which verified address the mail leaves from.
 *
 * <p>The escaping assertions are the only ones testing a FIX rather than a move: the dialog
 * interpolated the member's text straight into an HTML document.
 */
public class ContactRequestCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) {
        System.out.println("ContactRequestCheck");
        String source = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/"
            + "one/modality/crm/server/person/ContactRequestRules.java");
        check("the rules source was found", !source.isEmpty());

        // --- the batch has to be able to work at all ---
        check("the mail insert returns its id, which the history row's reference needs",
            source.contains("values ($1, false, $2, $3, $4, $5) returning id"));
        check("and asks for generated keys, which is the other half of the same thing",
            source.contains("setReturnGeneratedKeys(true)"));
        check("the history row names the mail through the batch, not a second read",
            source.contains("new GeneratedKeyReference(0)"));
        check("the event id is a primary key, not an EntityId's toString",
            source.contains("Entities.getPrimaryKey(event)"));

        // --- who may ask ---
        check("the member test is the domain model's own function",
            ContactRequestRules.MEMBER_DQL.contains("accountCanAccessPersonOrders($2, person)"));
        check("guests are answered, by the address they proved",
            ContactRequestRules.GUEST_DQL.contains("lower(person_email)=lower($2)"));
        check("both tests ride in the WHERE of the read, not in a check afterwards",
            ContactRequestRules.MEMBER_DQL.contains("from Document where id=$1")
            && ContactRequestRules.GUEST_DQL.contains("from Document where id=$1"));
        check("the two variants differ only in their access test",
            ContactRequestRules.MEMBER_DQL.substring(0, ContactRequestRules.MEMBER_DQL.indexOf("account"))
                .equals(ContactRequestRules.GUEST_DQL.substring(0, ContactRequestRules.GUEST_DQL.indexOf("lower"))));
        // Which of the two reads runs is decided by a parameter the ENDPOINT sets from the principal,
        // never by anything in the payload — so a caller cannot ask to be tested as the other kind.
        check("which test applies is a parameter, and it chooses the whole read",
            source.contains("guest ? GUEST_DQL : MEMBER_DQL"));

        // --- and the two mail endpoints agree about who may see a booking ---
        // The point of BookingMail holding these: a second copy written from memory
        // (person.frontendAccount = $2) refuses every member who reaches a booking through a link,
        // and looks entirely right while doing it.
        check("contact and refund use the SAME member test, character for character",
            RefundRequestRules.MEMBER_DQL.contains(BookingMail.MEMBER_TEST)
            && ContactRequestRules.MEMBER_DQL.contains(BookingMail.MEMBER_TEST));
        check("contact and refund use the SAME guest test, character for character",
            RefundRequestRules.GUEST_DQL.contains(BookingMail.GUEST_TEST)
            && ContactRequestRules.GUEST_DQL.contains(BookingMail.GUEST_TEST));

        // --- but this is not the refund: any booking you can see, you may ask about ---
        check("no overpayment clause, which belongs to the refund and not here",
            !ContactRequestRules.MEMBER_DQL.contains("price_deposit")
            && !ContactRequestRules.GUEST_DQL.contains("price_deposit"));

        // --- where it goes ---
        check("the mail is incoming, so the trigger addresses the centre",
            source.contains("values ($1, false,"));
        check("no recipient is written, which is what makes the database choose the address",
            !source.contains("insert into recipient"));
        // Pinned as the WHOLE column list rather than as "account_id is absent": prose about a column
        // counts as the column being mentioned, and the first version of this check failed on its own
        // javadoc. It is also the stronger assertion - any column added here has to come past it,
        // not just the one that was on my mind.
        check("the mail is written with exactly these columns, so the triggers fill the rest",
            source.contains("insert into mail (document_id, out, subject, content, from_name, from_email)"));

        // --- the caller's words are escaped, which the dialog did not do ---
        check("a script tag arrives as text, not as markup",
            "<html>&lt;script&gt;alert(1)&lt;/script&gt;</html>".equals(
                ContactRequestRules.content("<script>alert(1)</script>")));
        check("an anchor cannot be smuggled into the centre's mailbox",
            ContactRequestRules.content("<a href=\"http://evil\">click</a>").contains("&lt;a href=&quot;"));
        check("an ampersand is escaped once, not twice",
            "<html>Bob &amp; Sue</html>".equals(ContactRequestRules.content("Bob & Sue")));
        check("an apostrophe survives as an entity rather than breaking an attribute",
            ContactRequestRules.content("it's").contains("it&#39;s"));
        check("the line breaks the member typed are kept",
            "<html>one<br/>two</html>".equals(ContactRequestRules.content("one\ntwo")));
        check("and so are Windows line breaks",
            "<html>one<br/>two</html>".equals(ContactRequestRules.content("one\r\ntwo")));
        check("escaping happens BEFORE newlines become breaks, so a typed <br/> stays text",
            ContactRequestRules.content("<br/>").contains("&lt;br/&gt;"));

        // --- what the form could have produced, enforced where it counts ---
        check("an empty subject is refused", !ContactRequestRules.isSendable("", "hello"));
        check("an empty message is refused", !ContactRequestRules.isSendable("hi", ""));
        check("whitespace alone is empty", "".equals(ContactRequestRules.trimmed("  \n ")));
        check("a subject at the form's limit is accepted",
            ContactRequestRules.isSendable("s".repeat(ContactRequestRules.MAX_SUBJECT), "hello"));
        check("one character past it is not",
            !ContactRequestRules.isSendable("s".repeat(ContactRequestRules.MAX_SUBJECT + 1), "hello"));
        check("a message at the form's limit is accepted",
            ContactRequestRules.isSendable("hi", "m".repeat(ContactRequestRules.MAX_MESSAGE)));
        check("one character past it is not",
            !ContactRequestRules.isSendable("hi", "m".repeat(ContactRequestRules.MAX_MESSAGE + 1)));

        // --- the subject is a mail HEADER, so it has to be one line ---
        check("a newline cannot become a second header",
            "Hi Bcc: victim@example.com".equals(
                ContactRequestRules.singleLine("Hi\nBcc: victim@example.com")));
        check("nor can a carriage return",
            "Hi Bcc: victim@example.com".equals(
                ContactRequestRules.singleLine("Hi\rBcc: victim@example.com")));
        check("a tab is a control character too, and becomes a space",
            "a b".equals(ContactRequestRules.singleLine("a\tb")));
        check("so does DEL", "a b".equals(ContactRequestRules.singleLine("a\u007Fb")));
        check("a RUN of them is one space, not several",
            "a b".equals(ContactRequestRules.singleLine("a\r\n\tb")));
        check("ordinary text, including accents, is untouched",
            "Réservation — question".equals(ContactRequestRules.singleLine("  Réservation — question  ")));
        check("and it is the sanitised subject that is sent, not the raw one",
            source.contains("singleLine(rawSubject)"));

        // --- the subject the centre threads on ---
        check("the subject carries the event and the booking reference",
            "[1857-4242] My question".equals(ContactRequestRules.subject(1857, "4242", "My question")));
        check("a booking with no reference yet still gets a prefix",
            "[1857-] My question".equals(ContactRequestRules.subject(1857, null, "My question")));

        // --- the display name is cut rather than failing the send ---
        check("a name within the column is kept whole",
            "Ada Lovelace".equals(BookingMail.name("Ada", "Lovelace")));
        check("a name past the column is cut, not rejected",
            BookingMail.name("A".repeat(45), "B".repeat(45)).length() <= 64);
        check("no name at all is null rather than a blank string",
            BookingMail.name(null, null) == null);

        // --- and the endpoint is switched on ---
        String services = "";
        try (java.io.InputStream in = ContactRequestCheck.class.getClassLoader()
                .getResourceAsStream("META-INF/services/dev.webfx.stack.com.bus.call.spi.BusCallEndpoint")) {
            if (in != null)
                services = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            services = "(unreadable: " + e + ")";
        }
        check("ContactCentreEndpoint IS registered", services.contains("ContactCentreEndpoint"));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}
