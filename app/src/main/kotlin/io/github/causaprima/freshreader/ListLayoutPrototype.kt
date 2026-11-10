package io.github.causaprima.freshreader

// PROTOTYPE, throwaway (#10, criterion 5): four list layouts, switched by a floating bar in debug builds only.
// The winner is folded into ArticleListScreen; this file does not go to main.

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.causaprima.freshreader.core.Article
import io.github.causaprima.freshreader.core.Section

enum class ListLayout(val label: String) {
    I1("H5: Category stripe on each card"),
    I2("coloured header only"),
    I3("Category frame left and right"),
}

/** The layout shown; only debug builds offer another. */
object ListLayoutChoice {
    var current by androidx.compose.runtime.mutableStateOf(ListLayout.I1)
}

@Composable
fun isDebugBuild(): Boolean = LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

/**
 * A Category's colour: muted hues, neighbours in the Reading Order differ. In the real app the reader would
 * pick it beside the Reading Order; here it follows the position.
 */
private val categoryHues = listOf(210f, 30f, 140f, 280f, 0f, 180f, 55f, 320f)

@Composable
private fun categoryColour(index: Int): Color = Color.hsv(categoryHues[index % categoryHues.size], 0.45f, 0.85f)

/** [colour] blended into [base]; [amount] 0 keeps the base. */
private fun tint(base: Color, colour: Color, amount: Float) = lerp(base, colour, amount)

@Composable
fun PrototypeSectionHeader(section: Section, categoryIndex: Int) {
    val colour = categoryColour(categoryIndex)
    val page = MaterialTheme.colorScheme.surface
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().background(tint(page, colour, 0.22f))
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Text(
                section.category.uppercase(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = tint(MaterialTheme.colorScheme.onSurface, colour, 0.6f), modifier = Modifier.weight(1f),
            )
            Text(section.unreadCount.toString(), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The page's background, so that the first card keeps its distance even under the stuck header.
        Spacer(Modifier.fillMaxWidth().height(6.dp).background(page).categoryFrame(colour))
    }
}

/** In I3: the Category's colour as a thin line at both sides of the screen, through cards and gaps alike. */
private fun Modifier.categoryFrame(colour: Color): Modifier =
    if (ListLayoutChoice.current != ListLayout.I3) this else drawBehind {
        val width = 3.dp.toPx()
        drawRect(colour.copy(alpha = 0.7f), size = androidx.compose.ui.geometry.Size(width, size.height))
        drawRect(
            colour.copy(alpha = 0.7f), topLeft = androidx.compose.ui.geometry.Offset(size.width - width, 0f),
            size = androidx.compose.ui.geometry.Size(width, size.height),
        )
    }

@Composable
fun PrototypeArticleEntry(
    article: Article,
    categoryIndex: Int,
    isLast: Boolean,
    age: String,
    favicon: suspend (Article) -> ImageBitmap?,
    onOpen: () -> Unit,
) {
    val layout = ListLayoutChoice.current
    val colour = categoryColour(categoryIndex)
    val shape = RoundedCornerShape(8.dp)
    val side = if (layout == ListLayout.I3) 9.dp else 4.dp
    Box(
        Modifier.fillMaxWidth().categoryFrame(colour)
            // The last card keeps the same distance to the next header as the header keeps to its first card.
            .padding(start = side, end = side, top = 3.dp, bottom = if (isLast) 9.dp else 3.dp),
    ) {
        Box(
            Modifier.fillMaxWidth()
                .alpha(if (article.read) READ_ALPHA else 1f)
                .clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(onClick = onOpen)
                .height(IntrinsicSize.Min),
        ) {
            if (layout == ListLayout.I1) Box(Modifier.width(5.dp).fillMaxHeight().background(colour.copy(alpha = 0.8f)))
            Row(
                Modifier.padding(start = if (layout == ListLayout.I1) 16.dp else 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FeedIcon(article, favicon)
                Spacer(Modifier.width(12.dp))
                Text(
                    article.title, Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** The Feed's colour: its favicon's average colour, or its placeholder's. */
private fun feedColour(article: Article, icon: ImageBitmap?): Color {
    if (icon == null) return placeholderColour(article.feedTitle)
    val pixels = icon.toPixelMap()
    var r = 0f; var g = 0f; var b = 0f; var n = 0
    for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
        val c = pixels[x, y]
        // Transparent, near-white and near-black pixels are background, not the icon's colour.
        val light = (c.red + c.green + c.blue) / 3
        if (c.alpha < 0.5f || light > 0.92f || light < 0.08f) continue
        r += c.red; g += c.green; b += c.blue; n++
    }
    return if (n == 0) placeholderColour(article.feedTitle) else Color(r / n, g / n, b / n)
}

/** The floating bar to flip between the layouts; ← and → cycle. Debug builds only. */
@Composable
fun ListLayoutSwitcher(modifier: Modifier = Modifier) {
    if (!isDebugBuild()) return
    val layouts = ListLayout.entries
    val current = ListLayoutChoice.current
    Surface(
        modifier = modifier.padding(bottom = 24.dp),
        shape = RoundedCornerShape(50),
        color = androidx.compose.ui.graphics.Color(0xFF212121),
        shadowElevation = 6.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            TextButton(onClick = { ListLayoutChoice.current = layouts[(current.ordinal + layouts.size - 1) % layouts.size] }) {
                Text("←", color = androidx.compose.ui.graphics.Color.White)
            }
            Text(
                "${current.name} (${current.label})", color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(220.dp),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = { ListLayoutChoice.current = layouts[(current.ordinal + 1) % layouts.size] }) {
                Text("→", color = androidx.compose.ui.graphics.Color.White)
            }

        }
    }
}
