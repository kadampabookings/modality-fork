package one.modality.crm.server.authsession;

import dev.webfx.platform.boot.spi.ApplicationJob;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.scheduler.Scheduled;
import dev.webfx.platform.scheduler.Scheduler;
import dev.webfx.stack.db.datasource.LocalDataSourceService;
import dev.webfx.stack.session.token.SessionFamilyStoreRegistry;

/**
 * Installs the {@code auth_session} store, and keeps the table from becoming session history.
 *
 * <p>A plugin module is only built into a server if a plugin list names it, so a deployment that omits
 * this one still works: sessions slide and are capped from the signed token alone, but nothing rotates
 * and a stolen token stays undetectable. That is stated at boot by the registry rather than left to be
 * inferred from the absence of a log line.
 *
 * @author Claude Code
 */
public final class ModalityAuthSessionStoreInitializer implements ApplicationJob {

    /**
     * How often expired rows are removed.
     *
     * <p>Hourly rather than daily because the value of this sweep is that a record of when somebody was
     * online does not accumulate — and rarely rather than continuously because the rows it deletes are
     * already dead, so nothing depends on the delay. The first run is deferred so it does not compete
     * with the boot-time migration and connection warmup for the same pool.
     */
    private static final long PURGE_INTERVAL_MILLIS = 60 * 60 * 1000L;
    private static final long FIRST_PURGE_DELAY_MILLIS = 5 * 60 * 1000L;

    private final ModalityAuthSessionStore store = new ModalityAuthSessionStore();
    private Scheduled purgeScheduled;
    private boolean stopped;

    /**
     * Registered at init, and deliberately without waiting for anything: the store is not USED until
     * somebody logs in, which is long after the datasource is up, and a login that arrived before this
     * ran would silently get a session with no family.
     */
    @Override
    public void onInit() {
        SessionFamilyStoreRegistry.register(store);
    }

    @Override
    public void onStart() {
        // Jobs start before the datasource is initialised, and querying that early corrupts the whole
        // boot — the same gate MailerJob and DbMigrationJob wait behind.
        LocalDataSourceService.onInitialised(() -> schedulePurge(FIRST_PURGE_DELAY_MILLIS));
    }

    @Override
    public void onStop() {
        stopped = true;
        if (purgeScheduled != null) {
            purgeScheduled.cancel();
            purgeScheduled = null;
        }
    }

    private void schedulePurge(long delayMillis) {
        if (!stopped)
            purgeScheduled = Scheduler.scheduleDelay(Math.max(1, delayMillis), this::purge);
    }

    private void purge() {
        if (stopped)
            return;
        store.purgeExpired()
            .onSuccess(deleted -> {
                if (deleted > 0)
                    Console.log("🧹 Removed " + deleted + " expired session row(s) from auth_session");
            })
            // Best-effort by design: this deletes rows nothing reads any more, so a failure is worth a
            // line and nothing else. Retrying harder would put load on the same pool that just refused.
            .onFailure(e -> Console.log("⚠️ Could not purge expired auth_session rows: " + e))
            .onComplete(ar -> schedulePurge(PURGE_INTERVAL_MILLIS));
    }
}
