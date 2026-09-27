# ResourceSniffer v0.2

Android resource-sniffer prototype using Kotlin + Jetpack Compose + VpnService + overlay UI.

## v0.2 additions
- IPv4 TCP/UDP packet parser
- DNS query/A-record parser and IP -> host cache
- Best-effort TLS ClientHello SNI extraction (single-packet ClientHello only)
- Android 10+ `ConnectivityManager.getConnectionOwnerUid()` package attribution
- Existing overlay bubble, target-package VPN routing, resource classifier and capture session UI retained

## Important limitation
This source **does not yet implement a production TCP/UDP forwarding engine**. The TUN interface can read outbound packets, but without a user-space TCP/IP forwarder/NAT engine those packets are not forwarded to the real network. Therefore v0.2 is a capture-metadata development milestone, not yet the build to use as an always-on sniffer.

The next required core is one of:
1. clean-room user-space TCP/UDP forwarding/NAT, or
2. a license-compatible forwarding library with protected upstream sockets.

Once forwarding exists, the present DNS/SNI/app-attribution pipeline can remain in place.

## HTTPS scope
No TLS MITM is performed. SNI can reveal the hostname when present, but HTTP path, headers, cookies and response MIME remain encrypted under HTTPS. Certificate pinning is intentionally outside this milestone.
