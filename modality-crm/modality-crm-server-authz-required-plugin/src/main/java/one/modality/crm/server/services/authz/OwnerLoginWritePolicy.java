package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.db.submit.ClientSubmitGuard;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.EntityStore;
import one.modality.base.shared.entities.Person;

import java.util.Arrays;
import java.util.Objects;

/**
 * Stops a client changing an account's sign-in address by the back door: the owner person's email.
 *
 * <h3>The door</h3>
 *
 * <p>A database trigger (V0062) copies an OWNER person's email into {@code frontend_account.username} whenever it
 * is updated. That is how KBS2's booking forms let an owner change their login ("modifying your email address will
 * also modify your login"). But {@code person} rows are not yet guarded by ownership, so from KBS3 a client could
 * update ANY owner's email, and so any account's login — then use "forgot password" at the new address. The
 * verified route, where the change is proved and the old address told, is the email-change flow; the server makes
 * that write itself, and never passes through here.
 *
 * <h3>The rule, for client updates of a person</h3>
 *
 * <ul>
 *   <li><b>{@code owner} may not be set to true</b> (nor to a value this cannot read): making somebody an owner
 *       would arm the trigger for their next email write. Setting it false is allowed — merging customers does,
 *       and it cannot change a login.</li>
 *   <li><b>An owner's {@code email} may not CHANGE.</b> Writing the value it already has is allowed. A value this
 *       cannot read counts as a change, and a write whose target row cannot be read from the statement is
 *       refused.</li>
 * </ul>
 *
 * <p>The other half is in the database (V0097): the trigger now reacts only to an email that actually changes. Before,
 * it fired whenever {@code email} was merely written, so re-sending an unchanged value — on a row inserted into
 * somebody else's account, or moved there — rewrote that account's login, and no rule about values could see it.
 *
 * <p>Inserts are left alone: the trigger fires on update only, and who may add a person to which account is the
 * ownership rule's question, not this one's. KBS2 writes to the database itself and is unaffected.
 *
 * @author Claude Code
 */
final class OwnerLoginWritePolicy implements ClientSubmitGuard.WritePolicy {

    static final String OWNER_EMAIL_REFUSED = "An account owner's email is changed through the email-change flow";
    static final String OWNER_FLAG_REFUSED = "A client may not make somebody an account owner";

    @Override
    public Future<String> refusalReason(ProtectedEntityWriteRegistry.WriteRequest write) {
        String refusal = refusalWithoutLookup(write);
        if (refusal != null || !needsLookup(write))
            return Future.succeededFuture(refusal);
        return EntityStore.create(DataSourceModelService.getDefaultDataSourceModel())
            .<Person>executeQuery("select owner,email from Person where id=$1", Numbers.toLong(write.targetId()))
            .map(persons -> persons.isEmpty()
                ? refusalAfterLookup(write, false, false, null)
                : refusalAfterLookup(write, true, Boolean.TRUE.equals(persons.get(0).isOwner()), persons.get(0).getEmail()));
    }

    /**
     * What can be refused from the statement alone, or null. Package-private for the check in
     * scripts/checks/client-write-guard, like the two below.
     */
    static String refusalWithoutLookup(ProtectedEntityWriteRegistry.WriteRequest write) {
        if (!isPersonUpdate(write))
            return null;
        if (writes(write, "owner") && !Boolean.FALSE.equals(write.writtenValues().get("owner")))
            return OWNER_FLAG_REFUSED;
        // Whose email? Without a row to look at, this cannot tell an owner's from a member's, so it refuses
        if (writes(write, "email") && Numbers.toLong(write.targetId()) == null)
            return ClientSubmitGuard.UNCHECKABLE_REFUSED;
        return null;
    }

    /** Whether the answer depends on the target row: an update that writes an email on a row picked by its id. */
    static boolean needsLookup(ProtectedEntityWriteRegistry.WriteRequest write) {
        return isPersonUpdate(write) && writes(write, "email") && Numbers.toLong(write.targetId()) != null;
    }

    /** The decision once the target row is known: null to allow, or the refusal. */
    static String refusalAfterLookup(ProtectedEntityWriteRegistry.WriteRequest write, boolean targetExists,
                                     boolean targetIsOwner, String currentEmail) {
        if (!targetExists || !targetIsOwner)
            return null; // no row to change, or a member's email, which is not a login
        Object newEmail = write.writtenValues().get("email");
        // Unchanged is allowed — though the trigger no longer reacts to it (V0097), and the back office no longer
        // sends it. Anything else, including a value this could not read (null), is a change to a login, and
        // belongs to the email-change flow.
        return newEmail != null && Objects.equals(newEmail, currentEmail) ? null : OWNER_EMAIL_REFUSED;
    }

    private static boolean isPersonUpdate(ProtectedEntityWriteRegistry.WriteRequest write) {
        return write != null && "Person".equals(write.entityName())
               && write.verb() == ProtectedEntityWriteRegistry.WriteVerb.UPDATE
               && write.writtenValues() != null;
    }

    /** Field names as the domain model spells them, which is how the inspector reports them. */
    private static boolean writes(ProtectedEntityWriteRegistry.WriteRequest write, String field) {
        return Arrays.asList(write.writtenFields()).contains(field);
    }
}
