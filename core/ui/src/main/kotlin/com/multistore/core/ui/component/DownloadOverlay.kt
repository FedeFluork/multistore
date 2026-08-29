package com.multistore.core.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.VisibilityOff
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
import androidx.compose.ui.unit.dp
import com.multistore.core.designsystem.theme.LocalSpacing
import com.multistore.core.ui.R

/**
 * A transfer, as the card above the screens shows it.
 *
 * It is a `:core:ui` type and not `:core:data`'s `DownloadStatus` because of the dependency rule:
 * `:core:ui` does not see the repositories. It also carries less — name, how far along, whether it
 * is finished — because that is all a card as wide as the screen and three lines tall can say.
 */
data class DownloadProgress(
    val id: Long,
    /** `null` when the listing that originated it is no longer in the catalogue. */
    val title: String?,
    /** `null` when the server does not declare the size: indeterminate bar. */
    val fraction: Float?,
    /**
     * The file is whole and nobody has installed it.
     *
     * A field and not "fraction == 1f", and the difference is not pedantry: four stores out of nine
     * do not declare the size, so a finished transfer among them arrives here with a `null`
     * fraction — and a transfer at 99.6% rounds to a full bar without having finished. Only the
     * state in the row knows, and this is the state in the row.
     */
    val ready: Boolean = false,
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
 * ### Why a finished download is said in words, and not left as a full bar
 *
 * A full bar is not the same news as "this can be installed now", and with the system installer the
 * difference is the whole point: nothing else is going to happen unless somebody presses something.
 * The card used to draw those transfers exactly like the others — `observeActive` has always
 * included them — so the one moment at which it had something to ask was the one at which it looked
 * most finished.
 *
 * ### The two icons, and why neither is the one it used to be
 *
 * Both were changed because both were **describing the wrong gesture**, and on a card whose only
 * two controls they are, that is the whole card.
 *
 * The first was a `KeyboardArrowUp`, which is the sheet-expansion arrow: it promised the card would
 * grow upwards into a fuller list. It never did — the destination is the Downloads screen, a
 * different surface — so the icon was announcing a movement that does not happen. A **forward**
 * arrow says the thing that is true: pressing it takes you somewhere. `AutoMirrored` because a
 * "forward" arrow points the other way in a right-to-left layout; the five supported languages are
 * all left-to-right, so it costs nothing today and is right the day a sixth is added.
 *
 * The second was an X. On a card that has a progress bar on it, an X is the universal "stop this",
 * and the one thing it must not be taken for here is cancelling the download — which is the most
 * expensive mistake this card could invite, since the bytes are already paid for. A **struck-through
 * eye** cannot be read as "stop": it says hide, which is exactly and only what it does.
 *
 * ### Why it does not open a second list, and why it carries no cancel button
 *
 * There is room here for a number and a bar, not for a name, a size and two buttons per row. The
 * arrow leads to the Downloads screen, which is that list — the same one, not a copy of it: two
 * surfaces showing the same rows would be two places to keep in step, and the first divergence
 * would be invisible.
 *
 * Stopping a download is destructive and has to be done where one sees **what** is being stopped:
 * the app's listing and that same Downloads screen, which both have the button. A cancel two
 * centimetres from the hide control would be a way of making somebody throw a transfer away when
 * all they wanted was to get the card out of their sight.
 *
 * ### What dismissing it means
 *
 * It takes space at the bottom of every screen, and on a 250 MB download it takes it for minutes.
 * Dismissing it hides **this** set of transfers, not the feature: a new download brings it back. The
 * logic of that "new" lives in whoever owns the state, not here — see `DownloadOverlayViewModel` in
 * `:app`, which is also where the bar's badge is decided: that dot deliberately does **not** go away
 * with the card, because hiding a panel is not being told the download no longer matters.
 */
@Composable
fun DownloadOverlay(
    downloads: List<DownloadProgress>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    val transferring = downloads.filterNot { it.ready }
    val ready = downloads.count { it.ready }
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
                // 56dp, and the number was **measured**, not chosen. Two icon buttons where there
                // was one made the accessibility check report both at 46dp tall: their 48dp touch
                // targets overflow a row that wraps its content, and the check clips the overflow
                // against the parent. Setting the row to 48dp exactly is not enough — the targets
                // still spill by a few pixels and the check still says 46dp; 56dp, the standard
                // height of a dense bar, contains them. Injecting either value back turns
                // `DownloadOverlayScreenshotTest` red, which is how both were established.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(56.dp),
                ) {
                    Text(
                        text = title(transferring, ready),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onExpand) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = stringResource(R.string.downloads_overlay_expand),
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.VisibilityOff,
                            contentDescription =
                                stringResource(R.string.downloads_overlay_dismiss),
                        )
                    }
                }
                // Both facts at once, and only when there are both: with three transfers running and
                // one file waiting, a title that mentioned only one of the two would make the other
                // invisible — and the invisible one would be the one asking for a tap.
                if (transferring.isNotEmpty() && ready > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.downloads_overlay_ready,
                            ready,
                            ready,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = spacing.extraSmall),
                    )
                }
                // One bar per transfer, not an average: two downloads at 10% and 90% are not one at
                // 50%, and the average would say little is left precisely while the first has not even
                // begun. A finished download gets no bar at all — a full bar would say "nearly
                // there" about something that is not going to move again on its own.
                Column(
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                    modifier = Modifier.padding(top = spacing.small),
                ) {
                    transferring.forEach { download ->
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
 * "Downloading Telegram" when there is one, "Downloading 3 apps" when there are more — and, when
 * nothing is moving any more, what is waiting instead.
 *
 * The name is shown **only** with a single download: with three, listing them would take three lines
 * in a card that has one, and truncating them would give "Telegram, Firef…" — i.e. less information
 * than the number.
 *
 * When nothing is transferring the card is not about progress at all: everything that is left is
 * waiting for a tap, and that is what the one line says.
 */
@Composable
private fun title(transferring: List<DownloadProgress>, ready: Int): String {
    if (transferring.isEmpty()) {
        return pluralStringResource(R.plurals.downloads_overlay_ready, ready, ready)
    }
    val single = transferring.singleOrNull()
    return if (single != null) {
        stringResource(
            R.string.downloads_overlay_title_one,
            single.title ?: stringResource(R.string.downloads_overlay_unknown_app),
        )
    } else {
        pluralStringResource(
            R.plurals.downloads_overlay_title_many,
            transferring.size,
            transferring.size,
        )
    }
}
