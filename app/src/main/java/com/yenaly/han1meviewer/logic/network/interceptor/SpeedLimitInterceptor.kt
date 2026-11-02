package com.yenaly.han1meviewer.logic.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import okio.Throttler

/**
 * @param maxSpeed 每次请求时读取，设置改了立即生效；0 表示不限速
 */
class SpeedLimitInterceptor(private val maxSpeed: () -> Long) : Interceptor {

    companion object {
        const val NO_LIMIT = 0L

        const val NO_LIMIT_INDEX = 0

        @JvmField
        val SPEED_BYTES = longArrayOf(
            /* 不限速 */ 0L,
            128 * 1024L, 256 * 1024L, 512 * 1024L,
            1024 * 1024L, 2048 * 1024L, 4096 * 1024L,
            8192 * 1024L, 10240 * 1024L
        )
    }

    /**
     * 所有下载连接共用一个，限的是总速度；每条连接各用一个的话，多连接下载会把限速放大好几倍
     */
    private val throttler = Throttler()

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val speed = maxSpeed()
        if (speed <= NO_LIMIT) return response
        throttler.bytesPerSecond(speed)
        return response.newBuilder()
            .body(SpeedLimitResponseBody(response.body, throttler))
            .build()
    }
}
