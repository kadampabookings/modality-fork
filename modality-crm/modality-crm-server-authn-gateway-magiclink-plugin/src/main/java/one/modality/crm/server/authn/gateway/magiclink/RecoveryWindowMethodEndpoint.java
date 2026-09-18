package one.modality.crm.server.authn.gateway.magiclink;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;

/**
 * Answers how long the caller's recovery window has left, in milliseconds — 0 when there is none.
 *
 * <p>Asked by the front office right after a sign-in with an emailed link or code, so its password form can
 * leave out the old-password field for exactly as long as the server will accept that, and no longer. The
 * client cannot work this out for itself: a booking-access link and a recovery link look the same from the
 * browser, and only one of them opens a window.
 *
 * <p>The argument is ignored, deliberately: whose window is read from the caller's own run id and principal,
 * never from anything sent with the call. It discloses nothing but a duration, and only the caller's own.
 *
 * @author Claude Code
 */
public final class RecoveryWindowMethodEndpoint extends AsyncFunctionBusCallEndpoint<Object, Integer> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.RECOVERY_WINDOW. */
    public static final String RECOVERY_WINDOW_METHOD_ADDRESS = "modality/service/authn/recoveryWindow";

    public RecoveryWindowMethodEndpoint() {
        super(RECOVERY_WINDOW_METHOD_ADDRESS,
            ignoredArgument -> RecoveryWindow.remainingMillisForCaller(DataSourceModelService.getDefaultDataSourceModel()));
    }
}
