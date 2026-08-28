package com.multistore.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.multistore.core.designsystem.theme.LocalSpacing
import com.multistore.core.designsystem.theme.MultiStoreTheme
import com.multistore.core.model.ThemeMode

/**
 * The shared empty state: an icon, a title, an explanation.
 *
 * No colour and no typographic size is written here — they all come from `MaterialTheme` and from
 * `:core:designsystem`'s tokens, and that is what makes the component correct in light and dark
 * without having to check it by eye (rule 3).
 *
 * [title] and [description] are `String`s already resolved by the caller via `stringResource`: the
 * component knows no resources, so it stays usable in previews and in tests too.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = spacing.extraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            // The icon is decorative: the title below already says everything, and announcing it would
            // double the information for TalkBack users.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(56.dp)
                .clearAndSetSemantics { },
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.large),
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.small),
        )
    }
}

@Preview(name = "EmptyState light")
@Composable
private fun EmptyStateLightPreview() {
    MultiStoreTheme(themeMode = ThemeMode.LIGHT) {
        Surface {
            EmptyState(
                icon = Icons.Rounded.Search,
                title = "No results",
                description = "Try a different search term.",
            )
        }
    }
}

@Preview(name = "EmptyState dark")
@Composable
private fun EmptyStateDarkPreview() {
    MultiStoreTheme(themeMode = ThemeMode.DARK) {
        Surface {
            EmptyState(
                icon = Icons.Rounded.Search,
                title = "No results",
                description = "Try a different search term.",
            )
        }
    }
}
