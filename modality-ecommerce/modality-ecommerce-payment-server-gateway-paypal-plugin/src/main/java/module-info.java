// File managed by WebFX (DO NOT EDIT MANUALLY)

/**
 * The SPI payment gateway implementation for making redirect payments with PayPal.
 */
module modality.ecommerce.payment.server.gateway.paypal.plugin {

    // Direct dependencies modules
    requires io.vertx.core;
    requires io.vertx.web;
    requires modality.base.shared.entities;
    requires modality.ecommerce.payment;
    requires modality.ecommerce.payment.server.gateway;
    requires webfx.platform.ast;
    requires webfx.platform.async;
    requires webfx.platform.boot;
    requires webfx.platform.console;
    requires webfx.platform.fetch;
    requires webfx.platform.resource;
    requires webfx.platform.util;
    requires webfx.platform.util.http;
    requires webfx.platform.util.vertx;
    requires webfx.stack.orm.entity;
    requires webfx.stack.session.state;

    // Exported packages
    exports one.modality.ecommerce.payment.server.gateway.impl.paypal;

    // Resources packages
    opens one.modality.ecommerce.payment.server.gateway.impl.paypal;

    // Provided services
    provides dev.webfx.platform.boot.spi.ApplicationJob with one.modality.ecommerce.payment.server.gateway.impl.paypal.PayPalRestApiJob;
    provides one.modality.ecommerce.payment.server.gateway.PaymentGateway with one.modality.ecommerce.payment.server.gateway.impl.paypal.PayPalPaymentGateway;

}