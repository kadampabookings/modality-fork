package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.RegenerateTotpBackupCodesCredentials;

/**
 * @author Claude Code
 */
public final class RegenerateTotpBackupCodesCredentialsSerialCodec extends SerialCodecBase<RegenerateTotpBackupCodesCredentials> {

    private static final String CODEC_ID = "RegenerateTotpBackupCodesCredentials";
    private static final String CODE_KEY = "code";

    public RegenerateTotpBackupCodesCredentialsSerialCodec() {
        super(RegenerateTotpBackupCodesCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(RegenerateTotpBackupCodesCredentials arg, AstObject serial) {
        encodeString(serial, CODE_KEY, arg.code());
    }

    @Override
    public RegenerateTotpBackupCodesCredentials decode(ReadOnlyAstObject serial) {
        return new RegenerateTotpBackupCodesCredentials(
            decodeString(serial, CODE_KEY)
        );
    }
}
