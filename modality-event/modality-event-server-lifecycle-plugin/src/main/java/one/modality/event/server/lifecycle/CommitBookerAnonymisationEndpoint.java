package one.modality.event.server.lifecycle;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * Anonymises for good somebody who only ever booked without an account (GDPR erasure, V0163), started from one of
 * their bookings. Irreversible: the only way back is a point-in-time restore.
 *
 * <p>The argument is {@code [documentId, confirmPhrase, previewRunId, candidateDocumentIds]}: the phrase
 * {@code ANONYMISE BOOKER <documentId>}, typed; the id of a succeeded preview of the same booking from the last
 * {@value PersonAnonymisationRunner#PREVIEW_VALIDITY}, whose facts must still hold; and the ids of the candidate
 * bookings staff ticked, exactly those that preview ran with. The reply is the run as JSON, as for the preview.
 *
 * <p>Data Protection Officers only, re-checked on every call ({@link EventErasureAccess}).
 *
 * @author Claude Code
 */
public final class CommitBookerAnonymisationEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.BOOKER_ANONYMISE_COMMIT. */
    public static final String COMMIT_BOOKER_ANONYMISATION_ADDRESS = "modality/service/booker/anonymise/commit";

    public CommitBookerAnonymisationEndpoint() {
        super(COMMIT_BOOKER_ANONYMISATION_ADDRESS, argument -> EventErasureAccess.whenDataProtectionOfficer("booker-anonymise commit",
            caller -> PersonAnonymisationRunner.commitBooker(EventErasureArguments.array(argument), caller)));
    }
}
