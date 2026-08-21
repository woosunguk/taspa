/*
 * Copyright 2002-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Vendored from spring-security-web-6.4.4.jar
 * (org/springframework/security/spring-security-webauthn.js), unmodified.
 * disableDefaultRegistrationPage(true) 를 사용하면 기본 JS 서빙이 꺼지므로 직접 제공한다.
 * 전역 함수: setupLogin(csrfHeaders, contextPath, buttonEl), setupRegistration(...).
 */
"use strict";
(() => {
  // lib/base64url.js
  var base64url_default = {
    encode: function(buffer) {
      const base64 = window.btoa(String.fromCharCode(...new Uint8Array(buffer)));
      return base64.replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
    },
    decode: function(base64url) {
      const base64 = base64url.replace(/-/g, "+").replace(/_/g, "/");
      const binStr = window.atob(base64);
      const bin = new Uint8Array(binStr.length);
      for (let i = 0; i < binStr.length; i++) {
        bin[i] = binStr.charCodeAt(i);
      }
      return bin.buffer;
    }
  };

  // lib/http.js
  async function post(url, headers, body) {
    const options = {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...headers
      }
    };
    if (body) {
      options.body = JSON.stringify(body);
    }
    return fetch(url, options);
  }
  var http_default = { post };

  // lib/abort-controller.js
  var holder = {
    controller: new AbortController()
  };
  function newSignal() {
    if (!!holder.controller) {
      holder.controller.abort("Initiating new WebAuthN ceremony, cancelling current ceremony");
    }
    holder.controller = new AbortController();
    return holder.controller.signal;
  }
  var abort_controller_default = {
    newSignal
  };

  // lib/webauthn-core.js
  async function isConditionalMediationAvailable() {
    return !!(window.PublicKeyCredential && window.PublicKeyCredential.isConditionalMediationAvailable && await window.PublicKeyCredential.isConditionalMediationAvailable());
  }
  async function authenticate(headers, contextPath, useConditionalMediation) {
    let options;
    try {
      const optionsResponse = await http_default.post(`${contextPath}/webauthn/authenticate/options`, headers);
      if (!optionsResponse.ok) {
        throw new Error(`HTTP ${optionsResponse.status}`);
      }
      options = await optionsResponse.json();
    } catch (err) {
      throw new Error(`Authentication failed. Could not fetch authentication options: ${err.message}`, { cause: err });
    }
    const decodedAllowCredentials = !options.allowCredentials ? [] : options.allowCredentials.map((cred2) => ({
      ...cred2,
      id: base64url_default.decode(cred2.id)
    }));
    const decodedOptions = {
      ...options,
      allowCredentials: decodedAllowCredentials,
      challenge: base64url_default.decode(options.challenge)
    };
    const credentialOptions = {
      publicKey: decodedOptions,
      signal: abort_controller_default.newSignal()
    };
    if (useConditionalMediation) {
      credentialOptions.mediation = "conditional";
    }
    let cred;
    try {
      cred = await navigator.credentials.get(credentialOptions);
    } catch (err) {
      throw new Error(`Authentication failed. Call to navigator.credentials.get failed: ${err.message}`, { cause: err });
    }
    const { response, type: credType } = cred;
    let userHandle;
    if (response.userHandle) {
      userHandle = base64url_default.encode(response.userHandle);
    }
    const body = {
      id: cred.id,
      rawId: base64url_default.encode(cred.rawId),
      response: {
        authenticatorData: base64url_default.encode(response.authenticatorData),
        clientDataJSON: base64url_default.encode(response.clientDataJSON),
        signature: base64url_default.encode(response.signature),
        userHandle
      },
      credType,
      clientExtensionResults: cred.getClientExtensionResults(),
      authenticatorAttachment: cred.authenticatorAttachment
    };
    let authenticationResponse;
    try {
      const authenticationCallResponse = await http_default.post(`${contextPath}/login/webauthn`, headers, body);
      if (!authenticationCallResponse.ok) {
        throw new Error(`HTTP ${authenticationCallResponse.status}`);
      }
      authenticationResponse = await authenticationCallResponse.json();
    } catch (err) {
      throw new Error(`Authentication failed. Could not process the authentication request: ${err.message}`, {
        cause: err
      });
    }
    if (!(authenticationResponse && authenticationResponse.authenticated && authenticationResponse.redirectUrl)) {
      throw new Error(
        `Authentication failed. Expected {"authenticated": true, "redirectUrl": "..."}, server responded with: ${JSON.stringify(authenticationResponse)}`
      );
    }
    return authenticationResponse.redirectUrl;
  }
  async function register(headers, contextPath, label) {
    if (!label) {
      throw new Error("Error: Passkey Label is required");
    }
    let options;
    try {
      const optionsResponse = await http_default.post(`${contextPath}/webauthn/register/options`, headers);
      if (!optionsResponse.ok) {
        throw new Error(`Server responded with HTTP ${optionsResponse.status}`);
      }
      options = await optionsResponse.json();
    } catch (e) {
      throw new Error(`Registration failed. Could not fetch registration options: ${e.message}`, { cause: e });
    }
    const decodedExcludeCredentials = !options.excludeCredentials ? [] : options.excludeCredentials.map((cred) => ({
      ...cred,
      id: base64url_default.decode(cred.id)
    }));
    const decodedOptions = {
      ...options,
      user: {
        ...options.user,
        id: base64url_default.decode(options.user.id)
      },
      challenge: base64url_default.decode(options.challenge),
      excludeCredentials: decodedExcludeCredentials
    };
    let credentialsContainer;
    try {
      credentialsContainer = await navigator.credentials.create({
        publicKey: decodedOptions,
        signal: abort_controller_default.newSignal()
      });
    } catch (e) {
      throw new Error(`Registration failed. Call to navigator.credentials.create failed: ${e.message}`, { cause: e });
    }
    const { response } = credentialsContainer;
    const credential = {
      id: credentialsContainer.id,
      rawId: base64url_default.encode(credentialsContainer.rawId),
      response: {
        attestationObject: base64url_default.encode(response.attestationObject),
        clientDataJSON: base64url_default.encode(response.clientDataJSON),
        transports: response.getTransports ? response.getTransports() : []
      },
      type: credentialsContainer.type,
      clientExtensionResults: credentialsContainer.getClientExtensionResults(),
      authenticatorAttachment: credentialsContainer.authenticatorAttachment
    };
    const registrationRequest = {
      publicKey: {
        credential,
        label
      }
    };
    let verificationJSON;
    try {
      const verificationResp = await http_default.post(`${contextPath}/webauthn/register`, headers, registrationRequest);
      if (!verificationResp.ok) {
        throw new Error(`HTTP ${verificationResp.status}`);
      }
      verificationJSON = await verificationResp.json();
    } catch (e) {
      throw new Error(`Registration failed. Could not process the registration request: ${e.message}`, { cause: e });
    }
    if (!(verificationJSON && verificationJSON.success)) {
      throw new Error(`Registration failed. Server responded with: ${JSON.stringify(verificationJSON)}`);
    }
  }
  var webauthn_core_default = {
    authenticate,
    register,
    isConditionalMediationAvailable
  };

  // lib/webauthn-login.js
  async function authenticateOrError(headers, contextPath, useConditionalMediation) {
    try {
      const redirectUrl = await webauthn_core_default.authenticate(headers, contextPath, useConditionalMediation);
      window.location.href = redirectUrl;
    } catch (err) {
      console.error(err);
      window.location.href = `${contextPath}/login?error`;
    }
  }
  async function setupLogin(headers, contextPath, signinButton) {
    signinButton.addEventListener("click", async () => {
      await authenticateOrError(headers, contextPath, false);
    });
  }

  // lib/webauthn-registration.js
  function setVisibility(element, value) {
    if (!element) {
      return;
    }
    element.style.display = value ? "block" : "none";
  }
  function setError(ui, msg) {
    resetPopups(ui);
    const error = ui.getError();
    if (!error) {
      return;
    }
    error.textContent = msg;
    setVisibility(error, true);
  }
  function setSuccess(ui) {
    resetPopups(ui);
    const success = ui.getSuccess();
    if (!success) {
      return;
    }
    setVisibility(success, true);
  }
  function resetPopups(ui) {
    const success = ui.getSuccess();
    const error = ui.getError();
    setVisibility(success, false);
    setVisibility(error, false);
  }
  async function submitDeleteForm(contextPath, form, headers) {
    const options = {
      method: "DELETE",
      headers: {
        "Content-Type": "application/json",
        ...headers
      }
    };
    await fetch(form.action, options);
  }
  async function setupRegistration(headers, contextPath, ui) {
    resetPopups(ui);
    if (!window.PublicKeyCredential) {
      setError(ui, "WebAuthn is not supported");
      return;
    }
    const queryString = new URLSearchParams(window.location.search);
    if (queryString.has("success")) {
      setSuccess(ui);
    }
    ui.getRegisterButton().addEventListener("click", async () => {
      resetPopups(ui);
      const label = ui.getLabelInput().value;
      try {
        await webauthn_core_default.register(headers, contextPath, label);
        window.location.href = `${contextPath}/webauthn/register?success`;
      } catch (err) {
        setError(ui, err.message);
        console.error(err);
      }
    });
    ui.getDeleteForms().forEach(
      (form) => form.addEventListener("submit", async function(e) {
        e.preventDefault();
        try {
          await submitDeleteForm(contextPath, form, headers);
          window.location.href = `${contextPath}/webauthn/register?success`;
        } catch (err) {
          setError(ui, err.message);
        }
      })
    );
  }

  // lib/index.js
  window.setupLogin = setupLogin;
  window.setupRegistration = setupRegistration;
})();
