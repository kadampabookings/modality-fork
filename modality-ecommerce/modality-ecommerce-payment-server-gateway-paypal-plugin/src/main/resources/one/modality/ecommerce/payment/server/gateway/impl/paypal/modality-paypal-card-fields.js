console.log("Starting Modality-PayPal-CardFields script");

// Parameters injected by PayPalPaymentGateway java class on the server side
// Using 'var' so re-injection (React Strict Mode / retry) never throws "already declared".
var paypal_orderId = '${paypal_orderId}';
var paypal_sdkUrl  = '${paypal_sdkUrl}';

// Parameter injected by WebPaymentForm java class on the client side
var modality_javaPaymentForm;

var modality_initialized;
var modality_initError;
var modality_initNotificationCalled;
var modality_containerElement;
var modality_cardFields;   // set only when Card Fields API is used (null in Buttons fallback)
// Guard against same-injection double-calls: reset to false on each re-injection.
var modality_onLoadedRunning = false;

// Cross-injection stale detection for React Strict Mode: the counter lives on window so it
// survives script re-injection (unlike 'var' declarations which reset on every injection).
// onLoaded() captures its value as a LOCAL variable (myRunId) at call time; after each
// async await it checks whether window._paypalCardFieldsRunId still matches — if not, a newer
// injection has started and this run should abort to avoid PayPal's "zoid destroyed" error.
window._paypalCardFieldsRunId = (window._paypalCardFieldsRunId || 0) + 1;

// Constants
var modality_seamlessContainerId = 'modality-payment-form-container';

// ── Methods called by WebPaymentForm java class on the client side ─────────────

function modality_injectJavaPaymentForm(jpf) {
    console.log("modality_injectJavaPaymentForm() called (PayPal Card Fields)");
    modality_javaPaymentForm = jpf;
    if (modality_initialized) {
        // onLoaded() already ran — notify immediately with whatever outcome we have
        if (modality_initError)
            modality_notifyGatewayInitFailure(modality_initError);
        else
            modality_notifyGatewayInitSuccess();
        return;
    }
    onLoaded();
}

// Called by the React Pay button (via bridge.pay()) when Card Fields mode is active.
// Not called in Buttons fallback mode because onGatewayHasSelfContainedPayment hides the React Pay button.
function modality_submitGatewayPayment(firstName, lastName, email, phone, address, city, state, zipCode, countryCode, countryName) {
    console.log("modality_submitGatewayPayment() called (PayPal Card Fields)");
    if (!modality_cardFields) return; // no-op in Buttons fallback (React Pay button is hidden anyway)
    // The billing address is OPTIONAL, but if we send one it MUST carry a country code:
    // PayPal rejects the whole confirm-payment-source call with
    //   400 INVALID_REQUEST / MISSING_REQUIRED_PARAMETER on
    //   /payment_source/card/billing_address/country_code
    // when country_code is absent or empty. We used to send the object unconditionally, so
    // every card payment by a payer we hold no country for — every anonymous /pay-cart and
    // public-talk payer, and any member whose Person record has no country — failed with a
    // 400 no amount of retrying could fix. When we don't know the country we therefore send
    // no billing address at all, which is the same rule the server applies when it attaches
    // the payer address to the order (see PayPalPaymentGateway.createPayPalOrderId).
    var country = (countryCode || '').trim().toUpperCase();
    var submitOptions;
    if (country) {
        submitOptions = {
            billingAddress: {
                addressLines: address ? [address] : [],
                adminArea1:   state       || '',
                adminArea2:   city        || '',
                countryCode:  country,
                postalCode:   zipCode     || '',
            },
        };
    }
    modality_cardFields.submit(submitOptions).catch(function(err) {
        modality_notifyGatewayCardVerificationFailure(String(err));
    });
}

// ── Methods called by this JS script to call back the Java WebPaymentForm class ─

function modality_notifyGatewayInitSuccess() {
    modality_initialized = true;
    if (modality_javaPaymentForm) {
        modality_javaPaymentForm.onGatewayInitSuccess();
        modality_initNotificationCalled = true;
    }
}

