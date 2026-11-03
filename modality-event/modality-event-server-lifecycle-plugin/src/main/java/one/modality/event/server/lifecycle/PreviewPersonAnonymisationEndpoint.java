package one.modality.event.server.lifecycle;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * A dry run of a customer's anonymisation (GDPR erasure, V0153): what would be removed, and which bookings made
 * without an account might be theirs.
 *
 * <p>The argument is {@code [personId]}. The reply is a JSON string {@code {runId, personId, mode, grid,
 * candidateDetails, memberDetails, namesakeDetails}}: the grid — what the run ledger keeps — holds counts, ids, refs,
 * event names and dates, never personal data; the three details arrays name the people it lists (candidate bookings,
 * account members, other customers with one of their names), for this reply only. A refusal comes back as
 * {@code [PersonAnonymise…Error] text}; see {@link PersonAnonymisationRunner} and V0153 for each.
 *
 * <p>Super administrators only, re-checked on every call ({@link EventErasureAccess}). It lives beside the event
 * erasure because it needs the same dedicated connection and the same gate.
 *
 * @author Claude Code
 */
public final class PreviewPersonAnonymisationEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.PERSON_ANONYMISE_PREVIEW. */
    public static final String PREVIEW_PERSON_ANONYMISATION_ADDRESS = "modality/service/person/anonymise/preview";

    public PreviewPersonAnonymisationEndpoint() {
        super(PREVIEW_PERSON_ANONYMISATION_ADDRESS, argument -> EventErasureAccess.whenSuperAdmin("person-anonymise preview",
            caller -> PersonAnonymisationRunner.preview(EventErasureArguments.array(argument), caller)));
    }
}
