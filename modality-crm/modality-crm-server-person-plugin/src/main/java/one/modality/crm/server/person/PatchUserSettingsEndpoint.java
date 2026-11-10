package one.modality.crm.server.person;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

/**
 * Changes some of the caller's own user settings, and replies with the whole merged document (V0158).
 *
 * <p>Argument: {@code [key, value, key, value, …]}. A value is a boolean, an integer or a short token, and
 * {@code null} removes the key; {@link UserSettingsRules} says exactly what passes and why. Reply: the merged
 * document as JSON text, so a client that patched one key learns what another device wrote meanwhile.
 *
 * <p>The account comes from the session through {@link MemberSessionGuard} and nowhere else: the argument names
 * keys, never whose settings they are. Guests and support views are refused, as for
 * {@link GetUserSettingsEndpoint}.
 *
 * <p>The validation runs before the guard so that a malformed patch is refused the same way for everybody, and
 * before any SQL: the table's CHECKs are a last line of defence, not the validation.
 *
 * <p>A failure says nothing about why the database refused, and the log names the account, never a value: the
 * row's contents would otherwise travel in a constraint message.
 *
 * @author Claude Code
 */
public final class PatchUserSettingsEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.PATCH_USER_SETTINGS. */
    public static final String PATCH_USER_SETTINGS_ADDRESS = "modality/service/userSettings/patch";

    static final String BAD_REQUEST_KEY = "UserSettingsBadRequestError";

    static final String SAVE_FAILED_KEY = "UserSettingsSaveError";

    public PatchUserSettingsEndpoint() {
        super(PATCH_USER_SETTINGS_ADDRESS, argument -> {
            String patchJson = UserSettingsRules.patchJsonOf(argument);
            if (patchJson == null)
                return Future.failedFuture("[" + BAD_REQUEST_KEY + "] Not an acceptable settings patch");
            // Read on the caller's thread: the state is restored after the synchronous part of the call.
            Object callerUserId = StateAccessor.getUserId(ThreadLocalStateHolder.getThreadLocalState());
            return MemberSessionGuard.whenCallerIsVerifiedMember((personId, accountId) ->
                UserSettingsStore.patch(accountId, patchJson, callerUserId)
                    .recover(error -> {
                        Console.log("User settings patch failed for account " + accountId + ": "
                                    + error.getClass().getSimpleName());
                        return Future.failedFuture("[" + SAVE_FAILED_KEY + "] The settings could not be saved");
                    }));
        });
    }
}