function modality_notifyGatewayInitFailure(error) {
    modality_initialized = true;
    modality_initError = error;
    if (modality_javaPaymentForm) {
        modality_javaPaymentForm.onGatewayInitFailure(error);
        modality_initNotificationCalled = true;
    }
}

// Signals to the React bridge that the payment button is embedded in the gateway form itself
// (i.e. the Buttons fallback is active). React hides its own Pay button so the user interacts
// only with the PayPal Buttons widget — calling the React Pay button in this mode would stall
// the UI in 'processing' because the PayPal button iframe is cross-origin and cannot be triggered
// programmatically.
function modality_notifyGatewayHasSelfContainedPayment() {
    if (modality_javaPaymentForm && typeof modality_javaPaymentForm.onGatewayHasSelfContainedPayment === 'function')
        modality_javaPaymentForm.onGatewayHasSelfContainedPayment();
}

function modality_notifyGatewayCardVerificationFailure(error) {
    if (modality_javaPaymentForm)
        modality_javaPaymentForm.onGatewayCardVerificationFailure(error);
}

function modality_notifyGatewayBuyerVerificationFailure(error) {
    if (modality_javaPaymentForm)
        modality_javaPaymentForm.onGatewayBuyerVerificationFailure(error);
}

function modality_notifyGatewayPaymentVerificationSuccess(paymentCompletionPayload) {
    if (modality_javaPaymentForm)
        modality_javaPaymentForm.onGatewayPaymentVerificationSuccess(paymentCompletionPayload);
}

// ── Initialization ────────────────────────────────────────────────────────────

