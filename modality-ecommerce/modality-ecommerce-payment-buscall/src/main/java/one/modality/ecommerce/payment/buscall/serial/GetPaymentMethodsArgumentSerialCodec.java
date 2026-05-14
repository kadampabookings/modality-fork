package one.modality.ecommerce.payment.buscall.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.ecommerce.payment.GetPaymentMethodsArgument;

/**
 * @author Bruno Salmon
 */
public final class GetPaymentMethodsArgumentSerialCodec extends SerialCodecBase<GetPaymentMethodsArgument> {

    private static final String CODEC_ID               = "GetPaymentMethodsArgument";
    private static final String DOCUMENT_PRIMARY_KEY_KEY = "document";
    private static final String EVENT_PRIMARY_KEY_KEY    = "event";

    public GetPaymentMethodsArgumentSerialCodec() {
        super(GetPaymentMethodsArgument.class, CODEC_ID);
    }

    @Override
    public void encode(GetPaymentMethodsArgument arg, AstObject serial) {
        encodeObject(serial, DOCUMENT_PRIMARY_KEY_KEY, arg.documentPrimaryKey());
        encodeObject(serial, EVENT_PRIMARY_KEY_KEY,    arg.eventPrimaryKey());
    }

    @Override
    public GetPaymentMethodsArgument decode(ReadOnlyAstObject serial) {
        return new GetPaymentMethodsArgument(
            decodeObject(serial, DOCUMENT_PRIMARY_KEY_KEY),
            decodeObject(serial, EVENT_PRIMARY_KEY_KEY)
        );
    }
}
