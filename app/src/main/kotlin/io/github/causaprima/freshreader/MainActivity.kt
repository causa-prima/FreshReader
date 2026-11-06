package io.github.causaprima.freshreader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.causaprima.freshreader.core.Article
import io.github.causaprima.freshreader.core.Outcome
import io.github.causaprima.freshreader.core.Section
import io.github.causaprima.freshreader.core.SyncStatus
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
                        s.sections, syncStatus, listState, viewModel::openArticle, viewModel::openReadingOrder,
                    )
                    is Screen.ArticleView -> ArticleViewScreen(s.article, s.document, viewModel::closeArticle)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArticleListScreen(
    sections: List<Section>,
    syncStatus: SyncStatus,
    listState: LazyListState,
    onOpenArticle: (Article) -> Unit,
    onOpenReadingOrder: () -> Unit,
) {
    val total = sections.sumOf { it.unreadCount }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.list_title, total)) },
                actions = {
                    if (syncStatus is SyncStatus.Failed) SyncFailedHint(syncStatus)
                    IconButton(onClick = onOpenReadingOrder) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.reading_order_title))
                    }
                },
            )
        },
    ) { padding ->
        if (sections.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.list_empty))
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState) {
            sections.forEach { section ->
                stickyHeader(key = "category:${section.category}") { SectionHeader(section) }
                items(section.articles, key = { it.id }) { article -> ArticleEntry(article, onOpenArticle) }
            }
        }
    }
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

/**
 * The Article View: the Core's prepared document in a WebView. JavaScript stays off, zoom is disabled,
 * and links open in the browser instead of replacing the Article.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArticleViewScreen(article: Article, document: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        key(article.id) {
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(padding),
                factory = { context ->
                    WebView(context).apply {
                        setBackgroundColor(Color.TRANSPARENT)
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
                        loadDataWithBaseURL(article.url, document, "text/html", "utf-8", null)
                    }
                },
            )
        }
    }
}

