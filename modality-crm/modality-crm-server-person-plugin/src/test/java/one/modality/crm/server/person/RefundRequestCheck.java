package one.modality.crm.server.person;

/**
 * Check for the refund request's security properties, which are properties of its statement text.
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from main() and exits non-zero
 * on failure, like the other checks here.
 *
 * <p><b>What is pinned is what an edit could quietly undo.</b> The access test rides in the WHERE of
 * the read, so a booking this caller may not see is never loaded; moved out into a check afterwards it
 * would still look right and start composing mail about other people's bookings. And the absence of a
 * recipient is load-bearing rather than an omission: it is what makes the database address the mail to
 * the centre, so a well-meaning edit that "fixed" the missing recipient would be choosing an address —
 * the one thing this endpoint exists to take away from the client.
 */
public class RefundRequestCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) {
        System.out.println("RefundRequestCheck");
        String source = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/"
            + "one/modality/crm/server/person/RefundRequestRules.java");
        check("the rules source was found", !source.isEmpty());

        // --- the batch has to be able to work at all ---
        // The history row names the mail by the batch's generated key. Without " returning id" the
        // insert yields no key, the reference resolves to nothing and EVERY call fails - which the
        // first version of this check could not see, because it only read the statement's text and
        // the statement's text was the thing that was wrong.
        check("the mail insert returns its id, which the history row's reference needs",
            source.contains("values ($1, false, $2, $3, $4, $5) returning id"));
        check("and asks for generated keys, which is the other half of the same thing",
            source.contains("setReturnGeneratedKeys(true)"));
        check("the history row names the mail through the batch, not a second read",
            source.contains("new GeneratedKeyReference(0)"));
        // A foreign field evaluates to an EntityId, whose toString is "ID[Event:1857]" - which would
        // have gone into the subject line the centre threads replies on.
        check("the event id is a primary key, not an EntityId's toString",
            source.contains("Entities.getPrimaryKey(event)") && !source.contains("subject(order.evaluate"));

        // --- the caller may only ask about a booking they can already see ---
        // accountCanAccessPersonOrders spans TWO person rows (the document's person, and that
        // person's accountPerson link). A hand-written person.frontendAccount test would look right
        // and refuse every member who reaches their bookings through a link, so the domain model's
        // own function is used and its use is pinned here.
        check("the member test is the domain model's own function",
            RefundRequestRules.MEMBER_DQL.contains("accountCanAccessPersonOrders($2, person)"));
        // Guests book and overpay too, and the button is on their orders page. Their access is keyed
        // on the address their principal carries, exactly as the guest orders query keys it.
        check("guests are answered, by the address they proved",
            RefundRequestRules.GUEST_DQL.contains("lower(person_email)=lower($2)"));
        check("both tests ride in the WHERE of the read, not in a check afterwards",
            RefundRequestRules.MEMBER_DQL.contains("from Document where id=$1")
            && RefundRequestRules.GUEST_DQL.contains("from Document where id=$1"));
        // Built from one string so the select lists cannot drift apart - the guest branch is the one
        // nobody looks at.
        check("the two variants differ only in their access test",
            RefundRequestRules.MEMBER_DQL.substring(0, RefundRequestRules.MEMBER_DQL.indexOf("account"))
                .equals(RefundRequestRules.GUEST_DQL.substring(0, RefundRequestRules.GUEST_DQL.indexOf("lower"))));
        // Without this, any booking on the caller's account qualifies and an amount read as a
        // distance turns money still OWED into a refund request.
        check("only an overpaid booking can be asked about",
            RefundRequestRules.MEMBER_DQL.contains("price_deposit > price_net")
            && RefundRequestRules.GUEST_DQL.contains("price_deposit > price_net"));
        check("the scope asked about comes from the principal, not the argument",
            source.contains("Object callerScope") && !source.contains("callerScope = argument"));

        // --- nothing about the message comes from the caller ---
        check("the mail is incoming, so the trigger addresses the centre",
            source.contains("insert into mail (document_id, out, subject, content, from_name, from_email)")
            && source.contains("values ($1, false, $2, $3, $4, $5)"));
        // The absence that matters. No recipient row means auto_recipient derives one; writing one
        // here would be choosing an address, which is what a client used to do.
        check("no recipient is written, which is what makes the database choose the address",
            !source.contains("insert into recipient"));
        check("the subject and body are composed here, from the booking",
            source.contains("static String subject(") && source.contains("static String content("));

        // --- the amount reads the way the screen read it ---
        // Deposit first would read as a debt and yield nothing: paid 200.00 against a 76.55 booking.
        check("minor units, two decimals", "£123.45".equals(
            RefundRequestRules.amount(7655, 20000, null)));
        check("the currency symbol is used when there is one", "€5.00".equals(
            RefundRequestRules.amount(1000, 1500, "€")));
        // A DIRECTION, not a distance. The previous version of this check asserted the opposite and
        // called it desirable, which is how "you owe £41" would have become a refund request.
        check("money still owed is not a refund", "£0.00".equals(
            RefundRequestRules.amount(4100, 100, "£")));
        check("an overpayment is", "£40.00".equals(
            RefundRequestRules.amount(100, 4100, "£")));
        check("a missing price is nothing rather than a crash", "£0.00".equals(
            RefundRequestRules.amount(null, null, "£")));
        check("the subject carries the event and the booking reference",
            "[1857-4242] Refund of £10.00 requested"
                .equals(RefundRequestRules.subject(1857, "4242", "£10.00")));

        // --- and the endpoint is switched on ---
        String services = "";
        try (java.io.InputStream in = RefundRequestCheck.class.getClassLoader()
                .getResourceAsStream("META-INF/services/dev.webfx.stack.com.bus.call.spi.BusCallEndpoint")) {
            if (in != null)
                services = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            services = "(unreadable: " + e + ")";
        }
        check("RequestRefundEndpoint IS registered", services.contains("RequestRefundEndpoint"));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}
