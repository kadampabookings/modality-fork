package one.modality.base.server.mailer;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.boot.spi.ApplicationJob;
import dev.webfx.platform.conf.Config;
import dev.webfx.platform.conf.ConfigLoader;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.meta.Meta;
import dev.webfx.platform.scheduler.Scheduled;
import dev.webfx.platform.scheduler.Scheduler;
import dev.webfx.platform.util.Booleans;
import dev.webfx.stack.db.datasource.LocalDataSourceService;
import dev.webfx.stack.mail.transport.MailTransport;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.entity.EntityStore;
import one.modality.base.shared.entities.Mail;

import java.time.LocalDate;

/**
 * Drains the mail table: one mail per pulse (like KBS2's MailerActor), the next pulse
 * scheduled only when the current one completes — {@code minSendIntervalMs} after a send
 * (legacy 4s quota pace), {@code pollIntervalMs} when the queue was empty. Draining starts
 * only once BOTH gates are open: the {@code enabled} config flag AND a configured mail
 * transport provider; either missing leaves the job fully inert with a single log line.
 *
 * @author Bruno Salmon
 */
public final class MailerJob implements ApplicationJob {

    private static final String CONFIG_PATH = "modality.base.server.mailer";
    private static final String LOG_PREFIX = "[mailer] ";

    private long pollIntervalMs = 10_000;
    private long minSendIntervalMs = 4_000;
    private int drainWindowDays = 7;
    private boolean drainAll; // false = "flagged" progressive scope
    private final MailTransmitter mailTransmitter = new MailTransmitter();

    private DataSourceModel dataSourceModel;
    private volatile boolean stopped;
    private Scheduled pulseScheduled;

    @Override
    public void onStart() {
        ConfigLoader.onConfigLoaded(CONFIG_PATH, this::onConfigLoaded);
    }

    @Override
    public void onStop() {
        stopped = true;
        if (pulseScheduled != null)
            pulseScheduled.cancel();
    }

    private void onConfigLoaded(Config config) {
        if (config == null || !Booleans.isTrue(config.getBoolean("enabled"))) {
            Console.log(LOG_PREFIX + "Disabled by config — the mail table is not drained by this server");
            return;
        }
        if (Meta.isDevelopment() && !Booleans.isTrue(config.getBoolean("enabledInDevelopment"))) {
            // Second guard for developer machines, whose default datasource may reach the
            // production database: enabled alone must not make a local server drain prod mail.
            Console.log(LOG_PREFIX + "Not draining on a development machine (enabledInDevelopment is not set)");
            return;
        }
        Integer pollInterval = config.getInteger("pollIntervalMs");
        if (pollInterval != null)
            pollIntervalMs = pollInterval;
        Integer minSendInterval = config.getInteger("minSendIntervalMs");
        if (minSendInterval != null)
            minSendIntervalMs = minSendInterval;
        Integer windowDays = config.getInteger("drainWindowDays");
        if (windowDays != null)
            drainWindowDays = windowDays;
        drainAll = "all".equals(config.getString("drainScope"));
        mailTransmitter.setRedirectTo(config.getString("redirectTo"));

        Console.log(LOG_PREFIX + "Enabled (drainScope=" + (drainAll ? "all" : "flagged")
                + ", pollIntervalMs=" + pollIntervalMs + ", minSendIntervalMs=" + minSendIntervalMs
                + ") — waiting for the mail transport and the datasource");
        // Second gate: never pulse before a transport provider is configured & initialized
        // (with provider=none this Future never completes and the job stays inert).
        // Third & fourth gates: jobs start before the datasource and its model are initialized,
        // and querying that early corrupts the whole boot — wait for both, like DbMigrationJob.
        MailTransport.providerWhenReady().onSuccess(provider ->
                LocalDataSourceService.onInitialised(() ->
                        DataSourceModelService.loadDataSourceModel(DataSourceModelService.getDefaultDataSourceId())
                                .onFailure(e -> {
                                    Console.log("⛔️ " + LOG_PREFIX + "Could not load the data source model — mailer not started");
                                    Console.log(e);
                                })
                                .onSuccess(model -> {
                                    dataSourceModel = model;
                                    Console.log(LOG_PREFIX + "Draining mails through the '" + provider.getName() + "' transport");
                                    schedulePulse(0);
                                })));
    }

    private void schedulePulse(long delayMs) {
        if (!stopped) // Vert.x timers reject delay < 1ms — and a 0 here would throw through the boot's job starter
            pulseScheduled = Scheduler.scheduleDelay(Math.max(1, delayMs), this::pulse);
    }

    private void pulse() {
        if (stopped)
            return;
        Future<Boolean> drainFuture;
        try {
            drainFuture = drainNextMail();
        } catch (Exception e) {
            // A synchronous throw must never escape to the event loop — it would take the server down
            drainFuture = Future.failedFuture(e);
        }
        drainFuture
                .onFailure(e -> {
                    Console.log("⛔️ " + LOG_PREFIX + "Pulse failed (will retry on next poll)");
                    Console.log(e);
                    schedulePulse(pollIntervalMs);
                })
                // A processed mail (even one that failed to transmit — it's bookkept as
                // transmitted-with-error) is followed at send pace; an empty queue at poll pace.
                .onSuccess(mailProcessed -> schedulePulse(mailProcessed ? minSendIntervalMs : pollIntervalMs));
    }

    private Future<Boolean> drainNextMail() {
        // Same drain semantics as KBS2's MailerActor query, in DQL (?. = outer join — vital
        // for letterless magic-link mails, which an inner join through letter would drop).
        // The "flagged" scope condition is the KBS3 side of the progressive handover; KBS2's
        // mailer drains the exact complement (letter is not null and !letter..kbs3).
        // No `out` filter, exactly like KBS2: incoming mails (out=false — the cart "contact us"
        // and refund-request messages a booker sends to a centre) are transmitted too, to the
        // centre's address with Reply-To the booker. That's what the fromEmail branch of the
        // sender resolution is for. Filtering them out here would strand them forever, since
        // KBS2's side of the partition only drains lettered mail.
        String scopeCondition = drainAll ? "" : " and (letter=null or letter?.kbs3)";
        return EntityStore.create(dataSourceModel).<Mail>executeQuery(
                        // channel='email' (V0055): push-variant mails belong to the WebPushMailerJob —
                        // their subject/content hold a composed push title/body, not an email.
                        "select date from Mail where channel='email' and (transmitted=null or !transmitted) and date>$1"
                                + " and (letter=null or letter?.onHold=null or !letter?.onHold)"
                                + scopeCondition
                                + " order by letter?.type?.ord limit 1",
                        // mail.date is timestamp-typed in the DB, so the param must be a LocalDateTime
                        LocalDate.now().minusDays(drainWindowDays).atStartOfDay())
                .compose(mails -> mails.isEmpty()
                        ? Future.succeededFuture(false)
                        : mailTransmitter.transmitMail(mails.get(0)).map(ignored -> true));
    }
}
