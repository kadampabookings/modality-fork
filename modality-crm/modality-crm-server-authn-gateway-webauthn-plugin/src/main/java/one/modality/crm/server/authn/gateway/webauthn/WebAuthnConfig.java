package one.modality.crm.server.authn.gateway.webauthn;

import com.webauthn4j.data.client.Origin;
import dev.webfx.platform.conf.Config;
import dev.webfx.platform.substitution.Substitutor;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The passkey gateway's relying-party identity, parsed once from configuration
 * (config path {@code modality.crm.server.authn.webauthn}).
 *
 * <p>Front-office and back-office origins are declared as TWO separate lists rather than one
 * annotated list, so a misconfiguration is visible instead of behavioural: the boot log prints
 * both counts, and "0 back-office origins" reads as the mistake it is — whereas a mistagged
 * entry in a combined list would keep front-office logins working and fail every back-office
 * login with the generic error. The ceremony allowlist is the union of both — but each count in
 * the boot log comes from its own list, never from the union, which hides an origin written into
 * both (see the fields).
 *
 * <p>Unset or unresolved values leave the gateway inert rather than half-configured: with no rpId
 * or no allowed origin nothing can be verified, so every passkey operation is refused with a clear
 * error while the rest of authentication is untouched. An unresolved {@code ${{ VAR }}} template
 * comes back as the literal template text, not null, so it is detected with
 * {@link Substitutor#areValuesNonNullAndResolved} exactly as the session-token initializer does.
 *
 * <p>{@code backofficeApproval} is the one policy key: whether a passkey must be approved by a
 * super administrator before it opens the back office. Only {@code on} (case-insensitive) enables
 * it — unset, unresolved or anything else, {@code true} included, is off, because the gate is
 * dormant in phase 1 (the account's {@code backoffice} flag alone decides who enters) and the
 * failure mode of a typo should be that phase-1 behaviour, visible in the boot log, rather than a
 * surprise. The declare@ file spells out what the switch does and does not decide.
 *
 * @author Claude Code
 */
final class WebAuthnConfig {

    private final String rpId;
    private final String rpName;
    private final Set<Origin> allowedOrigins;     // union of both lists — what the ceremonies accept
    // BOTH parsed lists are kept, not just the union and one side: the two may OVERLAP (the same
    // origin written into both variables), and the union dedupes it. Deriving one count by
    // subtracting the other from the union would then report 0 front-office origins for a server
    // that in fact accepts one — the boot log would announce the back-office-only policy as
    // enforced while it is not, which is the one thing that line exists to tell the operator.
    // Each set is therefore reported from its own parse; only the allowlist is a union.
    private final Set<Origin> frontofficeOrigins; // the front-office list, from its own config key
    private final Set<Origin> backofficeOrigins;  // the back-office list, from its own config key
    private final boolean backofficeApprovalRequired; // the approval switch — see isBackofficeApprovalRequired()

    private WebAuthnConfig(String rpId, String rpName, Set<Origin> allowedOrigins, Set<Origin> frontofficeOrigins,
                           Set<Origin> backofficeOrigins, boolean backofficeApprovalRequired) {
        this.rpId = rpId;
        this.rpName = rpName;
        this.allowedOrigins = Collections.unmodifiableSet(allowedOrigins);
        this.frontofficeOrigins = Collections.unmodifiableSet(frontofficeOrigins);
        this.backofficeOrigins = Collections.unmodifiableSet(backofficeOrigins);
        this.backofficeApprovalRequired = backofficeApprovalRequired;
    }

    /** An inert configuration: {@link #isConfigured()} is false, every origin set is empty, approval is off. */
    static WebAuthnConfig unconfigured() {
        return new WebAuthnConfig(null, null, new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), false);
    }

    /**
     * Parses the loaded config, returning {@link #unconfigured()} when rpId is missing, no origin
     * list parses to anything, or any listed origin is malformed — never a partially usable
     * configuration (accepting the parsable rest would silently run with a narrower allowlist
     * than the operator wrote).
     */
    static WebAuthnConfig fromConfig(Config config) {
        if (config == null)
            return unconfigured();
        String rpId = resolvedOrNull(config.getString("rpId"));
        String rpName = resolvedOrNull(config.getString("rpName"));
        if (rpId == null)
            return unconfigured();
        Set<Origin> frontoffice = parseOrigins(resolvedOrNull(config.getString("frontofficeOrigins")));
        Set<Origin> backoffice = parseOrigins(resolvedOrNull(config.getString("backofficeOrigins")));
        if (frontoffice == null || backoffice == null)
            return unconfigured(); // a malformed origin poisons the whole configuration
        Set<Origin> allowed = new LinkedHashSet<>(frontoffice);
        allowed.addAll(backoffice);
        if (allowed.isEmpty())
            return unconfigured();
        // Only "on" (any case) enables the gate — not "true": the declare@ file documents on|off, and
        // the class javadoc says why every other value is off
        boolean backofficeApprovalRequired = "on".equalsIgnoreCase(resolvedOrNull(config.getString("backofficeApproval")));
        return new WebAuthnConfig(rpId, rpName != null ? rpName : "Kadampa Booking System", allowed, frontoffice,
            backoffice, backofficeApprovalRequired);
    }

    /** Parses a comma-separated origin list; empty/unset gives an empty set, a malformed entry gives null. */
    private static Set<Origin> parseOrigins(String originsValue) {
        Set<Origin> origins = new LinkedHashSet<>();
        if (originsValue == null)
            return origins;
        for (String token : originsValue.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty())
                continue;
            try {
                origins.add(new Origin(trimmed));
            } catch (RuntimeException e) {
                return null;
            }
        }
        return origins;
    }

    private static String resolvedOrNull(String value) {
        if (value == null || value.isBlank() || !Substitutor.areValuesNonNullAndResolved(value))
            return null;
        return value.trim();
    }

    boolean isConfigured() {
        return rpId != null && !allowedOrigins.isEmpty();
    }

    String getRpId() {
        return rpId;
    }

    String getRpName() {
        return rpName;
    }

    Set<Origin> getAllowedOrigins() {
        return allowedOrigins;
    }

    /**
     * How many origins the front-office list holds (for the boot log). Read from that list itself,
     * never from {@code allowedOrigins.size() - backofficeOrigins.size()}: an origin present in
     * BOTH lists collapses in the union, and the subtraction would then report 0 — i.e. announce
     * "front-office passkeys disabled" for a server that accepts one. Zero here means the list was
     * empty, which since 2026-09-14 is the enforcement of the back-office-only policy.
     */
    int getFrontofficeOriginCount() {
        return frontofficeOrigins.size();
    }

    /** How many origins the back-office list holds (for the boot log). Same rule: its own list, not a difference. */
    int getBackofficeOriginCount() {
        return backofficeOrigins.size();
    }

    /** Whether the browser-verified origin of a ceremony is one of the configured back-office origins. */
    boolean isBackofficeOrigin(Origin origin) {
        return origin != null && backofficeOrigins.contains(origin);
    }

    /**
     * Whether a passkey must be approved by a super administrator before it opens the back office
     * ({@code backofficeApproval = on}). Off in phase 1: a new passkey is stored APPROVED, and a
     * PENDING one is as usable as an APPROVED one. REJECTED is refused whatever the switch says.
     */
    boolean isBackofficeApprovalRequired() {
        return backofficeApprovalRequired;
    }
}
