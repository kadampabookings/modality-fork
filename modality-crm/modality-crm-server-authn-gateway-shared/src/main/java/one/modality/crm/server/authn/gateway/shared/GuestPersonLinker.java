package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.UpdateStore;
import one.modality.base.shared.entities.Person;

/**
 * Links unregistered (guest) Person records to a FrontendAccount when a user
 * creates an account or logs in after having previously booked as a guest.
 *
 * A guest booking creates a Person with the visitor's email but no
 * frontendAccount link. When the visitor later creates an account (or logs in
 * to an existing one) with the same email, this utility finds those unlinked
 * Person records and attaches them to the account, making the associated
 * Documents visible via accountCanAccessPersonOrders on the /orders page.
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
                return updateStore.submitChanges().mapEmpty();
            });
    }
}
