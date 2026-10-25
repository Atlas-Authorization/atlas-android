# Changelog

`net.atlasauth:atlas-kotlin` — the pure-JVM Kotlin SDK (source in `sdks/android`).

## 0.11.0 — 2026-10-25

### Added

- **Per-call audience on integration tokens.** `AtlasClient.getToken(integration, spaceId, cbid, audience, audienceParams)` accepts `cbid`, `audience` and `audienceParams`, so one integration can mint a token whose `aud` is chosen per call (for an integration configured with a per-call audience, such as one audience per site). The same options are available on the Sign in with Atlas token route. An integration with a per-call audience mints a token only when the call supplies a matching value.

## 0.10.0 — 2026-10-24

### Added

- **SSO claims.** `OidcClaims.ssoOrganizationId` / `ssoConnectionType` read the `sso_organization_id` (the Atlas organization that owns the enterprise SSO connection, independent of `org_id`) and `sso_connection_type` (`saml` or `oidc`) claims from userinfo, introspection, an ID or access token, or a JWT payload. Both are absent on a non-SSO sign-in; an instance-wide connection carries the type but no organization.

## 0.9.0 — 2026-10-24

### Added

- **`getToken({for})` for integrations.** ``AtlasClient.getToken(integration, spaceId)`` mints a short-lived token for a configured identity integration (a preset such as `chatly`, `forumulate`, `cookiemunch`, `referalli`, or a custom id) from the signed-in session; `null` when the integration is not enabled. Forumulate takes a `spaceId` and returns the redeem fields. ``AtlasClient.getAtlasIdToken(integration, accessToken, origin, dpopProof)`` is the Sign in with Atlas browser-token route (`POST /v1/client/integrations/:id/atlas_id_tokens`): Bearer or DPoP access token, an `Origin` the Sign in with Atlas app allows, no cookies.
- **OIDC claims.** `OidcClaims` reads `org_id`, `org_slug`, `org_role`, `org_permissions`, `orgs` and the `sso_connection_id` / `sso_tenant_id` / `sso_issuer` / `sso_domain` claims from userinfo, introspection or a JWT payload (the JWT form does not verify the signature). `AtlasScopes.organizations` / `withOrganizations(...)` add the `organizations` scope to a scope list such as the device sign-in's.
- **New error codes.** `INSUFFICIENT_SCOPE` (the old `SCOPE_MISSING` still reads as it; `requiredScope` reads `meta.required_scope`), `ORIGIN_NOT_ALLOWED`, `STEP_UP_REQUIRED`, `ROUTE_NOT_FOUND` (`didYouMean`, `allowedMethods`). Error items now carry `meta` and `docs_url`.
- **Key format.** The client SDKs check no key prefix; they send whatever publishable key they are given (`pk_...` or `atlas_<env>_pk_...`).
- FedCM / One Tap: not applicable to a native SDK (browser-only). Native Google sign-in keeps using the existing id_token route.

## 0.8.0 — 2026-10-24

### Added

- **Policy acceptance** (terms of service, privacy, any policy the app defines). `client.policies(locale)` (`GET /v1/client/policies`), `client.myPolicies()` and `client.acceptMyPolicies(...)` for in-app re-acceptance (`PolicyGate`), and `PolicyAcceptanceController` (per-policy ticks, reading clock, scroll-to-end flag, `canSubmit()`, `build()` into `policy_acceptances`; build one with `client.signUpPolicyController()` or `client.policyController(attempt)`). `beginSignUp(..., policyAcceptances)` sends them at sign-up; a 422 `POLICY_ACCEPTANCE_REQUIRED` is `AtlasException.isPolicyAcceptanceRequired` and `client.refreshPoliciesOnRequired(controller, error)` reloads the list. A sign-in held at `needs_policy_acceptance` is the new `SignInStep.AcceptPolicies` (also `PasswordResetStep.AcceptPolicies`), continued with `acceptPolicies(...)` on `SignInFlow` / `PasswordResetFlow`. `AtlasRedirectResult.parse(url)` reads `__atlas_attempt` / `__atlas_status` / `__atlas_policy_token` from a Custom Tab or browser callback and `client.resumeSignIn(redirect)` continues the held attempt. This module has no UI: draw the documents (`AtlasPolicy.contentHtml`, sanitised HTML) with your own toolkit.

## 0.7.0 — 2026-10-24

### Added

- **Upstream logout on sign-out.** `signOut(returnUrl, followLogoutUrl)` now returns a `SignOutResult` with the upstream OIDC `upstreamLogoutUrl` and the SAML `samlLogoutUrl` (https only); with `followLogoutUrl` it is passed to `AtlasClient.logoutUrlOpener` (set it to open the system browser). `returnUrl` must be on an allowed origin.
- **Device sign-in approval.** `lookupDeviceAuthorization(userCode)`, `approveDeviceAuthorization`, `denyDeviceAuthorization` (`DeviceAuthorization`); `formatUserCode`, `userCodeFromUrl`. A refusal needing a fresh re-authentication carries code `STEP_UP_REQUIRED`.

### Changed

- `signOut()` returns `SignOutResult` instead of `Unit` (existing calls compile unchanged).

## 0.6.0 — 2026-10-23

### Added

- **Device id on account-creating starts.** Email sign-up and the social / id_token sign-in starts carry a random `device_id` (`DeviceId`) for the instance's accounts-per-device abuse limit, like the JS SDK. `AtlasClient.deviceIdProvider` defaults to an id stable for the process; supply your own persistent one to keep it across launches.
- **Captcha refusals typed.** `AtlasErrorCode.CAPTCHA_REQUIRED` / `CAPTCHA_FAILED` / `CHALLENGE_UNAVAILABLE` and `AtlasError.isCaptchaRequired`.
- **SMS MFA enrolment mid-sign-in.** `SignInFlow.prepareSmsMfaEnrollment(phone)` and `attemptSmsMfaEnrollment(factorId, code)`. `MfaEnrollment` carries `strategy`, `phone`, and for TOTP the QR image `qrCode` (a `data:image/...` URL) with `qrImageBytes`.
- **Organization audit log.** `AtlasClient.organizationAuditLog(organizationId, limit, startingAfter, action)` (`GET /v1/client/organizations/:id/audit_logs`, organization admins).

## 0.5.0

### Added

- **Typed error codes.** `AtlasError.knownCode` maps the server code to the new
  `AtlasErrorCode` enum (`EMAIL_DELIVERY_NOT_CONFIGURED`, `VALIDATION_FAILED`,
  `RATE_LIMITED`, `IDENTIFIER_NOT_FOUND`, …); `code` still carries every code.
- **`EMAIL_DELIVERY_NOT_CONFIGURED` (422).** Returned before anything is sent
  when the app cannot deliver email; `isEmailDeliveryNotConfigured` is the
  shortcut, and `message` is the server's end-user wording, ready to show.
