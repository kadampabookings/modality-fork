package one.modality.crm.server.person;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.CallerRateLimit;

/**
 * "Is there an account I can sign in to with this address?" — yes or no, and nothing else.
 *
 * <p>Argument: {@code [email]}. Reply: a Boolean.
 *
 * <p>Asked by the booking forms and the sign-up screen BEFORE anyone is signed in, to choose between a password
 * field and the guest path, so it cannot ask who is calling. It replaces two client-side reads that answered far
 * more than the question: one matched {@code person.email} (a member's address, a child's, an old one), and the
 * public-talk form's returned the person's id and full name — a "who is this?" for any address typed into it.
 *
 * <p>Matched on {@code frontend_account.username}, the address one actually signs in with, because the answer is
 * acted on by offering a password field for it: a yes for an address that is only some member's
 * {@code person.email} led to a sign-in that could never succeed.
 *
 * <p>A yes/no is still a membership oracle — a booking form cannot work without one — so it is rate-limited, per
 * caller address and in total ({@link CallerRateLimit#EMAIL_LOOKUP}). A refused call fails rather than answering
 * false, so a caller cannot read a refusal as a no; the forms fall back to the guest path, as on any error.
 *
 * @author Claude Code
 */
public final class EmailHasAccountEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.EMAIL_HAS_ACCOUNT. */
    public static final String EMAIL_HAS_ACCOUNT_ADDRESS = "modality/service/person/emailHasAccount";

    static final String BAD_REQUEST_KEY = "EmailLookupBadRequestError";
    static final String RATE_LIMITED_KEY = "EmailLookupRateLimitedError";

    /** RFC 5321's limit on an address; anything longer is not one, and is not worth a query. */
    static final int MAX_EMAIL_LENGTH = 254;

    private static final String ACCOUNT_SQL =
        "select 1 from frontend_account where corporation_id = 1 and lower(username) = lower($1) limit 1";

    public EmailHasAccountEndpoint() {
        super(EMAIL_HAS_ACCOUNT_ADDRESS, argument -> {
            String email = emailOf(argument);
            if (email == null)
                return Future.failedFuture("[" + BAD_REQUEST_KEY + "] Not an email address");
            // On the caller's thread: the stamp is the bridge's, and gone after the first async step.
            if (!CallerRateLimit.EMAIL_LOOKUP.tryAcquire(ThreadLocalStateHolder.getClientAddress()))
                return Future.failedFuture("[" + RATE_LIMITED_KEY + "] Too many address checks, please try again later");
            return ServerWrite.asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(ACCOUNT_SQL)
                .setParameters(email)
                .build())
            ).map(result -> result != null && result.getRowCount() > 0);
        });
    }

    /** The trimmed address from {@code [email]}, or null when the argument is not one. Pure. */
    static String emailOf(Object argument) {
        Object[] array = argument instanceof Object[] a ? a : null;
        if (array == null || array.length != 1 || !(array[0] instanceof String s))
            return null;
        String email = s.trim();
        if (email.isEmpty() || email.length() > MAX_EMAIL_LENGTH || email.indexOf('@') <= 0)
            return null;
        return email;
    }
}
