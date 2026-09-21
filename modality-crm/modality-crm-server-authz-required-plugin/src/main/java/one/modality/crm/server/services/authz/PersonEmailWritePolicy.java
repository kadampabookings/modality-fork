package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Arrays;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.db.submit.ClientSubmitGuard;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.base.shared.entities.Person;
import one.modality.crm.server.authn.gateway.shared.RoleOperationMembership;
import one.modality.crm.server.authn.gateway.shared.SuperAdminMembership;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

/**
 * A member may change an email address on their OWN account's people, and nowhere else.
 *
 * <p>{@code OwnerLoginWritePolicy} already refuses changing an account OWNER's email, because that is
 * their sign-in address. This is about everybody else's — the member rows a centre creates when it
 * books for somebody who has no account yet — and it exists because of what that address now buys.
 *
 * <h3>Why it was worth adding before {@code person} closes</h3>
 *
 * <p>{@code claimMembers} accepts a member row as "this is me" when it carries the caller's verified
 * sign-in address. Until this rule, an ordinary client write could supply that: not a raw statement,
 * the shape a change set produces — {@code update Person set email=$1 where id=$2}, bounded by id so
 * the unbounded rule allows it, against a non-owner so the owner rule allows it.
 *
 * <p><b>And it was set-based in practice.</b> One batch could point any number of strangers' rows at
 * the attacker's own address, and one {@code claimMembers()} with no narrowing then linked all of
 * them — taking those people's recordings out of the accounts that booked for them and sharing their
 * orders. Person ids are small and sequential, so the sweep needs no read access at all. The rows it
 * reaches are exactly the population the claim exists for: people with no account, who will not
 * notice.
 *
 * <h3>Why "the caller's own account", and why staff are excepted</h3>
 *
 * <p>The React front office only ever edits people in the caller's own account — its two person-edit
 * paths are server endpoints that say so in their statements. The back office edits anybody's, which
 * is what a customers screen is; that is why staff are excepted, by the same test
 * {@link PersonAccountMovePolicy} uses ({@code RouteToCustomers} through a role, or a super admin) and
 * for the same reason: the screen requiring that grant is the one doing the editing.
 *
 * <p>The exception is not a hole in the attack above: a member sweeping strangers' rows holds no such
 * grant, and one who does can already reach those people through the screens directly.
 *
 * <p><b>Updates only.</b> An insert has no existing row to be somebody else's, and refusing one would
 * break the legacy back office's booking creation, which records a booker's address on a new person.
 * This is a narrowing of the forge, not the close — {@code person.email} denied outright, or person
 * ownership, is what closes it.
 *
 * @author Claude Code
 */
final class PersonEmailWritePolicy implements ClientSubmitGuard.WritePolicy {

    /** Deliberately the same shape of sentence as the sibling rules, and as uninformative. */
    static final String NOT_YOURS_REFUSED = "An email address can only be changed on your own account's people";

    /** Whether this write changes an email on an existing person. */
    static boolean isEmailUpdate(ProtectedEntityWriteRegistry.WriteRequest write) {
        return write != null
               && "Person".equals(write.entityName())
               && write.verb() == ProtectedEntityWriteRegistry.WriteVerb.UPDATE
               && write.writtenFields() != null
               && Arrays.asList(write.writtenFields()).contains("email");
    }

    /**
     * Refused before any lookup where the answer cannot depend on one.
     *
     * <p>An UPDATE that does not name its row by id cannot be checked against an account at all, so it
     * is refused outright — the same reading of a null target every rule here makes: unknown, not
     * unconstrained.
     */
    static String refusalWithoutLookup(ProtectedEntityWriteRegistry.WriteRequest write, Object userId) {
        if (!isEmailUpdate(write))
            return null;
        if (!(userId instanceof ModalityUserPrincipal principal) || principal.isSupportView())
            return NOT_YOURS_REFUSED;
        if (principal.getUserAccountId() == null || Numbers.toLong(write.targetId()) == null)
            return NOT_YOURS_REFUSED;
        return null;
    }

    /** Given the target's account and whether the caller is staff, may this write run? */
    static String refusalAfterLookup(Object targetAccountId, Object callerAccountId, boolean callerIsStaff) {
        if (callerIsStaff)
            return null;
        // Fails closed on a row that could not be read: a person who does not exist, or one this could
        // not resolve, is not "not somebody else's".
        return targetAccountId != null && targetAccountId.equals(callerAccountId) ? null : NOT_YOURS_REFUSED;
    }

    @Override
    public Future<String> refusalReason(ProtectedEntityWriteRegistry.WriteRequest write) {
        // Read on the caller's thread: the guard asks synchronously, and the state is gone after the
        // first async hop.
        Object userId = ThreadLocalStateHolder.getUserId();
        if (!isEmailUpdate(write))
            return Future.succeededFuture(null);
        String refusal = refusalWithoutLookup(write, userId);
        if (refusal != null)
            return Future.succeededFuture(refusal);
        ModalityUserPrincipal principal = (ModalityUserPrincipal) userId;
        Object callerPersonId = principal.getUserPersonId();
        Object callerAccountId = Numbers.toLong(principal.getUserAccountId());
        EntityStore entityStore = EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
        return Future.all(
            SuperAdminMembership.isSuperAdminPerson(callerPersonId, entityStore),
            RoleOperationMembership.holdsThroughRole(callerPersonId,
                PersonAccountMovePolicy.MOVE_PEOPLE_OPERATION, entityStore),
            entityStore.<Person>executeQuery("select frontendAccount.id from Person where id=$1",
                Numbers.toLong(write.targetId()))
        ).compose(answers -> {
            boolean staff = Boolean.TRUE.equals(answers.resultAt(0))
                            || Boolean.TRUE.equals(answers.resultAt(1));
            java.util.List<Person> rows = answers.resultAt(2);
            Object targetAccountId = rows == null || rows.isEmpty() ? null
                : Numbers.toLong(rows.get(0).getForeignEntityId("frontendAccount"));
            return Future.succeededFuture(refusalAfterLookup(targetAccountId, callerAccountId, staff));
        });
    }
}
