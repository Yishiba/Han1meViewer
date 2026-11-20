package com.yenaly.han1meviewer.logic.commerce

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

class MembershipApiClientTest {
    private val fixture = requireNotNull(javaClass.getResourceAsStream("/commerce-entitlement-fixture.txt"))
        .bufferedReader().use { it.readLines() }
    private val verifier = EntitlementVerifier(Base64.getUrlDecoder().decode(fixture[0]))
    private val device = "fixture-device-12345678"
    private val bearer = "a".repeat(43)

    private fun response(request: Request, body: String, code: Int = 200): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
            .message("test").body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun loginAndMembershipUseOnlyDedicatedHostAndVerifySignature() = runBlocking {
        val seen = mutableListOf<Request>()
        val client = MembershipApiClient("https://membership.example/", device, verifier,
            call = { request ->
                seen += request
                when (request.url.encodedPath) {
                    "/v1/auth/start" -> response(request,
                        """{"authorization_url":"https://afdian.com/oauth2/authorize?state=abc","login_ticket":"${"t".repeat(43)}"}""")
                    "/v1/auth/complete" -> response(request,
                        """{"status":"authenticated","session_token":"$bearer"}""")
                    "/v1/membership" -> response(request,
                        """{"status":"active","entitlement":"${fixture[1]}"}""")
                    else -> response(request, """{"status":"inactive","entitlement":null}""")
                }
            }, clockSeconds = { 2000 })
        val start = client.startLogin()
        assertTrue(start.authorizationUrl.startsWith("https://afdian.com/"))
        assertEquals(bearer, client.completeLogin(start.loginTicket))
        assertEquals("fixture-order", client.status(bearer).entitlement?.orderId)
        assertFalse(client.refresh(bearer).active)
        assertEquals("membership.example", seen[0].url.host)
        assertEquals(null, seen[0].header("Authorization"))
        assertEquals("Bearer $bearer", seen[2].header("Authorization"))
        assertNotNull(seen[0].body)
    }

    @Test fun rejectsUnsignedOrWrongDeviceMembership() = runBlocking {
        val client = MembershipApiClient("https://membership.example/", "another-device-123456", verifier,
            call = { request -> response(request,
                """{"status":"active","entitlement":"${fixture[1]}"}""") },
            clockSeconds = { 2000 })
        try {
            client.status(bearer)
            throw AssertionError("Expected invalid entitlement")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("Invalid membership entitlement"))
        }
    }

    @Test fun exposesExpiredSessionStatusForLocalRecovery() = runBlocking {
        val client = MembershipApiClient("https://membership.example/", device, verifier,
            call = { request -> response(request, "{}", 401) })
        try {
            client.refresh(bearer)
            throw AssertionError("Expected unauthorized response")
        } catch (expected: MembershipApiClient.MembershipHttpException) {
            assertEquals(401, expected.statusCode)
        }
    }
}
