package com.yenaly.han1meviewer.ui.viewmodel

import android.app.Application
import android.os.Parcelable
import android.util.Log
import android.util.SparseArray
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.yenaly.han1meviewer.HanimeConstants.HANIME_URL
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.DatabaseRepo
import com.yenaly.han1meviewer.logic.DatabaseRepo.HanimeAdvancedSearchRepo.toSearchOptionSet
import com.yenaly.han1meviewer.logic.NetworkRepo
import com.yenaly.han1meviewer.logic.entity.HanimeAdvancedSearchHistoryEntity
import com.yenaly.han1meviewer.logic.entity.SearchHistoryEntity
import com.yenaly.han1meviewer.logic.discovery.SearchCriteria
import com.yenaly.han1meviewer.logic.model.HanimeInfo
import com.yenaly.han1meviewer.logic.model.SearchOption
import com.yenaly.han1meviewer.logic.state.PageLoadingState
import com.yenaly.han1meviewer.util.loadAssetAs
import com.yenaly.yenaly_libs.base.YenalyViewModel
import com.yenaly.yenaly_libs.utils.unsafeLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/13 013 22:29
 */
class SearchViewModel(
    application: Application,
    private val state: SavedStateHandle
) : YenalyViewModel(application) {

    var page: Int = 1
    var query: String?
        get() = state["query"]
        set(value) { state["query"] = value }

    var genre: String?
        get() = state["genre"]
        set(value) { state["genre"] = value }

    var sort: String?
        get() = state["sort"]
        set(value) { state["sort"] = value }

    var year: Int?
        get() = state["year"]
        set(value) { state["year"] = value }

    var month: Int?
        get() = state["month"]
        set(value) { state["month"] = value }

    var approxTime: String?
        get() = state["approxTime"]
        set(value) { state["approxTime"] = value }

    var broad: Boolean
        get() = state["broad"] ?: false
        set(value) { state["broad"] = value }

    var duration: String?
        get() = state["duration"]
        set(value) { state["duration"] = value }

    var gridFirstVisibleItemIndex: Int
        get() = state["gridFirstVisibleItemIndex"] ?: 0
        set(value) {
            state["gridFirstVisibleItemIndex"] = value
        }

    var gridFirstVisibleItemScrollOffset: Int
        get() = state["gridFirstVisibleItemScrollOffset"] ?: 0
        set(value) {
            state["gridFirstVisibleItemScrollOffset"] = value
        }

    var tagMap = SparseArray<Set<SearchOption>>()
    var brandMap = SparseArray<Set<SearchOption>>()

    val genres by unsafeLazy {
        loadAssetAs<List<SearchOption>>(if (Preferences.baseUrl == HANIME_URL[3]) "search_options/genre_av.json" else "search_options/genre.json").orEmpty()
    }

    val tags by unsafeLazy {
        loadAssetAs<Map<String, List<SearchOption>>>("search_options/tags.json").orEmpty()
    }

    val brands by unsafeLazy {
        loadAssetAs<List<SearchOption>>("search_options/brands.json").orEmpty()
    }

    val sortOptions by unsafeLazy {
        loadAssetAs<List<SearchOption>>("search_options/sort_option.json").orEmpty()
    }

    val durations by unsafeLazy {
        loadAssetAs<List<SearchOption>>("search_options/duration.json").orEmpty()
    }
    val timeList by unsafeLazy {
        loadAssetAs<List<SearchOption>>("search_options/release_date.json").orEmpty()
    }

    private val _searchStateFlow =
        MutableStateFlow<PageLoadingState<List<HanimeInfo>>>(PageLoadingState.Loading)
    val searchStateFlow = _searchStateFlow.asStateFlow()

    private val _searchFlow = MutableStateFlow(emptyList<HanimeInfo>())
    val searchFlow = _searchFlow.asStateFlow()
    var recyclerViewState: Parcelable? = null

    var mergedQueries: List<String>
        get() = state["mergedQueries"] ?: emptyList()
        set(value) { state["mergedQueries"] = ArrayList(value) }
    private var requestJob: Job? = null
    private val paging = com.yenaly.han1meviewer.logic.translation.SearchPaging()
    val partialFailure = MutableStateFlow(false)
    var lastSuccessfulCriteria: SearchCriteria? = null
        private set

    fun clearHanimeSearchResult() {
        requestJob?.cancel()
        paging.reset(); partialFailure.value = false
        lastSuccessfulCriteria = null
        _searchFlow.value = emptyList()
        _searchStateFlow.value = PageLoadingState.Loading
    }

    fun resetSearchUiState() {
        clearHanimeSearchResult()
        mergedQueries = emptyList()
        page = 1
        query = null
        genre = null
        sort = null
        year = null
        month = null
        approxTime = null
        broad = false
        duration = null
        tagMap.clear()
        brandMap.clear()
        recyclerViewState = null
        gridFirstVisibleItemIndex = 0
        gridFirstVisibleItemScrollOffset = 0
        _searchFlow.value = emptyList()
        _searchStateFlow.value = PageLoadingState.Loading
    }

    fun getHanimeSearchResult(
        page: Int, query: String?, genre: String?,
        sort: String?, broad: Boolean, date: String?,
        duration: String?, tags: Set<String>, brands: Set<String>,
    ) {
        if (requestJob?.isActive == true) return
        if (mergedQueries.lastOrNull() != query) mergedQueries = emptyList()
        val queries = mergedQueries.ifEmpty { listOf(query) }.distinct()
        val criteria = SearchCriteria(Preferences.baseUrl, queries, genre, sort, broad, date,
            duration, tags.toSet(), brands.toSet())
        requestJob = viewModelScope.launch {
            _searchStateFlow.value = PageLoadingState.Loading
            var failure: Throwable? = null
            var succeeded = false
            val incoming = mutableListOf<HanimeInfo>()
            for (term in paging.pending(queries)) {
                val nextPage = paging.page(term)
                val result = NetworkRepo.getHanimeSearchResult(nextPage, term, genre, sort, broad, date, duration, tags, brands).last()
                ensureActive()
                when (result) {
                    is PageLoadingState.Success -> {
                        incoming += result.info
                        paging.success(term)
                        succeeded = true
                    }
                    is PageLoadingState.NoMoreData -> { paging.end(term); succeeded = true }
                    is PageLoadingState.Error -> { failure = result.throwable; paging.fail(term) }
                    else -> Unit
                }
            }
            val decorated = if (Preferences.showPlayedIndicator && incoming.isNotEmpty()) {
                val watched = withContext(Dispatchers.IO) { DatabaseRepo.WatchHistory.getWatched(incoming.map { it.videoCode }).toSet() }
                incoming.map { it.copy(watched = it.videoCode in watched) }
            } else incoming
            _searchFlow.value = (_searchFlow.value + decorated).distinctBy { it.videoCode }
            partialFailure.value = failure != null && (succeeded || _searchFlow.value.isNotEmpty())
            if (failure == null && succeeded) lastSuccessfulCriteria = criteria
            _searchStateFlow.value = when {
                failure != null && !succeeded -> PageLoadingState.Error(failure)
                paging.complete(queries) -> PageLoadingState.NoMoreData
                else -> PageLoadingState.Success(_searchFlow.value)
            }
        }
    }

    fun insertSearchHistory(history: SearchHistoryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.SearchHistory.insert(history)
            Log.d("insert_search_hty", "$history DONE!")
        }
    }

    fun getSearchDate(): String? {
        return when {
            approxTime != null -> approxTime
            year != null -> listOfNotNull(
                year?.let { "$it 年" },
                month?.let { "$it 月" }
            ).joinToString(" ")
            else -> null
        }
    }

    fun insertAdvancedSearchHistory(
        query: String?, genre: String?,
        sort: String?, broad: Boolean, date: String?,
        duration: String?, tags: Set<SearchOption>, brands: Set<SearchOption>,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val histories = DatabaseRepo.HanimeAdvancedSearchRepo.getSearchHistories(limit = 10)
                .first()

            val isDuplicate = histories.any { history ->
                history.query == query &&
                        history.genre == genre &&
                        history.sort == sort &&
                        history.broad == broad &&
                        history.date == date &&
                        history.duration == duration &&
                        history.tags?.toSearchOptionSet() == tags &&
                        history.brands?.toSearchOptionSet() == brands
            }

            if (!isDuplicate) {
                DatabaseRepo.HanimeAdvancedSearchRepo.saveSearch(
                    query = query,
                    genre = genre,
                    sort = sort,
                    broad = broad,
                    date = date,
                    duration = duration,
                    tags = tags,
                    brands = brands,
                )
                return@launch
            }
            Log.i("insertAdvancedSearchHistory","记录重复！")

        }
    }

    fun deleteSearchHistory(history: SearchHistoryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.SearchHistory.delete(history)
            Log.d("delete_search_hty", "$history DONE!")
        }
    }

    @JvmOverloads
    fun loadAllSearchHistories(keyword: String? = null) =
        DatabaseRepo.SearchHistory.loadAll(keyword).flowOn(Dispatchers.IO)

    fun deleteSearchHistoryByKeyword(query: String) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.SearchHistory.deleteByKeyword(query)
            Log.d("delete_search_hty", "$query DONE!")
        }
    }
    val refreshTriggerFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    fun triggerNewSearch() {
        page = 1
        clearHanimeSearchResult()
        refreshTriggerFlow.tryEmit(Unit)
    }

    fun restoreSavedSearch(criteria: SearchCriteria) {
        criteria.validate()
        check(criteria.baseUrl == Preferences.baseUrl)
        query = criteria.queries.last()
        mergedQueries = if (criteria.queries.size > 1) criteria.queries.filterNotNull() else emptyList()
        genre = criteria.genre
        sort = criteria.sort
        broad = criteria.broad
        duration = criteria.duration
        approxTime = criteria.date
        year = null
        month = null
        tagMap.clear()
        brandMap.clear()
        val allTags = tags.values.flatten()
        tagMap.put(0, criteria.tags.map { key -> allTags.firstOrNull { it.searchKey == key }
            ?: SearchOption(name = key, searchKey = key) }.toSet())
        brandMap.put(0, criteria.brands.map { key -> brands.firstOrNull { it.searchKey == key }
            ?: SearchOption(name = key, searchKey = key) }.toSet())
        triggerNewSearch()
    }
    fun restoreSearchMap(history: HanimeAdvancedSearchHistoryEntity) {
        mergedQueries = emptyList()
        with(this) {
            page = 1
            query = history.query
            genre = history.genre
            sort = history.sort
            broad = history.broad == true
            duration = history.duration

            restoreDate(this, history.date)

            tagMap.clear()
            brandMap.clear()

            history.tags?.takeIf { it.isNotBlank() }?.let { tagsString ->
                val tagOptions = tagsString.toSearchOptionSet()
                tagMap.put(0, tagOptions)
            }

            history.brands?.takeIf { it.isNotBlank() }?.let { brandsString ->
                val brandOptions = brandsString.toSearchOptionSet()
                brandMap.put(0, brandOptions)
            }
        }
    }
    private fun restoreDate(viewModel: SearchViewModel, date: String?) {
        if (date.isNullOrBlank()) {
            viewModel.year = null
            viewModel.month = null
            viewModel.approxTime = null
            return
        }

        if (date.contains("過去")) {
            viewModel.approxTime = date
            viewModel.year = null
            viewModel.month = null
        } else {
            viewModel.approxTime = null
            val regex = """(\d+)\s*年(?:\s*(\d+)\s*月)?""".toRegex()
            val match = regex.find(date)
            if (match != null) {
                val (y, m) = match.destructured
                viewModel.year = y.toIntOrNull()
                viewModel.month = m.toIntOrNull()
            } else {
                viewModel.year = null
                viewModel.month = null
            }
        }
    }
}
