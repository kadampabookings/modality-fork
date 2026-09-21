package one.modality.crm.server.person;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

/**
 * Creates the owner person of an account the caller has just finalised, and returns their id.
 *
 * <p>The one endpoint in this package whose caller is NOT signed in, and could not be: it runs between
 * the account being created and the first sign-in to it. What stands in for a principal is the magic
 * link the account was finalised with — see {@link AccountOwnerRules} for why that is enough and what it
 * is not.
 *
 * <p>Argument: {@code [credential, name, value, name, value, …]}. The credential is the same token or
 * verification code the client passed to {@code finaliseAccountCreation} a moment earlier; the pairs are
 * the profile fields, exactly as the update and the member insert take them.
 *
 * @author Claude Code
 */
public final class CreateAccountOwnerEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.CREATE_ACCOUNT_OWNER. */
    public static final String CREATE_ACCOUNT_OWNER_ADDRESS = "modality/service/person/createAccountOwner";

    public CreateAccountOwnerEndpoint() {
        super(CREATE_ACCOUNT_OWNER_ADDRESS, argument -> {
            Object[] arguments = argument instanceof Object[] array ? array : null;
            // A credential, then an even number of pairs — which is one test, not three: an empty array
            // has an even length and is refused by the same condition.
            if (arguments == null || arguments.length % 2 != 1)
                return refused();
            String credential = arguments[0] instanceof String s ? s : null;
            if (credential == null || credential.isBlank())
                return refused();
            // On the caller's thread: the run id identifies the tab that consumed the link, and the
            // thread-local is restored the moment the synchronous part of this call returns.
            String runId = ThreadLocalStateHolder.getRunId();
            Object[] namesAndValues = new Object[arguments.length - 1];
            System.arraycopy(arguments, 1, namesAndValues, 0, namesAndValues.length);
            return AccountOwnerRules.createOwner(credential, namesAndValues, runId);
        });
    }

    /** The same sentence every refusal here gives, saying nothing about which test failed. */
    private static <T> Future<T> refused() {
        return Future.failedFuture("[" + AccountOwnerRules.CREDENTIAL_KEY + "] "
                                   + AccountOwnerRules.sentenceFor(AccountOwnerRules.CREDENTIAL_KEY));
    }
}
