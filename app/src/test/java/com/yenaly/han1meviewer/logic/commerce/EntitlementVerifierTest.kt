package com.yenaly.han1meviewer.logic.commerce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class EntitlementVerifierTest {
    private val fixture = requireNotNull(javaClass.getResourceAsStream("/commerce-entitlement-fixture.txt"))
        .bufferedReader().use { it.readLines() }
    private val verifier = EntitlementVerifier(Base64.getUrlDecoder().decode(fixture[0]))
    private val token = fixture[1]
    private val device = "fixture-device-12345678"

    @Test fun verifiesPythonSignedEntitlement() {
        val parts = token.split('.')
        val key = java.security.KeyFactory.getInstance("EC")
            .generatePublic(java.security.spec.X509EncodedKeySpec(Base64.getUrlDecoder().decode(fixture[0])))
        val signature = java.security.Signature.getInstance("SHA256withECDSA")
        signature.initVerify(key)
        signature.update(parts[0].toByteArray(Charsets.US_ASCII))
        assertTrue("JCA cross-language signature", signature.verify(Base64.getUrlDecoder().decode(parts[1])))
        val result = verifier.verify(token, device, 2000)
        assertEquals("fixture-order", result?.orderId)
        assertEquals(5000L, result?.validUntilSeconds)
        assertEquals(10000L, result?.paidUntilSeconds)
    }

    @Test fun rejectsTamperingWrongDeviceAndExpiredSnapshot() {
        val altered = token.dropLast(1) + if (token.last() == 'A') "B" else "A"
        assertNull(verifier.verify(altered, device, 2000))
        assertNull(verifier.verify(token, "other-device-12345678", 2000))
        assertNull(verifier.verify(token, device, 999))
        assertNull(verifier.verify(token, device, 5000))
        assertNull(verifier.verify("bad.token.extra", device, 2000))
    }
}
