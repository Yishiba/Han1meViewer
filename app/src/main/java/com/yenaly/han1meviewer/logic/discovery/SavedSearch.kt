package com.yenaly.han1meviewer.logic.discovery

import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
data class SearchCriteria(
    val baseUrl: String,
    val queries: List<String?>,
    val genre: String? = null,
    val sort: String? = null,
    val broad: Boolean = false,
    val date: String? = null,
    val duration: String? = null,
    val tags: Set<String> = emptySet(),
    val brands: Set<String> = emptySet(),
) {
    fun validate() {
        val uri = URI(baseUrl)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(baseUrl.length <= 2000 && baseUrl.endsWith('/'))
        require(queries.size in 1..2 && queries.distinct().size == queries.size)
        require((queries + listOf(genre, sort, date, duration) + tags + brands).all { it == null || it.length <= 500 })
        require(tags.size <= 100 && brands.size <= 100)
    }
}

@Serializable data class WatchVideo(val code: String, val title: String, val discoveredAt: Long = 0, val read: Boolean = false)
@Serializable data class WatchSearch(
    val id: String,
    val name: String,
    val criteria: SearchCriteria,
    val enabled: Boolean = true,
    val initialized: Boolean = false,
    val seen: Set<String> = emptySet(),
    val updates: List<WatchVideo> = emptyList(),
    val lastAttempt: Long = 0,
    val lastSuccess: Long = 0,
    val failed: Boolean = false,
    val generation: Long = 0,
) {
    val unreadCount: Int get() = updates.count { !it.read }

    fun record(results: List<WatchVideo>, now: Long): WatchSearch {
        require(results.size <= 500 && results.all { it.code.matches(Regex("[0-9]{1,20}")) && it.title.length in 1..2000 })
        val distinct = results.distinctBy { it.code }
        val nextSeen = seen + distinct.map { it.code }
        // Never evict IDs and re-report old results as new. A full tracker can be recreated explicitly.
        require(nextSeen.size <= 5000)
        val discovered = if (initialized) distinct.filterNot { it.code in seen }
            .map { it.copy(discoveredAt = now, read = false) } else emptyList()
        return copy(initialized = true, seen = nextSeen,
            updates = (discovered + updates).distinctBy { it.code }.take(200),
            lastAttempt = now, lastSuccess = now, failed = false)
    }

    fun validate() {
        criteria.validate()
        require(id.length in 1..100 && name.isNotBlank() && name.length <= 80)
        require(seen.size <= 5000 && updates.size <= 200 && updates.distinctBy { it.code }.size == updates.size)
        require(seen.all { it.matches(Regex("[0-9]{1,20}")) })
        require(updates.all { it.code in seen && it.title.length in 1..2000 && it.discoveredAt >= 0 })
        require(lastAttempt >= 0 && lastSuccess >= 0 && generation >= 0)
    }
}

@Serializable data class SavedSearchData(val version: Int = 1, val searches: List<WatchSearch> = emptyList()) {
    fun validate() {
        require(version == 1 && searches.size <= 50 && searches.distinctBy { it.id }.size == searches.size)
        searches.forEach { it.validate() }
    }
}
