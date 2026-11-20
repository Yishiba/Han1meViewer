package com.yenaly.han1meviewer.logic.library

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CollectionCatalog(val collections: List<SmartCollection> = emptyList(), val readError: Boolean = false)

/** Uses the existing settings store, so normal manual backup/restore includes saved rules. */
class SmartCollectionStore(private val preferences: SharedPreferences) : AutoCloseable {
    private val lock = Any()
    private val mutableCatalog = MutableStateFlow(readCatalog())
    val catalog = mutableCatalog.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY || key == null) mutableCatalog.value = readCatalog()
    }

    init { preferences.registerOnSharedPreferenceChangeListener(listener) }

    fun save(collection: SmartCollection): Boolean = synchronized(lock) {
        runCatching {
            val current = MediaLibraryCodec.decodeCollections(preferences.getString(KEY, null))
            val normalized = collection.copy(name = collection.name.trim())
            val next = if (current.any { it.id == collection.id }) {
                current.map { if (it.id == collection.id) normalized else it }
            } else current + normalized
            write(next)
        }.getOrDefault(false)
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        runCatching {
            val current = MediaLibraryCodec.decodeCollections(preferences.getString(KEY, null))
            write(current.filterNot { it.id == id })
        }.getOrDefault(false)
    }

    fun move(id: String, offset: Int): Boolean = synchronized(lock) {
        runCatching {
            require(offset == -1 || offset == 1)
            val current = MediaLibraryCodec.decodeCollections(preferences.getString(KEY, null)).toMutableList()
            val index = current.indexOfFirst { it.id == id }
            require(index >= 0 && index + offset in current.indices)
            val item = current.removeAt(index)
            current.add(index + offset, item)
            write(current)
        }.getOrDefault(false)
    }

    private fun write(collections: List<SmartCollection>): Boolean {
        val encoded = MediaLibraryCodec.encodeCollections(collections)
        val success = preferences.edit().putString(KEY, encoded).commit()
        mutableCatalog.value = readCatalog()
        return success
    }

    private fun readCatalog(): CollectionCatalog = runCatching {
        CollectionCatalog(MediaLibraryCodec.decodeCollections(preferences.getString(KEY, null)))
    }.getOrElse { CollectionCatalog(readError = true) }

    override fun close() = preferences.unregisterOnSharedPreferenceChangeListener(listener)

    companion object { const val KEY = "media_library_smart_collections_v1" }
}
