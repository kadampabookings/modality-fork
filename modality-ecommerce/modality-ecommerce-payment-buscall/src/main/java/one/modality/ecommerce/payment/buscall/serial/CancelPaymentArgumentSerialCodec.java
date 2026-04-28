package one.modality.ecommerce.payment.buscall.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.ecommerce.payment.CancelPaymentArgument;
import one.modality.ecommerce.payment.PaymentFormType;
import one.modality.ecommerce.payment.PaymentMethod;

/**
 * @author Bruno Salmon
 */
public final class CancelPaymentArgumentSerialCodec extends SerialCodecBase<CancelPaymentArgument> {

    private static final String CODEC_ID = "CancelPaymentArgument";

    private static final String PAYMENT_PRIMARY_KEY_KEY        = "payment";
    private static final String EXPLICIT_USER_CANCELLATION_KEY = "explicit";
    private static final String PAYMENT_FORM_TYPE_KEY          = "formType";
    private static final String PAYMENT_METHOD_KEY             = "method";

    public CancelPaymentArgumentSerialCodec() {
        super(CancelPaymentArgument.class, CODEC_ID);
    }

    @Override
    public void encode(CancelPaymentArgument arg, AstObject serial) {
        encodeObject( serial, PAYMENT_PRIMARY_KEY_KEY,        arg.paymentPrimaryKey());
        encodeBoolean(serial, EXPLICIT_USER_CANCELLATION_KEY, arg.isExplicitUserCancellation());
        encodeString( serial, PAYMENT_FORM_TYPE_KEY,          arg.formType() == null ? null : arg.formType().name());
        encodeString( serial, PAYMENT_METHOD_KEY,             arg.paymentMethod() == null ? null : arg.paymentMethod().name());
    }

    @Override
    public CancelPaymentArgument decode(ReadOnlyAstObject serial) {
        String formTypeName = decodeString(serial, PAYMENT_FORM_TYPE_KEY);
        String methodName   = decodeString(serial, PAYMENT_METHOD_KEY);
        return new CancelPaymentArgument(
                decodeObject( serial, PAYMENT_PRIMARY_KEY_KEY),
                decodeBoolean(serial, EXPLICIT_USER_CANCELLATION_KEY),
                formTypeName == null ? null : PaymentFormType.valueOf(formTypeName),
                methodName   == null ? null : PaymentMethod.valueOf(methodName)
        );
    }
}
