package com.yenaly.han1meviewer.logic.library

import com.yenaly.han1meviewer.logic.entity.download.VideoWithCategories
import com.yenaly.han1meviewer.logic.entity.download.HanimeDownloadEntity
import com.yenaly.han1meviewer.logic.entity.download.DownloadGroupEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class LibrarySort { NEWEST, OLDEST, TITLE, LARGEST, SMALLEST }

@Serializable
data class MediaLibraryRule(
    val keyword: String = "",
    val excludedKeyword: String = "",
    val groupIds: Set<Int> = emptySet(),
    val qualities: Set<String> = emptySet(),
    val minSizeMiB: Long? = null,
    val maxSizeMiB: Long? = null,
    val recentDays: Int? = null,
    val sort: LibrarySort = LibrarySort.NEWEST,
) {
    fun withInputBounds(min: String, max: String, days: String): MediaLibraryRule? = runCatching {
        copy(
            minSizeMiB = min.trim().takeIf { it.isNotEmpty() }?.toLong(),
            maxSizeMiB = max.trim().takeIf { it.isNotEmpty() }?.toLong(),
            recentDays = days.trim().takeIf { it.isNotEmpty() }?.toInt(),
        ).also { it.validate() }
    }.getOrNull()

    fun validate() {
        require(minSizeMiB == null || minSizeMiB in 0..MAX_SIZE_MIB)
        require(maxSizeMiB == null || maxSizeMiB in 0..MAX_SIZE_MIB)
        require(minSizeMiB == null || maxSizeMiB == null || minSizeMiB <= maxSizeMiB)
        require(recentDays == null || recentDays in 1..36500)
    }

    /** OR within groups/qualities; AND across fields. Size is bytes, not video duration. */
    fun matches(video: HanimeDownloadEntity, nowMillis: Long): Boolean {
        validate()
        val included = keyword.trim()
        val excluded = excludedKeyword.trim()
        val cutoff = recentDays?.let { nowMillis - it.toLong() * DAY_MILLIS }
        fun contains(text: String) = video.title.contains(text, ignoreCase = true) ||
            video.videoCode.contains(text, ignoreCase = true)
        return video.isDownloaded &&
            (included.isEmpty() || contains(included)) &&
            (excluded.isEmpty() || !contains(excluded)) &&
            (groupIds.isEmpty() || video.groupId in groupIds) &&
            (qualities.isEmpty() || video.quality in qualities) &&
            (minSizeMiB == null || video.length >= minSizeMiB * MIB) &&
            (maxSizeMiB == null || video.length <= maxSizeMiB * MIB) &&
            (cutoff == null || video.addDate in cutoff..nowMillis)
    }

    fun apply(items: List<VideoWithCategories>, nowMillis: Long): List<VideoWithCategories> {
        validate()
        val matches = items.filter { this.matches(it.video, nowMillis) }
        val comparator = when (sort) {
            LibrarySort.NEWEST -> compareByDescending<VideoWithCategories> { it.video.addDate }
            LibrarySort.OLDEST -> compareBy<VideoWithCategories> { it.video.addDate }
            LibrarySort.TITLE -> compareBy<VideoWithCategories> { it.video.title.lowercase() }
            LibrarySort.LARGEST -> compareByDescending<VideoWithCategories> { it.video.length }
            LibrarySort.SMALLEST -> compareBy<VideoWithCategories> { it.video.length }
        }.thenByDescending { it.video.id }
        return matches.sortedWith(comparator)
    }

    companion object {
        const val MIB = 1024L * 1024L
        const val MAX_SIZE_MIB = Long.MAX_VALUE / MIB
        const val DAY_MILLIS = 86_400_000L
    }
}

@Serializable
data class SmartCollection(
    val id: String, val name: String, val rule: MediaLibraryRule,
    val autoEnabled: Boolean = false,
    val autoTargetGroupId: Int? = null,
)

@Serializable
private data class CollectionDocument(val version: Int = 1, val collections: List<SmartCollection>)

/** Shared by persistence, saved UI state and tests; never silently discard an unreadable catalog. */
object MediaLibraryCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encodeRule(rule: MediaLibraryRule): String = json.encodeToString(rule)
    fun decodeRule(text: String): MediaLibraryRule = json.decodeFromString<MediaLibraryRule>(text).also { it.validate() }
    fun encodeCollections(collections: List<SmartCollection>): String {
        validateCollections(collections)
        return json.encodeToString(CollectionDocument(collections = collections))
    }
    fun decodeCollections(text: String?): List<SmartCollection> {
        if (text == null) return emptyList()
        val document = json.decodeFromString<CollectionDocument>(text)
        require(document.version == 1) { "Unsupported collection format" }
        return document.collections.also(::validateCollections)
    }
    private fun validateCollections(collections: List<SmartCollection>) {
        require(collections.map { it.id }.distinct().size == collections.size)
        require(collections.map { it.name.trim().lowercase() }.distinct().size == collections.size)
        collections.forEach {
            require(it.id.isNotBlank() && it.name.isNotBlank() && it.name.length <= 80)
            it.rule.validate()
            require(!it.autoEnabled || (it.autoTargetGroupId != null && it.autoTargetGroupId > 0 &&
                it.autoTargetGroupId != DownloadGroupEntity.DEFAULT_GROUP_ID))
        }
    }
}
