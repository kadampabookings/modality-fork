package one.modality.crm.server.person;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * The caller's own user settings, as the JSON text of their {@code account_settings} document (V0158).
 *
 * <p>Argument: none (anything sent is ignored). Reply: a JSON object text, {@code "{}"} for an account that has
 * never saved a setting. Text rather than an AstObject so the client gets plain JSON instead of a
 * {@code {$codec, v}} wrapper (see {@link MatchingAccountsEndpoint}); it parses and validates it against its own
 * catalogue, as it does what it reads back from localStorage.
 *
 * <p>The account comes from the session through {@link MemberSessionGuard} and nowhere else. A guest has no
 * account and a support view would read the viewed member's settings, so both are refused, and the front office
 * keeps their settings on the device.
 *
 * @author Claude Code
 */
public final class GetUserSettingsEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.GET_USER_SETTINGS. */
    public static final String GET_USER_SETTINGS_ADDRESS = "modality/service/userSettings/get";

    public GetUserSettingsEndpoint() {
        super(GET_USER_SETTINGS_ADDRESS, argument ->
            MemberSessionGuard.whenCallerIsVerifiedMember((personId, accountId) ->
                UserSettingsStore.load(accountId)));
    }
}
