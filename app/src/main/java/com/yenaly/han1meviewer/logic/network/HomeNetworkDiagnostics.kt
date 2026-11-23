package com.yenaly.han1meviewer.logic.network

import com.yenaly.han1meviewer.BuildConfig
import com.yenaly.han1meviewer.Preferences
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.ArrayDeque

/** Short, redacted trace for the current homepage request. Never records URLs, headers or cookies. */
internal object HomeNetworkDiagnostics : EventListener.Factory {
    private const val MAX_EVENTS = 14
    private val lock = Any()
    private val events = ArrayDeque<String>()
    private var attemptId = 0L
    private var startedAtNanos = 0L
    private var targetUrl = ""
    private var targetHost = ""
    private var dnsMode = ""
    private var proxyMode = ""
    private var active = false

    fun begin(url: String): Long = synchronized(lock) {
        attemptId++
        startedAtNanos = System.nanoTime()
        targetUrl = url.trimEnd('/')
        targetHost = url.toHttpUrlOrNull()?.host.orEmpty()
        dnsMode = when {
            Preferences.useBuiltInHosts -> "Built-in Hosts"
            Preferences.useDoH -> "DoH (${Preferences.dohPreset})"
            else -> "System DNS"
        }
        proxyMode = when (Preferences.proxyType) {
            HProxySelector.TYPE_DIRECT -> "Direct"
            HProxySelector.TYPE_SYSTEM -> "System"
            HProxySelector.TYPE_HTTP -> "HTTP proxy"
            HProxySelector.TYPE_SOCKS -> "SOCKS proxy"
            else -> "Unknown"
        }
        events.clear()
        active = true
        attemptId
    }

    fun finish(id: Long, error: Throwable?): String = synchronized(lock) {
        if (id != attemptId) return@synchronized ""
        if (error != null) appendLocked(id, "Error ${error.javaClass.simpleName}")
        active = false
        buildString {
            append("App ").append(BuildConfig.VERSION_NAME).append('\n')
            append("Host ").append(targetHost.ifEmpty { "unknown" }).append('\n')
            append("DNS ").append(dnsMode).append(" | Proxy ").append(proxyMode)
            events.forEach { append('\n').append(it) }
        }
    }

    fun noteDns(host: String, event: String) = synchronized(lock) {
        if (active && host == targetHost) appendLocked(attemptId, event)
    }

    override fun create(call: Call): EventListener {
        val id = synchronized(lock) {
            if (active && call.request().url.toString().trimEnd('/') == targetUrl) attemptId else 0L
        }
        if (id == 0L) return EventListener.NONE
        return object : EventListener() {
            override fun callStart(call: Call) = record(id, "Request start")
            override fun dnsStart(call: Call, domainName: String) = record(id, "DNS start")
            override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
                val addresses = inetAddressList.take(3).joinToString(",") { it.hostAddress.orEmpty() }
                record(id, "DNS result $addresses (${inetAddressList.size})")
            }
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                val destination = if (proxy.type() == Proxy.Type.DIRECT) {
                    inetSocketAddress.address?.hostAddress ?: "unresolved"
                } else "${proxy.type()} proxy"
                record(id, "Connect $destination")
            }
            override fun secureConnectStart(call: Call) = record(id, "TLS start")
            override fun secureConnectEnd(call: Call, handshake: Handshake?) = record(id, "TLS ready")
            override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) =
                record(id, "Connect ready")
            override fun connectFailed(
                call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy,
                protocol: Protocol?, ioe: IOException,
            ) = record(id, "Connect failed ${ioe.javaClass.simpleName}")
            override fun responseHeadersEnd(call: Call, response: Response) = record(id, "HTTP ${response.code}")
            override fun callFailed(call: Call, ioe: IOException) = record(id, "Request failed ${ioe.javaClass.simpleName}")
            override fun callEnd(call: Call) = record(id, "Response complete")
        }
    }

    private fun record(id: Long, event: String) = synchronized(lock) { appendLocked(id, event) }

    private fun appendLocked(id: Long, event: String) {
        if (!active || id != attemptId) return
        val elapsed = ((System.nanoTime() - startedAtNanos) / 1_000_000).coerceAtLeast(0)
        events.addLast("+${elapsed}ms $event")
        while (events.size > MAX_EVENTS) events.removeFirst()
    }
}
