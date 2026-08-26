package one.modality.ecommerce.document.service;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;

/**
 * Server-side extension point: stores a BOOKING_ACCESS magic link record in the
 * database after a guest booking is successfully submitted.
 *
 * The implementation mints the link's bearer token itself, from a cryptographically
 * secure source. It used to be passed in from ServerDocumentServiceProvider, which
 * generated it with the GWT-compatible Uuid helper — Math.random(), i.e. a 48-bit LCG
 * on the JVM, whose whole output stream follows from a couple of observed values. The
 * parameter is gone rather than fixed in place so no future caller can reintroduce a
 * weak one. (The document.magic_link_token round-trip that once justified passing it
 * is long gone: [bookingUrl] derives the cart URL from person.frontend_account_id.)
 *
 * The confirmation email itself is handled entirely by the existing database-driven
 * letter system (trigger_document_generate_mails_on_booking + interpret_brackets).
 *
 * The interface lives in modality-ecommerce-document-service so that
 * ServerDocumentServiceProvider can call it without a circular dependency on
 * modality-crm. The implementation is in the CRM magic-link plugin and discovered
 * via ServiceLoader.
 *
 * @author Bruno Salmon
 */
public interface GuestBookingAccessService {

    /**
     * Persist the magic_link record for a guest booking and link it to the booking cart.
     * The cart link enables /cart/:cartUuid authentication and invalidation when the
     * guest later creates an account.
     *
     * @param documentPk     primary key of the newly created Document (used as requestedPath)
     * @param cartPk         primary key of the booking cart to link to the magic link
     * @param personEmail    guest email address
     * @param personLang     guest preferred language (2-char code, e.g. "en")
     * @param clientOrigin   frontend origin used to compose magic_link.link, e.g. "https://kbs.kadampa.net"
     * @param dataSourceModel data source to write to
     * @return future that completes when the magic_link record is stored and cart linked
     */
    Future<Void> registerBookingAccessMagicLink(
        Object documentPk,
        Object cartPk,
        String personEmail,
        String personLang,
        String clientOrigin,
        DataSourceModel dataSourceModel
    );
}
