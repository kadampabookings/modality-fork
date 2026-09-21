package one.modality.crm.server.person;

import dev.webfx.platform.async.Batch;
import dev.webfx.platform.async.Future;
import dev.webfx.stack.db.submit.GeneratedKeyReference;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.EntityStore;
import one.modality.base.shared.entities.Document;

import java.util.Locale;

/**
 * "I would like the overpayment on my cancelled booking back" — composed by the server.
 *
 * <p>The browser used to compose this mail itself: it chose the subject, the sentence, the amount and
 * the document the mail hangs off, and inserted the row. Everything in it is a fact about the booking,
 * so none of it was the browser's to choose — and a client that can insert a mail row can insert any
 * mail row. The caller now sends one document id and nothing else.
 *
 * <h3>Who may ask</h3>
 *
 * <p>Whoever can already see the booking on their orders page, and by the same test that page uses.
 * There are two, because there are two kinds of booker:
 *
 * <ul>
 *   <li>a member, tested with {@code accountCanAccessPersonOrders} — the domain model's own inline
 *       function, shared with the orders query, the legacy orders activity and the server document
 *       service. Reused rather than re-derived on purpose: it spans TWO person rows (the document's
 *       person, and that person's accountPerson link), so a hand-written
 *       {@code person.frontendAccount = $2} would look right and quietly refuse every member who
 *       reaches their bookings through a link.</li>
 *   <li>a guest, tested on the address their principal carries against the booking's own — the guest
 *       variant of the same orders query. Guests overpay and ask for refunds like anybody else, and
 *       the button is on their orders page too; refusing them here would have taken a working feature
 *       away and told them only that something failed.</li>
 * </ul>
 *
 * <p>Both tests ride in the WHERE of the read, so a booking the caller may not see is never loaded.
 *
 * <h3>And only where there is something to refund</h3>
 *
 * <p>{@code price_deposit > price_net} is part of the read, not a courtesy: without it any booking on
 * the caller's account qualifies, and an amount computed as a distance rather than a direction turns
 * £41 still OWED into "Refund of £41.00 requested" in the centre's mailbox. The screen only ever
 * offered this on an overpayment; that invariant now lives where it can be enforced.
 *
 * <h3>Where it goes, and why no recipient is written</h3>
 *
 * <p>{@code out=false} with a document, and no recipient row: the {@code auto_recipient} trigger then
 * addresses it to the mail account's own mailbox — the centre. That is the existing behaviour and it
 * is why this endpoint needs no recipient logic at all. Writing one here would be choosing an address,
 * which is the thing being taken away from clients.
 *
 * <p><b>Not closed, and the same as before this change:</b> nothing stops the same booking being asked
 * about twice. The screen never prevented it either — the legacy code carries the TODO — and the reach
 * is the caller's own bookings and the centre's own mailbox, so it is noise rather than disclosure. It
 * belongs with the "has a refund already been requested" state the orders page still lacks.
 *
 * @author Claude Code
 */
final class RefundRequestRules {

    private RefundRequestRules() {
    }

    /** What {@code mail.from_name} holds. Longer used to fail the request rather than send it. */
    private static final int MAX_FROM_NAME = 64;

    /** Not this caller's booking, not overpaid, or not a booking at all. One message for all of them. */
    static final String NOT_YOURS_KEY = "RefundNotYourBookingError";

    /**
     * Everything the message says, read from the booking — one variant per kind of booker.
     *
     * <p>Only the access test differs, so they are built from one string: a select list that drifted
     * between them would be a difference nobody meant, and the guest branch is the one nobody looks at.
     */
    private static final String ORDER_DQL =
        "select ref, price_net, price_deposit, person_firstName, person_lastName, person_email, event," +
        " event.(currency.symbol, organization.country.currency.symbol)" +
        " from Document where id=$1 and price_deposit > price_net and ";

    static final String MEMBER_DQL = ORDER_DQL + "accountCanAccessPersonOrders($2, person)";

    static final String GUEST_DQL = ORDER_DQL + "lower(person_email)=lower($2)";

    /** The caller's own sign-in address, for the reply a member without their own address still needs. */
    private static final String OWNER_ADDRESS_DQL = "select username from FrontendAccount where id=$1";

    /** The subject and body the browser used to build, kept word for word. */
    static String subject(Object eventId, String ref, String amount) {
        return "[" + eventId + "-" + (ref == null ? "" : ref) + "] Refund of " + amount + " requested";
    }

    static String content(String amount) {
        return "<html>The user has requested a refund for his canceled booking. Amount : " + amount + "</html>";
    }

    /**
     * The overpayment, formatted as the orders page formats it: minor units, two decimals, symbol first.
     *
     * <p>A DIRECTION, not a distance — see the class note. A booking that owes money yields nothing to
     * refund, and the read refuses it before this is reached; returning zero here keeps the two from
     * disagreeing if that ever changes.
     *
     * <p>{@link Locale#ROOT} because the pence are digits in a subject line, and a JVM started in a
     * locale whose numbering system is not latin would otherwise render them in another script.
     */
    static String amount(Integer priceNet, Integer priceDeposit, String currencySymbol) {
        long net = priceNet == null ? 0 : priceNet;
        long deposit = priceDeposit == null ? 0 : priceDeposit;
        long minorUnits = Math.max(0, deposit - net);
        String symbol = currencySymbol == null || currencySymbol.isEmpty() ? "£" : currencySymbol;
        return symbol + (minorUnits / 100) + "." + String.format(Locale.ROOT, "%02d", minorUnits % 100);
    }

