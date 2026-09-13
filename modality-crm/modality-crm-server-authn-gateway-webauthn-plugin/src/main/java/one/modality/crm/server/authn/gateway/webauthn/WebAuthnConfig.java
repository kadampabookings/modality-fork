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
 * login with the generic error. The ceremony allowlist is the union of both.
 *
 * <p>Unset or unresolved values leave the gateway inert rather than half-configured: with no rpId
 * or no allowed origin nothing can be verified, so every passkey operation is refused with a clear
 * error while the rest of authentication is untouched. An unresolved {@code ${{ VAR }}} template
 * comes back as the literal template text, not null, so it is detected with
 * {@link Substitutor#areValuesNonNullAndResolved} exactly as the session-token initializer does.
 *
 * @author Claude Code
 */
final class WebAuthnConfig {

    private final String rpId;
    private final String rpName;
    private final Set<Origin> allowedOrigins;    // union of both lists — what the ceremonies accept
    private final Set<Origin> backofficeOrigins; // the back-office subset, from its own config key

    private WebAuthnConfig(String rpId, String rpName, Set<Origin> allowedOrigins, Set<Origin> backofficeOrigins) {
        this.rpId = rpId;
        this.rpName = rpName;
        this.allowedOrigins = Collections.unmodifiableSet(allowedOrigins);
        this.backofficeOrigins = Collections.unmodifiableSet(backofficeOrigins);
    }

    /** An inert configuration: {@link #isConfigured()} is false and every origin set is empty. */
    static WebAuthnConfig unconfigured() {
        return new WebAuthnConfig(null, null, new LinkedHashSet<>(), new LinkedHashSet<>());
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
        return new WebAuthnConfig(rpId, rpName != null ? rpName : "Kadampa Booking System", allowed, backoffice);
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

    /** How many configured origins are front-office ones (for the boot log). */
    int getFrontofficeOriginCount() {
        return allowedOrigins.size() - backofficeOrigins.size();
    }

    /** How many configured origins are back-office ones (for the boot log). */
    int getBackofficeOriginCount() {
        return backofficeOrigins.size();
    }

    /** Whether the browser-verified origin of a ceremony is one of the configured back-office origins. */
    boolean isBackofficeOrigin(Origin origin) {
        return origin != null && backofficeOrigins.contains(origin);
    }
}
