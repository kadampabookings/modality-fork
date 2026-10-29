package one.modality.base.shared.knownitems;

/**
 * @author Bruno Salmon
 */
public enum KnownItem {
    VIDEO("video"),
    AUDIO_RECORDING_ENGLISH("audio-en"),
    PROGRAM_SESSION("program-session"),
    // Global items (organization_id NULL) of the UNRECONCILED family (V0148). A line of UNRECONCILED_BOOKING with a
    // custom price brings a booking's balance to zero when an event is reconciled.
    UNRECONCILED_BOOKING("unreconciled"),
    EXCHANGE_DISCREPANCY("EXCHD"),
    UNKNOWN(null);

    private final String code;

    KnownItem(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static KnownItem fromCode(String code) {
        if (code != null) {
            switch (code) {
                case "video" : return VIDEO;
                case "audio-en" : return AUDIO_RECORDING_ENGLISH;
                case "program-session" : return PROGRAM_SESSION;
                case "unreconciled" : return UNRECONCILED_BOOKING;
                case "EXCHD" : return EXCHANGE_DISCREPANCY;
            }
        }
        return UNKNOWN;
    }
}
