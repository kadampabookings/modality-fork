package one.modality.event.frontoffice.operations.routes.booking;

import dev.webfx.platform.windowhistory.spi.BrowsingHistory;
import dev.webfx.stack.routing.uirouter.operations.RoutePushRequest;
import dev.webfx.stack.ui.operation.HasOperationCode;
import one.modality.base.frontoffice.states.GeneralPM;

public class RouteToBookingRequest extends RoutePushRequest implements HasOperationCode {

    public RouteToBookingRequest(BrowsingHistory browsingHistory) {
        super(GeneralPM.BOOKING_PATH, browsingHistory);
    }

    @Override
    public Object getOperationCode() {
        return "RouteToBooking";
    }
}
