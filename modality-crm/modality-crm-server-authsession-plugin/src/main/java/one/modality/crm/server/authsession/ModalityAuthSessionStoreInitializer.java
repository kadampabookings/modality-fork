package one.modality.crm.server.authsession;

import dev.webfx.platform.boot.spi.ApplicationJob;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.scheduler.Scheduled;
import dev.webfx.platform.scheduler.Scheduler;
import dev.webfx.stack.db.datasource.LocalDataSourceService;
import dev.webfx.stack.session.token.RevocationPoll;
import dev.webfx.stack.session.token.RevokedFamilies;
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

    /**
     * How often this instance asks the store what has been revoked since it last looked.
     *
     * <p>Polling, because the instances share a database and nothing else — no clustered bus (verified
     * 2026-09-06), which is the same fact that made the token self-contained. A revocation performed on
     * the other instance is invisible here until this query finds it, and during a blue/green deploy that
     * other instance holds half the clients.
     *
     * <p>Twenty seconds is chosen against what it is competing with: a human noticing a laptop is gone.
     * The cost is one indexed query per instance per interval, and the delay costs promptness only —
     * renewal refuses a revoked family whether or not this poll ever runs.
     */
    private static final long REVOCATION_POLL_INTERVAL_MILLIS = 20_000L;

    /** After a full page there is more to read, so the next poll follows immediately rather than in 20s. */
    private static final long REVOCATION_POLL_CATCHUP_MILLIS = 1_000L;

    /**
     * How far back the occasional look-aside reaches, and how many ordinary polls pass between them.
     *
     * <p>Reading is positional — strictly after the last row read — because a mass revocation stamps
     * thousands of rows with one timestamp and a time bound cannot walk through them. What a position
     * cannot see is a revocation whose transaction commits slowly and appears BEHIND it, so every five
     * minutes one read covers the last five minutes instead. A revocation that takes longer than that to
     * become visible is refused at its next renewal like any other, which is the backstop under all of
     * this. See RevocationPoll for why that read must not move the cursor.
     */
    private static final long REVOCATION_SAFETY_WINDOW_MILLIS = 5 * 60 * 1000L;
    private static final int POLLS_BETWEEN_REVOCATION_SAFETY_READS = 15; // 15 × 20s = five minutes

    private final ModalityAuthSessionStore store = new ModalityAuthSessionStore();
    // Written from a scheduler callback and read from another: volatile, because a job whose state is
    // handed between callback threads has no same-thread guarantee to rely on. A stale `stopped` would
    // leave a timer querying a closing datasource; the poll's own state lives in RevocationPoll.
    private volatile Scheduled purgeScheduled;
    private volatile Scheduled revocationPollScheduled;
    private volatile RevocationPoll revocationPoll;
    private volatile boolean stopped;

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
        LocalDataSourceService.onInitialised(() -> {
            schedulePurge(FIRST_PURGE_DELAY_MILLIS);
            // Starts a retention window back rather than at "now": an instance booting mid-incident — a
            // deploy during one, say — must learn what was revoked while it did not exist, or it would
            // be the one task in the cluster still honouring those sessions.
            revocationPoll = new RevocationPoll(store,
                System.currentTimeMillis() - RevokedFamilies.RETENTION_MILLIS,
                REVOCATION_SAFETY_WINDOW_MILLIS, POLLS_BETWEEN_REVOCATION_SAFETY_READS);
            scheduleRevocationPoll(1);
        });
    }

    @Override
    public void onStop() {
        stopped = true;
        if (purgeScheduled != null) {
            purgeScheduled.cancel();
            purgeScheduled = null;
        }
        if (revocationPollScheduled != null) {
            revocationPollScheduled.cancel();
            revocationPollScheduled = null;
        }
    }

    private void schedulePurge(long delayMillis) {
        if (!stopped)
            purgeScheduled = Scheduler.scheduleDelay(Math.max(1, delayMillis), this::purge);
    }

    private void scheduleRevocationPoll(long delayMillis) {
        if (!stopped)
            revocationPollScheduled = Scheduler.scheduleDelay(Math.max(1, delayMillis), this::pollRevocations);
    }

    /**
     * Learns what has been revoked and hands it to {@link RevokedFamilies}, which is what makes a
     * revocation bite on the next message instead of at the next renewal.
     *
     * <p>Best-effort, like the sweep above and for a stronger reason: that set is an optimisation on
     * latency and never the enforcement, so a poll that fails costs promptness on this instance for
     * twenty seconds and nothing else.
     *
     * <p><b>The next poll is scheduled whatever happens here</b> — from a finally, around everything,
     * including whatever the store does synchronously before returning a future. This loop reschedules
     * only itself: one escaping exception and this instance stops learning of revocations until it is
     * next deployed, silently, which is the one failure that would not announce itself.
     */
    private void pollRevocations() {
        if (stopped)
            return;
        RevocationPoll poll = revocationPoll;
        try {
            poll.pollOnce(System.currentTimeMillis())
                .onSuccess(added -> {
                    if (added > 0) // silence is the normal case: most polls re-read only what they knew
                        Console.log("🛡 " + added + " revoked session family(ies) will now be refused on sight"
                                    + " (" + RevokedFamilies.size() + " held)");
                })
                .onFailure(e -> Console.log("⚠️ Could not read recent session revocations, so this instance"
                                            + " refuses them at renewal rather than on sight for now: " + e))
                // Catching up means the last page came back full and the store has more to hand over,
                // which should not wait for the ordinary interval.
                .onComplete(ar -> scheduleRevocationPoll(poll.isCatchingUp()
                                  ? REVOCATION_POLL_CATCHUP_MILLIS : REVOCATION_POLL_INTERVAL_MILLIS));
        } catch (RuntimeException e) {
            Console.log("⚠️ A session revocation poll could not be started: " + e);
            scheduleRevocationPoll(REVOCATION_POLL_INTERVAL_MILLIS);
        }
    }

    private void purge() {
        if (stopped)
            return;
        store.purgeExpired()
            .onSuccess(deleted -> {
                if (deleted > 0) // silence is the normal case, and the only honest report of it
                    Console.log("🧹 Removed " + deleted + " expired session row(s) from auth_session");
            })
            // Best-effort by design: this deletes rows nothing reads any more, so a failure is worth a
            // line and nothing else. Retrying harder would put load on the same pool that just refused.
            .onFailure(e -> Console.log("⚠️ Could not purge expired auth_session rows: " + e))
            .onComplete(ar -> schedulePurge(PURGE_INTERVAL_MILLIS));
    }
}
