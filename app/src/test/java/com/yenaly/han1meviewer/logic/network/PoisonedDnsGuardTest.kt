package com.yenaly.han1meviewer.logic.network

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class PoisonedDnsGuardTest {

    private val host = "hanime1.me"

    private fun ip(vararg raw: String) = raw.map { InetAddress.getByName(it) }

    private val builtIn = ip("104.19.0.1")

    private class FakeDns(private val answer: () -> List<InetAddress>) : Dns {
        var calls = 0
        override fun lookup(hostname: String): List<InetAddress> {
            calls++
            return answer()
        }
    }

    private fun guard(fallback: Dns, clock: () -> Long = { 0L }) =
        PoisonedDnsGuard(fallback, builtInIps = { builtIn }, now = clock)

    @Test
    fun cloudflareResultIsUsedDirectly() {
        val fallback = FakeDns { error("should not be called") }
        val primary = ip("172.67.74.156", "104.26.8.104")
        assertEquals(primary, guard(fallback).resolve(host, Result.success(primary)))
        assertEquals(0, fallback.calls)
    }

    @Test
    fun poisonedResultFallsBackToDoh() {
        val real = ip("104.26.9.104")
        val fallback = FakeDns { real }
        val result = guard(fallback).resolve(host, Result.success(ip("31.13.85.2")))
        assertEquals(real, result)
        assertEquals(1, fallback.calls)
    }

    @Test
    fun poisonedEverywhereUsesBuiltInIps() {
        val fallback = FakeDns { ip("31.13.85.2") }
        assertEquals(builtIn, guard(fallback).resolve(host, Result.success(ip("50.87.93.246"))))
    }

    @Test
    fun failuresUseBuiltInIps() {
        val fallback = FakeDns { throw UnknownHostException(host) }
        val result = guard(fallback).resolve(host, Result.failure(UnknownHostException(host)))
        assertEquals(builtIn, result)
    }

    @Test
    fun mixedResultKeepsOnlyCloudflare() {
        val fallback = FakeDns { error("should not be called") }
        val result = guard(fallback).resolve(host, Result.success(ip("31.13.85.2", "172.67.74.156")))
        assertEquals(ip("172.67.74.156"), result)
    }

    @Test
    fun fallbackResultIsCachedUntilExpired() {
        var time = 0L
        val fallback = FakeDns { ip("104.26.9.104") }
        val guard = guard(fallback) { time }
        val poisoned = Result.success(ip("31.13.85.2"))
        guard.resolve(host, poisoned)
        time += 9 * 60 * 1000L
        guard.resolve(host, poisoned)
        assertEquals(1, fallback.calls)
        time += 2 * 60 * 1000L
        guard.resolve(host, poisoned)
        assertEquals(2, fallback.calls)
    }

    @Test
    fun cloudflareRangeBoundaries() {
        val inside = ip("104.16.0.0", "104.23.255.255", "172.71.255.255", "162.159.0.1", "2606:4700:3035::ac43:bb8d")
        val outside = ip("104.15.255.255", "104.28.0.0", "172.72.0.0", "31.13.85.2", "74.86.12.173", "2606:4701::1")
        inside.forEach { assertTrue("$it", PoisonedDnsGuard.isCloudflare(it)) }
        outside.forEach { assertFalse("$it", PoisonedDnsGuard.isCloudflare(it)) }
    }
}
