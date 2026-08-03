package one.modality.base.server.webpushmailer;

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
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.webpush.WebPushServerService;
import one.modality.base.shared.entities.Mail;

import java.time.LocalDate;

/**
 * Drains the channel='push' partition of the mail table: one mail per pulse, the next pulse
 * scheduled only when the current one completes — same structure as the SMTP
 * {@code MailerJob}, without the SMTP quota pacing (push
 * services have no 4s budget; {@code minSendIntervalMs} defaults to 500ms as a courtesy).
 * Draining starts only once ALL gates are open: the {@code enabled} config flag, the
 * datasource + model, AND resolved VAPID keys; any missing leaves the job fully inert with
 * a single log line.
 *
 * @author Bruno Salmon
 */
public final class WebPushMailerJob implements ApplicationJob {

    private static final String CONFIG_PATH = "modality.base.server.webpushmailer";
    private static final String LOG_PREFIX = "[webpush-mailer] ";

    private long pollIntervalMs = 10_000;
    private long minSendIntervalMs = 500;
    private int drainWindowDays = 7;
    private final WebPushMailTransmitter transmitter = new WebPushMailTransmitter();

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
            Console.log(LOG_PREFIX + "Disabled by config — push mails are not drained by this server");
            return;
        }
        if (Meta.isDevelopment() && !Booleans.isTrue(config.getBoolean("enabledInDevelopment"))) {
            // Second guard for developer machines, whose default datasource may reach the
            // production database: enabled alone must not make a local server push to real
            // user devices.
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
        boolean sandbox = Booleans.isTrue(config.getBoolean("sandbox"));
        transmitter.setSandbox(sandbox);

        Console.log(LOG_PREFIX + "Enabled (sandbox=" + sandbox
                + ", pollIntervalMs=" + pollIntervalMs + ", minSendIntervalMs=" + minSendIntervalMs
                + ") — waiting for the datasource");
        // Jobs start before the datasource and its model are initialized, and querying that
        // early corrupts the whole boot — wait for both, like MailerJob / DbMigrationJob.
        LocalDataSourceService.onInitialised(() ->
                DataSourceModelService.loadDataSourceModel(DataSourceModelService.getDefaultDataSourceId())
                        .onFailure(e -> {
                            Console.log("⛔️ " + LOG_PREFIX + "Could not load the data source model — web-push mailer not started");
                            Console.log(e);
                        })
                        .onSuccess(model -> {
                            // Last gate: without resolved VAPID keys no push can ever be
                            // signed — stay inert rather than error on every pulse.
                            String vapidPublicKey = null;
                            try {
                                vapidPublicKey = WebPushServerService.currentVapidPublicKey();
                            } catch (Exception noProvider) {
                                // No web-push provider registered on this server assembly
                            }
                            if (vapidPublicKey == null) {
                                Console.log(LOG_PREFIX + "No VAPID keys configured — web-push mailer not started");
                                return;
                            }
                            dataSourceModel = model;
                            Console.log(LOG_PREFIX + "Draining push mails" + (sandbox ? " in sandbox mode (logging instead of sending)" : ""));
                            schedulePulse(0);
                        }));
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
                // A processed mail (even one that failed to deliver — it's bookkept as
                // transmitted-with-error) is followed at send pace; an empty queue at poll pace.
                .onSuccess(mailProcessed -> schedulePulse(mailProcessed ? minSendIntervalMs : pollIntervalMs));
    }

    private Future<Boolean> drainNextMail() {
        // Push mails always carry a letter (the system-letter trigger is the only writer),
        // but the outer joins (?.) keep a hand-inserted letterless row from either being
        // dropped by an inner join or blocking the queue — the transmitter bookkeeps it as
        // transmitted-with-error instead. Select-list FK traversal is outer-join semantics,
        // so the graph fields load null-safely.
        return EntityStore.create(dataSourceModel).<Mail>executeQuery(
                        "select date, subject, content, scheduledItem,"
                                + " document.(person, person_email),"
                                + " letter.(pushContext, pushUrl)"
                                + " from Mail where channel='push' and (transmitted=null or !transmitted) and date>$1"
                                + " and (letter?.onHold=null or !letter?.onHold)"
                                + " order by date limit 1",
                        // mail.date is timestamp-typed in the DB, so the param must be a LocalDateTime
                        LocalDate.now().minusDays(drainWindowDays).atStartOfDay())
                .compose(mails -> mails.isEmpty()
                        ? Future.succeededFuture(false)
                        : transmitter.transmitMail(mails.get(0)).map(ignored -> true));
    }
}
