package io.github.causaprima.freshreader

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.causaprima.freshreader.core.Article
import io.github.causaprima.freshreader.core.FeedSettings
import io.github.causaprima.freshreader.core.Outcome
import io.github.causaprima.freshreader.core.ReaderView
import io.github.causaprima.freshreader.core.Section
import io.github.causaprima.freshreader.core.SyncStatus
import io.github.causaprima.freshreader.core.ViewMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onStop() {
        super.onStop()
        CookieManager.getInstance().flush() // consents given in the in-app browser survive the app being killed
    }

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
                            age = viewModel::age,
                            favicon = viewModel::favicon,
                            onOpenArticle = viewModel::openArticle,
                            onOpenReadingOrder = viewModel::openReadingOrder,
                            onRefresh = viewModel::pullToRefresh,
                            onRefreshErrorShown = viewModel::dismissRefreshError,
                            onMarkAllAsRead = viewModel::markAllAsRead,
                        ),
                    )
                    is Screen.ArticleView -> ArticleViewScreen(
                        s, viewModel::articlePage,
                        ArticleViewActions(
                            originalPageUrl = viewModel::originalPageUrl,
                            readerViewScript = viewModel.readerViewScript,
                            readerView = viewModel::readerView,
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

/** Material's opacity for disabled content. */
private const val DISABLED_ALPHA = 0.38f


/** What the reader can do on the list screen. */
private class ListActions(
    val age: (Article) -> String,
    val favicon: suspend (Article) -> ImageBitmap?,
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
                    items(section.articles, key = { it.id }) { article -> ArticleEntry(article, actions) }
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
private fun ArticleEntry(article: Article, actions: ListActions) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { actions.onOpenArticle(article) }
            .alpha(if (article.read) READ_ALPHA else 1f)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FeedIcon(article, actions.favicon)
        Text(
            article.title,
            Modifier.weight(1f).padding(horizontal = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        AgePill(actions.age(article))
    }
    HorizontalDivider()
}

/** The Feed's favicon; while it loads, or if there is none, the Feed's first letter in a coloured circle. */
@Composable
private fun FeedIcon(article: Article, favicon: suspend (Article) -> ImageBitmap?) {
    val icon by produceState<ImageBitmap?>(null, article.feedId) { value = favicon(article) }
    val modifier = Modifier.size(FEED_ICON_SIZE)
    val loaded = icon
    if (loaded != null) {
        Image(loaded, contentDescription = article.feedTitle, modifier, filterQuality = FilterQuality.Medium)
    } else {
        Box(
            modifier.background(placeholderColour(article.feedTitle), CircleShape).semantics { contentDescription = article.feedTitle },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                article.feedTitle.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** A colour that stays the same for a Feed and tells most Feeds apart. */
private fun placeholderColour(feedTitle: String): Color =
    Color.hsv(hue = (feedTitle.hashCode().toUInt() % 360u).toFloat(), saturation = 0.55f, value = 0.65f)

private val FEED_ICON_SIZE = 24.dp

@Composable
private fun AgePill(age: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            age,
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
        )
    }
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
    val originalPageUrl: suspend (Article) -> String?,
    val readerViewScript: String,
    val readerView: (Article, String) -> ReaderView,
    val onShow: (Int) -> Unit,
    val onClose: () -> Unit,
    val onOpenFeedSettings: () -> Unit,
    val onChangeFeedSettings: (FeedSettings) -> Unit,
    val onCloseFeedSettings: () -> Unit,
)

/**
 * The Article View: a horizontal pager over the list snapshot. Arriving at an Article by swiping counts
 * as opening it. Each page shows, following its Feed's View Mode, the Core's prepared document, the Reader View
 * or the Original Page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArticleViewScreen(view: Screen.ArticleView, page: (Article) -> ArticlePage, actions: ArticleViewActions) {
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
                actions = { ArticleViewMenu(view.article, actions) },
            )
        },
    ) { padding ->
        HorizontalPager(
            pagerState,
            Modifier.fillMaxSize().padding(padding),
            beyondViewportPageCount = 1, // the neighbours are ready when the swipe starts
            key = { view.articles[it].id },
        ) { index ->
            val article = view.articles[index]
            when (val shown = remember(article.id, view.revision) { page(article) }) {
                is ArticlePage.FeedContent -> ArticleWebView(article, shown.document)
                ArticlePage.ReaderView -> ReaderViewPage(article, index == pagerState.currentPage, view.revision, actions)
                ArticlePage.OriginalPage -> OriginalPageView(article, actions.originalPageUrl)
            }
        }
    }
    view.feedSettings?.let { FeedSettingsBottomSheet(it, actions.onChangeFeedSettings, actions.onCloseFeedSettings) }
}

/** Feed Settings; and, if the Feed links the Article to a page, opening and sharing that page. */
@Composable
private fun ArticleViewMenu(article: Article, actions: ArticleViewActions) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    /** Runs [use] with the Original Page's link, tracking links unwrapped, which may take a request. */
    fun withLink(use: (String) -> Unit) {
        expanded = false
        scope.launch { actions.originalPageUrl(article)?.let(use) }
    }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feed_settings_open)) },
                onClick = {
                    expanded = false
                    actions.onOpenFeedSettings()
                },
            )
            if (article.url != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.open_in_browser)) },
                    onClick = { withLink { context.openExternally(Uri.parse(it)) } },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.share)) },
                    onClick = {
                        withLink { link ->
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, article.title)
                                .putExtra(Intent.EXTRA_TEXT, link)
                            context.startActivity(Intent.createChooser(send, null))
                        }
                    },
                )
            }
        }
    }
}

