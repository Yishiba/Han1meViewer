package com.yenaly.han1meviewer.logic.commerce

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Verifies a short-lived server entitlement. The server private key must never enter the APK. */
class EntitlementVerifier(publicKeyDer: ByteArray) {
    private val publicKey = KeyFactory.getInstance("EC")
        .generatePublic(X509EncodedKeySpec(publicKeyDer))

    data class Entitlement(val orderId: String, val validUntilSeconds: Long, val paidUntilSeconds: Long)

    fun verify(token: String, deviceId: String, nowSeconds: Long): Entitlement? {
        if (token.length !in 10..4096 || deviceId.length !in 16..128) return null
        val dot = token.indexOf('.')
        if (dot <= 0 || dot == token.lastIndex || token.indexOf('.', dot + 1) != -1) return null
        return try {
            val encoded = token.substring(0, dot)
            val signatureBytes = Base64.getUrlDecoder().decode(token.substring(dot + 1))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(encoded.toByteArray(Charsets.US_ASCII))
            if (!verifier.verify(signatureBytes)) return null

            val payload = Json.parseToJsonElement(
                String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
            ).jsonObject
            if (payload["v"]?.jsonPrimitive?.longOrNull != 2L ||
                payload["alg"]?.jsonPrimitive?.content != "ES256" ||
                payload["device"]?.jsonPrimitive?.content != deviceId
            ) return null
            val order = payload["order"]?.jsonPrimitive?.content ?: return null
            val issued = payload["issued"]?.jsonPrimitive?.longOrNull ?: return null
            val validUntil = payload["valid_until"]?.jsonPrimitive?.longOrNull ?: return null
            val paidUntil = payload["paid_until"]?.jsonPrimitive?.longOrNull ?: return null
            if (order.isBlank() || issued > nowSeconds || nowSeconds >= validUntil ||
                validUntil > paidUntil || validUntil - issued > 86_400 || issued < 0
            ) return null
            Entitlement(order, validUntil, paidUntil)
        } catch (_: Exception) {
            null
        }
    }
}
