package one.modality.event.server.lifecycle;

import dev.webfx.platform.conf.Config;
import dev.webfx.platform.conf.ConfigLoader;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Strings;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether a personal-data removal may COMMIT on this server, and how long an event's data is kept first.
 *
 * <p><b>The commit switch fails closed.</b> Removal ships dry-run-only: the design's enablement ladder
 * wants the DPO's sign-off before the back office can make an irreversible change, so a commit is refused
 * with {@code EventErasureCommitDisabledError} until {@code commitEnabled} reads {@code true} (in any letter
 * case). Every other state means off, and says so at boot: the value unset or anything else, the configuration not loaded
 * yet (a start that arrives before the callback has run), the load failing, and the section missing from the
 * server's aggregated {@code src-root.json} altogether. That last one is the failure this module is most
 * likely to meet — a {@code declare@} file is read only once {@code webfx update} has merged it into the
 * application, which is how the passkey gateway shipped inert on 2026-09-13 — so it is named in the boot
 * line rather than left to look like a deliberate "off".
 *
 * <p>Declared in {@code declare@modality.event.server.erasure.properties} as
 * {@code ${{ EVENT_ERASURE_COMMIT_ENABLED | false }}}; that file says how an environment turns it on, and
 * why a line in {@code public-variables.properties} does not.
 *
 * <p><b>The retention period is not configurable.</b> Three months after the later of the event's end and its
 * recordings closing is the managers' decision (D5 in the aggregate repo's docs/design/event-lifecycle-retention-design.md), so it
 * is a constant here, reported to the lifecycle page by the runs endpoint so the screen explains the same
 * line the server enforces.
 *
 * @author Claude Code
 */
final class EventErasureConfig {

    private EventErasureConfig() {
    }

    /** Where the switch lives; matches the module's declare@ file and the server's src-root.json. */
    static final String CONFIG_PATH = "modality.event.server.erasure";

    /** Managers' decision D5: data is kept this many months after GREATEST(end_date, audio_closing_date). */
    static final int RETENTION_MONTHS = 3;

    private static final String LOG_PREFIX = "[event-erasure] ";

    // Written once by the config-loaded callback, read by every start: volatile is the contract. False until
    // then, which is the fail-closed reading of "not known yet".
    private static volatile boolean commitEnabled;

    private static final AtomicBoolean LISTENING = new AtomicBoolean();

    /**
     * Starts listening for the configuration, once, however many endpoints ask. Called from the endpoints'
     * constructors, which run at boot (BusCallModuleBooter instantiates every endpoint), so the boot log says
     * which state this server is in before anybody opens the page.
     */
    static void listen() {
        if (!LISTENING.compareAndSet(false, true))
            return;
        ConfigLoader.onConfigLoaded(CONFIG_PATH, EventErasureConfig::onConfigLoaded);
    }

    private static void onConfigLoaded(Config config) {
        if (config == null) {
            commitEnabled = false;
            Console.log(LOG_PREFIX + "⚠️ " + CONFIG_PATH + " is missing from the server's configuration (src-root.json"
                        + " was not regenerated with `webfx update`?) — personal-data removal is DRY-RUN ONLY on this server");
            return;
        }
        String value;
        try {
            value = Strings.trim(config.getString("commitEnabled"));
        } catch (RuntimeException e) { // a value that is not a scalar: off, like any other unexpected value
            value = null;
        }
        commitEnabled = "true".equalsIgnoreCase(value);
        if (commitEnabled)
            Console.log(LOG_PREFIX + "Personal-data removal COMMIT is ENABLED on this server"
                        + " (EVENT_ERASURE_COMMIT_ENABLED=true): super administrators can remove a past event's guest data for good");
        else
            Console.log(LOG_PREFIX + "Personal-data removal is DRY-RUN ONLY on this server (commitEnabled is not true)");
    }

    /** True only once the configuration has loaded and said {@code true} (in any letter case). */
    static boolean isCommitEnabled() {
        return commitEnabled;
    }
}
