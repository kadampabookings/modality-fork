package one.modality.event.server.lifecycle;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * A dry run of the anonymisation of somebody who only ever booked without an account (GDPR erasure, V0163),
 * started from one of their bookings: what would be removed, and which other bookings might be theirs.
 *
 * <p>The argument is {@code [documentId, candidateDocumentIds?, mode?]} ({@code "mine"}, the default, or
 * {@code "everywhere"}, as for a customer). The reply is a JSON string {@code {runId, documentId, mode, scope,
 * coveredOrganizations, grid, candidateDetails, namesakeDetails, addressOwnerDetails, bookings, keptOrganizations,
 * approvals}}: the grid — what the run ledger
 * keeps — holds counts, ids, refs, event names and dates, never personal data; the details arrays name the people it
 * lists (candidate bookings, customers with their name, customers holding their booking's address), for this reply
 * only. A refusal comes back as {@code [PersonAnonymise…Error] text}; see {@link PersonAnonymisationRunner} and V0163.
 *
 * <p>Data Protection Officers only, re-checked on every call ({@link EventErasureAccess}).
 *
 * @author Claude Code
 */
public final class PreviewBookerAnonymisationEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.BOOKER_ANONYMISE_PREVIEW. */
    public static final String PREVIEW_BOOKER_ANONYMISATION_ADDRESS = "modality/service/booker/anonymise/preview";

    public PreviewBookerAnonymisationEndpoint() {
        super(PREVIEW_BOOKER_ANONYMISATION_ADDRESS, argument -> EventErasureAccess.whenDataProtectionOfficer("booker-anonymise preview",
            caller -> PersonAnonymisationRunner.previewBooker(EventErasureArguments.array(argument), caller)));
    }
}
