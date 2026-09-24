package com.multistore.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.multistore.core.common.net.FailureKind
import com.multistore.core.common.net.StoreDiagnosis
import com.multistore.core.designsystem.theme.LocalSpacing
import com.multistore.core.model.StoreHealthState
import com.multistore.core.ui.R
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Why a store is not answering, and for how long.
 *
 * ### It lives here because two screens ask the same question
 *
 * Settings opens it from the store's row; the search opens it from the notice already standing
 * beside the results. A `:feature:*` never depends on another, so the only place the two can share
 * a sentence is here — and sharing it is the point: "apkmirror has been failing since Tuesday" must
 * not read one way in one screen and another way in the other.
 *
 * ### What it adds to the two words that were there before
 *
 * `health_events` has recorded faults since M0. Inside the app they came down to `OPEN` or
 * `DEGRADED` beside a name, and neither can answer the question a person actually has: **is this
 * today, or has it been going on for a week?** One is worth waiting out, the other is worth
 * switching the store off — and only the second sentence makes the switch beside it usable.
 */
@Composable
fun StoreDiagnosisDialog(
    diagnosis: StoreDiagnosis,
    storeName: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The moment the durations are measured from.
     *
     * A parameter with a real default rather than a read of the clock inside, for the reason every
     * duration in this app is: a golden that read the time would not be comparable with itself.
     */
    now: Instant = Clock.System.now(),
) {
    val spacing = LocalSpacing.current
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(text = storeName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                Text(
                    text = stringResource(diagnosis.state.headlineRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // How long it has been going on, and it is the line this dialog exists for. It only
                // appears while there **is** a run: on a healthy store it would be a duration since
                // nothing.
                diagnosis.failingSince?.let { since ->
                    Text(
                        text = stringResource(
                            R.string.store_diagnosis_failing_for,
                            relativeAge(now - since),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                diagnosis.lastFailure?.let { fault ->
                    Text(
                        text = stringResource(fault.kind.explanationRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // "It answered N ago" and "it has never answered" are different sentences: a store
                // that has never been queried is not a store that has stopped working, and the
                // second reading is the one that would have somebody switch off a store that is
                // simply new to them.
                Text(
                    text = diagnosis.lastSuccessAt?.let { at ->
                        stringResource(R.string.store_diagnosis_last_success, relativeAge(now - at))
                    } ?: stringResource(R.string.store_diagnosis_never_answered),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // The selectors, and only where there are some. Distinct rather than counted, for
                // the reason written on `StoreHealth`: one malformed page can produce the same
                // selector a hundred times without the parser being broken, while three *different*
                // ones say the markup changed — which is the difference between waiting and this
                // store needing its parsers repaired.
                if (diagnosis.parseFailureSelectors.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.store_diagnosis_parse_selectors,
                            diagnosis.parseFailureSelectors.sorted().joinToString(", "),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.store_diagnosis_dismiss))
            }
        },
    )
}

/**
 * The state, in a sentence rather than a keyword.
 *
 * `HALF_OPEN` is not "half broken": it is the next request being allowed through as a probe, which
 * from the reader's side is "it is about to try again". Showing the enum's name would have been the
 * screen speaking the breaker's language instead of theirs.
 */
internal val StoreHealthState.headlineRes: Int
    get() = when (this) {
        StoreHealthState.CLOSED -> R.string.store_diagnosis_state_ok
        StoreHealthState.OPEN -> R.string.store_diagnosis_state_open
        StoreHealthState.HALF_OPEN -> R.string.store_diagnosis_state_half_open
        StoreHealthState.DEGRADED -> R.string.store_diagnosis_state_degraded
    }

/**
 * What the fault was, and what it implies for the reader.
 *
 * Five sentences and not five words, because the five lead to different expectations: a rate limit
 * clears itself, a block does not, and a parse failure needs the app to be fixed rather than the
 * store to recover. `NOT_FOUND` never reaches here — see `StoreDiagnosis` — but the branch is
 * written so that the compiler notices a sixth kind.
 */
internal val FailureKind.explanationRes: Int
    get() = when (this) {
        FailureKind.TRANSIENT -> R.string.store_diagnosis_fault_transient
        FailureKind.RATE_LIMITED -> R.string.store_diagnosis_fault_rate_limited
        FailureKind.BLOCKED -> R.string.store_diagnosis_fault_blocked
        FailureKind.PARSE -> R.string.store_diagnosis_fault_parse
        FailureKind.NOT_FOUND -> R.string.store_diagnosis_fault_transient
    }

/**
 * "3 days", "4 hours", "12 minutes" — the coarsest unit that is not zero.
 *
 * Coarse on purpose: the decision this dialog supports is "wait or switch it off", and that turns
 * on the order of magnitude. "2 days" and "2 days, 4 hours" lead to the same decision, and the
 * second is longer to read.
 */
@Composable
private fun relativeAge(elapsed: kotlin.time.Duration): String {
    val days = elapsed.inWholeDays
    val hours = elapsed.inWholeHours
    val minutes = elapsed.inWholeMinutes
    return when {
        days >= 1 -> androidx.compose.ui.res.pluralStringResource(
            R.plurals.store_diagnosis_days,
            days.toInt(),
            days.toInt(),
        )

        hours >= 1 -> androidx.compose.ui.res.pluralStringResource(
            R.plurals.store_diagnosis_hours,
            hours.toInt(),
            hours.toInt(),
        )

        // Zero minutes rounds up to one rather than reading "0 minutes", which on a fault recorded
        // seconds ago would look like a bug rather than like "just now".
        else -> androidx.compose.ui.res.pluralStringResource(
            R.plurals.store_diagnosis_minutes,
            minutes.coerceAtLeast(1).toInt(),
            minutes.coerceAtLeast(1).toInt(),
        )
    }
}
