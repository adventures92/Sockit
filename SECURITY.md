# Security policy

## Supported versions

Only the latest released version receives fixes. Sockit is pre-1.0; treat the API as stable within
a minor version and expect breaking changes between them.

## Reporting a vulnerability

Report privately through GitHub's
[security advisory form](https://github.com/adventures92/Sockit/security/advisories/new) rather
than a public issue. Expect an acknowledgement within a week.

Useful detail: affected version, platform (Android / iOS / JVM), transport (polling / WebSocket),
and a minimal reproduction.

## Known sharp edges

**`trustAllCerts`.** Setting `socketOptions { trustAllCerts = true }` disables certificate and
hostname verification. The implementation — a permissive `X509TrustManager` and `HostnameVerifier`
on Android, an accept-all challenge handler on Darwin — is compiled into the released artifact
whether or not you set the flag, and Google Play's pre-launch security review flags that pattern.
It exists for local development against self-signed certificates. Never enable it in a shipped
build; configure TLS on your own injected `HttpClient` instead.

**Certificate pinning.** Not provided. Configure it on the `HttpClient` you pass through
`socketOptions { httpClient = … }` (OkHttp's `CertificatePinner` on Android, Darwin's challenge
handler on iOS).

**Logging.** `Logger` output is sanitized — URLs, tokens and wire payloads are redacted before
reaching your sink. Full wire logging exists only in builds compiled from source with
`-Psocketio.internalLogging=true` and is never present in a published artifact.