    /**
     * Records the request as a mail to the centre.
     *
     * @param documentId   the booking, which must be one this caller can see and must be overpaid
     * @param callerScope  the caller's account id for a member, or their proven address for a guest
     * @param guest        which of the two tests to apply — decided from the principal, never the argument
     * @param callerUserId the caller's principal, READ ON THE CALLER'S THREAD, so the rows carry an
     *                     author the way every other server write in this package does
     */
    static Future<Object> request(Object documentId, Object callerScope, boolean guest, Object callerUserId) {
        if (documentId == null || callerScope == null)
            return refused();
        EntityStore entityStore = EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
        return ServerWrite.asServer(() -> entityStore.<Document>executeQuery(
                guest ? GUEST_DQL : MEMBER_DQL, documentId, callerScope))
            .compose(rows -> rows == null || rows.isEmpty() ? refused()
                : replyAddress(rows.get(0), callerScope, guest, entityStore)
                    .compose(replyTo -> send(rows.get(0), documentId, replyTo, callerUserId)));
    }

    /**
     * Where the centre should reply.
     *
     * <p>The booking's own address first. A member whose person carries none is reached through their
     * account owner — {@code person_email} is NULL for most of them — so the caller's own sign-in
     * address stands in, which is what the dialog told them would happen and what it still shows them
     * on screen. A guest has only ever had the one address, and it is the one they proved.
     */
    private static Future<String> replyAddress(Document order, Object callerScope, boolean guest,
                                               EntityStore entityStore) {
        String own = order.getStringFieldValue("person_email");
        if (own != null && !own.isBlank())
            return Future.succeededFuture(own);
        if (guest)
            return Future.succeededFuture(String.valueOf(callerScope));
        return ServerWrite.asServer(() -> entityStore.executeQuery(OWNER_ADDRESS_DQL, callerScope))
            .map(accounts -> accounts == null || accounts.isEmpty() ? null
                : accounts.get(0).getStringFieldValue("username"));
    }

    private static Future<Object> send(Document order, Object documentId, String replyTo, Object callerUserId) {
        // Event currency first, then the organisation's country's - the order the orders page resolves
        // them in, so the mail reads the same as the screen the member asked from.
        Object symbol = order.evaluate("event.currency.symbol");
        if (!(symbol instanceof String) || ((String) symbol).isEmpty())
            symbol = order.evaluate("event.organization.country.currency.symbol");
        String amount = amount(order.getPriceNet(), order.getPriceDeposit(),
            symbol instanceof String text ? text : null);
        // getPrimaryKey, because a foreign field evaluates to an EntityId whose toString is
        // "ID[Event:1857]" - which would have gone into the subject line the centre threads on.
        Object event = order.evaluate("event"); // via a variable: evaluate() is generic, and the
        Object eventId = Entities.getPrimaryKey(event); // Entity/EntityId overloads are ambiguous
        Integer ref = order.getRef();
        String subject = subject(eventId, ref == null ? null : String.valueOf(ref), amount);
        String fromName = name(order.getStringFieldValue("person_firstName"),
            order.getStringFieldValue("person_lastName"));

        SubmitArgument mail = new SubmitArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            // " returning id" is load-bearing, as it is everywhere else in this package: without it the
            // batch carries no generated key, and the history row's reference below resolves to nothing.
            .setStatement("insert into mail (document_id, out, subject, content, from_name, from_email)" +
                          " values ($1, false, $2, $3, $4, $5) returning id")
            .setParameters(documentId, subject, content(amount), fromName, replyTo)
            .setReturnGeneratedKeys(true)
            .build();
        // The history line the screen used to write, kept so the centre sees the request in the
        // booking's own trail. It names the mail through the batch's generated key rather than a
        // second read - the same mechanism a client change set uses, here on the server's side of
        // the guard.
        SubmitArgument history = statement(
            "insert into history (document_id, mail_id, username, comment)" +
            " values ($1, $2, 'online', $3)",
            documentId, new GeneratedKeyReference(0), "Sent " + subject);
        return ServerWrite.asServerActingFor(callerUserId,
                () -> SubmitService.executeSubmitBatch(new Batch<>(new SubmitArgument[] { mail, history })))
            .map(ignored -> (Object) Boolean.TRUE);
    }

    private static <T> Future<T> refused() {
        return Future.failedFuture("[" + NOT_YOURS_KEY + "] This operation is not available to you");
    }

    /**
     * The booker's name for the mail's display name, cut to what the column holds.
     *
     * <p>{@code mail.from_name} is 64 characters and the two document columns it is built from are 45
     * each, so a long name used to fail the whole request on a PG 22001 rather than sending. The
     * browser built the same string into the same column and had the same problem; the difference is
     * that the server is somewhere it can be cut.
     */
    private static String name(String first, String last) {
        String joined = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        if (joined.isEmpty())
            return null;
        return joined.length() <= MAX_FROM_NAME ? joined : joined.substring(0, MAX_FROM_NAME).trim();
    }

    private static SubmitArgument statement(String sql, Object... parameters) {
        return new SubmitArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(sql)
            .setParameters(parameters)
            .build();
    }
}