async function onLoaded() {
    if (modality_onLoadedRunning) {
        console.log("PayPal Card Fields: onLoaded already running, skipping duplicate call");
        return;
    }
    modality_onLoadedRunning = true;
    // Capture the run ID at call time so we can detect if a newer injection supersedes us.
    var myRunId = window._paypalCardFieldsRunId;
    console.log("PayPal Card Fields: onLoaded (runId=" + myRunId + ")");

    modality_containerElement = document.getElementById(modality_seamlessContainerId);
    if (!modality_containerElement) {
        modality_notifyGatewayInitFailure('Expected seamless container #' + modality_seamlessContainerId + ' but was not found in main DOM');
        return;
    }

    // Load the PayPal JS SDK (includes both card-fields and buttons components)
    console.log("PayPal Card Fields: loading SDK");
    try {
        await new Promise(function(resolve, reject) {
            var script = document.createElement('script');
            script.src = paypal_sdkUrl;
            script.async = true;
            script.onload = resolve;
            script.onerror = function() { reject(new Error('Failed to load PayPal SDK from ' + paypal_sdkUrl)); };
            document.head.appendChild(script);
        });
    } catch (e) {
        console.error('PayPal Card Fields: SDK load failed', e);
        modality_notifyGatewayInitFailure(e.message);
        return;
    }

    // Abort if a newer injection started while we were awaiting the SDK load.
    // This prevents "zoid destroyed all components" from React Strict Mode's double-injection.
    if (window._paypalCardFieldsRunId !== myRunId) {
        console.log("PayPal Card Fields: superseded by newer injection (runId=" + window._paypalCardFieldsRunId + "), aborting");
        return;
    }
    console.log("PayPal Card Fields: SDK loaded");

    // Common callbacks shared by both Card Fields and Buttons fallback
    var paymentCallbacks = {
        createOrder: function() { return Promise.resolve(paypal_orderId); },
        onApprove: function(data) {
            console.log("PayPal: onApprove", data.orderID);
            modality_notifyGatewayPaymentVerificationSuccess(JSON.stringify({ paypal_orderId: data.orderID }));
        },
        onError: function(err) {
            console.error('PayPal: onError', err);
            modality_notifyGatewayCardVerificationFailure(String(err));
        },
    };

    // ── Try Card Fields first (requires Advanced Credit and Debit Card Payments on the merchant account) ──
    if (typeof paypal !== 'undefined' && typeof paypal.CardFields === 'function') {
        var cardFields = paypal.CardFields(paymentCallbacks);
        if (cardFields.isEligible()) {
            console.log("PayPal Card Fields: eligible — rendering fields directly");
            modality_cardFields = cardFields;

            // Re-acquire the container by ID. The reference captured back in
            // onLoaded() may now point to a DETACHED node — React (or any
            // host wrapping this script) can unmount/remount the container
            // div during the SDK-load + CardFields() awaits above. Writing
            // innerHTML to a detached node would leave the placeholders
            // off-document so the document-wide selectors used by the
            // render() calls below would find nothing. Matches the fix in
            // stripe-payment-form.js and square-payment-form.js for the same
            // race; only manifests reliably on slower-await browsers
            // (Safari is the usual culprit).
            var freshCf = document.getElementById(modality_seamlessContainerId);
            if (!freshCf) {
                modality_notifyGatewayInitFailure('Payment form container disappeared during async init');
                return;
            }
            modality_containerElement = freshCf;
            // Render placeholders for the three card field iframes PayPal will inject
            modality_containerElement.innerHTML = `
                <form id="paypal-card-form">
                    <div id="paypal-card-number"  style="border:1px solid #d0d0d0; border-radius:4px; padding:8px 12px; min-height:44px; background:#fff; margin-bottom:8px;"></div>
                    <div style="display:flex; gap:12px;">
                        <div id="paypal-card-expiry" style="border:1px solid #d0d0d0; border-radius:4px; padding:8px 12px; min-height:44px; background:#fff; flex:1;"></div>
                        <div id="paypal-card-cvv"    style="border:1px solid #d0d0d0; border-radius:4px; padding:8px 12px; min-height:44px; background:#fff; flex:1;"></div>
                    </div>
                </form>
            `;

            try {
                await Promise.all([
                    modality_cardFields.NumberField({ placeholder: 'Card number' }).render('#paypal-card-number'),
                    modality_cardFields.ExpiryField({ placeholder: 'MM / YY'     }).render('#paypal-card-expiry'),
                    modality_cardFields.CVVField(   { placeholder: 'CVV'          }).render('#paypal-card-cvv'),
                ]);
                console.log("PayPal Card Fields: fields rendered successfully");
                modality_notifyGatewayInitSuccess();
            } catch (e) {
                console.error('PayPal Card Fields: field render failed', e);
                modality_notifyGatewayInitFailure('PayPal Card Fields failed to render: ' + e.message);
            }
            return;
        }
        console.warn("PayPal Card Fields: not eligible — falling back to Buttons (Advanced Credit and Debit Card Payments must be enabled in the merchant PayPal account to skip this step)");
    } else {
        console.warn("PayPal Card Fields: API not available — falling back to Buttons");
    }

    // ── Buttons fallback (card method) — shown when Card Fields is not eligible ──
    // The React Pay button is hidden via onGatewayHasSelfContainedPayment because the PayPal
    // Buttons widget is in a cross-origin iframe and cannot be triggered programmatically.
    // Re-acquire container — see matching comment in the eligible branch above.
    var freshBtn = document.getElementById(modality_seamlessContainerId);
    if (!freshBtn) {
        modality_notifyGatewayInitFailure('Payment form container disappeared during async init');
        return;
    }
    modality_containerElement = freshBtn;
    modality_containerElement.innerHTML = `<div id="paypal-card-button"></div>`;
    try {
        await paypal.Buttons({
            fundingSource: paypal.FUNDING.CARD,
            ...paymentCallbacks,
            onCancel: function() {
                modality_notifyGatewayBuyerVerificationFailure('Payment cancelled by user');
            },
        }).render('#paypal-card-button');
        console.log("PayPal Buttons fallback: rendered");
        // Signal BEFORE init success so the React Pay button is hidden from the start of 'ready' state
        modality_notifyGatewayHasSelfContainedPayment();
        modality_notifyGatewayInitSuccess();
    } catch (e) {
        console.error('PayPal Buttons fallback: render failed', e);
        modality_notifyGatewayInitFailure('PayPal payment form failed to render: ' + e.message);
    }
}
