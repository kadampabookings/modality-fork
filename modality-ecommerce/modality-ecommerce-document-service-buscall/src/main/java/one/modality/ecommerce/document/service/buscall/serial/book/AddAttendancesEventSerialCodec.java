package one.modality.ecommerce.document.service.buscall.serial.book;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import one.modality.ecommerce.document.service.buscall.serial.AbstractAttendancesEventSerialCodec;
import one.modality.ecommerce.document.service.events.book.AddAttendancesEvent;

/**
 * @author Bruno Salmon
 */
public final class AddAttendancesEventSerialCodec extends AbstractAttendancesEventSerialCodec<AddAttendancesEvent> {

    private static final String CODEC_ID = "AddAttendancesEvent";
    private static final String VIDEO_ACCESS_ENABLED_KEY = "vod";

    public AddAttendancesEventSerialCodec() {
        super(AddAttendancesEvent.class, CODEC_ID);
    }

    @Override
    public void encode(AddAttendancesEvent o, AstObject serial) {
        super.encode(o, serial);
        encodeBoolean(serial, VIDEO_ACCESS_ENABLED_KEY, o.isVideoAccessEnabled());
    }

    @Override
    public AddAttendancesEvent decode(ReadOnlyAstObject serial) {
        return postDecode(new AddAttendancesEvent(
            decodeDocumentPrimaryKey(serial),
            decodeDocumentLinePrimaryKey(serial),
            decodeAttendancesPrimaryKeys(serial),
            decodeScheduledItemsPrimaryKeys(serial),
            decodeDates(serial),
            decodeBoolean(serial, VIDEO_ACCESS_ENABLED_KEY)
        ), serial);
    }
}
