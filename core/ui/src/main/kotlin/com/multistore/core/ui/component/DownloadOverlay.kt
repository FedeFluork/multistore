package com.multistore.core.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.multistore.core.designsystem.theme.LocalSpacing
import com.multistore.core.ui.R

/**
 * A transfer in progress, as the card above the screens shows it.
 *
 * It is a `:core:ui` type and not `:core:data`'s `DownloadStatus` because of the dependency rule:
 * `:core:ui` does not see the repositories. It also carries less — name, how far along — because that
 * is all a card as wide as the screen and three lines tall can say.
 */
data class DownloadProgress(
    val id: Long,
    /** `null` when the listing that originated it is no longer in the catalogue. */
    val title: String?,
    /** `null` when the server does not declare the size: indeterminate bar. */
    val fraction: Float?,
)

/**
 * The downloads' progress, above any screen, dismissible.
 *
 * ### Why above everything and not on the listing
 *
 * The transfer lives in a worker with its foreground service: **it survives the screen**, and that is
 * intended — leaving the listing must not throw eighteen megabytes away. The consequence is that so
 * far the only place one could see how much was left was the listing it started from, i.e. precisely
 * the one the user leaves as soon as they press Install. The notification says so, but it sits
 * outside the app and can also be silenced.
 *
 * ### Why it can be dismissed, and what dismissing it means
 *
 * It takes space at the bottom of every screen, and on a 250 MB download it takes it for minutes.
 * Dismissing it hides **this** set of transfers, not the feature: a new download brings it back. The
 * logic of that "new" lives in whoever owns the state, not here — see `DownloadOverlayViewModel` in
 * `:app`.
 *
 * It carries no "cancel" button: stopping a download is a destructive gesture, and it has to be
 * performed where one sees **what** is being stopped — the app's listing, which already has that
 * button. An X and a Cancel two centimetres apart are a way of making somebody throw a download away
 * when all they wanted was to get the card out of their sight.
 */
@Composable
fun DownloadOverlay(
    downloads: List<DownloadProgress>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    AnimatedVisibility(
        visible = downloads.isNotEmpty(),
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screenHorizontal, vertical = spacing.small),
        ) {
            Column(modifier = Modifier.padding(spacing.large)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title(downloads),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription =
                                stringResource(R.string.downloads_overlay_dismiss),
                        )
                    }
                }
                // One bar per transfer, not an average: two downloads at 10% and 90% are not one at
                // 50%, and the average would say little is left precisely while the first has not even
                // begun.
                Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                    downloads.forEach { download ->
                        val fraction = download.fraction
                        if (fraction != null) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Downloading Telegram" when there is one, "Downloading 3 apps" when there are more.
 *
 * The name is shown **only** with a single download: with three, listing them would take three lines
 * in a card that has one, and truncating them would give "Telegram, Firef…" — i.e. less information
 * than the number.
 */
@Composable
private fun title(downloads: List<DownloadProgress>): String {
    val single = downloads.singleOrNull()
    return if (single != null) {
        stringResource(
            R.string.downloads_overlay_title_one,
            single.title ?: stringResource(R.string.downloads_overlay_unknown_app),
        )
    } else {
        pluralStringResource(
            R.plurals.downloads_overlay_title_many,
            downloads.size,
            downloads.size,
        )
    }
}
