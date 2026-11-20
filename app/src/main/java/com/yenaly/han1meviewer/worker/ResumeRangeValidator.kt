package com.yenaly.han1meviewer.worker

/** A resumed write is safe only when the response starts at the persisted byte offset. */
internal object ResumeRangeValidator {
    private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")

    fun accepts(code: Int, range: String?, length: String?, offset: Long, total: Long): Boolean {
        if (offset < 0 || offset >= total) return false
        return when (code) {
            200 -> offset == 0L && (length == null || length.toLongOrNull() == total)
            206 -> matches(range, offset, total)
            else -> false
        }
    }

    fun matches(header: String?, offset: Long, total: Long): Boolean {
        return end(header, offset, total) != null
    }

    fun end(header: String?, offset: Long, total: Long): Long? {
        val match = header?.let(CONTENT_RANGE::matchEntire) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val responseTotal = match.groupValues[3].toLongOrNull() ?: return null
        return end.takeIf { offset >= 0 && total > 0 && start == offset && end >= start && end < total && responseTotal == total }
    }
}
