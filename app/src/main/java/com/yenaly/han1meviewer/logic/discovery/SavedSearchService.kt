package com.yenaly.han1meviewer.logic.discovery

import android.content.Context
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.Parser
import com.yenaly.han1meviewer.logic.network.ServiceCreator
import com.yenaly.han1meviewer.logic.network.interceptor.CloudflareInterceptor
import com.yenaly.han1meviewer.logic.network.interceptor.UrlLoggingInterceptor
import com.yenaly.han1meviewer.logic.network.service.HanimeBaseService
import com.yenaly.han1meviewer.logic.state.PageLoadingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

class SavedSearchStore(context: Context) {
    private val prefs = context.getSharedPreferences("saved_search_discovery", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    fun read(): SavedSearchData {
        val raw = prefs.getString("data", null) ?: return SavedSearchData()
        require(raw.length <= 4 * 1024 * 1024)
        return json.decodeFromString<SavedSearchData>(raw).also { it.validate() }
    }
    fun write(data: SavedSearchData) {
        data.validate()
        val raw = json.encodeToString(SavedSearchData.serializer(), data)
        require(raw.length <= 4 * 1024 * 1024)
        check(prefs.edit().putString("data", raw).commit())
    }
}

object SavedSearchService {
    @Volatile private var repository: SavedSearchRepository? = null
    fun get(context: Context): SavedSearchRepository = repository ?: synchronized(this) {
        repository ?: SavedSearchStore(context.applicationContext).let { store ->
            SavedSearchRepository(store::read, store::write, { fetch(it) }).also { repository = it }
        }
    }

    internal suspend fun fetch(
        criteria: SearchCriteria,
        currentBaseUrl: () -> String = { Preferences.baseUrl },
        sourceClient: OkHttpClient = ServiceCreator.hClient,
    ): List<WatchVideo> = withContext(Dispatchers.IO) {
        criteria.validate()
        check(criteria.baseUrl == currentBaseUrl()) // Keep each tracker bound to its original site.
        val client = sourceClient.newBuilder().apply {
            interceptors().removeAll { it is CloudflareInterceptor || it is UrlLoggingInterceptor }
        }.callTimeout(20, TimeUnit.SECONDS).cache(null)
            .followRedirects(false).followSslRedirects(false).build()
        val service = Retrofit.Builder().baseUrl(criteria.baseUrl).client(client).build()
            .create(HanimeBaseService::class.java)
        criteria.queries.flatMap { query ->
            check(criteria.baseUrl == currentBaseUrl())
            val response = service.getHanimeSearchResult(1, query, criteria.genre, criteria.sort,
                if (criteria.broad) "on" else null, criteria.date, criteria.duration, criteria.tags, criteria.brands)
            if (!response.isSuccessful) {
                response.errorBody()?.close()
                response.body()?.close()
                error("Search update unavailable")
            }
            val html = requireNotNull(response.body()).use { body ->
                val source = body.source()
                source.request(2 * 1024 * 1024L + 1)
                val bytes = source.buffer.readByteArray()
                require(bytes.size <= 2 * 1024 * 1024)
                bytes.toString(Charsets.UTF_8)
            }
            val document = Jsoup.parse(html)
            check(document.selectFirst(".content-padding-new, .home-rows-videos-wrapper") != null)
            when (val parsed = Parser.hanimeSearch(html)) {
                is PageLoadingState.Success -> parsed.info.map { WatchVideo(it.videoCode, it.title) }
                is PageLoadingState.NoMoreData -> emptyList()
                else -> error("Search update unavailable")
            }
        }.distinctBy { it.code }.also { check(criteria.baseUrl == currentBaseUrl()) }
    }
}
