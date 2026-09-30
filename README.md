# Meerkat

Android resource discovery and download app built with Kotlin, Jetpack Compose,
WebView, VpnService, and a gVisor netstack bridge. Requires Android 10 or newer.

## Development version: 0.6.0-beta5

- Browser address/search bar, navigation controls, and expandable resource sheet
  with Images, Videos, Documents, and Other.
- Browser request, DOM/JavaScript, and blob resource discovery.
- Global IPv4/IPv6 VPN forwarding, HTTP inspection, and optional HTTPS MITM.
- Floating counter with actual VPN readiness, traffic, and decryption status.
- Media3 previews and direct HTTP, HLS, and DASH downloads.

## Global capture

Start capture before opening the target app. Grant VPN and overlay permissions.
For HTTPS inspection, export and install the Meerkat Local CA via Android settings.
Installing a CA does not make every app trust it: apps rejecting user CAs or
pinning certificates remain opaque. Rejected hosts pass through on subsequent
connections. Connection counts are separate from downloadable resource counts.

QUIC/HTTP3 passes through without decryption by default. The optional TCP inspection
switch blocks UDP/443 to try to induce HTTPS fallback. Some apps may stop loading;
disable the switch and restart capture if needed. It does not bypass pinning.
DRM/CENC content cannot be decrypted. The built-in browser discovers resources
independently of another app's TLS trust settings.

## Build and validation

GitHub Actions builds the ARM64 netstack AAR from NetValve, runs JVM unit tests,
and produces a debug APK with JDK 17, Go 1.25, NDK 27.2, and Gradle 9.6.
Local builds need the Android SDK and `app/libs/netstack.aar`.
Run `gradle :app:testDebugUnitTest :app:assembleDebug`.
Tests cover fragmented TLS reads, SNI, truncated records, and HTTP/1.1 without ALPN.
VPN forwarding, target-app TLS behavior, overlays, and browser layout still need
on-device validation.
