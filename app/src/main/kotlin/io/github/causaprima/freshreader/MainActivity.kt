package io.github.causaprima.freshreader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.causaprima.freshreader.core.Article
import io.github.causaprima.freshreader.core.FeedSettings
import io.github.causaprima.freshreader.core.Outcome
import io.github.causaprima.freshreader.core.Section
import io.github.causaprima.freshreader.core.SyncStatus
import kotlinx.coroutines.flow.first
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FreshReaderTheme {
                val screen by viewModel.screen.collectAsStateWithLifecycle()
                val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
                // Lives outside the screens so that the list keeps its position while an Article is open.
                val listState = rememberLazyListState()
                when (val s = screen) {
                    Screen.Loading -> Centered { CircularProgressIndicator() }
                    is Screen.Login -> LoginScreen(s, viewModel::logIn)
                    is Screen.Failed -> FailedScreen(s.outcome, viewModel::retry)
                    is Screen.Articles -> ArticleListScreen(
                        s, syncStatus, listState,
                        ListActions(
                            onOpenArticle = viewModel::openArticle,
                            onOpenReadingOrder = viewModel::openReadingOrder,
                            onRefresh = viewModel::pullToRefresh,
                            onRefreshErrorShown = viewModel::dismissRefreshError,
                            onMarkAllAsRead = viewModel::markAllAsRead,
                        ),
                    )
                    is Screen.ArticleView -> ArticleViewScreen(
                        s, viewModel::articleDocument,
                        ArticleViewActions(
                            onShow = viewModel::showArticle,
                            onClose = viewModel::closeArticle,
                            onOpenFeedSettings = viewModel::openFeedSettings,
                            onChangeFeedSettings = viewModel::setFeedSettings,
                            onCloseFeedSettings = viewModel::closeFeedSettings,
                        ),
                    )
                    is Screen.ReadingOrder -> ReadingOrderScreen(
                        s.categories, viewModel::setReadingOrder, viewModel::closeReadingOrder,
                    )
                }
            }
        }
    }
}

/** Follows the system light/dark setting, with the device's dynamic colours (Android 12+). */
@Composable
private fun FreshReaderTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun outcomeMessage(outcome: Outcome): String = when (outcome) {
    Outcome.Ok -> ""
    Outcome.WrongCredentials -> stringResource(R.string.error_wrong_credentials)
    Outcome.InvalidServerUrl -> stringResource(R.string.error_invalid_url)
    is Outcome.Unreachable -> stringResource(R.string.error_unreachable, outcome.reason)
    is Outcome.ServerError -> stringResource(R.string.error_server, outcome.httpStatus)
}

@Composable
private fun LoginScreen(state: Screen.Login, onLogIn: (String, String, String) -> Unit) {
    var serverUrl by rememberSaveable { mutableStateOf("https://") }
    var username by rememberSaveable { mutableStateOf("") }
    var apiPassword by rememberSaveable { mutableStateOf("") }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = serverUrl, onValueChange = { serverUrl = it },
                label = { Text(stringResource(R.string.login_server_url)) },
                singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text(stringResource(R.string.login_username)) },
                singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiPassword, onValueChange = { apiPassword = it },
                label = { Text(stringResource(R.string.login_api_password)) },
                supportingText = { Text(stringResource(R.string.login_api_password_hint)) },
                singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            state.error?.let {
                Text(outcomeMessage(it), color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = { onLogIn(serverUrl, username, apiPassword) },
                enabled = !state.busy && username.isNotBlank() && apiPassword.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.busy) CircularProgressIndicator(Modifier.padding(2.dp)) else Text(stringResource(R.string.login_button))
            }
        }
    }
}

