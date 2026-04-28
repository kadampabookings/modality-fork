console.log("Starting Modality-Square script");

// Parameters injected by SquarePaymentGateway java class on the server side
// Using 'var' so the script can be re-injected (React Strict Mode / retry) without
// throwing "already declared" errors that const/let would cause on re-execution.
var modality_amount = ${modality_amount};
var modality_currencyCode = "${modality_currencyCode}";
var modality_seamless = ${modality_seamless};
var square_webPaymentsSDKUrl = '${square_webPaymentsSDKUrl}';
var square_appId = '${square_appId}';
var square_locationId = '${square_locationId}';
var square_countryCode = '${square_countryCode}';
// The specific payment method to render ('CARD', 'APPLE_PAY', …). Injected by the server.
var modality_paymentMethodId = '${modality_paymentMethodId}';
// Refreshed at the start of each handlePaymentMethodSubmission call so retries after a
// decline get a fresh key and are not served Square's cached declined response.
var square_idempotencyKey = window.crypto.randomUUID();

// Parameter injected by WebPaymentForm java class on the client side (to allow JS -> Java callbacks)
var modality_javaPaymentForm;

var modality_initialized;
var modality_initError;
var modality_initNotificationCalled;
var modality_containerElement;

// Constants
var modality_seamlessContainerId = 'modality-payment-form-container';
var square_cardElementId = 'square-card-container';

// Variables
var square_card;       // set when payment method is CARD
var square_googlePay;  // set when payment method is GOOGLE_PAY
var square_applePay;   // set when payment method is APPLE_PAY
// Guard against React Strict Mode double-injection: both import().then() callbacks would otherwise
// both call onLoaded() and attach two card widgets. Reset to false on each script re-injection.
var modality_onLoadedRunning = false;

// Catches uncaught errors thrown by the Square SDK or browser extensions during initialisation
// (e.g. "insertBefore" DOM errors caused by password-manager extensions injecting into the card
// form). Routes them through modality_notifyGatewayInitFailure so the fallback redirect fires
// instead of leaving the user with a broken form or a raw browser error message.
function modality_uncaughtInitErrorHandler(event) {
    if (!modality_initialized) {
        console.error('Uncaught error during Square init (browser extension interference?)', event.error || event.message);
        modality_notifyGatewayInitFailure('Payment form failed to load: ' + (event.message || 'unknown error'));
    }
}
// Remove any listener left by a previous injection before adding a fresh one,
// so re-injection (React Strict Mode / retry) never registers duplicate listeners.
window.removeEventListener('error', modality_uncaughtInitErrorHandler);
window.addEventListener('error', modality_uncaughtInitErrorHandler);

// Methods called by WebPaymentForm java class on the client side

