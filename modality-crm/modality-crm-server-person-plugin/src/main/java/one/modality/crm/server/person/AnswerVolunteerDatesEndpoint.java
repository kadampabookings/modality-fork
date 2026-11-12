package one.modality.crm.server.person;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

/**
 * The volunteer accepts the dates the coordinators proposed, or proposes their own, from the emailed link.
 *
 * <p>Argument: {@code [token, "accept"]} or {@code [token, "counter", startDate, endDate, comment?]}, dates as
 * {@code YYYY-MM-DD}. Answers the application's id. Callable signed out: the token is the authorisation - see
 * {@link VolunteerLinkAnswers}.
 *
 * @author Claude Code
 */
public final class AnswerVolunteerDatesEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    /** The bus address - mirrored in the React front office's volunteer-dates feature. */
    public static final String ANSWER_VOLUNTEER_DATES_ADDRESS = "modality/service/volunteer/answerDates";

    public AnswerVolunteerDatesEndpoint() {
        super(ANSWER_VOLUNTEER_DATES_ADDRESS, argument -> {
            Object callerUserId = StateAccessor.getUserId(ThreadLocalStateHolder.getThreadLocalState());
            String clientAddress = ThreadLocalStateHolder.getClientAddress();
            Object[] a = argument instanceof Object[] array ? array : new Object[0];
            return VolunteerLinkAnswers.answerDates(AnswerVolunteerArrivalEndpoint.at(a, 0), AnswerVolunteerArrivalEndpoint.at(a, 1),
                AnswerVolunteerArrivalEndpoint.at(a, 2), AnswerVolunteerArrivalEndpoint.at(a, 3), AnswerVolunteerArrivalEndpoint.at(a, 4),
                callerUserId, clientAddress);
        });
    }
}
