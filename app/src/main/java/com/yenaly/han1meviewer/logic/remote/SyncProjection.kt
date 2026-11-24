package com.yenaly.han1meviewer.logic.remote

import com.yenaly.han1meviewer.logic.backup.*
import com.yenaly.han1meviewer.logic.entity.WatchHistoryEntity
import com.yenaly.han1meviewer.logic.entity.download.DownloadGroupEntity
import com.yenaly.han1meviewer.logic.library.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable data class PortableGroup(val name: String = "", val default: Boolean = false)
@Serializable data class PortableCollection(val collection: SmartCollection, val groups: List<PortableGroup>, val target: PortableGroup? = null, val priority: Int = 0)
data class SyncMaterialized(val backup: BackupPayload, val progress: Map<String, RemoteProgress>)

object SyncProjection {
    val settings = setOf("theme_color", "use_dark_mode", "video_language", "default_video_quality", "show_played_indicator", "allow_resume_playback", "disable_comments")
    private val json get() = SyncCrypto.json
    fun capture(snapshot: BackupPayload, progress: Map<String, RemoteProgress>): Map<String, JsonElement> = buildMap {
        require(snapshot.searchToolsRaw == null) { "Repair the local search catalog before synchronizing" }
        require(snapshot.savedSearchesRaw == null) { "Repair saved searches before synchronizing" }
        snapshot.settings.orEmpty().filterKeys { it in settings }.forEach { (k, v) -> put("setting/$k", json.encodeToJsonElement<PreferenceValue>(v)) }
        snapshot.watchHistories.orEmpty().groupBy { it.videoCode }.forEach { (code, values) ->
            put("watch/$code", json.encodeToJsonElement(values.maxBy { it.watchDate }.copy(id = 0)))
        }
        fun group(id: Int): PortableGroup {
            if (id == 1) return PortableGroup(default = true)
            return PortableGroup(snapshot.downloadGroups.orEmpty().single { it.id == id }.name)
        }
        MediaLibraryCodec.decodeCollections((snapshot.settings?.get(SmartCollectionStore.KEY) as? PreferenceValue.StringValue)?.value)
            .forEachIndexed { index, c ->
                put("rule/${c.id}", json.encodeToJsonElement(PortableCollection(
                    c.copy(rule = c.rule.copy(groupIds = emptySet()), autoEnabled = false, autoTargetGroupId = null),
                    c.rule.groupIds.map(::group), c.autoTargetGroupId?.let(::group), index,
                )).let { element ->
                    // Preserve automation separately from this portable, group-ID-free representation.
                    JsonObject(element.jsonObject + ("enabled" to JsonPrimitive(c.autoEnabled)))
                })
            }
        putAll(SearchSyncProjection.capture(snapshot.searchTools))
        putAll(SavedSearchSyncProjection.capture(snapshot.savedSearches))
        progress.forEach { (url, value) -> put("remote/${BackupCodec.digest(url.toByteArray())}", json.encodeToJsonElement(value)) }
    }
    fun materialize(current: BackupPayload, live: Map<String, JsonElement>): SyncMaterialized {
        val prefs = current.settings.orEmpty().toMutableMap()
        settings.forEach(prefs::remove)
        val watches = mutableListOf<WatchHistoryEntity>()
        val groups = current.downloadGroups.orEmpty().toMutableList()
        val portable = mutableListOf<Pair<PortableCollection, Boolean>>()
        val progress = mutableMapOf<String, RemoteProgress>()
        live.forEach { (key, value) ->
            when {
                key.startsWith("setting/") -> {
                    val name = key.removePrefix("setting/")
                    require(name in settings)
                    prefs[name] = json.decodeFromJsonElement<PreferenceValue>(value)
                }
                key.startsWith("watch/") -> {
                    val record = json.decodeFromJsonElement<WatchHistoryEntity>(value)
                    require(key == "watch/${record.videoCode}" && record.videoCode.length in 1..300 && record.progress >= 0 && record.title.length <= 2000)
                    watches += record.copy(id = current.watchHistories.orEmpty().firstOrNull { it.videoCode == record.videoCode }?.id ?: 0)
                }
                key.startsWith("rule/") -> {
                    val rule = json.decodeFromJsonElement<PortableCollection>(value)
                    require(key == "rule/${rule.collection.id}" && rule.priority in 0..20000)
                    rule.collection.rule.validate()
                    require(rule.collection.rule.groupIds.isEmpty() && rule.collection.autoTargetGroupId == null && !rule.collection.autoEnabled)
                    portable += rule to (value.jsonObject["enabled"]?.jsonPrimitive?.booleanOrNull ?: false)
                }
                key.startsWith("remote/") -> {
                    val item = json.decodeFromJsonElement<RemoteProgress>(value)
                    require(item.url.length <= 4000 && item.position >= 0 && item.title.length <= 2000)
                    val url = item.url.toHttpUrl()
                    require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty())
                    require(key == "remote/${BackupCodec.digest(item.url.toByteArray())}")
                    progress[item.url] = item
                }
                key.startsWith("search-alias/") || key.startsWith("search-history/") -> Unit
                key.startsWith("saved-search/") -> Unit
                else -> error("Unknown sync field")
            }
        }
        fun resolve(ref: PortableGroup): Int {
            if (ref.default) return 1
            require(ref.name.isNotBlank() && ref.name.length <= 200)
            val matches = groups.filter { it.id != 1 && it.name == ref.name }
            require(matches.size <= 1) { "Ambiguous local group" }
            if (matches.isNotEmpty()) return matches.single().id
            val id = (groups.maxOfOrNull { it.id } ?: 1).toLong() + 1
            require(id < Int.MAX_VALUE)
            groups += DownloadGroupEntity(ref.name, groups.size, id.toInt())
            return id.toInt()
        }
        val usedNames = mutableSetOf<String>()
        val collections = portable.sortedWith(compareBy<Pair<PortableCollection, Boolean>> { it.first.priority }.thenBy { it.first.collection.id }).map { (p, enabled) ->
            val collection = p.collection
            var name = collection.name.trim()
            require(name.isNotBlank() && name.length <= 80)
            var suffix = 1
            while (!usedNames.add(name.lowercase(Locale.ROOT))) { name = collection.name.take(65) + " (${suffix++})" }
            val target = p.target?.let(::resolve)
            collection.copy(name = name, rule = collection.rule.copy(groupIds = p.groups.map(::resolve).toSet()),
                autoTargetGroupId = target, autoEnabled = enabled && target != null && target != 1)
        }
        prefs[SmartCollectionStore.KEY] = PreferenceValue.StringValue(MediaLibraryCodec.encodeCollections(collections))
        // Assign stable new positive IDs before validation; existing database IDs never cross devices.
        var nextId = (current.watchHistories.orEmpty().maxOfOrNull { it.id } ?: 0).toLong()
        val histories = watches.map { if (it.id > 0) it else it.copy(id = (++nextId).also { id -> require(id < Int.MAX_VALUE) }.toInt()) }
        val result = current.copy(version = 4, settings = prefs, watchHistories = histories, downloadGroups = groups, exportedAt = 0,
            searchTools = SearchSyncProjection.materialize(live), searchToolsRaw = null,
            savedSearches = SavedSearchSyncProjection.materialize(live), savedSearchesRaw = null)
        BackupCodec.validate(result)
        return SyncMaterialized(result, progress)
    }
}
