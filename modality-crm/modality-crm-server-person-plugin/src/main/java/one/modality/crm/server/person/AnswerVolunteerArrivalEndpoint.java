package one.modality.crm.server.person;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

/**
 * The volunteer confirms or cancels their arrival, from the link the coordinators emailed.
 *
 * <p>Argument: {@code [token, "confirm" | "cancel", reason?]}. Answers the application's id. Callable signed out:
 * the token is the authorisation - see {@link VolunteerLinkAnswers}.
 *
 * @author Claude Code
 */
public final class AnswerVolunteerArrivalEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    /** The bus address - mirrored in the React front office's volunteer-arrival feature. */
    public static final String ANSWER_VOLUNTEER_ARRIVAL_ADDRESS = "modality/service/volunteer/answerArrival";

    public AnswerVolunteerArrivalEndpoint() {
        super(ANSWER_VOLUNTEER_ARRIVAL_ADDRESS, argument -> {
            // On the caller's thread: by the first async step there is nobody left to read.
            Object callerUserId = StateAccessor.getUserId(ThreadLocalStateHolder.getThreadLocalState());
            String clientAddress = ThreadLocalStateHolder.getClientAddress();
            Object[] a = argument instanceof Object[] array ? array : new Object[0];
            return VolunteerLinkAnswers.answerArrival(at(a, 0), at(a, 1), at(a, 2), callerUserId, clientAddress);
        });
    }

    static Object at(Object[] array, int index) {
        return index < array.length ? array[index] : null;
    }
}
