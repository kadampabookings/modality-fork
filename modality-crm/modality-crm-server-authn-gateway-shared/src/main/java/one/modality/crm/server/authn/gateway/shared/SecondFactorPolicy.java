package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.conf.Config;
import dev.webfx.platform.conf.ConfigLoader;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.scheduler.Scheduler;
import dev.webfx.platform.substitution.Substitutor;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
 * <li>{@code required} (with a valid {@code requiredFrom}) — "setup first", from the deploy that sets it: an account
 *     with no approved factor gets a setup-only session and reaches nothing else of the back office until its first
 *     device is approved, which a super administrator must do ({@link #isFactorApprovalRequired}). Nobody is refused
 *     (David, 2026-11-07); {@code requiredFrom} is the date by which staff should have a device, after which an account
 *     without one counts as overdue. Without a valid date {@code required} behaves as {@code optional}.</li>
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
    private static volatile SecondFactorPolicy current = new SecondFactorPolicy(Mode.OFF, null, false);
    private static final AtomicBoolean BOOTED = new AtomicBoolean();
    private static final AtomicBoolean LOADED = new AtomicBoolean();
    // Run after each config load, with the new policy — how the TOTP gateway learns that setup-first is in force and
    // runs its boot-time session sweep (it holds the session-revocation plumbing this module does not depend on)
    private static final List<Consumer<SecondFactorPolicy>> LOAD_LISTENERS = new CopyOnWriteArrayList<>();

    private final Mode mode;
    private final LocalDate requiredFrom; // null unless `required` was given a parsable date
    // The first-factor approval switch, read here as well as by the passkey gateway's own config so that the TOTP
    // gateway, the status call and the approval queue all answer from one place. Same variable, same rule: only
    // "on" turns it on, so a typo lands on the behaviour that was already shipping.
    private final boolean factorApprovalRequired;

    private SecondFactorPolicy(Mode mode, LocalDate requiredFrom, boolean factorApprovalRequired) {
        this.mode = mode;
        this.requiredFrom = requiredFrom;
        this.factorApprovalRequired = factorApprovalRequired;
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
            LOADED.set(true);
            Console.log(LOG_PREFIX + "Back-office second factor: " + policy.describe());
            if (policy.mode == Mode.REQUIRED && policy.requiredFrom == null)
                // Loud: `required` was asked for and is NOT being enforced. Said once, at boot,
                // because the alternative is discovering it the day the deadline was meant to bite.
                Console.log(LOG_PREFIX + "⚠️ BACKOFFICE_SECOND_FACTOR=required but BACKOFFICE_SECOND_FACTOR_REQUIRED_FROM is unset or not an ISO date (e.g. 2026-11-01) — behaving as optional");
            Console.log(LOG_PREFIX + "First-factor approval: " + (policy.factorApprovalRequired
                ? "on — an account's first passkey or authenticator app waits for a super administrator"
                : policy.isSetupFirst()
                ? "on (forced by required) — an account's first passkey or authenticator app waits for a super administrator"
                : "off — every new passkey or authenticator app is usable at once"));
            if (!policy.isOff())
                logWhatIsNotEnforcedYet();
            IpLocator.init(resolvedOrNull(loadedConfig == null ? null : loadedConfig.getString("ipLocationDatabase")));
            for (Consumer<SecondFactorPolicy> listener : LOAD_LISTENERS)
                notifyLater(listener, policy);
        });
    }

    /**
     * Calls {@code listener} with the policy after every config load (the boot one included) — and at once when the
     * config is already loaded: a plugin that boots after this one registers too late to see the boot load otherwise
     * (which is how the setup-first session sweep first failed to run).
     */
    public static void onLoaded(Consumer<SecondFactorPolicy> listener) {
        LOAD_LISTENERS.add(listener);
        if (LOADED.get())
            notifyLater(listener, current);
    }

    /**
     * Listeners do database work (the session sweep, the context clearing), and the config loads while the server is
     * still booting — before the data source is up. Run synchronously there, the first such call stalled the boot for
     * good. So every listener runs off the boot path, once the server has had time to finish starting, and a failure is
     * logged rather than thrown into whoever loaded the config.
     */
    private static void notifyLater(Consumer<SecondFactorPolicy> listener, SecondFactorPolicy policy) {
        Scheduler.scheduleDelay(LISTENER_DELAY_MILLIS, () -> {
            try {
                listener.accept(policy);
            } catch (RuntimeException e) {
                Console.log(LOG_PREFIX + "⚠️ A second-factor policy listener failed: " + e.getMessage());
            }
        });
    }

    private static final long LISTENER_DELAY_MILLIS = 15_000;

    /**
     * Where a non-{@code off} policy is enforced and what it does NOT cover, said out loud at every boot that turns it
     * on — because a green deploy otherwise reads as a working control.
     *
     * <p>Since setup-first (2026-11): the password login asks for the factor; a magic link or emailed code for an
     * account that must present one opens a RECOVERY-ONLY session (front-office audience, so every back-office guard
     * refuses it); under {@code required} an account with no approved factor holds a setup-only session
     * (RouteAccessGuard). View passes follow the agent's own session.
     */
    private static void logWhatIsNotEnforcedYet() {
        Console.log(LOG_PREFIX + "Enforced at: the password login, the emailed link/code (recovery-only for an account that"
                    + " must present a factor), and — under `required` — RouteAccessGuard (setup-only sessions).");
        Console.log(LOG_PREFIX + "⚠️ Not covered: the legacy GWT back office cannot answer the second-factor step and is"
                    + " refused once an account holds a factor.");
    }

    /** The policy in force. Never null: before the configuration loads, and without it, that is {@code off}. */
    public static SecondFactorPolicy get() {
        return current;
    }

    private static SecondFactorPolicy fromConfig(Config config) {
        if (config == null)
            return new SecondFactorPolicy(Mode.OFF, null, false);
        Mode mode = parseMode(resolvedOrNull(config.getString("backofficePolicy")));
        // Only meaningful for `required`; parsed only there so a stale date cannot be read as a live one
        LocalDate requiredFrom = mode == Mode.REQUIRED ? parseDate(resolvedOrNull(config.getString("requiredFrom"))) : null;
        boolean factorApprovalRequired = "on".equalsIgnoreCase(resolvedOrNull(config.getString("factorApproval")));
        return new SecondFactorPolicy(mode, requiredFrom, factorApprovalRequired);
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
     * Whether the set-up-by date has arrived: {@code required} with a parsable date on or before today. Since
     * 2026-11-07 nobody is refused for it; an account still without a device then counts as OVERDUE (the super
     * administrators' Needs-attention line, the coverage). Evaluated in the server's own time zone; the date is a day,
     * not an instant.
     */
    public boolean isRequiredNow() {
        return mode == Mode.REQUIRED && requiredFrom != null && !LocalDate.now().isBefore(requiredFrom);
    }

    /**
     * Whether setup-first is in force: `required` with a valid date, from the deploy that set it — NOT from the date.
     * From that moment an account with no approved factor holds a setup-only back-office session (it may set up a
     * device and nothing else), at every sign-in — the date never stops it (2026-11-07); it only marks who is overdue.
     */
    public boolean isSetupFirst() {
        return mode == Mode.REQUIRED && requiredFrom != null;
    }

    /**
     * Whether an account's first factor waits for a super administrator: {@code WEBAUTHN_BACKOFFICE_APPROVAL=on}, or
     * setup-first. Under {@code required} an account without a device always gets the setup screen (no deadline refusal
     * since 2026-11-07), so a password alone could otherwise add its own device and be in.
     */
    public boolean isFactorApprovalRequired() {
        return factorApprovalRequired || approvalRequiredElsewhere || isSetupFirst();
    }

    // The passkey gateway's own reading of the same variable (webauthn.backofficeApproval): OR-ed in, so that if the two
    // config keys ever disagree, approval is required on every path — TOTP enrolment and verification included
    private static volatile boolean approvalRequiredElsewhere;

    /** Called by the passkey gateway on each config load with its own approval key. */
    public static void noteApprovalRequiredElsewhere(boolean required) {
        if (required && !current.factorApprovalRequired)
            Console.log(LOG_PREFIX + "🛑 webauthn.backofficeApproval is on but secondfactor.factorApproval is not"
                        + " (is src-root.json current?) — treating approval as REQUIRED everywhere");
        approvalRequiredElsewhere = required;
    }

    /**
     * Whether a factor in this approval {@code status} opens the back office — the rule the passkey store applies to
     * its own rows ({@code WebAuthnCredentialStore.opensBackofficeLogin}), stated once more here for the TOTP rows and
     * for the setup-only test, so the three cannot disagree: REJECTED never; with approval on, APPROVED only; with it
     * off, anything but REJECTED (a PENDING row from a time the switch was on keeps working, as passkeys do).
     */
    public boolean factorOpensBackoffice(String status) {
        if ("REJECTED".equals(status))
            return false;
        return !isFactorApprovalRequired() || "APPROVED".equals(status);
    }

    /** The configured deadline, or null — only set for `required`. */
    public LocalDate getRequiredFrom() {
        return requiredFrom;
    }

    /** off | optional | required, as the status call reports it (`required` without a valid date reads optional). */
    public String modeName() {
        if (mode == Mode.REQUIRED)
            return requiredFrom == null ? "optional" : "required";
        return mode == Mode.OPTIONAL ? "optional" : "off";
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
                return "required — an account without an approved device gets the setup screen only (set up by "
                       + requiredFrom + (isRequiredNow() ? ", passed: those accounts are overdue)" : ")");
            default:
                return "off — the password alone opens the back office (enrolment stays available)";
        }
    }
}
