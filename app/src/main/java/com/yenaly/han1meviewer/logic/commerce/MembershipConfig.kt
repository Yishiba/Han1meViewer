package com.yenaly.han1meviewer.logic.commerce

import com.yenaly.han1meviewer.BuildConfig
import java.util.Base64

/** Build contains only the public service URL and public verification key. */
object MembershipConfig {
    private val setup: Pair<String, EntitlementVerifier>? by lazy {
        runCatching {
            val url = String(Base64.getDecoder().decode(BuildConfig.COMMERCE_API_URL_B64),
                Charsets.UTF_8)
            val key = Base64.getDecoder().decode(BuildConfig.COMMERCE_PUBLIC_KEY_DER_B64)
            val verifier = EntitlementVerifier(key)
            MembershipApiClient(url, "config-check-12345678", verifier)
            url to verifier
        }.getOrNull()
    }

    val available: Boolean get() = setup != null

    fun client(deviceId: String): MembershipApiClient? = setup?.let { (url, verifier) ->
        MembershipApiClient(url, deviceId, verifier)
    }
}
