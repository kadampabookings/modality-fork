package one.modality.ecommerce.payment.buscall.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.ecommerce.payment.GatewayPaymentMethodInfo;
import one.modality.ecommerce.payment.GetPaymentMethodsResult;

/**
 * @author Bruno Salmon
 */
public final class GetPaymentMethodsResultSerialCodec extends SerialCodecBase<GetPaymentMethodsResult> {

    private static final String CODEC_ID        = "GetPaymentMethodsResult";
    private static final String GATEWAY_NAME_KEY = "gateway";
    private static final String LIVE_KEY         = "live";
    private static final String METHODS_KEY      = "methods";

    public GetPaymentMethodsResultSerialCodec() {
        super(GetPaymentMethodsResult.class, CODEC_ID);
    }

    @Override
    public void encode(GetPaymentMethodsResult result, AstObject serial) {
        encodeString( serial, GATEWAY_NAME_KEY, result.gatewayName());
        encodeBoolean(serial, LIVE_KEY,          result.live());
        encodeArray(  serial, METHODS_KEY,       result.methods());
    }

    @Override
    public GetPaymentMethodsResult decode(ReadOnlyAstObject serial) {
        return new GetPaymentMethodsResult(
            decodeString( serial, GATEWAY_NAME_KEY),
            decodeBoolean(serial, LIVE_KEY),
            decodeArray(  serial, METHODS_KEY, GatewayPaymentMethodInfo.class)
        );
    }
}
