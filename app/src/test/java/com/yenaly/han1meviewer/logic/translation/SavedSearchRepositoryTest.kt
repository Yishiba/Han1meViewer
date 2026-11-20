package com.yenaly.han1meviewer.logic.translation

import com.yenaly.han1meviewer.logic.discovery.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SavedSearchRepositoryTest {
    private val criteria = SearchCriteria("https://example.com/", listOf("term"))

    @Test fun failuresRetainBaselineUpdatesAndReadStatusAndCanBeRetried() = runBlocking {
        var data = SavedSearchData()
        var now = 100L
        var result = listOf(WatchVideo("1", "one"))
        var fail = false
        val repo = SavedSearchRepository({ data }, { data = it }, { if (fail) error("offline") else result }, { now++ })
        repo.add("name", criteria)
        repo.check(true)
        result = listOf(WatchVideo("2", "two"))
        repo.check(true)
        val id = data.searches.single().id
        repo.markRead(id)
        val old = data.searches.single()
        fail = true
        repo.check(true)
        val failed = data.searches.single()
        assertTrue(failed.failed)
        assertEquals(old.updates, failed.updates)
        assertEquals(old.seen, failed.seen)
        assertEquals(old.lastSuccess, failed.lastSuccess)
        fail = false
        result = listOf(WatchVideo("3", "three"))
        repo.check(true)
        assertFalse(data.searches.single().failed)
        assertEquals(1, data.searches.single().unreadCount)
    }

    @Test fun deletingDuringCheckNeverResurrectsTracker() = runBlocking {
        var data = SavedSearchData()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val repo = SavedSearchRepository({ data }, { data = it }, { entered.complete(Unit); finish.await(); emptyList() })
        repo.add("name", criteria)
        val job = launch { repo.check(true) }
        entered.await()
        repo.remove(data.searches.single().id)
        finish.complete(Unit)
        job.join()
        assertTrue(data.searches.isEmpty())
    }

    @Test fun pauseThenResumeDuringCheckDiscardsTheOldRequest() = runBlocking {
        var data = SavedSearchData()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val repo = SavedSearchRepository({ data }, { data = it }, { entered.complete(Unit); finish.await(); listOf(WatchVideo("1", "one")) })
        repo.add("name", criteria)
        val job = launch { repo.check(true) }
        entered.await()
        val id = data.searches.single().id
        repo.setEnabled(id, false)
        repo.setEnabled(id, true)
        finish.complete(Unit)
        job.join()
        assertFalse(data.searches.single().initialized)
        assertTrue(data.searches.single().seen.isEmpty())
    }

    @Test fun backgroundCooldownAndPauseAvoidUnnecessaryRequests() = runBlocking {
        var data = SavedSearchData()
        var now = 100L
        var calls = 0
        val repo = SavedSearchRepository({ data }, { data = it }, { calls++; emptyList() }, { now })
        repo.add("name", criteria)
        repo.check()
        now += 1000
        repo.check()
        assertEquals(1, calls)
        now += 6 * 60 * 60 * 1000L
        repo.check()
        assertEquals(2, calls)
        repo.setEnabled(data.searches.single().id, false)
        repo.check(true)
        assertEquals(2, calls)
    }

    @Test fun corruptStorageCannotBeOverwritten() = runBlocking {
        var writes = 0
        val repo = SavedSearchRepository({ error("corrupt") }, { writes++ }, { emptyList() })
        try { repo.refresh(); fail() } catch (_: IllegalStateException) { }
        try { repo.add("name", criteria); fail() } catch (_: IllegalStateException) { }
        assertTrue(repo.unreadable.value)
        assertEquals(0, writes)
    }

    @Test fun cancellationDoesNotBecomeAFailedCheckAndReleasesBusyState() = runBlocking {
        var data = SavedSearchData()
        val entered = CompletableDeferred<Unit>()
        val repo = SavedSearchRepository({ data }, { data = it }, { entered.complete(Unit); awaitCancellation() })
        repo.add("name", criteria)
        val job = launch { repo.check(true) }
        entered.await()
        job.cancelAndJoin()
        assertFalse(repo.checking.value)
        assertFalse(data.searches.single().failed)
        assertEquals(0L, data.searches.single().lastSuccess)
    }

    @Test fun renameAndReadChangesArePreservedWhenARequestFinishes() = runBlocking {
        var data = SavedSearchData(searches = listOf(WatchSearch("id", "before", criteria, initialized = true,
            seen = setOf("1"), updates = listOf(WatchVideo("1", "one")))))
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val repo = SavedSearchRepository({ data }, { data = it }, { entered.complete(Unit); finish.await(); listOf(WatchVideo("2", "two")) })
        val job = launch { repo.check(true) }
        entered.await()
        repo.rename("id", "after")
        repo.markRead("id", "1")
        finish.complete(Unit)
        job.join()
        assertEquals("after", data.searches.single().name)
        assertTrue(data.searches.single().updates.first { it.code == "1" }.read)
        assertEquals(1, data.searches.single().unreadCount)
    }

    @Test fun duplicateCriteriaAndInvalidNamesDoNotWrite() = runBlocking {
        var data = SavedSearchData()
        val repo = SavedSearchRepository({ data }, { data = it }, { emptyList() })
        repo.add("name", criteria)
        try { repo.add("second", criteria); fail() } catch (_: IllegalArgumentException) { }
        try { repo.rename(data.searches.single().id, " "); fail() } catch (_: IllegalArgumentException) { }
        assertEquals("name", data.searches.single().name)
        assertEquals(1, data.searches.size)
    }

    @Test fun unsafeCriteriaAndVideoIdsAreRejected() {
        listOf("http://example.com/", "https://user:pass@example.com/", "https://example.com/?private=query").forEach {
            try { criteria.copy(baseUrl = it).validate(); fail() } catch (_: IllegalArgumentException) { }
        }
        try { WatchSearch("id", "name", criteria).record(listOf(WatchVideo("../../file", "name")), 1); fail() }
        catch (_: IllegalArgumentException) { }
    }
}
