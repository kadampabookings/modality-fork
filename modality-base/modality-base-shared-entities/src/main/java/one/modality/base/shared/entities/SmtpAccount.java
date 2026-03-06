package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.markers.EntityHasOrganization;

/**
 * @author Bruno Salmon
 */
public interface SmtpAccount extends Entity, EntityHasOrganization {
    String host = "host";
    String port = "port";
    String username = "username";
    String password = "password";
    String ssl = "ssl";
    String quota = "quota";

    default void setHost(String value) {
        setFieldValue(host, value);
    }

    default String getHost() {
        return getStringFieldValue(host);
    }

    default void setPort(Integer value) {
        setFieldValue(port, value);
    }

    default Integer getPort() {
        return (Integer) getFieldValue(port);
    }

    default void setUsername(String value) {
        setFieldValue(username, value);
    }

    default String getUsername() {
        return getStringFieldValue(username);
    }

    default void setPassword(String value) {
        setFieldValue(password, value);
    }

    default String getPassword() {
        return getStringFieldValue(password);
    }

    default void setSsl(Boolean value) {
        setFieldValue(ssl, value);
    }

    default Boolean isSsl() {
        return getBooleanFieldValue(ssl);
    }

    default void setQuota(Object value) {
        setForeignField(quota, value);
    }

    default EntityId getQuotaId() {
        return getForeignEntityId(quota);
    }
}