@Composable
private fun FailedScreen(outcome: Outcome, onRetry: () -> Unit) {
    Centered {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(outcomeMessage(outcome), color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

/** Read Articles stay in the list until the next refresh, greyed out. */
private const val READ_ALPHA = 0.45f

private val dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

/** What the reader can do on the list screen. */
private class ListActions(
    val onOpenArticle: (Article) -> Unit,
    val onOpenReadingOrder: () -> Unit,
    val onRefresh: () -> Unit,
    val onRefreshErrorShown: () -> Unit,
    val onMarkAllAsRead: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArticleListScreen(
    list: Screen.Articles,
    syncStatus: SyncStatus,
    listState: LazyListState,
    actions: ListActions,
) {
    val sections = list.sections
    val total = sections.sumOf { it.unreadCount }
    LaunchedEffect(list.focus) { if (list.focus != null) bringIntoView(listState, sections, list.focus) }
    val snackbars = remember { SnackbarHostState() }
    val refreshError = list.refreshError?.let { outcomeMessage(it) }
    LaunchedEffect(list.refreshError) {
        if (refreshError != null) {
            snackbars.showSnackbar(refreshError)
            actions.onRefreshErrorShown()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.list_title, total)) },
                actions = {
                    if (syncStatus is SyncStatus.Failed) SyncFailedHint(syncStatus)
                    IconButton(onClick = actions.onOpenReadingOrder) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.reading_order_title))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = list.refreshing,
            onRefresh = actions.onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                if (sections.isEmpty()) {
                    item(key = "empty") {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.list_empty))
                        }
                    }
                }
                sections.forEach { section ->
                    stickyHeader(key = "category:${section.category}") { SectionHeader(section) }
                    items(section.articles, key = { it.id }) { article -> ArticleEntry(article, actions.onOpenArticle) }
                }
                if (total > 0) {
                    item(key = "mark-all") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            OutlinedButton(onClick = actions.onMarkAllAsRead) { Text(stringResource(R.string.mark_all_as_read)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Scrolls the list so that the Article with id [articleId] is fully visible below the sticky Category
 * header, unless it already is. Leaves the list alone when the reader comes back to where they were.
 */
private suspend fun bringIntoView(listState: LazyListState, sections: List<Section>, articleId: String) {
    // Lazy list indices count one header per section.
    var index = -1
    var offset = 0
    for (section in sections) {
        val position = section.articles.indexOfFirst { it.id == articleId }
        if (position >= 0) {
            index = offset + 1 + position
            break
        }
        offset += 1 + section.articles.size
    }
    if (index < 0) return
    val layout = snapshotFlow { listState.layoutInfo }.first { it.visibleItemsInfo.isNotEmpty() }
    val header = layout.visibleItemsInfo.first().takeIf { (it.key as? String)?.startsWith("category:") == true }?.size ?: 0
    val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
    if (item != null && item.offset >= header && item.offset + item.size <= layout.viewportEndOffset) return
    // The item before it ends up under the sticky header, or is that header itself.
    listState.scrollToItem(maxOf(index - 1, 0))
}

/** The subtle hint that read marks permanently failed to reach the Server; tapping it explains why. */
@Composable
private fun SyncFailedHint(status: SyncStatus.Failed) {
    var explained by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { explained = true }) {
        Icon(
            painterResource(R.drawable.ic_cloud_off), stringResource(R.string.sync_failed_title),
            tint = MaterialTheme.colorScheme.error,
        )
    }
    if (explained) {
        AlertDialog(
            onDismissRequest = { explained = false },
            confirmButton = { TextButton(onClick = { explained = false }) { Text(stringResource(android.R.string.ok)) } },
            title = { Text(stringResource(R.string.sync_failed_title)) },
            text = {
                Text(pluralStringResource(R.plurals.sync_failed_message, status.count, status.count, outcomeMessage(status.reason)))
            },
        )
    }
}

@Composable
private fun SectionHeader(section: Section) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                section.category, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
            )
            Text(
                section.unreadCount.toString(), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ArticleEntry(article: Article, onOpen: (Article) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clickable { onOpen(article) }
            .alpha(if (article.read) READ_ALPHA else 1f)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(article.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(
            "${article.feedTitle} · ${dateFormat.format(Instant.ofEpochSecond(article.published))}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

/**
 * Settings screen for the Reading Order. The reader drags Categories by their handle; every drop is
 * stored at once, so the list follows the new order immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingOrderScreen(categories: List<String>, onReorder: (List<String>) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val haptics = LocalHapticFeedback.current
    // Follows the finger during a drag; handed to onReorder on drop.
    var order by remember(categories) { mutableStateOf(categories) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        // Lazy list indices count the hint above the Categories, so locate the moved items by key.
        order = order.toMutableList().apply {
            val fromIndex = indexOf(from.key)
            val toIndex = indexOf(to.key)
            add(toIndex, removeAt(fromIndex))
        }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reading_order_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState) {
            item(key = "hint") {
                Text(
                    stringResource(R.string.reading_order_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(order, key = { it }) { category ->
                ReorderableItem(reorderState, key = category) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 6.dp else 0.dp)
                    Surface(shadowElevation = elevation) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(category, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = {},
                                modifier = Modifier.draggableHandle(
                                    onDragStarted = {
                                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                        onReorder(order)
                                    },
                                ),
                            ) {
                                Icon(painterResource(R.drawable.ic_drag_handle), stringResource(R.string.reading_order_drag, category))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What the reader can do in the Article View. */
private class ArticleViewActions(
    val onShow: (Int) -> Unit,
    val onClose: () -> Unit,
    val onOpenFeedSettings: () -> Unit,
    val onChangeFeedSettings: (FeedSettings) -> Unit,
    val onCloseFeedSettings: () -> Unit,
)

/**
 * The Article View: a horizontal pager over the list snapshot. Arriving at an Article by swiping counts
 * as opening it. Each page shows the Core's prepared document in a WebView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArticleViewScreen(view: Screen.ArticleView, document: (Article) -> String, actions: ArticleViewActions) {
    BackHandler(onBack = actions.onClose)
    val pagerState = rememberPagerState(initialPage = view.position) { view.articles.size }
    LaunchedEffect(pagerState) { snapshotFlow { pagerState.settledPage }.collect { actions.onShow(it) } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = actions.onClose) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
                actions = { ArticleViewMenu(actions.onOpenFeedSettings) },
            )
        },
    ) { padding ->
        HorizontalPager(
            pagerState,
            Modifier.fillMaxSize().padding(padding),
            beyondViewportPageCount = 1, // the neighbours are ready when the swipe starts
            key = { view.articles[it].id },
        ) { page ->
            val article = view.articles[page]
            ArticleWebView(article, remember(article.id, view.revision) { document(article) })
        }
    }
    view.feedSettings?.let { FeedSettingsBottomSheet(it, actions.onChangeFeedSettings, actions.onCloseFeedSettings) }
}

@Composable
private fun ArticleViewMenu(onOpenFeedSettings: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feed_settings_open)) },
                onClick = {
                    expanded = false
                    onOpenFeedSettings()
                },
            )
        }
    }
}

/** The Feed Settings of the shown Article's Feed; each change applies at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedSettingsBottomSheet(sheet: FeedSettingsSheet, onChange: (FeedSettings) -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Text(
            stringResource(R.string.feed_settings_title, sheet.feedTitle),
            Modifier.padding(horizontal = 24.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.bonus_text)) },
            supportingContent = { Text(stringResource(R.string.bonus_text_hint)) },
            trailingContent = {
                Switch(checked = sheet.settings.bonusText, onCheckedChange = null) // the whole row toggles
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 16.dp)
                .toggleable(
                    value = sheet.settings.bonusText,
                    role = Role.Switch,
                    onValueChange = { onChange(sheet.settings.copy(bonusText = it)) },
                ),
        )
    }
}

/** One Article's document in a WebView. JavaScript stays off, zoom is disabled, and links open in the browser. */
@Composable
private fun ArticleWebView(article: Article, document: String) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.apply {
                    javaScriptEnabled = false
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    allowFileAccess = false
                    allowContentAccess = false
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        try {
                            view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        } catch (_: ActivityNotFoundException) {
                            // No app can open this link; stay on the Article.
                        }
                        return true
                    }
                }
            }
        },
        // Feed Settings changes prepare the document again; it is reloaded only then.
        update = { webView ->
            if (webView.tag != document) {
                webView.tag = document
                webView.loadDataWithBaseURL(article.url, document, "text/html", "utf-8", null)
            }
        },
    )
}
