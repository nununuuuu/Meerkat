package com.resourcesniffer.app.capture

import javax.security.auth.x500.X500Principal
import org.bouncycastle.asn1.x500.X500Name

// RFC2253 text reverses the ASN.1 RDN sequence; preserve the certificate's actual issuer name.
internal fun certificateIssuer(subject: X500Principal): X500Name = X500Name.getInstance(subject.encoded)
