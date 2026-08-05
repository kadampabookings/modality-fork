package one.modality.base.server.services.datasource;

import dev.webfx.platform.boot.ApplicationReadiness;
import dev.webfx.platform.boot.spi.ApplicationJob;
import dev.webfx.platform.conf.Config;
import dev.webfx.platform.conf.ConfigLoader;
import dev.webfx.stack.db.datasource.ConnectionDetails;
import dev.webfx.stack.db.datasource.DBMS;
import dev.webfx.stack.db.datasource.spi.simple.SimpleLocalDataSource;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.query.QueryService;

/**
 * @author Bruno Salmon
 */
public class ModalityLocalDataSourceInitializer implements ApplicationJob {

    private final static String CONFIG_PATH = "modality.base.server.datasource";

    @Override
    public void onInit() {
        // Hold /health at 503 — and thus block the CodeDeploy blue/green swap — until the datasource is not
        // merely reachable but WARM (its connection pool + query path can serve a client query fast).
        // Registered synchronously, before the async config load, so the gate is pending from the earliest
        // point. Without it, green's /health can go ready while its pool is still cold, so the swap cuts
        // traffic to a task whose first WebSocket/auth query stalls ~20s — the post-deploy "white page".
        Runnable markReady = ApplicationReadiness.registerPendingReadinessGate("db-warmup");

        // 1) Configuring the database connection
        ConfigLoader.onConfigLoaded(CONFIG_PATH, config -> {

            if (config == null) {
                log("❌ No configuration found for Modality datasource (check " + CONFIG_PATH + ")");
                markReady.run(); // nothing to warm — don't hold readiness (the DB-migration gate covers a broken DB)
                return;
            }

            ConnectionDetails connectionDetails = new ConnectionDetails(
                    config.getString("host"),
                    config.getInteger("port", -1),
                    config.getString("filePath"),
                    config.getString("databaseName"),
                    config.getString("url"),
                    config.getString("username"),
                    config.getString("password"),
                    // Max parallel read/write connections (-1 = provider default); tunable per environment
                    // because the read pool is the throughput bottleneck during a deploy reconnection stampede
                    getPositiveIntegerOrDefault(config, "queryPoolSize"),
                    getPositiveIntegerOrDefault(config, "submitPoolSize")
            );

            Object dataSourceId = ModalityLocalDataSourceProvider.getModalityDataSourceId();
            DBMS dbms = DBMS.POSTGRES;
            ModalityLocalDataSourceProvider.setModalityDataSource(new SimpleLocalDataSource(dataSourceId, dbms, connectionDetails));

            // 2) Warm the pool + query path with a trivial query, then release the readiness gate. A cheap
            //    "select 1" (not count(*), which would scan a large table and needlessly delay readiness)
            //    forces the pool's first — most expensive — connection (TLS + auth) so the first real
            //    client query after the swap is fast. On failure we STILL release the gate: a best-effort
            //    warmup must never independently fail a deploy (the DB-migration gate already gates on the
            //    database being reachable), it only shifts when the swap happens.
            QueryService.executeQuery(new QueryArgumentBuilder()
                            .setDataSourceId(dataSourceId)
                            .setStatement("select 1")
                            .build())
                    .onComplete(ar -> {
                        if (ar.succeeded())
                            log("✅ Successfully connected to Modality database (pool warmed — /health ready)");
                        else
                            log("⚠️ Modality database warmup query failed (" + ar.cause() + ") — releasing readiness anyway");
                        markReady.run();
                    });
        });

    }

    // Robust positive-integer read: the CI renders these keys through envsubst, so an unset environment
    // variable produces an EMPTY value (not an absent key), which must fall back to the provider default (-1)
    // rather than break the datasource initialisation.
    private int getPositiveIntegerOrDefault(Config config, String key) {
        try {
            Integer value = config.getInteger(key, -1);
            if (value != null && value > 0)
                return value;
        } catch (RuntimeException e) {
            log("⚠️ Ignoring invalid datasource config value for '" + key + "'");
        }
        return -1;
    }
}
