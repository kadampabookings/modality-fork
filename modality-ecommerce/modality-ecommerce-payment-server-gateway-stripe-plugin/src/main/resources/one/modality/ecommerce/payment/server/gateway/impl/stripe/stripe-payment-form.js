console.log("Starting Modality-Stripe script");

// Parameters injected by StripePaymentGateway on the server side.
// Using 'var' so the script can be re-injected (React Strict Mode / retry) without
// throwing "already declared" errors that const/let would cause on re-execution.
var modality_amount = ${modality_amount};
var modality_currencyCode = "${modality_currencyCode}";
var modality_seamless = ${modality_seamless};
var modality_paymentMethodId = '${modality_paymentMethodId}';
var stripe_publishableKey = '${stripe_publishableKey}';
var stripe_clientSecret = '${stripe_clientSecret}';
var stripe_paymentIntentId = '${stripe_paymentIntentId}';
var stripe_returnUrl = '${stripe_returnUrl}';
var stripe_countryCode = '${stripe_countryCode}';

// Parameter injected by WebPaymentForm java class on the client side (to allow JS -> Java callbacks)
var modality_javaPaymentForm;

var modality_initialized;
var modality_initError;
var modality_initNotificationCalled;
var modality_containerElement;

// Constants
var modality_seamlessContainerId = 'modality-payment-form-container';
var stripe_paymentElementContainerId = 'stripe-payment-element';

// Stripe state (recreated when the script is re-injected)
var stripe_stripeInstance;
var stripe_elements;
var stripe_paymentElement;  // set when payment method is CARD
var stripe_paymentRequest;  // set when payment method is GOOGLE_PAY / APPLE_PAY

// Guard against React Strict Mode double-injection: both load callbacks would otherwise
// run onLoaded() twice and attach two widgets. Reset to false on each script re-injection.
var modality_onLoadedRunning = false;

// Catches uncaught errors thrown by Stripe.js or browser extensions during initialisation
// (e.g. extensions injecting into the card form). Routes them through
// modality_notifyGatewayInitFailure so the fallback redirect can fire instead of leaving
// the user with a broken form or a raw browser error message.
function modality_uncaughtInitErrorHandler(event) {
    if (!modality_initialized) {
        console.error('Uncaught error during Stripe init (browser extension interference?)', event.error || event.message);
        modality_notifyGatewayInitFailure('Payment form failed to load: ' + (event.message || 'unknown error'));
    }
}
// Remove any listener left by a previous injection before adding a fresh one,
// so re-injection (React Strict Mode / retry) never registers duplicate listeners.
window.removeEventListener('error', modality_uncaughtInitErrorHandler);
window.addEventListener('error', modality_uncaughtInitErrorHandler);

// === Methods called by the Java WebPaymentForm class on the client side ===

function modality_injectJavaPaymentForm(jpf) {
    console.log("modality_injectJavaPaymentForm() called");
    modality_javaPaymentForm = jpf;
    modality_notifyGatewayDebugStep(1);
    // Tear down any previous Stripe widget so the new one attaches cleanly
    if (stripe_paymentElement) {
        modality_notifyGatewayDebugStep(2);
        try {
            console.log("Unmounting previous Stripe Payment Element");
            stripe_paymentElement.unmount();
            stripe_paymentElement = undefined;
            stripe_elements = undefined;
        } catch (e) {
            modality_notifyGatewayDebugStep(3);
            console.error('Unmounting previous Stripe Payment Element failed', e);
            modality_notifyGatewayInitFailure('Unmounting previous Stripe widget failed: ' + e.message);
            return;
        }
    }
    if (modality_initialized) {
        if (modality_initError) {
            modality_notifyGatewayDebugStep(4);
            modality_notifyGatewayInitFailure(modality_initError);
        } else {
            modality_notifyGatewayDebugStep(5);
            modality_notifyGatewayInitSuccess();
        }
    }
}

function modality_submitGatewayPayment(firstName, lastName, email, phone, address, city, state, postCode, countryCode, countryName) {
    console.log("modality_submitGatewayPayment() called");
    // Google Pay and Apple Pay are self-contained: clicking their native button directly
    // triggers the payment sheet, so the React Pay button is hidden and this function is never called.
    if (modality_paymentMethodId === 'GOOGLE_PAY' || modality_paymentMethodId === 'APPLE_PAY') return;
    handleCardSubmission({ firstName, lastName, email, phone, address, city, state, postCode, countryCode, countryName });
}

