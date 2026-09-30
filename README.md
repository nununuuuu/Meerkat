# Meerkat

Android resource discovery and download app built with Kotlin, Jetpack Compose,
WebView, VpnService, and a gVisor netstack bridge. Requires Android 10 or newer.

## Development version: 0.6.0-beta6

- Browser address/search bar, navigation and home controls, and expandable resource sheet
  with Images, Videos, Documents, and Other.
- Browser request, DOM/JavaScript, and blob resource discovery.
- Global IPv4/IPv6 VPN forwarding, HTTP inspection, and optional HTTPS MITM.
- Floating counter with actual VPN readiness, traffic, and decryption status.
- Media3 previews and direct HTTP, HLS, and DASH downloads.

## Global capture

Start capture before opening the target app. Grant VPN and overlay permissions.
For HTTPS inspection, export and install the Meerkat Local CA via Android settings.
If automatic CA detection fails, confirm installation of the current exported CA
on the App screen and restart capture. Confirmation is tied to that CA fingerprint.
Removing/reinstalling the app can change the CA; an old CA will no longer match.
Installing a CA does not make every app trust it: apps rejecting user CAs or
pinning certificates remain opaque. Rejected hosts pass through on subsequent
connections. Connection counts are separate from downloadable resource counts.

QUIC/HTTP3 passes through without decryption by default. The optional TCP inspection
switch can be changed while capturing and blocks UDP/443 to try HTTPS fallback. Some apps may stop loading;
disable the switch and restart capture if needed. It does not bypass pinning.
DRM/CENC content cannot be decrypted. The built-in browser discovers resources
independently of another app's TLS trust settings.

## Build and validation

GitHub Actions builds the ARM64 netstack AAR from NetValve, runs JVM unit tests,
and produces a debug APK with JDK 17, Go 1.25, NDK 27.2, and Gradle 9.6.
Local builds need the Android SDK and `app/libs/netstack.aar`.
Run `gradle :app:testDebugUnitTest :app:assembleDebug`.
Tests cover fragmented TLS reads, SNI, truncated records, and HTTP/1.1 without ALPN.
The independent floating-ball switch is on the App screen. Resource category
labels and counts use separate centered rows.
VPN forwarding, target-app TLS behavior, overlays, and browser layout still need
on-device validation.

Test builds use the public development signing identity in
`signing/development.keystore` (standard Android debug credentials). This keeps
beta6 and subsequent test APK upgrades compatible and preserves app-local CA keys.
Older beta APKs used ephemeral CI debug identities and may require a one-time
reinstall and a freshly exported CA. This key is not for production signing.

Every successful main-branch APK build is automatically published with its SHA-256.
New versionNames use their version tag; additional APKs with the same version use
build-number tags. Identical already-published APKs are skipped, and old release
assets are preserved.
