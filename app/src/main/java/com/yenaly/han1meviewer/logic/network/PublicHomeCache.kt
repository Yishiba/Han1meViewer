package com.yenaly.han1meviewer.logic.network

import android.content.Context
import android.util.AtomicFile
import com.yenaly.han1meviewer.logic.model.HanimeInfo
import com.yenaly.han1meviewer.logic.model.HomePage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Only public listing metadata is cached. Account identifiers, CSRF and announcements stay out. */
internal class PublicHomeCache(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "public-homepage-cache.json"))
    private val json = Json { ignoreUnknownKeys = true }

    data class Snapshot(val page: HomePage, val savedAt: Long)

    fun read(siteUrl: String, now: Long = System.currentTimeMillis()): Snapshot? = runCatching {
        if (!file.baseFile.exists() || file.baseFile.length() !in 1..MAX_BYTES.toLong()) return@runCatching null
        val cached = json.decodeFromString<CachedHome>(file.readFully().decodeToString())
        if (cached.version != 1 || cached.siteUrl != siteUrl ||
            cached.savedAt !in (now - MAX_AGE_MILLIS)..now || cached.sections.size > SECTION_NAMES.size ||
            cached.sections.values.any { it.size > MAX_ITEMS_PER_SECTION } ||
            cached.sections.keys.any { it !in SECTION_NAMES }
        ) return@runCatching null
        val sections = cached.sections.mapValues { (_, items) -> items.map { it.toInfo() }.toMutableList() }
        if (sections.values.all { it.isEmpty() }) return@runCatching null
        Snapshot(HomePage(
            csrfToken = null, avatarUrl = null, username = null, banner = null,
            latestHanime = sections["latestHanime"] ?: mutableListOf(),
            latestRelease = sections["latestRelease"] ?: mutableListOf(),
            ecchiAnime = sections["ecchiAnime"] ?: mutableListOf(),
            shortEpisodeAnime = sections["shortEpisodeAnime"] ?: mutableListOf(),
            twoPointFiveDAnime = sections["twoPointFiveDAnime"] ?: mutableListOf(),
            threeDCG = sections["threeDCG"] ?: mutableListOf(),
            motionAnime = sections["motionAnime"] ?: mutableListOf(),
            twoDAnime = sections["twoDAnime"] ?: mutableListOf(),
            aiGenerated = sections["aiGenerated"] ?: mutableListOf(),
            mmd = sections["mmd"] ?: mutableListOf(),
            cosplay = sections["cosplay"] ?: mutableListOf(),
            watchingNow = sections["watchingNow"] ?: mutableListOf(),
            newAnimeTrailer = sections["newAnimeTrailer"] ?: mutableListOf(),
            timestamp = cached.savedAt, userId = "",
        ), cached.savedAt)
    }.getOrNull()

    fun write(siteUrl: String, page: HomePage, now: Long = System.currentTimeMillis()) {
        val sections = mapOf(
            "latestHanime" to page.latestHanime, "latestRelease" to page.latestRelease,
            "ecchiAnime" to page.ecchiAnime, "shortEpisodeAnime" to page.shortEpisodeAnime,
            "twoPointFiveDAnime" to page.twoPointFiveDAnime, "threeDCG" to page.threeDCG,
            "motionAnime" to page.motionAnime, "twoDAnime" to page.twoDAnime,
            "aiGenerated" to page.aiGenerated, "mmd" to page.mmd,
            "cosplay" to page.cosplay, "watchingNow" to page.watchingNow,
            "newAnimeTrailer" to page.newAnimeTrailer,
        ).mapValues { (_, items) -> items.take(MAX_ITEMS_PER_SECTION).map(CachedVideo::fromInfo) }
        if (sections.values.all { it.isEmpty() }) return
        val bytes = json.encodeToString(CachedHome.serializer(),
            CachedHome(siteUrl = siteUrl, savedAt = now, sections = sections)).toByteArray()
        if (bytes.size > MAX_BYTES) return
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private companion object {
        const val MAX_BYTES = 1_048_576
        const val MAX_ITEMS_PER_SECTION = 40
        const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
        val SECTION_NAMES = setOf("latestHanime", "latestRelease", "ecchiAnime", "shortEpisodeAnime",
            "twoPointFiveDAnime", "threeDCG", "motionAnime", "twoDAnime", "aiGenerated", "mmd",
            "cosplay", "watchingNow", "newAnimeTrailer")
    }
}

@Serializable
private data class CachedHome(
    val version: Int = 1,
    val siteUrl: String,
    val savedAt: Long,
    val sections: Map<String, List<CachedVideo>>,
)

@Serializable
private data class CachedVideo(
    val title: String,
    val coverUrl: String,
    val videoCode: String,
    val duration: String? = null,
    val views: String? = null,
    val uploadTime: String? = null,
    val genre: String? = null,
    val itemType: Int,
    val reviews: String? = null,
    val currentArtist: String? = null,
) {
    fun toInfo() = HanimeInfo(title, coverUrl, videoCode, duration, views, uploadTime, genre,
        itemType = itemType, reviews = reviews, currentArtist = currentArtist, watched = false)

    companion object {
        fun fromInfo(info: HanimeInfo) = CachedVideo(info.title, info.coverUrl, info.videoCode,
            info.duration, info.views, info.uploadTime, info.genre, info.itemType,
            info.reviews, info.currentArtist)
    }
}
