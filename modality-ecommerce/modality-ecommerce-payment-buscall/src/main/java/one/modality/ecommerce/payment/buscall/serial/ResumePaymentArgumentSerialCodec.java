package one.modality.ecommerce.payment.buscall.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.ecommerce.payment.ResumePaymentArgument;

/**
 * @author Claude Code
 */
public final class ResumePaymentArgumentSerialCodec extends SerialCodecBase<ResumePaymentArgument> {

    private static final String CODEC_ID = "ResumePaymentArgument";

    private static final String PAYMENT_PRIMARY_KEY_KEY = "payment";
    private static final String RESUME_TOKEN_KEY        = "token";

    public ResumePaymentArgumentSerialCodec() {
        super(ResumePaymentArgument.class, CODEC_ID);
    }

    @Override
    public void encode(ResumePaymentArgument arg, AstObject serial) {
        encodeObject(serial, PAYMENT_PRIMARY_KEY_KEY, arg.paymentPrimaryKey());
        encodeString(serial, RESUME_TOKEN_KEY,        arg.resumeToken());
    }

    @Override
    public ResumePaymentArgument decode(ReadOnlyAstObject serial) {
        return new ResumePaymentArgument(
                decodeObject(serial, PAYMENT_PRIMARY_KEY_KEY),
                decodeString(serial, RESUME_TOKEN_KEY)
        );
    }
}
