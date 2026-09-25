package one.modality.ecommerce.document.service;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;

/**
 * Server-side extension point: keeps a booking cart's BOOKING_ACCESS link — what lets somebody
 * without an account open their booking from the letter's /cart/:cartUuid button — in line with
 * the bookings in it, after a booking in that cart is submitted.
 *
 * The implementation decides everything from the database: whether the cart gets a link at all
 * (every booking in it for one address, none belonging to an account, none billed to a payer),
 * the address it is for, the host it points at (this server's configured front office), and the
 * bearer token, minted from a cryptographically secure source. The caller supplies none of them.
 * The token parameter went first — ServerDocumentServiceProvider generated it with the
 * GWT-compatible Uuid helper, Math.random(), i.e. a 48-bit LCG on the JVM — and the address and
 * origin followed, so no future caller can hand the link a weak token, somebody else's address or
 * its own host.
 *
 * The confirmation email itself is handled entirely by the existing database-driven
 * letter system (trigger_document_generate_mails_on_booking + interpret_brackets).
 *
 * The interface lives in modality-ecommerce-document-service so that
 * ServerDocumentServiceProvider can call it without a circular dependency on
 * modality-crm. The implementation is in the CRM guest gateway plugin and discovered
 * via ServiceLoader.
 *
 * @author Bruno Salmon
 */
public interface GuestBookingAccessService {

    /**
     * Gives the cart the guest link its bookings call for — keeping a live one it already has — or
     * withdraws the link it should no longer carry.
     *
     * @param cartPk          primary key of the booking cart a booking was just submitted into
     * @param dataSourceModel data source to read and write
     * @return future that completes once the cart's link is in line with its bookings
     */
    Future<Void> syncCartAccessLink(Object cartPk, DataSourceModel dataSourceModel);
}
