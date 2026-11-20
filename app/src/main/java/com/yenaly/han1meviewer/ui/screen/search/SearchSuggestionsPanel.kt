package com.yenaly.han1meviewer.ui.screen.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.translation.SearchSuggestion
import com.yenaly.han1meviewer.logic.translation.SuggestionSource

@Composable
fun SearchSuggestionsPanel(suggestions: List<SearchSuggestion>, onSelect: (SearchSuggestion) -> Unit) {
    if (suggestions.isEmpty()) return
    Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.discovery_suggestions), Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium)
        suggestions.forEach { suggestion ->
            Row(Modifier.fillMaxWidth().clickable { onSelect(suggestion) }.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(suggestion.label, Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(when (suggestion.source) {
                    SuggestionSource.ALIAS -> R.string.discovery_source_alias
                    SuggestionSource.TRANSLATION -> R.string.discovery_source_translation
                    SuggestionSource.HISTORY -> R.string.discovery_source_history
                    SuggestionSource.TAG -> R.string.discovery_source_tag
                    SuggestionSource.AUTHOR -> R.string.discovery_source_author
                }), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