/** Hands [uri] to another app, e.g. the browser; stays here if no app can open it. */
private fun Context.openExternally(uri: Uri) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
        // No app can open this link.
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
        Column(Modifier.padding(top = 16.dp).selectableGroup()) {
            ViewModeOption(sheet, ViewMode.FeedContent, R.string.view_mode_feed_content, R.string.view_mode_feed_content_hint, onChange)
            ViewModeOption(sheet, ViewMode.ReaderView, R.string.view_mode_reader_view, R.string.view_mode_reader_view_hint, onChange)
            ViewModeOption(sheet, ViewMode.OriginalPage, R.string.view_mode_original_page, R.string.view_mode_original_page_hint, onChange)
        }
        HorizontalDivider(Modifier.padding(horizontal = 24.dp))
        val bonusTextApplies = sheet.settings.viewMode != ViewMode.OriginalPage
        ListItem(
            headlineContent = { Text(stringResource(R.string.bonus_text)) },
            supportingContent = { Text(stringResource(R.string.bonus_text_hint)) },
            trailingContent = {
                // The whole row toggles; Bonus Text applies to Feed Content and the Reader View.
                Switch(checked = sheet.settings.bonusText, onCheckedChange = null, enabled = bonusTextApplies)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 16.dp)
                .alpha(if (bonusTextApplies) 1f else DISABLED_ALPHA)
                .toggleable(
                    value = sheet.settings.bonusText,
                    enabled = bonusTextApplies,
                    role = Role.Switch,
                    onValueChange = { onChange(sheet.settings.copy(bonusText = it)) },
                ),
        )
    }
}

@Composable
private fun ViewModeOption(
    sheet: FeedSettingsSheet,
    mode: ViewMode,
    @StringRes label: Int,
    @StringRes hint: Int,
    onChange: (FeedSettings) -> Unit,
) {
    val selected = sheet.settings.viewMode == mode
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        supportingContent = { Text(stringResource(hint)) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .selectable(selected, role = Role.RadioButton) { onChange(sheet.settings.copy(viewMode = mode)) },
    )
}

/**
 * The Article's Original Page in the in-app browser. Its cookies are kept by the WebView's cookie store, so a
 * consent given once stays given.
 */
@Composable
private fun OriginalPageView(article: Article, originalPageUrl: suspend (Article) -> String?) {
    val url by produceState<String?>(null, article.id) { value = originalPageUrl(article) }
    val link = url ?: return Centered { CircularProgressIndicator() }
    AndroidView(modifier = Modifier.fillMaxSize().keepPagerStill(), factory = { context -> inAppBrowser(context).apply { loadUrl(link) } })
}

/**
 * The Reader View. The Original Page loads in the in-app browser, hidden, and the Core's script reports from it what
 * to show: its main content, laid out like Feed Content; or, while the page's consent wall waits for the reader's
 * decision, the page itself, checked every second until the wall is gone; or the page, if it has no main content.
 * A neighbouring page that met a wall waits until it is [shown] and then loads again, as the reader may have
 * consented on another page meanwhile.
 */
