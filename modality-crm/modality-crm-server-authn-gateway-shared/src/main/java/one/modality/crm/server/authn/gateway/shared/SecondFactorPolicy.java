package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.conf.Config;
import dev.webfx.platform.conf.ConfigLoader;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.substitution.Substitutor;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether a back-office login must answer for a second factor, from configuration
 * (config path {@code modality.crm.server.authn.secondfactor}).
 *
 * <p>Configuration, not a per-account column: nothing in this phase needs a per-account exemption,
 * and a column would be a second place to get the policy wrong.
 *
 * <ul>
 * <li>{@code off} — the password alone opens the back office. Enrolled factors are ignored at login,
 *     while enrolment and management stay available. The default, and what unset, unresolved or an
 *     unrecognised value means: a typo must land on the behaviour that was already shipping, visibly
 *     in the boot log, not on a surprise.</li>
 * <li>{@code optional} — an account with a confirmed factor is asked for it on every back-office
 *     login; an account without one is not.</li>
 * <li>{@code required} — additionally, an account with no confirmed factor is refused, from
 *     {@code requiredFrom} onwards. Before that date {@code required} behaves as {@code optional}
 *     and the client shows the deadline.</li>
 * </ul>
 *
 * <p><b>What "asked for" and "refused" mean in THIS build, which is less than the words suggest.</b>
 * The policy is enforced at one mint site — the username/password gateway. A magic link, an emailed
 * verification code, a support or back-office view pass and the guest cart login each still open a
 * back-office session without passing this policy; and because a session carries no audience claim
 * yet ({@code $aud}, milestone M1 of {@code docs/security/backoffice-second-factor-totp-design.md}),
 * a FRONT-OFFICE login of the same account followed by back-office messages is not stopped either.
 * So {@code optional} and {@code required} raise the cost of a cracked back-office password; they do
 * not fence the back office. {@link #boot()} says this in the log every time the policy is not
 * {@code off}, because a green deploy otherwise reads as a working control.
 *
 * <p>An unresolved {@code ${{ VAR }}} template comes back as the literal template text, not null, so
 * it is detected with {@link Substitutor#areValuesNonNullAndResolved}, exactly as
 * {@code WebAuthnConfig} does.
 *
 * <p><b>How it boots.</b> {@link #boot()} registers the config listener and is called from the
 * password gateway's {@code boot()} — this module provides no {@code ApplicationModuleBooter} of its
 * own today, and the password gateway is the one component that is always present wherever this
 * policy can matter (it is the gateway that owns the mint site the policy guards). A deployment
 * without it reads no configuration and the policy stays {@code off}, which is what it was before
 * this existed. The boot log prints the resolved policy on every start, because silence would
 * otherwise read as success.
 *
 * @author Claude Code
 */
public final class SecondFactorPolicy {

    private static final String CONFIG_PATH = "modality.crm.server.authn.secondfactor";
    private static final String LOG_PREFIX = "[2fa] ";

    private enum Mode {OFF, OPTIONAL, REQUIRED}

    // Written once per config load, read by every back-office login — volatile is the contract
    private static volatile SecondFactorPolicy current = new SecondFactorPolicy(Mode.OFF, null);
    private static final AtomicBoolean BOOTED = new AtomicBoolean();

    private final Mode mode;
    private final LocalDate requiredFrom; // null unless `required` was given a parsable date

    private SecondFactorPolicy(Mode mode, LocalDate requiredFrom) {
        this.mode = mode;
        this.requiredFrom = requiredFrom;
    }

    /**
     * Starts listening for the configuration. Idempotent: registering the listener twice would print
     * the policy twice and read as two different servers in the log.
     */
    public static void boot() {
        if (!BOOTED.compareAndSet(false, true))
            return;
        ConfigLoader.onConfigLoaded(CONFIG_PATH, loadedConfig -> {
            SecondFactorPolicy policy = fromConfig(loadedConfig);
            current = policy;
            Console.log(LOG_PREFIX + "Back-office second factor: " + policy.describe());
            if (policy.mode == Mode.REQUIRED && policy.requiredFrom == null)
                // Loud: `required` was asked for and is NOT being enforced. Said once, at boot,
                // because the alternative is discovering it the day the deadline was meant to bite.
                Console.log(LOG_PREFIX + "⚠️ BACKOFFICE_SECOND_FACTOR=required but BACKOFFICE_SECOND_FACTOR_REQUIRED_FROM is unset or not an ISO date (e.g. 2026-11-01) — behaving as optional");
            if (!policy.isOff())
                logWhatIsNotEnforcedYet();
        });
    }

    /**
     * What a non-{@code off} policy does NOT do yet, said out loud at every boot that turns it on.
     *
     * <p>Here because a green deploy of a policy that reads {@code optional} or {@code required}
     * invites exactly one reading — "the back office now needs two factors" — and that is not what
     * this build does. It enforces at ONE mint site, and the ways round it are not exotic: they are
     * the login link people already use. Saying so in the log costs six lines at boot and is the
     * difference between a known phase and a believed control.
     */
    private static void logWhatIsNotEnforcedYet() {
        Console.log(LOG_PREFIX + "⚠️ PARTIAL ENFORCEMENT — this build asks for a factor at ONE mint site: the"
                    + " username/password gateway. It does NOT cover:");
        Console.log(LOG_PREFIX + "⚠️   • magic-link logins — the login link, the emailed verification code, the"
                    + " support view and the back-office view still open a back-office session with no factor;");
        Console.log(LOG_PREFIX + "⚠️   • the guest cart login, same;");
        Console.log(LOG_PREFIX + "⚠️   • a FRONT-OFFICE login of the same account: nothing stops that session's"
                    + " messages reaching the back office, because sessions carry no audience ($aud) yet.");
        Console.log(LOG_PREFIX + "⚠️ Those close with the audience binding — milestone M1 of"
                    + " docs/security/backoffice-second-factor-totp-design.md. Until then this policy RAISES the"
                    + " cost of a cracked back-office password; it does not fence the back office.");
    }

    /** The policy in force. Never null: before the configuration loads, and without it, that is {@code off}. */
    public static SecondFactorPolicy get() {
        return current;
    }

    private static SecondFactorPolicy fromConfig(Config config) {
        if (config == null)
            return new SecondFactorPolicy(Mode.OFF, null);
        Mode mode = parseMode(resolvedOrNull(config.getString("backofficePolicy")));
        // Only meaningful for `required`; parsed only there so a stale date cannot be read as a live one
        LocalDate requiredFrom = mode == Mode.REQUIRED ? parseDate(resolvedOrNull(config.getString("requiredFrom"))) : null;
        return new SecondFactorPolicy(mode, requiredFrom);
    }

    private static Mode parseMode(String value) {
        if ("optional".equalsIgnoreCase(value))
            return Mode.OPTIONAL;
        if ("required".equalsIgnoreCase(value))
            return Mode.REQUIRED;
        return Mode.OFF; // unset, unresolved, "off", or anything unrecognised
    }

    private static LocalDate parseDate(String value) {
        if (value == null)
            return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null; // reported by the boot log, and the policy then behaves as optional
        }
    }

    private static String resolvedOrNull(String value) {
        if (value == null || value.isBlank() || !Substitutor.areValuesNonNullAndResolved(value))
            return null;
        return value.trim();
    }

    /** Whether no back-office login is ever asked for a factor. */
    public boolean isOff() {
        return mode == Mode.OFF;
    }

    /**
     * Whether an account with NO confirmed factor must now be refused the back office: {@code required}
     * with a parsable date that has arrived. A {@code required} policy with no usable date behaves as
     * {@code optional} — refusing everyone because a date was mistyped is not a safety property.
     * Evaluated in the server's own time zone; the grace date is a day, not an instant.
     */
    public boolean isRequiredNow() {
        return mode == Mode.REQUIRED && requiredFrom != null && !LocalDate.now().isBefore(requiredFrom);
    }

    /**
     * Whether THIS login should be asked for a factor at all: a back-office login under a policy
     * that is not off. Front-office logins are never asked — the requirement attaches to back-office
     * authentication, not to the account, which is what keeps enrolment reachable from the front
     * office for someone the policy would otherwise lock out.
     */
    public boolean asksForFactor(boolean backofficeLogin) {
        return backofficeLogin && mode != Mode.OFF;
    }

    /** One line for the boot log, saying what is in force and, for a deadline, whether it has arrived. */
    public String describe() {
        switch (mode) {
            case OPTIONAL:
                return "optional — asked of accounts with a confirmed factor, back-office logins only";
            case REQUIRED:
                if (requiredFrom == null)
                    return "required WITHOUT a valid date — behaving as optional";
                return isRequiredNow()
                       ? "required since " + requiredFrom + " — a back-office login with no confirmed factor is refused"
                       : "required from " + requiredFrom + " — behaving as optional until then";
            default:
                return "off — the password alone opens the back office (enrolment stays available)";
        }
    }
}
