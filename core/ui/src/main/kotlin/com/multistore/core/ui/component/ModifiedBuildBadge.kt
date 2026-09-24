package com.multistore.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.multistore.core.designsystem.theme.LocalSpacing
import com.multistore.core.designsystem.theme.MultiStoreTheme
import com.multistore.core.model.ModifiedBuild
import com.multistore.core.model.ThemeMode
import com.multistore.core.ui.R

/**
 * "This is a rework", or "it might be", next to an app that comes from a store that publishes them.
 *
 * ### Why it is a badge and not a warning
 *
 * Five of the nine stores redistribute APKs somebody other than the developer has changed. That is
 * what those stores are **for**, and a user who opened apkmody knows it; drawing an error-coloured
 * alarm on every one of their listings would be shouting a fact the person chose. What is missing
 * is not alarm but **information at the right moment**: until now the only place it was written was
 * the title, in the store's own words, and the sentence explaining what the verification can prove
 * arrived after the download.
 *
 * So the badge is tertiary rather than error-coloured, it sits inline with the row's other
 * supporting text, and everything it has to say is one tap away instead of taking three lines of a
 * list row.
 *
 * ### Two labels, because two things are being declared
 *
 * [ModifiedBuild.DECLARED] is the store saying so about **this** listing — an1's `mod` class,
 * modyolo's `mod_info`, pdalife's file-list label. [ModifiedBuild.POSSIBLE] is the store publishing
 * reworks and saying nothing here, which on apkmody and liteapks is *every* listing, because those
 * two write it only in the title. Collapsing the two into one label would either promise something
 * about a file nobody has judged, or call somebody's untouched upload a rework.
 *
 * [ModifiedBuild.NONE] draws nothing at all. A "not modified" chip on four stores out of nine would
 * be a claim, and the only thing behind it would be the absence of a claim from someone else.
 */
@Composable
fun ModifiedBuildBadge(
    state: ModifiedBuild,
    storeDisplayName: String,
    modifier: Modifier = Modifier,
) {
    if (!state.isFlagged) return
    val spacing = LocalSpacing.current
    var explaining by remember { mutableStateOf(false) }

    // The action label, not a content description: the chip's text already says *what* it is, and
    // what a screen reader is otherwise missing is that tapping it does something and what. Without
    // it the badge announces itself as a button with no stated action.
    val actionLabel = stringResource(R.string.modified_build_badge_action)
    Surface(
        modifier = modifier.semantics { onClick(label = actionLabel, action = null) },
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        onClick = { explaining = true },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.small, vertical = spacing.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.extraSmall),
        ) {
            Icon(
                imageVector = Icons.Rounded.Build,
                // Null, and deliberately: the text beside it says the same thing, and a screen
                // reader announcing both would read the label twice. The tap target's own
                // description is the label.
                contentDescription = null,
                modifier = Modifier.size(BADGE_ICON),
            )
            Text(
                text = stringResource(state.labelRes),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }

    if (explaining) {
        ModifiedBuildDialog(
            state = state,
            storeDisplayName = storeDisplayName,
            onDismiss = { explaining = false },
        )
    }
}

/**
 * The sentence the badge exists for: what the check before installing proves, and what it does not.
 *
 * The second half is the one that matters and is the limit `PreInstallVerifier` already declares in
 * its own words: for a rework there is no original developer signature to compare against, so the
 * pipeline protects against the **package being substituted** and not against the archive having
 * been **tampered with upstream**. Saying only the reassuring half would be worse than saying
 * nothing.
 */
@Composable
private fun ModifiedBuildDialog(
    state: ModifiedBuild,
    storeDisplayName: String,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.modified_build_dialog_dismiss))
            }
        },
        icon = { Icon(Icons.Rounded.Build, contentDescription = null) },
        title = { Text(stringResource(R.string.modified_build_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
                Text(
                    text = stringResource(state.explanationRes, storeDisplayName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.modified_build_dialog_verified),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.modified_build_dialog_unverified),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/**
 * The chip's label. A `when` over the enum and not a map, so the compiler notices a fourth value.
 *
 * [ModifiedBuild.NONE] has none: nothing is drawn for it, and inventing a "not modified" string
 * would make it possible to draw one by accident.
 */
private val ModifiedBuild.labelRes: Int
    get() = when (this) {
        ModifiedBuild.DECLARED -> R.string.modified_build_declared_label
        ModifiedBuild.POSSIBLE -> R.string.modified_build_possible_label
        ModifiedBuild.NONE -> R.string.modified_build_possible_label
    }

private val ModifiedBuild.explanationRes: Int
    get() = when (this) {
        ModifiedBuild.DECLARED -> R.string.modified_build_dialog_declared
        ModifiedBuild.POSSIBLE -> R.string.modified_build_dialog_possible
        ModifiedBuild.NONE -> R.string.modified_build_dialog_possible
    }

/** Small enough to sit inline with `labelMedium`, large enough not to be a smudge. */
private val BADGE_ICON = 14.dp

@Preview(name = "ModifiedBuildBadge light")
@Composable
private fun ModifiedBuildBadgeLightPreview() {
    MultiStoreTheme(themeMode = ThemeMode.LIGHT) {
        Surface {
            Column {
                ModifiedBuildBadge(ModifiedBuild.DECLARED, "an1")
                ModifiedBuildBadge(ModifiedBuild.POSSIBLE, "APKMody")
            }
        }
    }
}

@Preview(name = "ModifiedBuildBadge dark")
@Composable
private fun ModifiedBuildBadgeDarkPreview() {
    MultiStoreTheme(themeMode = ThemeMode.DARK) {
        Surface {
            Column {
                ModifiedBuildBadge(ModifiedBuild.DECLARED, "an1")
                ModifiedBuildBadge(ModifiedBuild.POSSIBLE, "APKMody")
            }
        }
    }
}
