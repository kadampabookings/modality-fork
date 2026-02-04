package one.modality.ecommerce.document.service.events.book;

import one.modality.base.shared.entities.Attendance;
import one.modality.ecommerce.document.service.events.AbstractAttendancesEvent;

import java.time.LocalDate;

/**
 * @author Bruno Salmon
 */
public final class AddAttendancesEvent extends AbstractAttendancesEvent {

    private final boolean videoAccessEnabled;

    public AddAttendancesEvent(Attendance[] attendances, boolean videoAccessEnabled) {
        super(attendances);
        this.videoAccessEnabled = videoAccessEnabled;
    }

    public AddAttendancesEvent(Object documentPrimaryKey, Object documentLinePrimaryKey, Object[] attendancesPrimaryKeys, Object[] scheduledItemsPrimaryKeys, LocalDate[] dates, boolean videoAccessEnabled) {
        super(documentPrimaryKey, documentLinePrimaryKey, attendancesPrimaryKeys, scheduledItemsPrimaryKeys, dates);
        this.videoAccessEnabled = videoAccessEnabled;
    }

    public boolean isVideoAccessEnabled() {
        return videoAccessEnabled;
    }

    @Override
    protected Attendance createAttendance(Object attendancePrimaryKey) {
        if (isForSubmit())
            return updateStore.insertEntity(Attendance.class, attendancePrimaryKey);
        return super.createAttendance(attendancePrimaryKey);
    }

    @Override
    protected void replayEventOnAttendances() {
        super.replayEventOnAttendances();
        for (Attendance attendance : getAttendances()) {
            attendance.setVideoAccessEnabled(videoAccessEnabled);
        }
    }
}
