package one.modality.crm.server.person;

/**
 * The operations the customers screen's writes require — seeded by V0134, to every role that could open that
 * screen when they were introduced, so nobody lost anything on the day.
 *
 * <p><b>Why codes, when the route grant was already checked on the server.</b> Opening /customers used to be
 * the right to change people: whoever could see the list could edit, merge and remove anyone in it. Merging and
 * removing are destructive and irreversible, and an organization may reasonably want somebody to look people up
 * without being able to delete them. Separate codes make each act grantable on its own — the shape
 * {@code ViewAsCustomer} already has for the impersonation control on the same screen.
 *
 * <p>No {@code grant_route}: each gates a control, not a page (the V0106 precedent).
 *
 * @author Claude Code
 */
final class CustomerOperations {

    private CustomerOperations() {
    }

    /** Edit a person's details from the customers screen (UpdateCustomerEndpoint). */
    static final String EDIT_CUSTOMERS = "EditCustomers";

    /** Merge people or accounts (MergeDuplicatePersonsEndpoint, MergeIntoAccountEndpoint). */
    static final String MERGE_CUSTOMERS = "MergeCustomers";

    /** Remove people from the customers list (RemoveCustomersEndpoint). */
    static final String REMOVE_CUSTOMERS = "RemoveCustomers";
}
