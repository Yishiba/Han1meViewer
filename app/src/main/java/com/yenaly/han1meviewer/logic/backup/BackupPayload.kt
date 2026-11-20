package com.yenaly.han1meviewer.logic.backup

import com.yenaly.han1meviewer.logic.entity.CheckInRecordEntity
import com.yenaly.han1meviewer.logic.entity.HKeyframeEntity
import com.yenaly.han1meviewer.logic.entity.SideDishEntity
import com.yenaly.han1meviewer.logic.entity.WatchHistoryEntity
import com.yenaly.han1meviewer.logic.entity.download.*
import com.yenaly.han1meviewer.logic.state.DownloadState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import java.security.MessageDigest

@Serializable
sealed interface PreferenceValue {
    @Serializable data class BooleanValue(val value: Boolean) : PreferenceValue
    @Serializable data class FloatValue(val value: Float) : PreferenceValue
    @Serializable data class IntValue(val value: Int) : PreferenceValue
    @Serializable data class LongValue(val value: Long) : PreferenceValue
    @Serializable data class StringValue(val value: String) : PreferenceValue
    @Serializable data class StringSetValue(val value: Set<String>) : PreferenceValue
}

@Serializable
data class BackupPayload(
    val version: Int = 1,
    val appVersionCode: Int = 0,
    val appVersionName: String = "",
    val exportedAt: Long = 0,
    val settings: Map<String, PreferenceValue>? = null,
    val hKeyframes: List<HKeyframeEntity>? = null,
    val checkInRecords: List<CheckInRecordEntity>? = null,
    val sideDishes: List<SideDishEntity>? = null,
    val watchHistories: List<WatchHistoryEntity>? = null,
    val downloadGroups: List<DownloadGroupEntity>? = null,
    val downloads: List<HanimeDownloadEntity>? = null,
    val downloadCategories: List<DownloadCategoryEntity>? = null,
    val downloadCategoryCrossRefs: List<HanimeCategoryCrossRef>? = null,
    val searchTools: com.yenaly.han1meviewer.logic.translation.SearchToolsData? = null,
    val searchToolsRaw: String? = null,
) {
    fun safeForRestore(): BackupPayload = copy(
        settings = settings?.filterKeys(BackupCodec::restorableKey),
        downloads = downloads?.map { if (it.isDownloaded) it else it.copy(state = DownloadState.Paused) },
        checkInRecords = checkInRecords?.map { record ->
            record.copy(sideDishes = record.sideDishes.replace("\\u001E", "\u001E").replace("\\u001e", "\u001E")
                .split(",").joinToString(",") { item ->
                    if (item.contains('\u001E') || !item.contains('|')) item
                    else if (item.substringAfter('|').isBlank()) item.substringBefore('|')
                    else item.substringBefore('|') + "\u001E" + item.substringAfter('|')
                })
        },
    )
}

@Serializable
private data class BackupEnvelope(val format: String = "hanime-backup", val envelopeVersion: Int = 1,
                                  val payload: String, val sha256: String)

object BackupCodec {
    const val MAX_BYTES = 32 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    // Preserve this discriminator for v1 files produced by the original private nested class.
    private val legacyType = "com.yenaly.han1meviewer.logic.BackupManager.PreferenceValue."
    private val newType = "com.yenaly.han1meviewer.logic.backup.PreferenceValue."
    private val excluded = setOf(
        "cookie", "cf_cookie", "already_login", "saved_user_id",
        "usage_notice_accepted", "usage_notice_2026_11_20_accepted",
        "onboarding_completed", "onboarding_pending",
    )
    fun restorableKey(key: String) = key !in excluded && key != "auto_translate_mobile_models"
    fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun readBounded(stream: java.io.InputStream, limit: Int = MAX_BYTES): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= limit) { "Backup exceeds size limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun encode(payload: BackupPayload): ByteArray {
        val safe = payload.safeForRestore()
        validate(safe)
        val body = json.encodeToString(safe)
        val output = json.encodeToString(BackupEnvelope(payload = body, sha256 = digest(body.toByteArray()))).toByteArray()
        require(output.size <= MAX_BYTES) { "Backup exceeds size limit" }
        return output
    }

    fun decode(bytes: ByteArray): BackupPayload {
        require(bytes.size in 1..MAX_BYTES) { "Invalid backup size" }
        val text = bytes.toString(Charsets.UTF_8)
        val objectValue = json.parseToJsonElement(text).jsonObject
        val body = if ("format" in objectValue) {
            val envelope = json.decodeFromString<BackupEnvelope>(text)
            require(envelope.format == "hanime-backup" && envelope.envelopeVersion == 1)
            require(digest(envelope.payload.toByteArray()) == envelope.sha256) { "Backup checksum mismatch" }
            envelope.payload
        } else text
        val document = json.parseToJsonElement(body).jsonObject
        val settings = document["settings"] as? JsonObject
        val compatible = if (settings == null) document else JsonObject(document + ("settings" to
            JsonObject(settings.mapValues { (_, value) ->
                val entry = value as? JsonObject ?: return@mapValues value
                val type = (entry["type"] as? JsonPrimitive)?.contentOrNull
                if (type?.startsWith(legacyType) == true)
                    JsonObject(entry + ("type" to JsonPrimitive(newType + type.removePrefix(legacyType))))
                else entry
            })))
        val payload = json.decodeFromJsonElement<BackupPayload>(compatible)
        validate(payload)
        return payload.safeForRestore()
    }

    fun validate(data: BackupPayload) {
        require(data.version in 1..3) { "Unsupported backup version" }
        BackupPreferencePolicy.validate(data.settings)
        data.searchTools?.validate()
        require(data.searchTools == null || data.searchToolsRaw == null)
        data.searchToolsRaw?.let { require(it.length <= 1024 * 1024) }
        require(listOf(data.settings, data.hKeyframes, data.checkInRecords, data.sideDishes, data.watchHistories,
            data.downloadGroups, data.downloads, data.downloadCategories, data.downloadCategoryCrossRefs, data.searchTools, data.searchToolsRaw).any { it != null })
        fun <T> unique(values: List<T>?) { if (values != null) require(values.distinct().size == values.size) { "Duplicate record key" } }
        unique(data.hKeyframes?.map { it.videoCode }); unique(data.checkInRecords?.map { it.id })
        unique(data.sideDishes?.map { it.videoCode }); unique(data.watchHistories?.map { it.id })
        unique(data.downloadGroups?.map { it.id }); unique(data.downloads?.map { it.id })
        unique(data.downloadCategories?.map { it.id }); unique(data.downloadCategoryCrossRefs)
        data.downloadGroups?.forEach { require(it.id > 0) }
        data.downloadCategories?.forEach { require(it.id > 0) }
        data.downloads?.forEach { require(it.id > 0 && it.length >= 0 && it.downloadedLength >= 0 && it.videoCode.isNotBlank()) }
        val groupIds = data.downloadGroups.orEmpty().map { it.id }.toSet() + DownloadGroupEntity.DEFAULT_GROUP_ID
        data.downloads?.forEach { require(it.groupId in groupIds) { "Missing download group" } }
        val videoIds = data.downloads.orEmpty().map { it.id }.toSet()
        val categoryIds = data.downloadCategories.orEmpty().map { it.id }.toSet()
        data.downloadCategoryCrossRefs?.forEach { require(it.videoId in videoIds && it.categoryId in categoryIds) }
        // Rule catalogs are opaque preference values: retain even an already damaged catalog so a
        // protection snapshot can still be made before recovering from a known-good older backup.
    }
}
