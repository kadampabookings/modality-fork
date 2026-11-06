package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * A new second factor as it is being added — the one approval rule for passkeys and authenticator apps, and the
 * context a super administrator is shown to decide it.
 *
 * <h3>The rule</h3>
 * <ul>
 *   <li>approval switch off ({@code WEBAUTHN_BACKOFFICE_APPROVAL} unset — staging, local): APPROVED at once,
 *       {@code GATE_OFF};</li>
 *   <li>the same tab proved an APPROVED factor of this account in the last five minutes
 *       ({@link CredentialChangeProof#strongFactorProvedRecently}): APPROVED at once, {@code PROVED} — the person
 *       already holds a device the back office trusts, so the new one adds nothing an attacker could not already do;</li>
 *   <li>anything else — a password, an emailed link or code, a session hours old: PENDING, until a super administrator
 *       other than the owner approves it. That includes every account's FIRST factor, which is the one an attacker
 *       holding a stolen password would add.</li>
 * </ul>
 * "The account already has a factor" is deliberately NOT the test: a session opened through the mailbox would then
 * add an auto-approved device.
 *
 * <h3>Capture, then resolve</h3>
 *
 * <p>{@link #capture} MUST run on the caller's thread — the run id, the account, the client address and the session
 * family are read from it before the first async step. {@link #resolve} then adds what takes a lookup: the offline
 * location of the address and how the enrolling session signed in ({@code auth_session.methods}, the token's
 * {@code $amr}). Neither lookup may fail an enrolment: a missing context is shown as missing.
 *
 * @author Claude Code
 */
public final class FactorRequest {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String KIND_PROVED = "PROVED";
    public static final String KIND_GATE_OFF = "GATE_OFF";
    public static final String KIND_PERSON = "PERSON";

    private static final int MAX_USER_AGENT = 256;

    private static final String SESSION_METHODS_SQL = "SELECT methods FROM auth_session WHERE id = $1";

    /** What was read on the caller's thread. */
    public record Captured(String runId, Object accountId, String ip, String sessionFamilyId, boolean provedRecently) {}

    /** The status a new factor is stored with, how it got there, and the context the approver will see. */
    public record Resolved(String status, String approvalKind, String ip, String location, String userAgent,
                           String signInMethod) {
        public boolean isPending() {
            return STATUS_PENDING.equals(status);
        }
    }

    private FactorRequest() {}

    /** Reads the request on the caller's thread — see the class comment. */
    public static Captured capture(Object accountId) {
        Object state = ThreadLocalStateHolder.getThreadLocalState();
        String runId = ThreadLocalStateHolder.getRunId();
        Long account = accountId == null ? null : Numbers.toLong(accountId);
        // A strong proof counts only on a session opened FOR the back office ($aud=bo). The run id is chosen by the
        // client, so a proof noted for a tab could otherwise be borrowed by a front-office, setup-only or recovery-only
        // session of the same account (all `fo`) that learned it — and a `bo` session of an account holding an
        // approved factor has necessarily presented it.
        boolean openedForBackoffice = Boolean.TRUE.equals(StateAccessor.getVerifiedBackoffice(state));
        return new Captured(runId, account, ipLiteralOrNull(ThreadLocalStateHolder.getClientAddress()),
            StateAccessor.getSessionFamilyId(state),
            openedForBackoffice && account != null && CredentialChangeProof.strongFactorProvedRecently(runId, account));
    }

    /**
     * Decides the status and completes the context. Never fails. The approval switch is the policy's reading, which is
     * already the OR of both config keys (SecondFactorPolicy.noteApprovalRequiredElsewhere).
     */
    public static Future<Resolved> resolve(Captured captured, String reportedUserAgent) {
        boolean approvalRequired = SecondFactorPolicy.get().isFactorApprovalRequired();
        Future<String> signInMethod = sessionMethods(captured.sessionFamilyId());
        if (!approvalRequired || captured.provedRecently()) {
            // No approver will ever look at an approved-at-once device, so its IP, place and browser are not kept:
            // only the sign-in method code, which is not personal (data minimisation).
            String kind = approvalRequired ? KIND_PROVED : KIND_GATE_OFF;
            return signInMethod.map(method -> new Resolved(STATUS_APPROVED, kind, null, null, null, method));
        }
        String location = IpLocator.locate(captured.ip());
        String userAgent = cap(reportedUserAgent, MAX_USER_AGENT);
        return signInMethod.map(method -> new Resolved(STATUS_PENDING, null, captured.ip(), location, userAgent, method));
    }

    /** {@code auth_session.methods} of a session family — the token's {@code $amr} as recorded. Never fails. */
    public static Future<String> sessionMethods(String sessionFamilyId) {
        if (sessionFamilyId == null)
            return Future.succeededFuture(null);
        return RawSql.query(SESSION_METHODS_SQL, sessionFamilyId)
            .map(result -> result.getRowCount() == 1 && result.getValue(0, 0) != null ? result.getValue(0, 0).toString() : null)
            .otherwise(e -> null);
    }

    /** Whether a recorded {@code $amr} says the session was opened by an emailed link or code. */
    public static boolean isEmailedSignIn(String methods) {
        return methods != null && methods.matches("(^|.*,)(ml|code)(,.*|$)");
    }

    /** Whether a recorded {@code $amr} includes a real second factor (a passkey or an authenticator code). */
    public static boolean includesSecondFactor(String methods) {
        return methods != null && methods.matches("(^|.*,)(pk|totp)(,.*|$)");
    }

    /**
     * The address only if it is an IP literal — it is bound into an {@code inet} column, and whatever the bridge
     * stamped must not be able to fail an enrolment (or reach a DNS resolver through the locator).
     */
    static String ipLiteralOrNull(String address) {
        if (address == null)
            return null;
        String trimmed = address.trim();
        // Dotted-quad IPv4, or IPv6 (which always holds a colon). A string of hex letters and dots — "dead.beef" — is a
        // hostname, and InetAddress would resolve it over DNS.
        boolean ipv4 = trimmed.matches("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
        boolean ipv6 = trimmed.length() <= 45 && trimmed.contains(":") && trimmed.matches("[0-9A-Fa-f:.]+");
        if (ipv6) {
            try { // a literal with a colon is parsed, never resolved; a malformed one throws and is dropped
                InetAddress.getByName(trimmed);
            } catch (UnknownHostException | RuntimeException e) {
                return null;
            }
        }
        return ipv4 || ipv6 ? trimmed : null;
    }

    private static String cap(String value, int max) {
        if (value == null)
            return null;
        // Control characters AND format characters (\p{Cf}: bidi overrides such as U+202E, zero-width joiners) — a
        // browser-reported string must not be able to reorder or hide text in the approver's email and screen
        String trimmed = value.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").trim();
        if (trimmed.isEmpty())
            return null;
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
