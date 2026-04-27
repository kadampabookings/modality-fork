package one.modality.ecommerce.payment.buscall.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.ecommerce.payment.GatewayPaymentMethodInfo;
import one.modality.ecommerce.payment.PaymentFormType;
import one.modality.ecommerce.payment.PaymentMethod;

/**
 * @author Bruno Salmon
 */
public final class GatewayPaymentMethodInfoSerialCodec extends SerialCodecBase<GatewayPaymentMethodInfo> {

    private static final String CODEC_ID = "GatewayPaymentMethodInfo";
    private static final String METHOD_KEY = "method";
    private static final String FORM_TYPE_KEY = "formType";

    public GatewayPaymentMethodInfoSerialCodec() {
        super(GatewayPaymentMethodInfo.class, CODEC_ID);
    }

    @Override
    public void encode(GatewayPaymentMethodInfo info, AstObject serial) {
        encodeString(serial, METHOD_KEY,    info.method().name());
        encodeString(serial, FORM_TYPE_KEY, info.formType().name());
    }

    @Override
    public GatewayPaymentMethodInfo decode(ReadOnlyAstObject serial) {
        return new GatewayPaymentMethodInfo(
            PaymentMethod.valueOf(decodeString(serial, METHOD_KEY)),
            PaymentFormType.valueOf(  decodeString(serial, FORM_TYPE_KEY))
        );
    }
}