// === JS -> Java callbacks (notifying the WebPaymentForm about the progress of the gateway flow) ===

function modality_notifyGatewayDebugStep(step) {
    if (modality_javaPaymentForm) modality_javaPaymentForm.onGatewayDebugStep(step);
}

function modality_notifyGatewayInitSuccess() {
    modality_initialized = true;
    // Remove the uncaught-error safety net — no longer needed once Stripe is up
    window.removeEventListener('error', modality_uncaughtInitErrorHandler);
    if (modality_javaPaymentForm) {
        modality_javaPaymentForm.onGatewayInitSuccess();
        modality_initNotificationCalled = true;
    }
}

function modality_notifyGatewayInitFailure(error) {
    modality_initialized = true;
    modality_initError = error;
    window.removeEventListener('error', modality_uncaughtInitErrorHandler);
    if (modality_javaPaymentForm) {
        modality_javaPaymentForm.onGatewayInitFailure(error);
        modality_initNotificationCalled = true;
    }
}

function modality_notifyGatewayCardVerificationFailure(error) {
    if (modality_javaPaymentForm) modality_javaPaymentForm.onGatewayCardVerificationFailure(error);
}

function modality_notifyGatewayBuyerVerificationFailure(error) {
    if (modality_javaPaymentForm) modality_javaPaymentForm.onGatewayBuyerVerificationFailure(error);
}

function modality_notifyGatewayPaymentVerificationSuccess(paymentCompletionPayload) {
    if (modality_javaPaymentForm) modality_javaPaymentForm.onGatewayPaymentVerificationSuccess(paymentCompletionPayload);
}

// Signals to the React bridge that this form contains its own pay button (Google Pay / Apple Pay).
// React hides the external Pay button so the user interacts only with the wallet button —
// these payment sheets must be opened by a direct user gesture on the native button.
function modality_notifyGatewayHasSelfContainedPayment() {
    if (modality_javaPaymentForm && typeof modality_javaPaymentForm.onGatewayHasSelfContainedPayment === 'function')
        modality_javaPaymentForm.onGatewayHasSelfContainedPayment();
}

// === Stripe.js bootstrap ===

// Stripe.js is delivered as a regular script (not an ES module), so dynamic import() doesn't work
// the way it does for Square. In seamless mode we inject the <script> tag ourselves; in iframe
// mode it's already loaded by the iframe HTML template.
function loadStripeJs() {
    return new Promise(function (resolve, reject) {
        if (window.Stripe) return resolve();
        var existing = document.querySelector('script[src^="https://js.stripe.com/v3"]');
        if (existing) {
            existing.addEventListener('load',  function () { resolve(); });
            existing.addEventListener('error', function () { reject(new Error('Stripe.js script failed to load')); });
            return;
        }
        var s = document.createElement('script');
        s.src = 'https://js.stripe.com/v3/';
        s.async = true;
        s.onload  = function () { resolve(); };
        s.onerror = function () { reject(new Error('Stripe.js script failed to load')); };
        document.head.appendChild(s);
    });
}

// === Payment Element flow (CARD) ===

async function initializeCard() {
    stripe_elements = stripe_stripeInstance.elements({ clientSecret: stripe_clientSecret });
    stripe_paymentElement = stripe_elements.create('payment');
    await stripe_paymentElement.mount('#' + stripe_paymentElementContainerId);
}

