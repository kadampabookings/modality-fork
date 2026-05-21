package one.modality.base.shared.knownitems;

/**
 * Stable handles for well-known rows in the {@code Issue} catalog table.
 *
 * <p>Mirrors the {@link KnownItemFamily} pattern: each enum entry pins a
 * pair (module, code) that the runtime can resolve to an actual {@code Issue}
 * row at insert time. The wizard never hardcodes Issue primary keys — it
 * uses these enum entries to refer to its catalog rows in a refactor-safe
 * way, even when the DB-side primary keys differ between environments.
 *
 * <p>New modules append their own block of entries. The convention is to
 * prefix the enum name with the uppercased module short-form
 * ({@code LS_…} for livestream) so future cross-module lookups stay
 * unambiguous when read in code.
 *
 * @author Bruno Salmon
 */
public enum KnownIssue {

    // ── Livestream module ────────────────────────────────────────────────
    LS_NO_SOUND     ("livestream", "no-sound"),
    LS_LOW_VOLUME   ("livestream", "low-volume"),
    LS_VIDEO_QUALITY("livestream", "video-quality"),
    LS_AV_SYNC      ("livestream", "av-sync"),
    LS_LANGUAGE     ("livestream", "language"),
    LS_ECHO         ("livestream", "echo"),

    UNKNOWN(null, null);

    private final String module;
    private final String code;

    KnownIssue(String module, String code) {
        this.module = module;
        this.code = code;
    }

    public String getModule() {
        return module;
    }

    public String getCode() {
        return code;
    }

    /**
     * Resolve an enum entry from a (module, code) pair. Returns
     * {@link #UNKNOWN} if no entry matches — never null, so callers can
     * keep using the result without an extra null check.
     */
    public static KnownIssue fromModuleCode(String module, String code) {
        if (module != null && code != null) {
            for (KnownIssue known : values()) {
                if (module.equals(known.module) && code.equals(known.code)) {
                    return known;
                }
            }
        }
        return UNKNOWN;
    }
}
