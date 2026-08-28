package com.multistore.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.multistore.core.designsystem.theme.LocalSpacing
import com.multistore.core.designsystem.theme.MultiStoreTheme
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.ThemeMode

/**
 * A list row for an app: icon, title, developer, summary.
 *
 * **No version**, and that is a choice: the version to show is not the maximum `versionCode` — across
 * 4,257 F-Droid packages there are 14 where the two differ — and the rule that chooses it needs the
 * device and the installed signer, things a list query does not have. A number in the row that the
 * detail screen then contradicts is worse than no number.
 *
 * [supporting] is the slot the aggregated search writes "available on 3 stores" into. A slot and not
 * a string: that text is plural, translated into five languages and names the stores, i.e. it is made
 * of resources that live in the feature — and `:core:ui` must not own its users' strings.
 *
 * [preferredLanguageTags] comes from the caller instead of being read here from the `Configuration`:
 * the summary is a [LocalizedText] coming from the store, not from `strings.xml`, and its resolution
 * is `:core:model` logic already tested on the JVM. Reading the locale inside the composable would
 * make the result depend on the rendering environment, i.e. different in a screenshot test.
 */
@Composable
fun AppListItem(
    summary: StoreListingSummary,
    preferredLanguageTags: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: @Composable (() -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.screenHorizontal, vertical = spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(iconUrl = summary.iconUrl)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = spacing.large),
        ) {
            Text(
                text = summary.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            summary.developer?.let { developer ->
                Text(
                    text = developer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            summary.summary.resolve(preferredLanguageTags)?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = spacing.extraSmall),
                )
            }
            supporting?.let {
                Box(modifier = Modifier.padding(top = spacing.extraSmall)) { it() }
            }
        }
    }
}

@Preview(name = "AppListItem light")
@Composable
private fun AppListItemLightPreview() {
    MultiStoreTheme(themeMode = ThemeMode.LIGHT) {
        Surface { AppListItem(previewSummary(), listOf("it"), onClick = {}) }
    }
}

@Preview(name = "AppListItem dark")
@Composable
private fun AppListItemDarkPreview() {
    MultiStoreTheme(themeMode = ThemeMode.DARK) {
        Surface { AppListItem(previewSummary(), listOf("it"), onClick = {}) }
    }
}

private fun previewSummary() = StoreListingSummary(
    storeId = StoreId.FDROID,
    ref = StoreAppRef("org.example.app"),
    title = "Example",
    packageName = "org.example.app",
    summary = LocalizedText(mapOf("en" to "A sample app for the preview.")),
    developer = "Example Ltd.",
)