async function handleCardSubmission(billing) {
    try {
        // redirect: 'if_required' keeps the user inline whenever possible (no 3DS challenge);
        // when a redirect IS required, Stripe.js navigates to return_url. For iframe mode this
        // would navigate the iframe — the React side handles the return on the parent window.
        const result = await stripe_stripeInstance.confirmPayment({
            elements: stripe_elements,
            confirmParams: {
                return_url: stripe_returnUrl || window.location.href,
                payment_method_data: buildBillingDetails(billing),
            },
            redirect: 'if_required',
        });
        if (result.error) {
            displayPaymentResults('FAILURE');
            modality_notifyGatewayCardVerificationFailure(result.error.message);
            return;
        }
        const pi = result.paymentIntent;
        if (!pi) {
            modality_notifyGatewayCardVerificationFailure('Stripe.confirmPayment returned no paymentIntent');
            return;
        }
        if (pi.status === 'succeeded' || pi.status === 'requires_capture')
            displayPaymentResults('SUCCESS');
        // Tell Java the payment is verified. The server will retrieve the PaymentIntent via the
        // Stripe API and use that as the source of truth (so the client cannot spoof success).
        modality_notifyGatewayPaymentVerificationSuccess(JSON.stringify({
            modality_amount: modality_amount,
            modality_currencyCode: modality_currencyCode,
            modality_paymentMethodId: modality_paymentMethodId,
            stripe_paymentIntentId: pi.id,
            stripe_paymentStatus: pi.status,
        }));
    } catch (e) {
        displayPaymentResults('FAILURE');
        modality_notifyGatewayCardVerificationFailure(e.message);
    }
}

// Only includes billing details if the user provided enough data — Stripe rejects empty objects.
function buildBillingDetails(b) {
    if (!b) return undefined;
    var name = ((b.firstName || '') + ' ' + (b.lastName || '')).trim();
    var bd = {};
    if (name)     bd.name  = name;
    if (b.email)  bd.email = b.email;
    if (b.phone)  bd.phone = b.phone;
    var addr = {};
    if (b.address)    addr.line1       = b.address;
    if (b.city)       addr.city        = b.city;
    if (b.state)      addr.state       = b.state;
    if (b.postCode)   addr.postal_code = b.postCode;
    if (b.countryCode) addr.country    = b.countryCode.toUpperCase();
    if (Object.keys(addr).length) bd.address = addr;
    if (!Object.keys(bd).length) return undefined;
    return { billing_details: bd };
}

// === Payment Request flow (GOOGLE_PAY / APPLE_PAY) ===

async function initializeWallet(method) {
    stripe_paymentRequest = stripe_stripeInstance.paymentRequest({
        country:  stripe_countryCode,
        currency: modality_currencyCode.toLowerCase(),
        total:    { label: 'Total', amount: Number(modality_amount) },
        requestPayerName:  true,
        requestPayerEmail: true,
    });
    var canPay = await stripe_paymentRequest.canMakePayment();
    if (!canPay) {
        // Prefix __METHOD_UNSUPPORTED__ so React can distinguish a permanent config issue
        // (which should remove the payment option) from a transient initialisation failure.
        modality_notifyGatewayInitFailure('__METHOD_UNSUPPORTED__:' + method + ' is not available on this device/browser');
        return;
    }
    if (method === 'GOOGLE_PAY' && !canPay.googlePay) {
        modality_notifyGatewayInitFailure('__METHOD_UNSUPPORTED__:Google Pay is not available on this device/browser');
        return;
    }
    if (method === 'APPLE_PAY' && !canPay.applePay) {
        modality_notifyGatewayInitFailure('__METHOD_UNSUPPORTED__:Apple Pay is not available on this device/browser');
        return;
    }
    modality_containerElement.innerHTML =
        '<div id="stripe-wallet-button" style="padding: 4px 0;"></div>' +
        '<div id="stripe-payment-status-container"></div>';
    var elements = stripe_stripeInstance.elements();
    var prButton = elements.create('paymentRequestButton', { paymentRequest: stripe_paymentRequest });
    prButton.mount('#stripe-wallet-button');

    stripe_paymentRequest.on('paymentmethod', async function (ev) {
        try {
            // handleActions: false lets us drive the 3DS step ourselves below — needed because
            // we want to send the final PaymentIntent state back to Java in a single payload.
            var first = await stripe_stripeInstance.confirmCardPayment(
                stripe_clientSecret,
                { payment_method: ev.paymentMethod.id },
                { handleActions: false }
            );
            if (first.error) {
                ev.complete('fail');
                displayPaymentResults('FAILURE');
                modality_notifyGatewayCardVerificationFailure(first.error.message);
                return;
            }
            ev.complete('success');
            var pi = first.paymentIntent;
            if (pi && pi.status === 'requires_action') {
                // Rare for wallet flows, but possible — let Stripe handle the 3DS challenge.
                var second = await stripe_stripeInstance.confirmCardPayment(stripe_clientSecret);
                if (second.error) {
                    displayPaymentResults('FAILURE');
                    modality_notifyGatewayCardVerificationFailure(second.error.message);
                    return;
                }
                pi = second.paymentIntent;
            }
            displayPaymentResults('SUCCESS');
            modality_notifyGatewayPaymentVerificationSuccess(JSON.stringify({
                modality_amount: modality_amount,
                modality_currencyCode: modality_currencyCode,
                modality_paymentMethodId: modality_paymentMethodId,
                stripe_paymentIntentId: pi.id,
                stripe_paymentStatus: pi.status,
            }));
        } catch (e) {
            ev.complete('fail');
            displayPaymentResults('FAILURE');
            modality_notifyGatewayCardVerificationFailure(e.message);
        }
    });

    // Wallet button is self-contained — hide the host Pay button.
    modality_notifyGatewayHasSelfContainedPayment();
}

