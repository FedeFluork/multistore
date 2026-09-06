package com.multistore.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.multistore.core.designsystem.theme.LocalSpacing
import com.multistore.core.designsystem.theme.MultiStoreTheme
import com.multistore.core.model.ThemeMode
import com.multistore.core.ui.R

/**
 * The images a store publishes for an app: a strip that scrolls sideways, and a full-screen reader.
 *
 * ### Why it is here and not in `:feature:appdetail`
 *
 * Coil lives in `:core:ui` and nowhere else — `AppIcon` is the only other thing that loads a remote
 * image — and a gallery is a UI component, which is what this module is for. Putting it in the
 * feature would have meant a second Coil dependency and a second image-loading style, and the two
 * would have drifted: the shared `ImageLoader` `:app` configures is what gives both the store's
 * User-Agent, the shared connection pool and the disk budget the user chose.
 *
 * ### Fixed cells, and the reason it is not "height fixed, width from the image"
 *
 * The obvious layout — one fixed height and a width the loaded bitmap decides — reflows the strip as
 * each image arrives, and does something worse in a screenshot test: with no network the intrinsic
 * width is zero, so the golden photographs a row of nothing and stops being able to say whether the
 * strip works at all. A fixed cell with the placeholder **underneath** is the same choice already
 * made in [AppIcon], for the same two reasons: no layout jump, and a golden with something in it.
 *
 * The cell is 9:16 because eight of the nine stores publish phone captures and only F-Droid declares
 * a kind at all. A landscape capture is therefore cropped in the strip — and shown whole in the
 * reader, which is what the reader is for.
 *
 * ### The reader is a `Dialog`, not a destination
 *
 * A route would need an argument carrying a list of URLs through `SavedStateHandle`, a serializer,
 * and an entry in the NavHost — for a surface whose entire state is "which index" and whose only way
 * out is Back. As a dialog, Back closes it for free and nothing else on the page moves.
 */
@Composable
fun ScreenshotGallery(
    urls: List<String>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 0.dp),
) {
    if (urls.isEmpty()) return

    // The reader's state and not the strip's: `rememberSaveable` so a rotation with the reader open
    // comes back to the same image instead of to the first one.
    var openAt: Int? by rememberSaveable { mutableStateOf(null) }
    val spacing = LocalSpacing.current

    LazyRow(
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        items(items = urls, key = { it }) { url ->
            val index = urls.indexOf(url)
            ScreenshotCell(
                url = url,
                position = index + 1,
                total = urls.size,
                onClick = { openAt = index },
            )
        }
    }

    openAt?.let { index ->
        ScreenshotReader(
            urls = urls,
            initialIndex = index,
            onDismiss = { openAt = null },
        )
    }
}

/**
 * One thumbnail.
 *
 * The description carries the **position**, not the word "screenshot" alone: with five identical
 * announcements a screen reader gives no way of telling where in the strip one is, and the strip is
 * the one thing on the page one navigates by position.
 */
@Composable
private fun ScreenshotCell(
    url: String,
    position: Int,
    total: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(width = CELL_WIDTH, height = CELL_HEIGHT)
            .clip(RoundedCornerShape(CELL_CORNER))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(
                imageVector = Icons.Rounded.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(PLACEHOLDER_INSET),
            )
        }
        AsyncImage(
            model = url,
            contentDescription = stringResource(R.string.gallery_screenshot_position, position, total),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The full-screen reader: one image at a time, swiped between.
 *
 * `usePlatformDefaultWidth = false` is what makes it full-screen; without it the dialog keeps the
 * platform's inset and the image is shown inside a card, which is the one thing this surface exists
 * not to do.
 *
 * The background is opaque black rather than a `colorScheme` role, and it is the single deliberate
 * exception to rule 3 on this screen: a screenshot is somebody else's image, and a themed surface
 * behind it would tint the letterboxing differently in light and dark for no reason anybody could
 * name. The close button carries its own container colour so it stays legible over it in **both**
 * themes — over black, `onSurface` from the light palette would be invisible.
 */
@Composable
private fun ScreenshotReader(
    urls: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, urls.lastIndex),
        pageCount = { urls.size },
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(READER_BACKGROUND),
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                AsyncImage(
                    model = urls[page],
                    contentDescription = stringResource(
                        R.string.gallery_screenshot_position,
                        page + 1,
                        urls.size,
                    ),
                    // `Fit` and not `Crop`: here the whole image is the point, and a landscape
                    // capture cropped to the screen would hide exactly the part the strip already
                    // hid.
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            IconButton(
                onClick = onDismiss,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(spacing.large),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.gallery_close),
                )
            }

            // Only with something to count: "1 / 1" is a counter that says there is nothing to
            // count, and it would be the only text on a surface meant to have none.
            if (urls.size > 1) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(CELL_CORNER),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(spacing.extraLarge),
                ) {
                    Text(
                        text = stringResource(
                            R.string.gallery_position,
                            pagerState.currentPage + 1,
                            urls.size,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(
                            horizontal = spacing.medium,
                            vertical = spacing.small,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * 9:16, the aspect ratio of a phone capture — which is what eight stores out of nine publish.
 *
 * The height is what decides how much of the page the strip takes; the width follows from it, so
 * that a cell whose image has not arrived has the same shape as one whose image has.
 */
private val CELL_HEIGHT = 176.dp
private val CELL_WIDTH = 99.dp
private val CELL_CORNER = 12.dp
private val PLACEHOLDER_INSET = 32.dp

/** Opaque, and the same in both themes: see the note on [ScreenshotReader]. */
private val READER_BACKGROUND = Color(0xFF000000)

@Preview(name = "ScreenshotGallery light")
@Composable
private fun ScreenshotGalleryLightPreview() {
    MultiStoreTheme(themeMode = ThemeMode.LIGHT) {
        Surface { GalleryPreviewContent() }
    }
}

@Preview(name = "ScreenshotGallery dark")
@Composable
private fun ScreenshotGalleryDarkPreview() {
    MultiStoreTheme(themeMode = ThemeMode.DARK) {
        Surface { GalleryPreviewContent() }
    }
}

@Composable
private fun GalleryPreviewContent() {
    ScreenshotGallery(
        urls = List(5) { "https://example.invalid/shot-$it.png" },
        modifier = Modifier
            .height(CELL_HEIGHT)
            .width(CELL_WIDTH * 3),
    )
}
