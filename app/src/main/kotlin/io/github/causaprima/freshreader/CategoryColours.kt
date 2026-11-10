package io.github.causaprima.freshreader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.causaprima.freshreader.core.CategoryColour

/** The Category Colour itself: a muted hue, so that it marks the Category without drawing the eye. */
internal val CategoryColour.color: Color
    get() = Color.hsv(
        hue = when (this) {
            CategoryColour.Red -> 0f
            CategoryColour.Orange -> 30f
            CategoryColour.Yellow -> 55f
            CategoryColour.Lime -> 90f
            CategoryColour.Green -> 140f
            CategoryColour.Teal -> 180f
            CategoryColour.Blue -> 210f
            CategoryColour.Indigo -> 245f
            CategoryColour.Purple -> 280f
            CategoryColour.Pink -> 320f
        },
        saturation = 0.45f,
        value = 0.85f,
    )

/** The Category header's band: the page's colour, tinted with the Category Colour. */
@Composable
internal fun categoryBandColor(colour: CategoryColour): Color = lerp(MaterialTheme.colorScheme.surface, colour.color, 0.22f)

/** The Category header's text, tinted with the Category Colour but still easy to read. */
@Composable
internal fun categoryTextColor(colour: CategoryColour): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return lerp(MaterialTheme.colorScheme.onSurface, colour.color, if (dark) 0.6f else 0.35f)
}

/** A Category's colour as a dot; tapping it opens the [CategoryColourPicker]. */
@Composable
internal fun CategoryColourDot(category: String, colour: CategoryColour, onClick: () -> Unit) {
    val description = stringResource(R.string.category_colour_choose, category)
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        Box(Modifier.size(20.dp).background(colour.color, CircleShape))
    }
}

/**
 * Picks [category]'s colour from the palette, or "Automatic" for one that differs from its neighbours'.
 * [chosen]: the reader's current choice, null for automatic.
 */
@Composable
internal fun CategoryColourPicker(
    category: String,
    chosen: CategoryColour?,
    onChoose: (CategoryColour?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.category_colour_title, category)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CategoryColour.entries.chunked(5).forEach { row ->
                    Row(Modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { colour -> Swatch(colour, selected = colour == chosen) { onChoose(colour) } }
                    }
                }
                Row(
                    Modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.RadioButton) { onChoose(null) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = chosen == null, onClick = { onChoose(null) })
                    Text(stringResource(R.string.category_colour_automatic))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun Swatch(colour: CategoryColour, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent
    Box(
        Modifier.size(40.dp).clip(CircleShape).border(3.dp, ring, CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = colour.name
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(30.dp).background(colour.color, CircleShape))
    }
}