// status is either SUCCESS or FAILURE
function displayPaymentResults(status) {
    var c = document.getElementById('stripe-payment-status-container');
    if (!c) return;
    if (status === 'SUCCESS') {
        c.classList.remove('is-failure');
        c.classList.add('is-success');
    } else {
        c.classList.remove('is-success');
        c.classList.add('is-failure');
    }
    c.style.visibility = 'visible';
}

// === Bootstrap ===

async function onLoaded() {
    if (modality_onLoadedRunning) {
        console.log("Stripe: onLoaded already running, skipping duplicate call");
        return;
    }
    modality_onLoadedRunning = true;
    console.log("Stripe DOMContentLoaded");
    if (modality_javaPaymentForm) {
        console.log("modality_javaPaymentForm is set");
    } else {
        console.log("modality_javaPaymentForm is NOT set");
    }

    try {
        await loadStripeJs();
    } catch (e) {
        console.error('Stripe.js failed to load', e);
        modality_notifyGatewayInitFailure('Stripe.js failed to load: ' + e.message);
        return;
    }
    if (!window.Stripe) {
        modality_notifyGatewayInitFailure('Stripe.js loaded but window.Stripe is undefined');
        return;
    }

    modality_containerElement = modality_seamless ? document.getElementById(modality_seamlessContainerId) : document.body;
    if (!modality_containerElement) {
        modality_notifyGatewayInitFailure('Expected seamless container #' + modality_seamlessContainerId + ' but was not found in main DOM');
        return;
    }

    try {
        stripe_stripeInstance = window.Stripe(stripe_publishableKey);
    } catch (e) {
        console.error('Stripe init failed', e);
        modality_notifyGatewayInitFailure('Stripe initialisation failed: ' + e.message);
        return;
    }

    if (modality_paymentMethodId === 'GOOGLE_PAY' || modality_paymentMethodId === 'APPLE_PAY') {
        try {
            console.log("Calling initializeWallet for " + modality_paymentMethodId);
            await initializeWallet(modality_paymentMethodId);
        } catch (e) {
            console.error('Initializing wallet failed', e);
            // PaymentRequestUnsupportedError-style condition → permanent config issue
            var prefix = (e && e.name === 'PaymentMethodUnsupportedError') ? '__METHOD_UNSUPPORTED__:' : '';
            modality_notifyGatewayInitFailure(prefix + 'Stripe wallet initialization failed: ' + e.message);
            return;
        }
    } else {
        // CARD: container HTML for the Payment Element + status footer
        modality_containerElement.innerHTML =
            '<form id="stripe-payment-form">' +
            '    <div id="' + stripe_paymentElementContainerId + '"></div>' +
            '</form>' +
            '<div id="stripe-payment-status-container"></div>';
        try {
            console.log("Calling initializeCard");
            await initializeCard();
        } catch (e) {
            console.error('Initializing Card failed', e);
            modality_notifyGatewayInitFailure('Stripe card initialization failed: ' + e.message);
            var statusContainer = document.getElementById('stripe-payment-status-container');
            if (statusContainer) {
                statusContainer.className = 'missing-credentials';
                statusContainer.style.visibility = 'visible';
            }
            return;
        }
    }

    modality_notifyGatewayInitSuccess();
}

if (document.readyState === "complete" || document.readyState === "interactive") {
    // Document is already ready, so just call the function directly
    onLoaded();
} else {
    // Document is not ready yet, so add an event listener for DOMContentLoaded
    document.addEventListener('DOMContentLoaded', onLoaded);
}
