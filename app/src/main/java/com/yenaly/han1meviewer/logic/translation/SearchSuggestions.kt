package com.yenaly.han1meviewer.logic.translation

enum class SuggestionSource { ALIAS, TRANSLATION, HISTORY, TAG, AUTHOR }
data class SuggestionTerm(val label: String, val names: List<String>, val key: String, val tag: Boolean)
data class SearchSuggestion(val label: String, val query: String, val source: SuggestionSource, val tagKey: String? = null, val original: String = query)

object SearchSuggestions {
    fun find(input: String, tools: SearchToolsData, histories: List<String>, terms: List<SuggestionTerm>): List<SearchSuggestion> {
        val needle = SearchTerms.normalize(input)
        if (needle.isBlank()) return emptyList()
        fun matches(values: List<String>) = values.any { SearchTerms.normalize(it).contains(needle) }
        return buildList {
            tools.aliases.filter { matches(listOf(it.original, it.translated)) }.forEach {
                add(SearchSuggestion("${it.original} → ${it.translated}", it.translated, SuggestionSource.ALIAS, original = it.original))
            }
            tools.history.filter { matches(listOf(it.original, it.translated)) }.forEach {
                add(SearchSuggestion("${it.original} → ${it.translated}", it.translated, SuggestionSource.TRANSLATION, original = it.original))
            }
            histories.filter { matches(listOf(it)) }.forEach { add(SearchSuggestion(it, it, SuggestionSource.HISTORY)) }
            terms.filter { matches(it.names) }.forEach {
                add(SearchSuggestion(it.label, it.key, if (it.tag) SuggestionSource.TAG else SuggestionSource.AUTHOR,
                    if (it.tag) it.key else null))
            }
        }.distinctBy { (it.tagKey != null) to SearchTerms.normalize(it.query) }
            .sortedWith(compareBy<SearchSuggestion>({ it.source.ordinal }, {
                val names = listOf(it.original, it.query, it.label).map(SearchTerms::normalize)
                when {
                    needle in names -> 0
                    names.any { name -> name.startsWith(needle) } -> 1
                    else -> 2
                }
            })).take(8)
    }
}
