package one.modality.base.shared.entities;

import one.modality.base.shared.entities.markers.*;

public interface Site extends
    EntityHasName,
    EntityHasLabel,
    EntityHasIcon,
    EntityHasEvent,
    EntityHasOrganization,
    EntityHasItemFamily,
    EntityHasOrd {

    String main = "main";
    String code = "code";

    default void setMain(Boolean value) {
        setFieldValue(main, value);
    }

    default Boolean isMain() {
        return getBooleanFieldValue(main);
    }

    default void setCode(String value) {
        setFieldValue(code, value);
    }

    default String getCode() {
        return getStringFieldValue(code);
    }
}