package one.modality.event.server.lifecycle;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * Anonymises a customer for good (GDPR erasure, V0153). Irreversible: the only way back is a point-in-time restore.
 *
 * <p>The argument is {@code [personId, confirmPhrase, previewRunId, candidateDocumentIds, memberIds, mode?,
 * proposalRunId?]} ({@code mode} as for the preview; {@code proposalRunId}, for {@code "everywhere"}, the proposal the
 * other organisations approved — {@link ErasureApprovals}): the phrase
 * {@code ANONYMISE PERSON <personId>}, typed; the id of a succeeded preview of the same person from the last
 * {@value PersonAnonymisationRunner#PREVIEW_VALIDITY}; the ids of the candidate bookings staff ticked, each of
 * which must be one that preview offered; and the ids of the account's members to anonymise along, each a member
 * without an account of their own that the same preview listed. The preview's covered organisations must be those
 * the caller covers now. The reply is the run as JSON, as for the preview.
 *
 * <p>Data Protection Officers only, re-checked on every call ({@link EventErasureAccess}).
 *
 * @author Claude Code
 */
public final class CommitPersonAnonymisationEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.PERSON_ANONYMISE_COMMIT. */
    public static final String COMMIT_PERSON_ANONYMISATION_ADDRESS = "modality/service/person/anonymise/commit";

    public CommitPersonAnonymisationEndpoint() {
        super(COMMIT_PERSON_ANONYMISATION_ADDRESS, argument -> EventErasureAccess.whenDataProtectionOfficer("person-anonymise commit",
            caller -> PersonAnonymisationRunner.commit(EventErasureArguments.array(argument), caller)));
    }
}
