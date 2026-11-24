package com.yenaly.han1meviewer.ui.screen.home.homepage

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.state.PageState
import com.yenaly.han1meviewer.logic.network.HomeNetworkDiagnostics
import com.yenaly.han1meviewer.logic.state.dataOrNull
import com.yenaly.han1meviewer.ui.component.PageContent
import com.yenaly.han1meviewer.ui.component.PullRefreshOverlay
import com.yenaly.han1meviewer.ui.component.isFirstPageEmpty
import com.yenaly.han1meviewer.ui.component.isFirstPageError
import com.yenaly.han1meviewer.ui.component.isFirstPageLoading
import com.yenaly.han1meviewer.ui.screen.home.homepage.component.HomePageTopBar
import com.yenaly.han1meviewer.ui.screen.rememberRandomLoadingHint
import com.yenaly.han1meviewer.util.toNetworkErrorMessageRes
import java.text.DateFormat
import java.util.Date

/**
 * 首页容器屏幕，负责连接 ViewModel 状态与导航回调。
 *
 * @param viewModel 提供首页数据与公告数据的 ViewModel。
 * @param isDrawerOpen 侧边抽屉是否已打开。
 * @param modifier 作用于屏幕根布局的修饰符。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomePageScreen(
    viewModel: HomePageViewModel,
    isDrawerOpen: Boolean,
    onEvent: (HomeUiEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pageState by viewModel.homePageFlow.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val cachedAt by viewModel.cachedAt.collectAsStateWithLifecycle()
    val refreshState = rememberPullToRefreshState()
    var wasRefreshing by remember { mutableStateOf(false) }
    val loadingHint = rememberRandomLoadingHint()
    LaunchedEffect(Unit) {
        if (pageState !is PageState.Success) {
            viewModel.getHomePage(isRefresh = false)
        }
    }

    BackHandler(enabled = !isDrawerOpen) {
        onEvent(HomeUiEvent.ShowExitDialog)
    }

    val isCurrentlyRefreshing = (pageState as? PageState.Success)?.isRefreshing == true

    LaunchedEffect(pageState) {
        val errorState = pageState as? PageState.Error
        if (wasRefreshing && errorState?.cachedInfo != null) {
            Toast.makeText(
                context,
                errorState.throwable.toNetworkErrorMessageRes(),
                Toast.LENGTH_SHORT
            ).show()
        }
        wasRefreshing = isCurrentlyRefreshing
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HomePageTopBar(
                onOpenDrawer = { onEvent(HomeUiEvent.OpenDrawer) },
                onSearchClick = { onEvent(HomeUiEvent.OpenSearchPage()) },
                onNavigateToPreview = { onEvent(HomeUiEvent.NavigateToPreview) }
            )
            val savedAt = cachedAt
            if (savedAt != null && pageState.dataOrNull != null) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = stringResource(
                                if (pageState is PageState.Error) R.string.home_cached_failed
                                else R.string.home_cached_notice,
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                    .format(Date(savedAt)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (pageState is PageState.Error) {
                            TextButton(onClick = { viewModel.getHomePage(isRefresh = true) }) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pullToRefresh(
                        state = refreshState,
                        isRefreshing = isCurrentlyRefreshing,
                        onRefresh = {
                            viewModel.getHomePage(isRefresh = true)
                        }
                    )
            ) {
                PageContent(
                    isLoading = pageState.isFirstPageLoading,
                    isError = pageState.isFirstPageError,
                    isEmpty = pageState.isFirstPageError || pageState.isFirstPageEmpty,
                    onRetry = { viewModel.getHomePage(isRefresh = false) },
                    loadingMessage = loadingHint,
                    error = {
                        HomePageFailure(
                            message = (pageState as? PageState.Error)?.throwable
                                ?.toNetworkErrorMessageRes()?.let { stringResource(it) }.orEmpty(),
                            diagnostics = diagnostics,
                            failureStage = HomeNetworkDiagnostics.failureStage(),
                            onRetry = { viewModel.getHomePage(isRefresh = false) },
                            onNetworkSettings = { onEvent(HomeUiEvent.OpenNetworkSettings) },
                        )
                    },
                ) {
                    val homeData = pageState.dataOrNull

                    if (homeData != null) {
                        AnimatedContent(
                            targetState = homeData.page,
                            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                            label = "HomeContentAnimation"
                        ) { page ->
                            HomePageContent(
                                data = HomeData(page = page, announcements = homeData.announcements),
                                onEvent = onEvent,
                                onCloseAnnouncement = viewModel::dismissAnnouncements,
                            )
                        }
                    }
                }

                PullRefreshOverlay(
                    state = refreshState,
                    isRefreshing = isCurrentlyRefreshing,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            }
        }
    }
}

@Composable
private fun HomePageFailure(
    message: String,
    diagnostics: String,
    failureStage: HomeNetworkDiagnostics.FailureStage,
    onRetry: () -> Unit,
    onNetworkSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.h_chan_sad),
            contentDescription = null,
            modifier = Modifier.size(80.dp),
        )
        Text(
            text = stringResource(R.string.load_failed_retry),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        if (diagnostics.isNotBlank()) {
            val suggestion = when (failureStage) {
                HomeNetworkDiagnostics.FailureStage.DNS -> R.string.home_failure_dns_hint
                HomeNetworkDiagnostics.FailureStage.CONNECT, HomeNetworkDiagnostics.FailureStage.TLS -> R.string.home_failure_connect_hint
                HomeNetworkDiagnostics.FailureStage.HTTP -> R.string.home_failure_http_hint
                HomeNetworkDiagnostics.FailureStage.UNKNOWN -> R.string.home_failure_unknown_hint
            }
            Text(text = stringResource(suggestion), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onNetworkSettings) { Text(stringResource(R.string.network_settings)) }
        }
        if (diagnostics.isNotBlank()) {
            Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 2.dp) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.home_diagnostic_title), style = MaterialTheme.typography.titleSmall)
                    SelectionContainer {
                        Text(
                            text = diagnostics,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
