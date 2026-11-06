package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.db.query.QueryArgument;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;

/**
 * Raw SQL reads for the second-factor classes of this package (WebAuthnCredentialStore pattern): language = null ⇒ no
 * DQL compilation, native {@code $n} placeholders, and sendMetadata = false — results are read by position.
 *
 * @author Claude Code
 */
final class RawSql {

    private RawSql() {}

    static Future<QueryResult> query(String sql, Object... parameters) {
        return QueryService.executeQuery(new QueryArgument(
            null, DataSourceModelService.getDefaultDataSourceId(), null, null, sql, parameters, null, false, false));
    }
}
