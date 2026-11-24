package com.yenaly.han1meviewer.logic.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class SavedSearchRepository(
    private val load: () -> SavedSearchData,
    private val save: (SavedSearchData) -> Unit,
    private val fetch: suspend (SearchCriteria) -> List<WatchVideo>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val access: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    private fun WatchSearch.matchesCheckSnapshot(snapshot: WatchSearch): Boolean =
        enabled && copy(name = snapshot.name, updates = updates.map { it.copy(read = false) }) ==
            snapshot.copy(updates = snapshot.updates.map { it.copy(read = false) })

    private val changes = Mutex()
    private val checks = Mutex()
    private val mutableState = MutableStateFlow(SavedSearchData())
    val state = mutableState.asStateFlow()
    private val mutableChecking = MutableStateFlow(false)
    val checking = mutableChecking.asStateFlow()
    private val mutableUnreadable = MutableStateFlow(false)
    val unreadable = mutableUnreadable.asStateFlow()

    private fun read(): SavedSearchData = try {
        load().also { it.validate(); mutableUnreadable.value = false }
    } catch (error: Exception) {
        mutableUnreadable.value = true
        throw error
    }

    suspend fun refresh() = access { changes.withLock { mutableState.value = read() } }

    /** Called from the exclusive backup restore path, where taking a reader gate would deadlock. */
    suspend fun refreshAfterRestore() = changes.withLock { mutableState.value = read() }

    private suspend fun mutate(action: (SavedSearchData) -> SavedSearchData) = access {
        changes.withLock {
            val updated = action(read()).also { it.validate() }
            save(updated)
            mutableState.value = updated
        }
    }

    suspend fun add(name: String, criteria: SearchCriteria) = mutate { data ->
        require(data.searches.none { it.criteria == criteria })
        data.copy(searches = data.searches + WatchSearch(UUID.randomUUID().toString(), name.trim(), criteria))
    }

    suspend fun rename(id: String, name: String) = mutate { data ->
        data.copy(searches = data.searches.map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = mutate { data ->
        data.copy(searches = data.searches.map {
            if (it.id == id) it.copy(enabled = enabled, generation = it.generation + 1) else it
        })
    }

    suspend fun remove(id: String) = mutate { it.copy(searches = it.searches.filterNot { search -> search.id == id }) }

    suspend fun markRead(id: String, code: String? = null) = mutate { data ->
        data.copy(searches = data.searches.map { search ->
            if (search.id == id) search.copy(updates = search.updates.map {
                if (code == null || it.code == code) it.copy(read = true) else it
            }) else search
        })
    }

    /** Network work never owns the mutation lock: deleting/pausing remains available in flight. */
    suspend fun check(force: Boolean = false) {
        if (!checks.tryLock()) return
        mutableChecking.value = true
        try {
            refresh()
            val now = clock()
            val candidates = state.value.searches.filter { it.enabled &&
                (force || it.lastAttempt == 0L || now - it.lastAttempt >= 6 * 60 * 60 * 1000L) }
                .sortedBy { it.lastAttempt }.take(if (force) 50 else 10)
            candidates.forEachIndexed { index, snapshot ->
                if (index > 0) delay(300)
                val current = state.value.searches.firstOrNull { it.id == snapshot.id }
                if (current == null || !current.enabled || current.generation != snapshot.generation) return@forEachIndexed
                try {
                    val results = fetch(snapshot.criteria)
                    mutate { data -> data.copy(searches = data.searches.map {
                        if (it.matchesCheckSnapshot(snapshot))
                            it.record(results, clock()) else it
                    }) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutate { data -> data.copy(searches = data.searches.map {
                        if (it.matchesCheckSnapshot(snapshot))
                            it.copy(lastAttempt = clock(), failed = true) else it
                    }) }
                }
            }
        } finally {
            mutableChecking.value = false
            checks.unlock()
        }
    }
}
