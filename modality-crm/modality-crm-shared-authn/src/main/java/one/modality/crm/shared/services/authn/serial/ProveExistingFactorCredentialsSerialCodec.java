package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.ProveExistingFactorCredentials;

/**
 * @author Claude Code
 */
public final class ProveExistingFactorCredentialsSerialCodec extends SerialCodecBase<ProveExistingFactorCredentials> {

    private static final String CODEC_ID = "ProveExistingFactorCredentials";
    private static final String CODE_KEY = "code";

    public ProveExistingFactorCredentialsSerialCodec() {
        super(ProveExistingFactorCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(ProveExistingFactorCredentials arg, AstObject serial) {
        encodeString(serial, CODE_KEY, arg.code());
    }

    @Override
    public ProveExistingFactorCredentials decode(ReadOnlyAstObject serial) {
        return new ProveExistingFactorCredentials(
            decodeString(serial, CODE_KEY)
        );
    }
}
