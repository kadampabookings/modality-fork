package one.modality.ecommerce.payment.buscall.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.platform.util.Arrays;
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
        // encodeArray, NOT encodeObject: the two are not interchangeable, and getting it wrong cost a day.
        // encodeObject sends an AstArray through the generic object encoder, which wraps it as
        // {"$codec":"AstNode","v":[...]} — an OBJECT. decode() then asks getArray() for this key, the Vert.x
        // AST factory casts the JsonObject it finds to AstArray, and throws. The reply crosses a decode hop
        // inside the server (VertxBus.vertxToWebfxBody, when the bridge hands the reply back), so the server
        // tripped over its own payload in an unwrapped handler: no reply was sent at all and every caller
        // waited out its timeout. encodeArray writes a plain array, which is what decodeArray reads and what
        // the TypeScript client expects.
        encodeArray(  serial, BOOKINGS_KEY,             arg.bookings());
    }

    @Override
    public ResumePaymentResult decode(ReadOnlyAstObject serial) {
        // Decoded as Object[] and copied with an array GENERATOR, not with decodeArray(.., Class): that
        // path builds the typed array reflectively, through an array constructor that has to be registered
        // for the class. Where it is not — a check, a client, anything that has not called
        // RArray.injectJavaArrayNewInstanceMethod — it warns "No array constructor found", falls back to
        // new Object[], and the assignment below throws ClassCastException. The generator needs no registry
        // and compiles for GWT too.
        Object[] decoded = decodeObjectArray(serial, BOOKINGS_KEY);
        ResumePaymentBooking[] bookings = decoded == null ? new ResumePaymentBooking[0]
            : Arrays.map(decoded, element -> (ResumePaymentBooking) element, ResumePaymentBooking[]::new);
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
