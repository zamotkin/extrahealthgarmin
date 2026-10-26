---
type: entity
title: GarminAuthClient — DI OAuth2 with MFA resume
status: current
generated: 2026-10-26
tags: [garmin, auth, security]
---
**Responsibility.** Implements Garmin's DI OAuth2 login with MFA resume (the
garth / python-garminconnect wire contract), feeding `GarminRepository` and
the app-level login dialog (`ui/navigation/GarminLoginDialog`). Package:
`garmin/`.

**Components.**
- `GarminAuthClient` — email + password + MFA code flows; token refresh is
  automatic once a valid token is stored.
- `GarminTokenStore` — persists the DI tokens; AES-encrypted by
- `TokenCrypto` — Android Keystore, alias `extrahealthgarmin_di_tokens`
  (same pattern as the Omron pairing-key crypto, alias
  `extrahealthgarmin_omron_pairing_key`).
- `RedactingHttpLogger` — keeps secrets out of debug logs (inactive in
  release builds).
- `GarminExceptions` — the error hierarchy (`GarminAuthError`,
  `GarminTooManyRequestsError`, `GarminConnectionError`,
  `GarminValidationError`) that determines records-outcome mapping upstream.

**Seams.** `GarminRemote` interface (login/resumeLogin/uploadWeighIns/
fetchExistingWeighIns/fetchProfileHeightMeters/uploadBloodPressure/logout) —
`HttpGarminRemote` is the HTTP implementation, exercised in tests via
MockWebServer.

**Tests.** `GarminAuthClientTest`, `GarminTokenStoreTest`,
`RedactingHttpLoggerTest`.
