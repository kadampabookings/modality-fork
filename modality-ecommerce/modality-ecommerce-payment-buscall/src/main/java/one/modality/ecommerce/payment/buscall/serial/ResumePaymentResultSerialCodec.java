package one.modality.ecommerce.payment.buscall.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstArray;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.SerialCodecManager;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.ecommerce.payment.ResumePaymentBooking;
import one.modality.ecommerce.payment.ResumePaymentResult;

/**
 * @author Claude Code
 */
public final class ResumePaymentResultSerialCodec extends SerialCodecBase<ResumePaymentResult> {

    private static final String CODEC_ID = "ResumePaymentResult";

    private static final String PENDING_KEY              = "pending";
    private static final String SUCCESSFUL_KEY           = "successful";
    private static final String AMOUNT_KEY               = "amount";
    private static final String DOCUMENT_PRIMARY_KEY_KEY = "document";
    private static final String EVENT_PRIMARY_KEY_KEY    = "event";
    private static final String BOOKINGS_KEY             = "bookings";

    public ResumePaymentResultSerialCodec() {
        super(ResumePaymentResult.class, CODEC_ID);
    }

    @Override
    public void encode(ResumePaymentResult arg, AstObject serial) {
        encodeBoolean(serial, PENDING_KEY,              arg.pending());
        encodeBoolean(serial, SUCCESSFUL_KEY,           arg.successful());
        encodeInteger(serial, AMOUNT_KEY,               arg.amount());
        encodeObject( serial, DOCUMENT_PRIMARY_KEY_KEY, arg.documentPrimaryKey());
        encodeObject( serial, EVENT_PRIMARY_KEY_KEY,    arg.eventPrimaryKey());
        ResumePaymentBooking[] bookings = arg.bookings();
        if (bookings != null)
            encodeObject(serial, BOOKINGS_KEY, SerialCodecManager.encodeJavaArrayToAstArray(bookings));
    }

    @Override
    public ResumePaymentResult decode(ReadOnlyAstObject serial) {
        ReadOnlyAstArray array = serial.getArray(BOOKINGS_KEY);
        ResumePaymentBooking[] bookings = array == null
                ? new ResumePaymentBooking[0]
                : SerialCodecManager.decodeAstArrayToJavaArray(array, ResumePaymentBooking.class);
        return new ResumePaymentResult(
                decodeBoolean(serial, PENDING_KEY),
                decodeBoolean(serial, SUCCESSFUL_KEY),
                decodeInteger(serial, AMOUNT_KEY, 0),
                decodeObject( serial, DOCUMENT_PRIMARY_KEY_KEY),
                decodeObject( serial, EVENT_PRIMARY_KEY_KEY),
                bookings
        );
    }
}
