package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import one.modality.base.shared.entities.markers.EntityHasCode;
import one.modality.base.shared.entities.markers.EntityHasLabel;
import one.modality.base.shared.entities.markers.EntityHasOrd;

/**
 * Catalog entry for the cross-module support triage system. One row per
 * (module, code) pair — the module discriminator lets each surface
 * (livestream, login, audio library, ...) own its own catalog without
 * a global enum.
 *
 * <p>Code-side access is via {@link one.modality.base.shared.knownitems.KnownIssue}
 * — typed enum entries map a (module, code) pair to a stable handle so
 * the wizard never has to hardcode primary keys.
 *
 * <p>{@code label} is a multilingual {@link Label} surfaced in the
 * backoffice dashboard; the frontoffice wizard renders its own copy
 * via i18next for now so this can be left null until a backoffice
 * dashboard surfaces.
 *
 * @author Bruno Salmon
 */
public interface Issue extends Entity, EntityHasCode, EntityHasLabel, EntityHasOrd {

    String module = "module";
    String hasFix = "hasFix";

    default void setModule(String value) {
        setFieldValue(module, value);
    }

    default String getModule() {
        return getStringFieldValue(module);
    }

    default void setHasFix(Boolean value) {
        setFieldValue(hasFix, value);
    }

    default Boolean getHasFix() {
        return getBooleanFieldValue(hasFix);
    }
}
