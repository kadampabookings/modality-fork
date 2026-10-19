package one.modality.crm.server.authn.gateway.magiclink;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.base.shared.entities.FrontendAccount;
import one.modality.base.shared.entities.Person;

/**
 * Which account a typed recovery address belongs to: its sign-in address, or null when there is none.
 *
 * <p>People do not always type the address they sign in with. The common case is a household: a member's own
 * address is stored on their {@code person} row under the account of whoever manages it, so typing it finds no
 * account by username. Such an address resolves to the account it lives under, and the recovery mail goes to that
 * account's address — the person who can actually sign in.
 *
 * <p>This walk used to run in the browser (React {@code resolveEmailToAccountUsername}, 2026-10-19), which meant any
 * visitor could read the sign-in address of the account behind ANY {@code person.email}: type a child's address,
 * get the parent's. Here the answer goes into a mail and never back to the caller, whose reply is the same whether
 * an address resolved, resolved elsewhere, or did not resolve at all.
 *
 * <p>The order is the client's, unchanged: the sign-in address itself first; then a person carrying the address,
 * account owners before members, most recent first.
 *
 * @author Claude Code
 */
final class RecoveryAddressResolver {

    private RecoveryAddressResolver() {
    }

    static Future<String> accountUsernameFor(String typedEmail, DataSourceModel dataSourceModel) {
        String email = typedEmail == null ? "" : typedEmail.trim();
        if (email.isEmpty())
            return Future.succeededFuture(null);
        // As the server, not as the caller: who may read these rows is not the question here, and the caller learns
        // nothing from the answer. Only the synchronous part runs in this state — the second query is issued from a
        // completion, which carries no caller state either.
        return ThreadLocalStateHolder.runWithState(StateAccessor.createEmptyState(), () -> EntityStore.create(dataSourceModel)
            .<FrontendAccount>executeQuery("select username from FrontendAccount where corporation=$1 and lower(username)=lower($2) limit 1", 1, email)
            .compose(accounts -> {
                if (!accounts.isEmpty())
                    return Future.succeededFuture(accounts.get(0).getUsername());
                return EntityStore.create(dataSourceModel)
                    .<Person>executeQuery("select frontendAccount.username from Person where lower(email)=lower($1) and removed!=true and frontendAccount!=null and frontendAccount.corporation=$2 order by owner desc, id desc limit 1", email, 1)
                    .map(persons -> {
                        FrontendAccount account = persons.isEmpty() ? null : persons.get(0).getFrontendAccount();
                        String username = account == null ? null : account.getUsername();
                        return username == null || username.isBlank() ? null : username;
                    });
            }));
    }
}
