package com.multistore.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * An app's icon, with the placeholder **underneath** and not in its place.
 *
 * The placeholder sits in a `Box` behind the image instead of being an `if` branch, and the two
 * consequences are both intended: the cell has its final size from the start — no layout jump when
 * the icon arrives — and a screenshot test, where there is no network, still photographs a
 * placeholder of the right size instead of an empty box.
 *
 * The icon is not announced to TalkBack: the title next to it already says which app it is, and
 * repeating it would make it read twice.
 */
@Composable
fun AppIcon(
    iconUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = DEFAULT_SIZE,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(percent = CORNER_PERCENT)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(
                imageVector = Icons.Rounded.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(size * PLACEHOLDER_INSET),
            )
        }
        if (iconUrl != null) {
            AsyncImage(
                model = iconUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private val DEFAULT_SIZE = 48.dp
private const val CORNER_PERCENT = 25
private const val PLACEHOLDER_INSET = 0.17f
