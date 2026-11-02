package com.yenaly.han1meviewer.logic.network.interceptor

import okhttp3.ResponseBody
import okio.Throttler
import okio.buffer

class SpeedLimitResponseBody(
    private val responseBody: ResponseBody,
    private val throttler: Throttler,
) : ResponseBody() {

    private val source by lazy { throttler.source(responseBody.source()).buffer() }

    override fun contentLength(): Long = responseBody.contentLength()

    override fun contentType() = responseBody.contentType()

    override fun source() = source

    override fun close() = responseBody.close()
}
