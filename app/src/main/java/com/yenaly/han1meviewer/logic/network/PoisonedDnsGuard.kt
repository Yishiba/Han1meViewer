package com.yenaly.han1meviewer.logic.network

import okhttp3.Dns
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 识别被投毒的解析结果并兜底。
 *
 * hanime 的几个域名都托管在 Cloudflare 上，国内 DNS 却会把它们解析到 Cloudflare 以外的假 IP
 * （例如 hanime1.me → 31.13.85.2），连上去必然失败。所以解析结果里只认 Cloudflare 网段的地址，
 * 一个都没有就视为被投毒：先换 [fallbackDns] 再查一次，还不行就用 [builtInIps]。
 *
 * 前提是这些域名一直挂在 Cloudflare 上；哪天迁走了，这里会把真实结果当成投毒，要跟着改。
 *
 * @project Han1meViewer
 */
internal class PoisonedDnsGuard(
    private val fallbackDns: Dns,
    private val builtInIps: (hostname: String) -> List<InetAddress>,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {

    companion object {
        private const val FALLBACK_CACHE_MILLIS = 10 * 60 * 1000L

        /**
         * https://www.cloudflare.com/ips/
         */
        private val CLOUDFLARE_RANGES = listOf(
            "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
            "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
            "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
            "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
            "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32",
            "2405:8100::/32", "2a06:98c0::/29", "2c0f:f248::/32",
        ).map(::Cidr)

        fun isCloudflare(address: InetAddress): Boolean =
            CLOUDFLARE_RANGES.any { it.contains(address) }
    }

    private class Cidr(notation: String) {
        private val network: ByteArray
        private val prefix: Int

        init {
            val (ip, bits) = notation.split('/')
            network = InetAddress.getByName(ip).address
            prefix = bits.toInt()
        }

        fun contains(address: InetAddress): Boolean {
            val bytes = address.address
            if (bytes.size != network.size) return false
            var remaining = prefix
            var i = 0
            while (remaining > 0) {
                val mask = if (remaining >= 8) 0xFF else (0xFF shl (8 - remaining)) and 0xFF
                if ((bytes[i].toInt() and mask) != (network[i].toInt() and mask)) return false
                remaining -= 8
                i++
            }
            return true
        }
    }

    private class CachedResult(val addresses: List<InetAddress>, val expireAt: Long)

    private val fallbackCache = ConcurrentHashMap<String, CachedResult>()

    /**
     * @param primary 常规解析（系统 DNS 或用户设置的 DoH）的结果，失败也传进来
     */
    fun resolve(hostname: String, primary: Result<List<InetAddress>>): List<InetAddress> {
        val trusted = primary.getOrNull().orEmpty().filter(::isCloudflare)
        if (trusted.isNotEmpty()) return trusted

        fallbackCache[hostname]?.takeIf { it.expireAt > now() }?.let { return it.addresses }

        log(
            "untrusted result for $hostname: " +
                    (primary.exceptionOrNull()?.toString() ?: primary.getOrNull().toString())
        )
        val fromFallback = runCatching { fallbackDns.lookup(hostname) }
            .onFailure { log("fallback lookup failed for $hostname: $it") }
            .getOrNull().orEmpty()
            .filter(::isCloudflare)
        val result = fromFallback.ifEmpty { builtInIps(hostname) }
        log("use ${if (fromFallback.isNotEmpty()) "fallback dns" else "built-in ips"} for $hostname: $result")
        fallbackCache[hostname] = CachedResult(result, now() + FALLBACK_CACHE_MILLIS)
        return result
    }
}
