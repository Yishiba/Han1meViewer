package com.yenaly.han1meviewer.logic.remote

import com.yenaly.han1meviewer.logic.discovery.SavedSearchData
import com.yenaly.han1meviewer.logic.discovery.WatchSearch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** A stable search ID is the merge key. Concurrent edits to the same search require manual resolution. */
internal object SavedSearchSyncProjection {
    private const val PREFIX = "saved-search/"
    private val json get() = SyncCrypto.json

    fun capture(data: SavedSearchData?): Map<String, JsonElement> = buildMap {
        data?.validate()
        data?.searches?.forEach { put(PREFIX + it.id, json.encodeToJsonElement(it)) }
    }

    fun materialize(live: Map<String, JsonElement>): SavedSearchData {
        val searches = live.filterKeys { it.startsWith(PREFIX) }.map { (key, value) ->
            json.decodeFromJsonElement<WatchSearch>(value).also {
                it.validate()
                require(key == PREFIX + it.id)
            }
        }.sortedBy { it.name }
        return SavedSearchData(searches = searches).also { it.validate() }
    }
}