function modality_injectJavaPaymentForm(jpf) {
    console.log("modality_injectJavaPaymentForm() called");
    modality_javaPaymentForm = jpf;
    modality_notifyGatewayDebugStep(1);
    // Destroy any previous Square payment widget so the new one can attach cleanly
    const previousWidget = square_card || square_googlePay || square_applePay;
    if (previousWidget) {
        modality_notifyGatewayDebugStep(2);
        try {
            console.log("Destroying previous Square widget");
            previousWidget.destroy();
            square_card = undefined;
            square_googlePay = undefined;
            square_applePay = undefined;
        } catch (e) {
            modality_notifyGatewayDebugStep(3);
            console.error('Destroying previous Square widget failed', e);
            modality_notifyGatewayInitFailure('Destroying previous Square widget failed: ' + e.message);
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
    // Google Pay and Apple Pay are self-contained: their buttons directly trigger the
    // payment sheet, so the React Pay button is hidden and this function is never called.
    if (modality_paymentMethodId === 'GOOGLE_PAY' || modality_paymentMethodId === 'APPLE_PAY') return;
    handlePaymentMethodSubmission(firstName, lastName, email, phone, address, city, state, countryCode);
}

// Methods called by this JS script to call back the Java WebPaymentForm class to notify about the progress in the gateway process

function modality_notifyGatewayDebugStep(debugStep) {
    if (modality_javaPaymentForm) {
        modality_javaPaymentForm.onGatewayDebugStep(debugStep);
    }
}

function modality_notifyGatewayInitSuccess() {
    modality_initialized = true;
    // Remove the uncaught-error safety net — no longer needed once Square is up
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

// Signals to the React bridge that this form contains its own pay button (Google Pay / Apple Pay).
// React hides the external Pay button so the user interacts only with the wallet button —
// these payment sheets must be opened by a direct user gesture on the native button.
function modality_notifyGatewayHasSelfContainedPayment() {
    if (modality_javaPaymentForm && typeof modality_javaPaymentForm.onGatewayHasSelfContainedPayment === 'function')
        modality_javaPaymentForm.onGatewayHasSelfContainedPayment();
}

console.log("Loading Square module");

import(square_webPaymentsSDKUrl)
    .catch(err => {
        console.error('Failed to load Square module', err);
    })
    .then(module => {
        console.log("Square module loaded");

        let payments;

        async function initializeCard(payments) {
            const card = await payments.card();
            await card.attach('#' + square_cardElementId);
            return card;
        }

        // Initialises the Google Pay payment method and renders a Google Pay button.
        // The SDK attaches the button via googlePay.attach(); clicking it opens the Google Pay
        // payment sheet automatically. The 'ontokenization' event fires with the result so
        // we do not need a separate click handler calling tokenize().
        async function initializeGooglePay(payments) {
            const paymentRequest = payments.paymentRequest({
                countryCode:  square_countryCode,
                currencyCode: modality_currencyCode,
                total: {
                    amount: (Number(modality_amount) / 100).toFixed(2),
                    label:  'Total',
                },
            });
            const googlePay = await payments.googlePay(paymentRequest);

            modality_containerElement.innerHTML = `
                <div id="square-google-pay-button" style="padding: 4px 0;"></div>
                <div id="square-payment-status-container"></div>
            `;
            await googlePay.attach('#square-google-pay-button');
            console.log("Square Google Pay: button attached");

            // Primary path: ontokenization event (Square SDK fires this after the user approves
            // in the Google Pay sheet — the button handles the sheet opening internally).
            googlePay.addEventListener('ontokenization', async function(event) {
                square_idempotencyKey = window.crypto.randomUUID();
                const { tokenResult, error } = event.detail;
                if (error) {
                    displayPaymentResults('FAILURE');
                    console.error('Google Pay tokenization error:', error.message);
                    modality_notifyGatewayCardVerificationFailure(error.message);
                    return;
                }
                if (tokenResult.status !== 'OK') {
                    const msg = `Google Pay tokenization failed: ${tokenResult.status}`;
                    displayPaymentResults('FAILURE');
                    console.error(msg);
                    modality_notifyGatewayCardVerificationFailure(msg);
                    return;
                }
                await handleGooglePayToken(tokenResult.token);
            });

            // Fallback: some Square SDK versions deliver the result via a click handler on the
            // button container instead of (or in addition to) the ontokenization event.
            // Clicking the container is safe because the Google Pay button fills it entirely.
            document.getElementById('square-google-pay-button').addEventListener('click', async function() {
                square_idempotencyKey = window.crypto.randomUUID();
                let token;
                try {
                    token = await tokenize(googlePay);
                } catch (e) {
                    // If ontokenization already handled this, tokenize() may throw a harmless
                    // "already in progress" error — suppress it silently.
                    if (String(e).toLowerCase().includes('progress') || String(e).toLowerCase().includes('pending')) {
                        return; // already handled by ontokenization
                    }
                    displayPaymentResults('FAILURE');
                    console.error('Google Pay tokenize failed:', e.message);
                    modality_notifyGatewayCardVerificationFailure(e.message);
                    return;
                }
                await handleGooglePayToken(token);
            });

            async function handleGooglePayToken(token) {
                // verifyBuyer() adds 3DS/SCA on top of Google Pay's own authentication.
                // Non-fatal: wallet device auth already satisfies SCA; proceed without token if it fails.
                let verificationToken = null;
                try {
                    verificationToken = await verifyBuyer(token, '', '', '', '', '', '', '', '');
                } catch (e) {
                    console.warn('Google Pay: verifyBuyer failed, proceeding without verification token:', e.message);
                }
                const paymentCompletionPayload = {
                    modality_amount:          modality_amount,
                    modality_currencyCode:    modality_currencyCode,
                    square_locationId:        square_locationId,
                    square_sourceId:          token,
                    square_verificationToken: verificationToken,
                    square_idempotencyKey:    square_idempotencyKey,
                };
                modality_notifyGatewayPaymentVerificationSuccess(JSON.stringify(paymentCompletionPayload));
            }

            return googlePay;
        }

        // Initialises the Apple Pay payment method and renders a native Apple Pay button.
        // The button's click handler calls tokenize(applePay), which opens the Apple Pay
        // payment sheet. The sheet is initiated within the click event so the browser
        // recognises it as a direct user gesture (required by Safari for Apple Pay).
        // Apple Pay handles SCA natively, so verifyBuyer receives an empty billingContact.
        async function initializeApplePay(payments) {
            const paymentRequest = payments.paymentRequest({
                countryCode:  square_countryCode,
                currencyCode: modality_currencyCode,
                total: {
                    amount: (Number(modality_amount) / 100).toFixed(2),
                    label:  'Total',
                },
            });
            const applePay = await payments.applePay(paymentRequest);

            // Render a CSS-styled Apple Pay button (no attach() needed — clicking the button
            // calls tokenize() which internally opens the Apple Pay sheet).
            modality_containerElement.innerHTML = `
                <div id="square-apple-pay-container" style="padding: 4px 0;">
                    <button id="square-apple-pay-button" lang="en"
                        style="-webkit-appearance: -apple-pay-button; apple-pay-button-type: plain;
                               apple-pay-button-style: black; width: 100%; height: 48px;
                               border: none; cursor: pointer;">
                    </button>
                </div>
                <div id="square-payment-status-container"></div>
            `;

            document.getElementById('square-apple-pay-button').addEventListener('click', async (event) => {
                event.preventDefault();
                square_idempotencyKey = window.crypto.randomUUID();

                let token;
                try {
                    token = await tokenize(applePay);
                } catch (e) {
                    displayPaymentResults('FAILURE');
                    console.error(e.message);
                    modality_notifyGatewayCardVerificationFailure(e.message);
                    return;
                }

                // verifyBuyer() adds 3DS/SCA on top of Apple Pay's own authentication.
                // Non-fatal: wallet device auth already satisfies SCA; proceed without token if it fails.
                let verificationToken = null;
                try {
                    verificationToken = await verifyBuyer(token, '', '', '', '', '', '', '', '');
                } catch (e) {
                    console.warn('Apple Pay: verifyBuyer failed, proceeding without verification token:', e.message);
                }

                const paymentCompletionPayload = {
                    modality_amount:        modality_amount,
                    modality_currencyCode:  modality_currencyCode,
                    square_locationId:      square_locationId,
                    square_sourceId:        token,
                    square_verificationToken: verificationToken,
                    square_idempotencyKey:  square_idempotencyKey,
                };
                modality_notifyGatewayPaymentVerificationSuccess(JSON.stringify(paymentCompletionPayload));
            });

            return applePay;
        }

        async function tokenize(paymentMethod) {
            const tokenResult = await paymentMethod.tokenize();
            if (tokenResult.status === 'OK') {
                return tokenResult.token;
            } else {
                let errorMessage = `Tokenization failed with status: ${tokenResult.status}`;
                if (tokenResult.errors) {
                    errorMessage += ` and errors: ${JSON.stringify(
                        tokenResult.errors,
                    )}`;
                }

                throw new Error(errorMessage);
            }
        }

        // Required in SCA Mandated Regions: Learn more at https://developer.squareup.com/docs/sca-overview
        async function verifyBuyer(token, firstName, lastName, email, phone, address, city, state, countryCode) {
            if (email)
                email = email.toLowerCase();
            if (countryCode)
                countryCode = countryCode.toUpperCase(); // Square rejects lower case country codes
            const verificationDetails = {
                amount: (Number(modality_amount) / 100).toFixed(2),
                billingContact: {
                    givenName: firstName,
                    familyName: lastName,
                    email: email,
                    phone: phone,
                    addressLines: address ? [address] : [],
                    city: city,
                    state: state,
                    countryCode: countryCode,
                },
                currencyCode: modality_currencyCode,
                intent: 'CHARGE',
            };

            const verificationResults = await payments.verifyBuyer(
                token,
                verificationDetails,
            );
            return verificationResults.token;
        }

        // status is either SUCCESS or FAILURE;
        function displayPaymentResults(status) {
            const statusContainer = document.getElementById(
                'square-payment-status-container',
            );
            if (status === 'SUCCESS') {
                statusContainer.classList.remove('is-failure');
                statusContainer.classList.add('is-success');
            } else {
                statusContainer.classList.remove('is-success');
                statusContainer.classList.add('is-failure');
            }

            statusContainer.style.visibility = 'visible';
        }

        async function onLoaded() {
            if (modality_onLoadedRunning) {
                console.log("Square: onLoaded already running, skipping duplicate call");
                return;
            }
            modality_onLoadedRunning = true;
            console.log("Square DOMContentLoaded");
            if (modality_javaPaymentForm) {
                console.log("modality_javaPaymentForm is set");
            } else {
                console.log("modality_javaPaymentForm is NOT set");
            }

            if (!window.Square) {
                modality_notifyGatewayInitFailure('Square.js failed to load properly');
                return;
            }

            modality_containerElement = modality_seamless ? document.getElementById(modality_seamlessContainerId) : document.body;

            if (!modality_containerElement) {
                modality_notifyGatewayInitFailure('Expected seamless container #' + modality_seamlessContainerId + ' but was not found in main DOM');
                return;
            }

            // Container HTML for CARD only; wallet methods (GOOGLE_PAY, APPLE_PAY) set their own HTML
            if (modality_paymentMethodId !== 'GOOGLE_PAY' && modality_paymentMethodId !== 'APPLE_PAY') {
                modality_containerElement.innerHTML = `
                    <form id="square-payment-form">
                        <div id="square-card-container"></div>
                    </form>
                    <div id="square-payment-status-container"></div>
                `;
            }

            try {
                console.log("Calling payments");
                payments = window.Square.payments(square_appId, square_locationId);
            } catch (e) {
                console.error('Payments failed', e);
                modality_notifyGatewayInitFailure('Square payments failed to initialize: ' + e.message);
                const statusContainer = document.getElementById('square-payment-status-container');
                if (statusContainer) {
                    statusContainer.className = 'missing-credentials';
                    statusContainer.style.visibility = 'visible';
                }
                return;
            }

            if (modality_paymentMethodId === 'GOOGLE_PAY') {
                try {
                    console.log("Calling initializeGooglePay");
                    square_googlePay = await initializeGooglePay(payments);
                } catch (e) {
                    console.error('Initializing Google Pay failed', e);
                    modality_notifyGatewayInitFailure('Square Google Pay initialization failed: ' + e.message);
                    return;
                }
                // Hide the React Pay button — the Google Pay button is the payment trigger
                modality_notifyGatewayHasSelfContainedPayment();
            } else if (modality_paymentMethodId === 'APPLE_PAY') {
                try {
                    console.log("Calling initializeApplePay");
                    square_applePay = await initializeApplePay(payments);
                } catch (e) {
                    console.error('Initializing Apple Pay failed', e);
                    modality_notifyGatewayInitFailure('Square Apple Pay initialization failed: ' + e.message);
                    return;
                }
                // Hide the React Pay button — the Apple Pay button is the payment trigger
                modality_notifyGatewayHasSelfContainedPayment();
            } else {
                try {
                    console.log("Calling initializeCard");
                    square_card = await initializeCard(payments);
                } catch (e) {
                    console.error('Initializing Card failed', e);
                    modality_notifyGatewayInitFailure('Square card initialization failed: ' + e.message);
                    return;
                }
            }

            // Expose a direct trigger so React can open the payment sheet from within
            // the Pay button's click handler (a live user gesture) without a second click
            // on the embedded wallet button. Only set for wallet methods.
            if (modality_paymentMethodId === 'GOOGLE_PAY' || modality_paymentMethodId === 'APPLE_PAY') {
                window.modality_triggerWalletPayment = async function() {
                    const wallet = square_googlePay || square_applePay;
                    if (!wallet) return;
                    let token;
                    try {
                        token = await tokenize(wallet);
                    } catch(e) {
                        modality_notifyGatewayCardVerificationFailure(e.message);
                        return;
                    }
                    // verifyBuyer() adds 3DS/SCA on top of the wallet's own authentication.
                    // It can fail in sandbox or non-SCA regions — treat as non-fatal and
                    // proceed without it; the wallet's device auth already satisfies SCA.
                    let verificationToken = null;
                    try {
                        verificationToken = await verifyBuyer(token, '', '', '', '', '', '', '', '');
                    } catch(e) {
                        console.warn('Wallet: verifyBuyer failed, proceeding without verification token:', e.message);
                    }
                    modality_notifyGatewayPaymentVerificationSuccess(JSON.stringify({
                        modality_amount:          modality_amount,
                        modality_currencyCode:    modality_currencyCode,
                        square_locationId:        square_locationId,
                        square_sourceId:          token,
                        square_verificationToken: verificationToken,
                        square_idempotencyKey:    window.crypto.randomUUID(),
                    }));
                };
            }

            modality_notifyGatewayInitSuccess();
        }

        async function handlePaymentMethodSubmission(firstName, lastName, email, phone, address, city, state, countryCode) {
            // Fresh key for every attempt so Square doesn't serve its cached response from a previous declined payment
            square_idempotencyKey = window.crypto.randomUUID();
            // Firstly: Card number verification
            let token;
            try {
                token = await tokenize(square_card);
            } catch (e) {
                displayPaymentResults('FAILURE');
                console.error(e.message);
                modality_notifyGatewayCardVerificationFailure(e.message);
                return;
            }

            // Secondly: Buyer verification
            let verificationToken;
            try {
                verificationToken = await verifyBuyer(token, firstName, lastName, email, phone, address, city, state, countryCode);
            } catch (e) {
                displayPaymentResults('FAILURE');
                console.error(e.message);
                modality_notifyGatewayBuyerVerificationFailure(e.message);
                return;
            }

            // Thirdly: Modality payment form callback for payment completion
            const paymentCompletionPayload= {
                modality_amount: modality_amount,
                modality_currencyCode: modality_currencyCode,
                square_locationId : square_locationId,
                square_sourceId: token,
                square_verificationToken: verificationToken,
                square_idempotencyKey: square_idempotencyKey,
            };

            // Notifying the Java WebPaymentForm that the verification is successful => it will complete the payment on
            // the server-side, passing it this payload. It will basically call PaymentService.completePayment() - after
            // the UI informed the user this is happening - which will update the payment state in the database in
            // dependence on the result of AuthorizePaymentGateway.completePayment() <= will treat the payload
            modality_notifyGatewayPaymentVerificationSuccess(JSON.stringify(paymentCompletionPayload));
        }

        if (document.readyState === "complete" || document.readyState === "interactive") {
            // Document is already ready, so just call the function directly
            onLoaded();
        } else {
            // Document is not ready yet, so add an event listener for DOMContentLoaded
            document.addEventListener('DOMContentLoaded', onLoaded);
        }

        // Making handlePaymentMethodSubmission function visible for modality_submitGatewayPayment
        window.handlePaymentMethodSubmission = handlePaymentMethodSubmission;

    });

