package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import one.modality.base.shared.entities.markers.EntityHasOrganization;

/**
 * Work department within a volunteer organization (Kitchen, Garden, Cafe, etc.).
 */
public interface VolunteeringArea extends Entity, EntityHasOrganization {

    String name = "name";
    String slug = "slug";
    String icon = "icon";
    String color = "color";
    String managerName = "managerName";
    String managerEmail = "managerEmail";
    String managerPhone = "managerPhone";
    String baselineWeekday = "baselineWeekday";
    String baselineWeekend = "baselineWeekend";
    String workDaysPerWeek = "workDaysPerWeek";
    String active = "active";

    default void setName(String value) { setFieldValue(name, value); }
    default String getName() { return getStringFieldValue(name); }

    default void setSlug(String value) { setFieldValue(slug, value); }
    default String getSlug() { return getStringFieldValue(slug); }

    default void setIcon(String value) { setFieldValue(icon, value); }
    default String getIcon() { return getStringFieldValue(icon); }

    default void setColor(String value) { setFieldValue(color, value); }
    default String getColor() { return getStringFieldValue(color); }

    default void setManagerName(String value) { setFieldValue(managerName, value); }
    default String getManagerName() { return getStringFieldValue(managerName); }

    default void setManagerEmail(String value) { setFieldValue(managerEmail, value); }
    default String getManagerEmail() { return getStringFieldValue(managerEmail); }

    default void setManagerPhone(String value) { setFieldValue(managerPhone, value); }
    default String getManagerPhone() { return getStringFieldValue(managerPhone); }

    default void setBaselineWeekday(Integer value) { setFieldValue(baselineWeekday, value); }
    default Integer getBaselineWeekday() { return getIntegerFieldValue(baselineWeekday); }

    default void setBaselineWeekend(Integer value) { setFieldValue(baselineWeekend, value); }
    default Integer getBaselineWeekend() { return getIntegerFieldValue(baselineWeekend); }

    default void setWorkDaysPerWeek(Integer value) { setFieldValue(workDaysPerWeek, value); }
    default Integer getWorkDaysPerWeek() { return getIntegerFieldValue(workDaysPerWeek); }

    default void setActive(Boolean value) { setFieldValue(active, value); }
    default Boolean isActive() { return getBooleanFieldValue(active); }
}
