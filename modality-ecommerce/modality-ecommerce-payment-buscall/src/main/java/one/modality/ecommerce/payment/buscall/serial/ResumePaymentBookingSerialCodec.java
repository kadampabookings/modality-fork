package one.modality.ecommerce.payment.buscall.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.ecommerce.payment.ResumePaymentBooking;

/**
 * @author Claude Code
 */
public final class ResumePaymentBookingSerialCodec extends SerialCodecBase<ResumePaymentBooking> {

    private static final String CODEC_ID = "ResumePaymentBooking";

    private static final String DOCUMENT_PRIMARY_KEY_KEY = "document";
    private static final String PERSON_PRIMARY_KEY_KEY   = "person";
    private static final String PERSON_NAME_KEY          = "personName";
    private static final String PERSON_EMAIL_KEY         = "personEmail";
    private static final String DOCUMENT_REF_KEY         = "ref";
    private static final String PRICE_NET_KEY            = "priceNet";
    private static final String PRICE_DEPOSIT_KEY        = "priceDeposit";
    private static final String ATTENDANCE_COUNT_KEY     = "attendanceCount";
    private static final String IN_PERSON_KEY            = "inPerson";

    public ResumePaymentBookingSerialCodec() {
        super(ResumePaymentBooking.class, CODEC_ID);
    }

    @Override
    public void encode(ResumePaymentBooking arg, AstObject serial) {
        encodeObject( serial, DOCUMENT_PRIMARY_KEY_KEY, arg.documentPrimaryKey());
        encodeObject( serial, PERSON_PRIMARY_KEY_KEY,   arg.personPrimaryKey());
        encodeString( serial, PERSON_NAME_KEY,          arg.personName());
        encodeString( serial, PERSON_EMAIL_KEY,         arg.personEmail());
        encodeString( serial, DOCUMENT_REF_KEY,         arg.documentRef());
        encodeInteger(serial, PRICE_NET_KEY,            arg.priceNet());
        encodeInteger(serial, PRICE_DEPOSIT_KEY,        arg.priceDeposit());
        encodeInteger(serial, ATTENDANCE_COUNT_KEY,     arg.attendanceCount());
        // Only when known: a key that is absent decodes back as null, so a Boolean stays three-valued
        // rather than collapsing "not an in-person booking" and "we do not know" into false.
        if (arg.inPerson() != null)
            encodeBoolean(serial, IN_PERSON_KEY,        arg.inPerson());
    }

    @Override
    public ResumePaymentBooking decode(ReadOnlyAstObject serial) {
        return new ResumePaymentBooking(
                decodeObject( serial, DOCUMENT_PRIMARY_KEY_KEY),
                decodeObject( serial, PERSON_PRIMARY_KEY_KEY),
                decodeString( serial, PERSON_NAME_KEY),
                decodeString( serial, PERSON_EMAIL_KEY),
                decodeString( serial, DOCUMENT_REF_KEY),
                decodeInteger(serial, PRICE_NET_KEY, 0),
                decodeInteger(serial, PRICE_DEPOSIT_KEY, 0),
                decodeInteger(serial, ATTENDANCE_COUNT_KEY, 0),
                serial.has(IN_PERSON_KEY) ? decodeBoolean(serial, IN_PERSON_KEY) : null
        );
    }
}
