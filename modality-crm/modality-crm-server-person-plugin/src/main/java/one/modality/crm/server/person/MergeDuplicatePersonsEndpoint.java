package one.modality.crm.server.person;

import dev.webfx.platform.util.Arrays;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

/**
 * Folds a duplicate person into the one being kept, and deletes it.
 *
 * <p>The last of the six raw statements the browser used to send (front-office write-authorization plan,
 * step 1). The customers screen looped over a list of {@code {entity, field}} pairs it carried and sent
 * {@code update <entity> set <field>=$1 where <field>=$2} for each — the table name interpolated from the
 * list — then deleted the person. Which tables a client could rewrite in one call was therefore whatever
 * that client said, and the list travelled with the code most able to be changed by whoever was looking at
 * it. It lives in {@link PersonReferences} now. <b>Once this is deployed, {@code ChangeSet.execute()} can
 * be refused for client origins, which is what closes step 1.</b>
 *
 * <p>The caller must be able to reach {@code /customers} in BOTH people's organizations, checked against
 * the database, and be on a session this server established. Both, not just the survivor's: a grant is held
 * per organization, and checking only the person being kept would let a manager of one centre delete
 * another centre's person by naming one of their own as the survivor.
 *
 * <p>Two things refuse rather than merge, and they are refusals rather than silent choices because the
 * alternative in each case is an escalation or a lockout nobody asked for: a duplicate holding any
 * authorization row, whose rights would otherwise transfer to the survivor, and a duplicate that is an
 * account's person, whose account would be left with nobody able to sign in — merging ACCOUNTS is a
 * different screen, and the right one.
 *
 * <p>The argument is {@code [keptPersonId, duplicatePersonId]}. One duplicate per call: a merge that half
 * succeeded across several people would leave rows naming somebody who no longer exists.
 *
 * @author Claude Code
 */
public final class MergeDuplicatePersonsEndpoint extends AsyncFunctionBusCallEndpoint<Object, Boolean> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.MERGE_DUPLICATE_PERSONS. */
    public static final String MERGE_DUPLICATE_PERSONS_ADDRESS = "modality/service/person/mergeDuplicatePersons";

    public MergeDuplicatePersonsEndpoint() {
        super(MERGE_DUPLICATE_PERSONS_ADDRESS, argument -> {
            Object[] ids = argument instanceof Object[] array ? array : null;
            if (ids == null || Arrays.length(ids) < 2)
                return RouteAccessGuard.refused();
            // Read on THIS thread and carried into each guard: the guards run once the organizations are
            // known, which is after a database read, and by then the thread no longer holds the caller.
            Object state = ThreadLocalStateHolder.getThreadLocalState();
            // Read here too, and for a different reason than the guard: person carries audit triggers
            // that stamp who changed a link, and the batch runs without the caller's state. Carried
            // explicitly so the trail still names them.
            Object callerUserId = StateAccessor.getUserId(state);
            return PersonMergeCascade.mergeDuplicatePerson(ids[0], ids[1], callerUserId,
                (organizationId, eventId, work) -> ThreadLocalStateHolder.runWithState(state,
                    () -> RouteAccessGuard.whenCallerMayReach("/customers", organizationId, eventId, work)));
        });
    }
}
