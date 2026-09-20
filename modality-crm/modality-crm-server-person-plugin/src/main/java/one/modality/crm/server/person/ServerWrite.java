package one.modality.crm.server.person;

import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

import java.util.function.Supplier;

/**
 * Running a person operation's statements as the server — and, for a write, still saying who asked.
 *
 * <p>Two separable things live in the caller's state, and an endpoint on an audited table needs one
 * without the other:
 *
 * <ul>
 *   <li>the <b>client-origin stamp</b>, which is what makes the write path refuse a raw statement. These
 *       operations are set-based SQL, so they must not carry it;</li>
 *   <li>the <b>principal</b>, which is where the submit path reads {@code kbs.audit_person_id} from, and
 *       therefore what {@code person}'s audit triggers stamp into {@code changed_by_person_id}.</li>
 * </ul>
 *
 * <p>Dropping the whole state drops both, which is how the first version of the merge came to write
 * {@code person_link_change} rows naming nobody — where the screen it replaced had recorded the member of
 * staff. Every operation in this package writes to an audited table, so every write goes through
 * {@link #asServerActingFor}, and only reads use {@link #asServer}.
 *
 * @author Claude Code
 */
final class ServerWrite {

    private ServerWrite() {
    }

    /** For reads, which have nothing to audit and no reason to name anybody. */
    static <T> T asServer(Supplier<T> call) {
        return ThreadLocalStateHolder.runWithState(StateAccessor.createEmptyState(), call);
    }

    /**
     * For writes: the server's origin, the caller's name.
     *
     * <p>{@code callerUserId} must have been read on the caller's own thread, before the first async
     * step — by then the thread-local has been restored and there is nobody to read.
     */
    static <T> T asServerActingFor(Object callerUserId, Supplier<T> call) {
        Object state = StateAccessor.setUserId(StateAccessor.createEmptyState(), callerUserId);
        return ThreadLocalStateHolder.runWithState(state, call);
    }
}
