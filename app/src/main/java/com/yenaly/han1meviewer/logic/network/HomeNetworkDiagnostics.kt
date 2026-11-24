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
    enum class FailureStage { DNS, CONNECT, TLS, HTTP, UNKNOWN }

    private const val HEAD_EVENTS = 8
    private const val TAIL_EVENTS = 12
    private val lock = Any()
    private val firstEvents = ArrayList<String>()
    private val lastEvents = ArrayDeque<String>()
    private var attemptId = 0L
    private var startedAtNanos = 0L
    private var targetUrl = ""
    private var targetHost = ""
    private var dnsMode = ""
    private var proxyMode = ""
    private var active = false
    private var lastCompletedReport = ""
    private var lastFailureStage = FailureStage.UNKNOWN
    private var dnsStartedAt = 0L
    private var connectStartedAt = 0L
    private var tlsStartedAt = 0L
    private var dnsMillis = 0L
    private var connectMillis = 0L
    private var tlsMillis = 0L
    private var connectionTlsMillis = 0L
    private var dnsResults = 0
    private var connectionAttempts = 0
    private var tlsReady = false
    private var lastConnectionFailedDuringTls = false
    private var httpSeen = false
    private var omittedEvents = 0

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
        firstEvents.clear()
        lastEvents.clear()
        dnsStartedAt = 0L
        connectStartedAt = 0L
        tlsStartedAt = 0L
        dnsMillis = 0L
        connectMillis = 0L
        tlsMillis = 0L
        connectionTlsMillis = 0L
        dnsResults = 0
        connectionAttempts = 0
        tlsReady = false
        lastConnectionFailedDuringTls = false
        httpSeen = false
        omittedEvents = 0
        lastFailureStage = FailureStage.UNKNOWN
        active = true
        attemptId
    }

    fun finish(id: Long, error: Throwable?): String = synchronized(lock) {
        if (id != attemptId) return@synchronized ""
        if (!active) return@synchronized lastCompletedReport
        val now = System.nanoTime()
        dnsMillis += closePhase(dnsStartedAt, now)
        val unfinishedTls = closePhase(tlsStartedAt, now)
        tlsMillis += unfinishedTls
        connectMillis += (closePhase(connectStartedAt, now) - connectionTlsMillis - unfinishedTls).coerceAtLeast(0)
        lastFailureStage = if (error == null) FailureStage.UNKNOWN else when {
            httpSeen -> FailureStage.HTTP
            lastConnectionFailedDuringTls || (tlsStartedAt != 0L && !tlsReady) -> FailureStage.TLS
            connectionAttempts > 0 -> FailureStage.CONNECT
            dnsResults == 0 -> FailureStage.DNS
            else -> FailureStage.UNKNOWN
        }
        if (error != null) appendLocked(id, "Error ${error.javaClass.simpleName}")
        appendLocked(id, if (error == null) "Home complete" else "Home failed")
        active = false
        lastCompletedReport = buildString {
            append("App ").append(BuildConfig.VERSION_NAME).append('\n')
            append("Host ").append(targetHost.ifEmpty { "unknown" }).append('\n')
            append("DNS ").append(dnsMode).append(" | Proxy ").append(proxyMode)
            append('\n').append("Total +").append((now - startedAtNanos) / 1_000_000).append("ms")
            append(" | Stage ").append(lastFailureStage)
            append('\n').append("Approx. DNS ").append(dnsMillis).append("ms; connect ")
                .append(connectMillis).append("ms; TLS ").append(tlsMillis).append("ms")
            append('\n').append("DNS results ").append(dnsResults)
                .append("; connection attempts ").append(connectionAttempts)
            firstEvents.forEach { append('\n').append(it) }
            if (omittedEvents > 0) append('\n').append("... ").append(omittedEvents).append(" events omitted ...")
            lastEvents.forEach { append('\n').append(it) }
        }
        lastCompletedReport
    }

    fun latestReport(): String = synchronized(lock) { lastCompletedReport }
    fun failureStage(): FailureStage = synchronized(lock) { lastFailureStage }

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
            override fun dnsStart(call: Call, domainName: String) = synchronized(lock) {
                if (!active || id != attemptId) return@synchronized
                dnsStartedAt = System.nanoTime()
                appendLocked(id, "DNS start")
            }
            override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
                val addresses = inetAddressList.take(3).joinToString(",") { it.hostAddress.orEmpty() }
                synchronized(lock) {
                    if (!active || id != attemptId) return@synchronized
                    dnsMillis += closePhase(dnsStartedAt, System.nanoTime())
                    dnsStartedAt = 0L
                    dnsResults++
                    appendLocked(id, "DNS result $addresses (${inetAddressList.size})")
                }
            }
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                val destination = if (proxy.type() == Proxy.Type.DIRECT) {
                    inetSocketAddress.address?.hostAddress ?: "unresolved"
                } else "${proxy.type()} proxy"
                synchronized(lock) {
                    if (!active || id != attemptId) return@synchronized
                    connectStartedAt = System.nanoTime()
                    connectionAttempts++
                    connectionTlsMillis = 0L
                    lastConnectionFailedDuringTls = false
                    appendLocked(id, "Connect $destination")
                }
            }
            override fun secureConnectStart(call: Call) = synchronized(lock) {
                if (!active || id != attemptId) return@synchronized
                tlsStartedAt = System.nanoTime()
                appendLocked(id, "TLS start")
            }
            override fun secureConnectEnd(call: Call, handshake: Handshake?) = synchronized(lock) {
                if (!active || id != attemptId) return@synchronized
                val elapsed = closePhase(tlsStartedAt, System.nanoTime())
                tlsMillis += elapsed
                connectionTlsMillis += elapsed
                tlsStartedAt = 0L
                tlsReady = true
                appendLocked(id, "TLS ready")
            }
            override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) =
                synchronized(lock) {
                    if (!active || id != attemptId) return@synchronized
                    connectMillis += (closePhase(connectStartedAt, System.nanoTime()) - connectionTlsMillis).coerceAtLeast(0)
                    connectStartedAt = 0L
                    connectionTlsMillis = 0L
                    appendLocked(id, "Connect ready")
                }
            override fun connectFailed(
                call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy,
                protocol: Protocol?, ioe: IOException,
            ) = synchronized(lock) {
                if (!active || id != attemptId) return@synchronized
                val now = System.nanoTime()
                lastConnectionFailedDuringTls = tlsStartedAt != 0L
                val tlsElapsed = closePhase(tlsStartedAt, now)
                connectMillis += (closePhase(connectStartedAt, now) - connectionTlsMillis - tlsElapsed).coerceAtLeast(0)
                tlsMillis += tlsElapsed
                connectStartedAt = 0L
                tlsStartedAt = 0L
                connectionTlsMillis = 0L
                appendLocked(id, "Connect failed ${ioe.javaClass.simpleName}")
            }
            override fun responseHeadersEnd(call: Call, response: Response) = synchronized(lock) {
                if (!active || id != attemptId) return@synchronized
                httpSeen = true
                appendLocked(id, "HTTP ${response.code}")
            }
            override fun callFailed(call: Call, ioe: IOException) = record(id, "Request failed ${ioe.javaClass.simpleName}")
            override fun callEnd(call: Call) = record(id, "Response complete")
        }
    }

    private fun record(id: Long, event: String) = synchronized(lock) { appendLocked(id, event) }

    private fun closePhase(start: Long, end: Long): Long =
        if (start == 0L) 0L else ((end - start) / 1_000_000).coerceAtLeast(0)

    private fun appendLocked(id: Long, event: String) {
        if (!active || id != attemptId) return
        val elapsed = ((System.nanoTime() - startedAtNanos) / 1_000_000).coerceAtLeast(0)
        val line = "+${elapsed}ms $event"
        if (firstEvents.size < HEAD_EVENTS) firstEvents.add(line)
        else {
            lastEvents.addLast(line)
            if (lastEvents.size > TAIL_EVENTS) {
                lastEvents.removeFirst()
                omittedEvents++
            }
        }
    }
}