@Composable
private fun ReaderViewPage(article: Article, shown: Boolean, revision: Int, actions: ArticleViewActions) {
    val url by produceState<String?>(null, article.id) { value = actions.originalPageUrl(article) }
    val link = url ?: return Centered { CircularProgressIndicator() }
    var browser by remember(link) { mutableStateOf<WebView?>(null) }
    var pagesLoaded by remember(link) { mutableIntStateOf(0) }
    var report by remember(link) { mutableStateOf<String?>(null) }
    var state by remember(link) { mutableStateOf<ReaderView>(ReaderView.NotYet) }
    val isShown by rememberUpdatedState(shown)
    Box(Modifier.fillMaxSize().keepPagerStill()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> inAppBrowser(context, onPageFinished = { pagesLoaded++ }).apply { browser = this; loadUrl(link) } },
        )
        when (val current = state) {
            // Opaque, as the Article View's own background is transparent and the page lies beneath.
            is ReaderView.Ready -> Surface(Modifier.fillMaxSize()) { ArticleWebView(article, current.document) }
            ReaderView.NotYet -> Centered { CircularProgressIndicator() }
            ReaderView.ConsentNeeded, ReaderView.Failed -> Unit // the page itself shows
        }
    }
    // Each loaded page, e.g. the Article after a consent page, is checked again.
    LaunchedEffect(browser, pagesLoaded) {
        val page = browser ?: return@LaunchedEffect
        if (pagesLoaded == 0) return@LaunchedEffect
        delay(READER_VIEW_SETTLE_MILLIS) // consent managers start after the page has loaded
        var waited = 0L
        while (true) {
            val latest = page.evaluate(actions.readerViewScript).also { report = it }
            val result = actions.readerView(article, latest)
            state = if (result == ReaderView.NotYet && waited >= READER_VIEW_MAX_WAIT_MILLIS) ReaderView.Failed else result
            val pause = when (state) {
                ReaderView.NotYet -> READER_VIEW_RETRY_MILLIS
                ReaderView.ConsentNeeded -> {
                    if (!isShown) {
                        snapshotFlow { isShown }.first { it }
                        page.reload()
                        return@LaunchedEffect
                    }
                    READER_VIEW_CONSENT_RETRY_MILLIS
                }
                is ReaderView.Ready, ReaderView.Failed -> return@LaunchedEffect
            }
            delay(pause)
            waited += pause
        }
    }
    // Feed Settings changes, e.g. Bonus Text, prepare the same main content again.
    LaunchedEffect(revision) {
        val latest = report ?: return@LaunchedEffect
        if (state is ReaderView.Ready) state = actions.readerView(article, latest)
    }
}

/** Runs [script] in the shown page and returns its result as JSON; `null` if it could not run. */
private suspend fun WebView.evaluate(script: String): String =
    suspendCancellableCoroutine { result -> evaluateJavascript(script) { result.resume(it ?: "null") } }

/**
 * The in-app browser for Original Pages. JavaScript and DOM storage are on, as pages and their consent managers
 * need them; the cookies, and with them consents, are kept. Links stay in the browser; other schemes, e.g.
 * mailto:, go to other apps.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun inAppBrowser(context: Context, onPageFinished: () -> Unit = {}): WebView = WebView(context).apply {
    // Measured to an exact height. Otherwise the WebView takes 0 as its viewport height, and so does CSS `vh`:
    // consent walls sized to the screen collapse to nothing.
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        allowFileAccess = false
        allowContentAccess = false
    }
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.url.scheme in setOf("http", "https")) return false
            view.context.openExternally(request.url)
            return true
        }

        override fun onPageFinished(view: WebView, url: String) = onPageFinished()
    }
}

/**
 * Keeps requests to scroll a page into view from reaching the pager. An in-app browser makes such requests, e.g.
 * when a consent wall focuses its button, also on a neighbouring page the pager has loaded in advance; passed on,
 * they would swipe the pager to that page, which then counts as opened and is marked as read.
 */
private fun Modifier.keepPagerStill(): Modifier = this then KeepPagerStill

private data object KeepPagerStill : ModifierNodeElement<KeepPagerStillNode>() {
    override fun create() = KeepPagerStillNode()
    override fun update(node: KeepPagerStillNode) = Unit
}

private class KeepPagerStillNode : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> ComposeRect?) = Unit
}

private const val READER_VIEW_SETTLE_MILLIS = 1500L
private const val READER_VIEW_RETRY_MILLIS = 500L
private const val READER_VIEW_CONSENT_RETRY_MILLIS = 1000L
private const val READER_VIEW_MAX_WAIT_MILLIS = 10_000L

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
                        view.context.openExternally(request.url)
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
