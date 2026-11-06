package io.github.causaprima.freshreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.causaprima.freshreader.core.Article
import io.github.causaprima.freshreader.core.Outcome
import io.github.causaprima.freshreader.core.Section
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
                when (val s = screen) {
                    Screen.Loading -> Centered { CircularProgressIndicator() }
                    is Screen.Login -> LoginScreen(s, viewModel::logIn)
                    is Screen.Failed -> FailedScreen(s.outcome, viewModel::retry)
                    is Screen.Articles -> ArticleListScreen(s.sections)
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

private val dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArticleListScreen(sections: List<Section>) {
    val total = sections.sumOf { it.unreadCount }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.list_title, total)) }) }) { padding ->
        if (sections.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.list_empty))
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            sections.forEach { section ->
                stickyHeader(key = "category:${section.category}") { SectionHeader(section) }
                items(section.articles, key = { it.id }) { article -> ArticleEntry(article) }
            }
        }
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
private fun ArticleEntry(article: Article) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(article.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(
            "${article.feedTitle} · ${dateFormat.format(Instant.ofEpochSecond(article.published))}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}
