# Changelog

`net.atlasauth:atlas-kotlin` — the pure-JVM Kotlin SDK (source in `sdks/android`).

## 0.5.0

### Added

- **Typed error codes.** `AtlasError.knownCode` maps the server code to the new
  `AtlasErrorCode` enum (`EMAIL_DELIVERY_NOT_CONFIGURED`, `VALIDATION_FAILED`,
  `RATE_LIMITED`, `IDENTIFIER_NOT_FOUND`, …); `code` still carries every code.
- **`EMAIL_DELIVERY_NOT_CONFIGURED` (422).** Returned before anything is sent
  when the app cannot deliver email; `isEmailDeliveryNotConfigured` is the
  shortcut, and `message` is the server's end-user wording, ready to show.
