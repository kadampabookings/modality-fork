package one.modality.ecommerce.document.service.buscall.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import one.modality.ecommerce.document.service.events.AbstractDocumentEvent;

/**
 * @author Bruno Salmon
 */
public abstract class AbstractDocumentEventSerialCodec<T extends AbstractDocumentEvent> extends AbstractSourceEventSerialCodec<T> {

    private static final String DOCUMENT_PRIMARY_KEY = "document";

    public AbstractDocumentEventSerialCodec(Class<T> javaClass, String codecId) {
        super(javaClass, codecId);
    }

    @Override
    public void encode(T o, AstObject serial) {
        super.encode(o, serial);
        encodeObject(serial, DOCUMENT_PRIMARY_KEY, o.getDocumentPrimaryKey());
    }

    protected Object decodeDocumentPrimaryKey(ReadOnlyAstObject serial) {
        return decodeObject(serial, DOCUMENT_PRIMARY_KEY);
    }
}
