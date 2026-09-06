package com.multistore.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.multistore.core.designsystem.theme.LocalSpacing
import com.multistore.core.model.UpdateAllUiState
import com.multistore.core.ui.R

/**
 * "There are N updates" and the button that applies them, wherever that gesture is offered.
 *
 * ### Why it is one component and not one per screen
 *
 * The gesture now exists on two screens — the Home, and "My apps", which is the one that actually
 * *lists* what is updatable — and it has to mean exactly the same thing on both: the same order, the
 * same MultiStore-last rule, the same definition of "failed". The behaviour is shared already, in
 * `UpdateAllAppsUseCase`; this is the other half, so that the **sentences** cannot drift apart
 * either. Two copies of "3 updated, 1 not done" are two copies somebody eventually edits one of.
 *
 * It is the same reasoning that put the error vocabulary in this module: the same sentence has to say
 * the same thing in search, on the Home and on the detail page.
 *
 * ### What it deliberately does not draw
 *
 * The list of what is about to be updated. The Home shows those rows and "My apps" is already that
 * list, so a component drawing them would be right on one screen and duplicated on the other. The
 * caller adds them, in [extra], underneath — and only when there is a button to add them under.
 *
 * ### Why the progress is "2 of 5" and not a bar that fills by itself
 *
 * With only `SessionInstaller` every app opens the system confirmation screen: the pace is set by the
 * user, not by the network. A bar advancing on its own would state something false. The determinate
 * bar that *is* here counts confirmations, which is a real quantity.
 */
@Composable
fun UpdateAllPanel(
    state: UpdateAllUiState,
    updatable: Int,
    onUpdateAll: () -> Unit,
    onDismissResult: () -> Unit,
    modifier: Modifier = Modifier,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    // Nothing to update and no outcome to report: the panel does not exist. A card saying "0
    // updates" takes up the screen's space to say nothing.
    if (updatable == 0 && state is UpdateAllUiState.Idle) return

    val spacing = LocalSpacing.current
    Column(modifier = modifier.fillMaxWidth()) {
        when (state) {
            is UpdateAllUiState.Running -> {
                Text(
                    // `done` is zero-based and the sentence is not: "updating 0 of 5" is a count
                    // nobody reads as "the first one".
                    text = stringResource(
                        R.string.update_all_applying,
                        state.done + 1,
                        state.total,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = state.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = spacing.extraSmall),
                )
                LinearProgressIndicator(
                    progress = { (state.done + 1).toFloat() / state.total },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.small),
                )
            }

            is UpdateAllUiState.Finished -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    // Successes and failures in the same sentence: saying only "3 updated" when two
                    // were cancelled would leave the user wondering why the list did not empty.
                    text = stringResource(
                        R.string.update_all_finished,
                        state.installed,
                        state.failed,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismissResult) {
                    Text(text = stringResource(R.string.update_all_dismiss))
                }
            }

            UpdateAllUiState.Idle -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.update_all_title,
                            updatable,
                            updatable,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onUpdateAll) {
                        Text(text = stringResource(R.string.update_all_action))
                    }
                }
                extra()
            }
        }
    }
}
