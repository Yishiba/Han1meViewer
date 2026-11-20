package com.yenaly.han1meviewer.logic.translation

/** Each query advances only after success. A retry requests failed routes without advancing others. */
class SearchPaging {
    private val pages = mutableMapOf<String?, Int>()
    private val ended = mutableSetOf<String?>()
    private val failed = mutableSetOf<String?>()
    fun reset() { pages.clear(); ended.clear(); failed.clear() }
    fun pending(queries: List<String?>) = queries.distinct().filter { it !in ended && (failed.isEmpty() || it in failed) }
    fun page(query: String?) = pages[query] ?: 1
    fun success(query: String?) { pages[query] = page(query) + 1; failed.remove(query) }
    fun end(query: String?) { ended += query; failed.remove(query) }
    fun fail(query: String?) { failed += query }
    fun complete(queries: List<String?>) = queries.all { it in ended }
}
