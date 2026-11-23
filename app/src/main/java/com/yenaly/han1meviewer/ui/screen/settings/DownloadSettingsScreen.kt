package com.yenaly.han1meviewer.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.ui.component.SettingNavigationItem
import com.yenaly.han1meviewer.ui.component.SettingSliderItem
import com.yenaly.han1meviewer.ui.component.SettingSwitchItem
import com.yenaly.han1meviewer.ui.component.lazy.LazyColumn
import com.yenaly.han1meviewer.ui.preview.ComponentPreview

data class DownloadSettingsUiState(
    val downloadPathSummary: String,
    val downloadCountLimit: Int,
    val downloadCountLimitSummary: String,
    val downloadSpeedLimitIndex: Int,
    val downloadSpeedLimitSummary: String,
    val highPerformanceDownload: Boolean,
    val standardSample: String,
    val enhancedSample: String,
)

@Composable
fun DownloadSettingsScreen(
    state: DownloadSettingsUiState,
    maxDownloadCountLimit: Int,
    maxDownloadSpeedLimitIndex: Int,
    onOpenDownloadPath: () -> Unit,
    onRestoreDefaultPath: () -> Unit,
    onImportDownloadedFiles: () -> Unit,
    onDownloadCountLimitChange: (Int) -> Unit,
    onDownloadSpeedLimitChange: (Int) -> Unit,
    onHighPerformanceDownloadChange: (Boolean) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            SettingNavigationItem(
                title = stringResource(R.string.download_path),
                summary = state.downloadPathSummary,
                iconRes = R.drawable.baseline_path_24,
                onClick = onOpenDownloadPath,
            )
        }

        item {
            SettingNavigationItem(
                title = stringResource(R.string.pref_export_downloads_title),
                summary = stringResource(R.string.pref_export_downloads_summary),
                iconRes = R.drawable.baseline_export_24,
                onClick = onImportDownloadedFiles,
            )
        }

        item {
            SettingSliderItem(
                title = stringResource(R.string.download_count_limit),
                summary = state.downloadCountLimitSummary,
                value = state.downloadCountLimit,
                valueRange = 0..maxDownloadCountLimit,
                iconRes = R.drawable.baseline_count_24,
                onValueChange = onDownloadCountLimitChange,
            )
        }

        item {
            SettingSliderItem(
                title = stringResource(R.string.download_speed_limit),
                summary = state.downloadSpeedLimitSummary,
                value = state.downloadSpeedLimitIndex,
                valueRange = 0..maxDownloadSpeedLimitIndex,
                iconRes = R.drawable.baseline_speed2_24,
                onValueChange = onDownloadSpeedLimitChange,
            )
        }

        item {
            SettingSwitchItem(
                title = stringResource(R.string.download_high_performance),
                summary = stringResource(R.string.download_high_performance_summary),
                checked = state.highPerformanceDownload,
                iconRes = R.drawable.baseline_speed2_24,
                onCheckedChange = onHighPerformanceDownloadChange,
            )
        }

        item {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.download_performance_heading),
                    style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.download_performance_standard, state.standardSample))
                Text(stringResource(R.string.download_performance_enhanced, state.enhancedSample))
                Text(stringResource(R.string.download_performance_note),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DownloadSettingsScreenPreview() {
    ComponentPreview {
        DownloadSettingsScreen(
            state = DownloadSettingsUiState(
                downloadPathSummary = "/storage/emulated/0/Android/data/.../files",
                downloadCountLimit = 2,
                downloadCountLimitSummary = "2",
                downloadSpeedLimitIndex = 0,
                downloadSpeedLimitSummary = "无限制",
                highPerformanceDownload = false,
                standardSample = "尚无记录",
                enhancedSample = "尚无记录",
            ),
            maxDownloadCountLimit = 10,
            maxDownloadSpeedLimitIndex = 5,
            onOpenDownloadPath = {},
            onRestoreDefaultPath = {},
            onImportDownloadedFiles = {},
            onDownloadCountLimitChange = {},
            onDownloadSpeedLimitChange = {},
            onHighPerformanceDownloadChange = {},
        )
    }
}
