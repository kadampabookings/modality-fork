package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.collection.Collections;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.entity.EntityStore;
import one.modality.base.shared.entities.Person;

/**
 * Resolves WHICH person a credential-based login signs in, for gateways that already know the
 * {@code frontend_account} (the password gateway resolves account and person in one query; the
 * passkey gateway proves the account first via the credential and resolves the person here).
 *
 * <p>One home for the account-login fences and the owner-first ordering, because this rule has
 * been wrong in production before: resolving by lowest person id signed people in as another
 * (sometimes live) member of the same account — see the ORDER BY commentary in
 * {@link MagicLinkService#loadUserPersonFromMagicLink}, which records the incident and why the
 * account OWNER must win. Every fence here mirrors the password gateway's login query:
 * corporation, {@code !disabled}, and the {@code backoffice} flag when the login happens in a
 * back-office context.
 *
 * <p>{@code removed} is FILTERED here (like the password gateway), not sorted-last (like the
 * magic-link resolution): a login method that substitutes for the password must refuse whenever
 * the password would, so an account whose every person row is soft-deleted cannot sign in with a
 * passkey either. Magic links deliberately keep those accounts reachable (their javadoc explains
 * why); that looser rule stays theirs.
 *
 * @author Claude Code
 */
public final class LoginPersonResolver {

    private LoginPersonResolver() {
    }

    /**
     * Loads the live person to sign in for the given account, or a null-completing future when the
     * account has no eligible person (unknown, disabled, corporation mismatch, missing back-office
     * flag in a back-office login, or no live person row).
     *
     * @param frontendAccountId  the {@code frontend_account} id the credential proved possession of
     * @param requireBackoffice  true when the login context is the back office — adds the
     *                           {@code backoffice} account fence, exactly like the password gateway
     * @param dataSourceModel    the data source to query
     */
    public static Future<Person> loadLiveLoginPersonForAccount(Object frontendAccountId, boolean requireBackoffice, DataSourceModel dataSourceModel) {
        return EntityStore.create(dataSourceModel)
            .<Person>executeQuery("select id from Person where !removed and frontendAccount.(id=$1 and corporation=$2 and !disabled and ($3=false or backoffice)) order by owner desc, id limit 1",
                frontendAccountId, 1, requireBackoffice)
            .map(Collections::first);
    }
}
