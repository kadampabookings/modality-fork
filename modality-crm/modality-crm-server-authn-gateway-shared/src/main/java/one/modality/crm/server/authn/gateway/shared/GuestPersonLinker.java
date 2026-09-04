package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.UpdateStore;
import one.modality.base.shared.entities.Cart;
import one.modality.base.shared.entities.Document;
import one.modality.base.shared.entities.MagicLink;
import one.modality.base.shared.entities.MagicLinkType;
import one.modality.base.shared.entities.Person;

/**
 * Attaches what a visitor booked as a guest to the account they later create or
 * sign in to, matched on the email address they proved control of.
 *
 * Two shapes of guest booking exist:
 * <ul>
 *   <li>legacy flows created a Person carrying the visitor's email but no
 *       frontendAccount — {@link #linkGuestPersonsToAccount} attaches that Person
 *       to the account, and the Documents follow through it;</li>
 *   <li>the React front office books guests with NO Person at all: the Document
 *       carries only the person_* capture columns, person_email among them —
 *       {@link #linkGuestDocumentsToPerson} points those Documents at the
 *       account's owner Person, since nothing else could ever reach them (the
 *       orders and media queries all go through document.person).</li>
 * </ul>
 * Both make the bookings visible via accountCanAccessPersonOrders on /orders.
 *
 * @author Bruno Salmon
 */
public final class GuestPersonLinker {

    private GuestPersonLinker() {}

    /**
     * Finds all Person records whose email matches {@code email} (case-insensitive)
     * and that have no FrontendAccount yet, then links them to {@code accountPrimaryKey}.
     *
     * This is a best-effort background operation — failures are logged but do not
     * block the authentication response.
     *
     * @param email              the email used to find matching guest Person records
     * @param accountPrimaryKey  the FrontendAccount primary key to link them to
     * @param dataSourceModel    data source to read/write
     */
    public static Future<Void> linkGuestPersonsToAccount(String email, Object accountPrimaryKey, DataSourceModel dataSourceModel) {
        return EntityStore.create(dataSourceModel)
            .<Person>executeQuery(
                "select id from Person where lower(email)=lower($1) and frontendAccount=null",
                email)
            .compose(persons -> {
                if (persons.isEmpty())
                    return Future.succeededFuture();
                UpdateStore updateStore = UpdateStore.create(dataSourceModel);
                for (Person person : persons)
                    updateStore.updateEntity(person).setForeignField("frontendAccount", accountPrimaryKey);
                return updateStore.submitChanges()
                    .compose(ignored -> clearCartMagicLinksForEmail(email, dataSourceModel));
            });
    }

    /**
     * Points every Document booked as a guest under {@code email} (no person, matching
     * person_email) at {@code personPrimaryKey} — the owner Person of the account that just
     * signed in with that address — then retires the cart's guest link, since the booking is
     * reachable through the account from here on. Best-effort background operation, like
     * {@link #linkGuestPersonsToAccount}: failures are logged, never block the sign-in.
     *
     * <p>Proof of the address is the whole question. A magic link or code redeemed in THIS
     * sign-in proves it ({@code addressVerified = true}). A password proves only the account:
     * its username follows the owner Person's email, which the front office can rewrite without
     * any verification, so a member could point their username at a stranger's address and sign
     * in with their own password. And the proof cannot be merely address-level either — "some
     * link for this address was redeemed once" would let a redemption by whoever held that
     * inbox years ago (an abandoned sign-up, a since-dissolved account) vouch for whoever holds
     * the username today. So on the password path the address must have been verified by THIS
     * session: a LOGIN link or code for it redeemed under the same runId that is now signing in
     * (supersession stamps the date only, so a merely requested code never counts). That is
     * exactly the shape of every account-creation flow — verify the address, create the Person,
     * sign in with the password from the same tab — and nothing else, which is the point.
     *
     * <p>The Document's person_* capture columns stay as typed at booking time (the capture
     * trigger runs on insert only) — until the account holder edits their profile, when the
     * upcoming-bookings sync (V0080) refreshes them from the holder's details, as for any of
     * their bookings. The address check runs only when person-less bookings exist for the email
     * (rare per sign-in), which keeps the un-indexed magic_link lookup off the common path.
     *
     * @param email             the address the session signed in with (the account's username)
     * @param personPrimaryKey  the Person to attach the bookings to
     * @param addressVerified   true when this very sign-in redeemed a link or code mailed to {@code email}
     * @param sessionRunId      the signing-in session's runId (captured before any async step); when
     *                          {@code addressVerified} is false, a LOGIN link for {@code email} must
     *                          have been redeemed under it — null then attaches nothing
     * @param dataSourceModel   data source to read/write
     */
    public static Future<Void> linkGuestDocumentsToPerson(String email, Object personPrimaryKey, boolean addressVerified, String sessionRunId, DataSourceModel dataSourceModel) {
        if (!addressVerified && sessionRunId == null)
            return Future.succeededFuture();
        EntityStore entityStore = EntityStore.create(dataSourceModel);
        return entityStore
            .<Document>executeQuery(
                "select id from Document where person=null and lower(person_email)=lower($1)",
                email)
            .compose(documents -> {
                if (documents.isEmpty())
                    return Future.succeededFuture();
                Future<Boolean> verified = addressVerified
                    ? Future.succeededFuture(true)
                    : entityStore.<MagicLink>executeQuery(
                        "select id from MagicLink where lower(email)=lower($1) and linkType=$2 and usageRunId=$3 limit 1",
                        email, MagicLinkType.LOGIN.name(), sessionRunId)
                      .map(redeemed -> !redeemed.isEmpty());
                return verified.compose(ok -> {
                    if (!ok)
                        return Future.succeededFuture();
                    UpdateStore updateStore = UpdateStore.createAbove(documents.getStore());
                    for (Document document : documents)
                        updateStore.updateEntity(document).setForeignField("person", personPrimaryKey);
                    return updateStore.submitChanges()
                        .compose(ignored -> clearCartMagicLinksForEmail(email, dataSourceModel));
                });
            });
    }

    /**
     * Clears the magic_link association from all carts belonging to the given email's
     * guest bookings. This invalidates the long-lived /cart/:cartUuid guest access
     * after the person has created a registered account — access now requires login.
     */
    private static Future<Void> clearCartMagicLinksForEmail(String email, DataSourceModel dataSourceModel) {
        return EntityStore.create(dataSourceModel)
            .<Cart>executeQuery(
                "select id from Cart c where magicLink!=null and exists(select Document d where d.cart=c and lower(d.person_email)=lower($1))",
                email)
            .compose(carts -> {
                if (carts.isEmpty())
                    return Future.succeededFuture();
                UpdateStore updateStore = UpdateStore.create(dataSourceModel);
                for (Cart cart : carts)
                    updateStore.updateEntity(cart).setMagicLink(null);
                return updateStore.submitChanges().mapEmpty();
            });
    }
}
