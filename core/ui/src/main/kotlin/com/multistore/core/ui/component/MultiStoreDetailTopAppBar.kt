package com.multistore.core.ui.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.multistore.core.ui.R

/**
 * The top bar of a screen **inside** a top-level destination.
 *
 * It differs from [MultiStoreTopAppBar] in one thing, which however decides the rest: it has a back
 * button, so the title is not centred. Centring it with an icon on the left would misalign it
 * optically against the top-level screens, where the title really is in the centre.
 *
 * The arrow is `AutoMirrored`: in a right-to-left language "back" points the other way, and today's
 * five languages are all LTR but the sixth might not be. The TalkBack description lives here and not
 * in the features: it would be the same sentence repeated across three modules in five languages, i.e.
 * fifteen chances to diverge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiStoreDetailTopAppBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = modifier,
    )
}
