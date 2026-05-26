// File managed by WebFX (DO NOT EDIT MANUALLY)

/**
 * The SPI payment gateway implementation for making embedded payments with Stripe.
 */
module modality.ecommerce.payment.server.gateway.stripe.plugin {

    // Direct dependencies modules
    requires com.stripe;
    requires io.vertx.core;
    requires io.vertx.web;
    requires modality.base.shared.entities;
    requires modality.ecommerce.payment;
    requires modality.ecommerce.payment.server.gateway;
    requires webfx.platform.ast;
    requires webfx.platform.async;
    requires webfx.platform.boot;
    requires webfx.platform.console;
    requires webfx.platform.resource;
    requires webfx.platform.util;
    requires webfx.platform.util.http;
    requires webfx.platform.util.vertx;
    requires webfx.stack.orm.entity;
    requires webfx.stack.session.state;

    // Exported packages
    exports one.modality.ecommerce.payment.server.gateway.impl.stripe;

    // Resources packages
    opens one.modality.ecommerce.payment.server.gateway.impl.stripe;

    // Provided services
    provides dev.webfx.platform.boot.spi.ApplicationJob with one.modality.ecommerce.payment.server.gateway.impl.stripe.StripeRestApiJob;
    provides one.modality.ecommerce.payment.server.gateway.PaymentGateway with one.modality.ecommerce.payment.server.gateway.impl.stripe.StripePaymentGateway;

}