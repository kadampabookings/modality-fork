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

// Billing details pre-filled from the booking (server side). Any non-empty value here means
// (a) the Payment Element will hide that field and (b) we'll send the value at confirmPayment()
// time. Empty string = we don't have it, fall back to whatever the user types in the form.
var modality_billingFirstName   = '${modality_billingFirstName}';
var modality_billingLastName    = '${modality_billingLastName}';
var modality_billingEmail       = '${modality_billingEmail}';
var modality_billingPhone       = '${modality_billingPhone}';
var modality_billingAddress     = '${modality_billingAddress}';
var modality_billingCity        = '${modality_billingCity}';
var modality_billingState       = '${modality_billingState}';
var modality_billingPostCode    = '${modality_billingPostCode}';
var modality_billingCountryCode = '${modality_billingCountryCode}';

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
    // Hide each billing-details input the server pre-filled — 'never' tells the Payment Element
    // we'll supply the value ourselves at confirmPayment() time. We keep 'auto' for missing
    // values so the user can fill them in. Address stays 'auto' regardless — Stripe requires
    // a full address when set to 'never' and our customer data may be partial.
    //
    // wallets.link='never' disables Onelink's "save my info for faster checkout" CTA, which
    // would otherwise render its own Email / Mobile / Full name inputs inside the form — these
    // are NOT controlled by fields.billingDetails (Link is a separate Stripe feature) and they
    // also can't be suppressed via payment_method_types on the PaymentIntent. Without this,
    // hiding billing details has no visible effect because Link re-introduces equivalent fields.
    //
    // wallets.applePay/googlePay='never' suppresses the wallet shortcut buttons at the top of
    // the card form — in our gateway, Apple Pay / Google Pay are surfaced as discrete methods
    // upstream and use the Payment Request Button widget instead, so they shouldn't double up
    // inside the card flow.
    // Pre-fill the visible address inputs (Country + Postal code, plus line1/city/state if
    // Stripe chooses to render them based on currency / country). User can still edit if
    // their saved profile is wrong, and a pre-filled postal code helps with card AVS checks.
    var addressDefaults = {};
    if (modality_billingAddress)     addressDefaults.line1       = modality_billingAddress;
    if (modality_billingCity)        addressDefaults.city        = modality_billingCity;
    if (modality_billingState)       addressDefaults.state       = modality_billingState;
    if (modality_billingPostCode)    addressDefaults.postal_code = modality_billingPostCode;
    if (modality_billingCountryCode) addressDefaults.country     = modality_billingCountryCode.toUpperCase();

    var paymentElementOptions = {
        fields: {
            billingDetails: {
                name:  (modality_billingFirstName || modality_billingLastName) ? 'never' : 'auto',
                email: modality_billingEmail ? 'never' : 'auto',
                phone: modality_billingPhone ? 'never' : 'auto',
            }
        },
        wallets: {
            applePay:  'never',
            googlePay: 'never',
            link:      'never',
        }
    };
    if (Object.keys(addressDefaults).length) {
        paymentElementOptions.defaultValues = {
            billingDetails: { address: addressDefaults }
        };
    }
    stripe_paymentElement = stripe_elements.create('payment', paymentElementOptions);
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

// Builds the billing_details object sent to Stripe at confirmPayment() time. Merges values
// in this order of precedence: React-provided billing > server-injected modality_billing_*
// defaults. Whatever we send here MUST cover any field set to 'never' in fields.billingDetails
// (otherwise Stripe rejects the confirm) — and the modality_billing_* defaults are exactly
// what we keyed the 'never' decision on, so coverage is guaranteed.
function buildBillingDetails(b) {
    b = b || {};
    var firstName   = b.firstName   || modality_billingFirstName;
    var lastName    = b.lastName    || modality_billingLastName;
    var email       = b.email       || modality_billingEmail;
    var phone       = b.phone       || modality_billingPhone;
    var address     = b.address     || modality_billingAddress;
    var city        = b.city        || modality_billingCity;
    var state       = b.state       || modality_billingState;
    var postCode    = b.postCode    || modality_billingPostCode;
    var countryCode = b.countryCode || modality_billingCountryCode;

    var name = ((firstName || '') + ' ' + (lastName || '')).trim();
    var bd = {};
    if (name)  bd.name  = name;
    if (email) bd.email = email;
    if (phone) bd.phone = phone;
    var addr = {};
    if (address)     addr.line1       = address;
    if (city)        addr.city        = city;
    if (state)       addr.state       = state;
    if (postCode)    addr.postal_code = postCode;
    if (countryCode) addr.country     = countryCode.toUpperCase();
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
    // Re-acquire the container by ID. The reference captured back in onLoaded()
    // may now point to a DETACHED node — React (or any host that wraps this
    // script) can unmount/remount the container div during the long await on
    // canMakePayment() above. Apple Pay's canMakePayment() round-trips with the
    // OS Wallet and is noticeably slower on Safari Mac than the Google Pay path
    // is on Chrome, which is why this only manifests on Safari Mac. Writing
    // innerHTML to a detached node + then mounting via document-wide selector
    // produces the misleading "selector applies to no DOM elements" error
    // because the live container (the freshly-mounted React node) is empty.
    if (modality_seamless) {
        var fresh = document.getElementById(modality_seamlessContainerId);
        if (!fresh) {
            modality_notifyGatewayInitFailure('Payment form container disappeared during async init');
            return;
        }
        modality_containerElement = fresh;
    }
    modality_containerElement.innerHTML =
        '<div id="stripe-wallet-button" style="padding: 4px 0;"></div>' +
        '<div id="stripe-payment-status-container"></div>';
    var elements = stripe_stripeInstance.elements();
    var prButton = elements.create('paymentRequestButton', { paymentRequest: stripe_paymentRequest });
    // Mount using the actual DOM node rather than a CSS selector. Stripe's
    // selector-based mount runs `document.querySelector` against the global
    // document — if React reconciles between the innerHTML write above and
    // this call, the selector may match a stale or missing node. Passing the
    // node we just wrote into avoids that race entirely.
    var walletButtonEl = modality_containerElement.querySelector('#stripe-wallet-button');
    prButton.mount(walletButtonEl);

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

    // User dismissed the Apple Pay / Google Pay sheet without completing — Stripe fires
    // 'cancel' (the 'paymentmethod' event NEVER fires in this case), so we need a separate
    // listener to unblock React's Pay button. Without this, the spinner runs forever waiting
    // for a verification result that will never come.
    stripe_paymentRequest.on('cancel', function () {
        modality_notifyGatewayCardVerificationFailure('Payment cancelled by user');
    });

    // Wallet button is self-contained — hide the host Pay button.
    modality_notifyGatewayHasSelfContainedPayment();

    // Expose a programmatic trigger for the wallet sheet so the React Pay button can open it
    // from inside its own click handler — mirrors the Square gateway's same-named function so
    // the React side works identically across gateways. paymentRequest.show() opens the Apple
    // Pay / Google Pay sheet; the existing paymentRequest.on('paymentmethod') listener picks
    // up the result and runs the confirmCardPayment + completion flow.
    //
    // Must be invoked from inside a live user-gesture (click/tap) — iOS Safari rejects
    // paymentRequest.show() otherwise. The React Pay button click handler is fine; calling
    // this from a setTimeout/promise continuation is not.
    window.modality_triggerWalletPayment = function () {
        try {
            stripe_paymentRequest.show();
        } catch (e) {
            console.error('Stripe paymentRequest.show() threw:', e);
            modality_notifyGatewayCardVerificationFailure(e.message);
        }
    };
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
