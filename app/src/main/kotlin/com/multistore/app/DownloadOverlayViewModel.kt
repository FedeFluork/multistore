package com.multistore.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multistore.core.data.repository.DownloadRepository
import com.multistore.core.model.DownloadState
import com.multistore.core.ui.component.DownloadProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the progress card above the screens shows, and when it stops showing it.
 *
 * It lives in `:app` and not in a feature because the card sits **above the NavHost**: it belongs
 * to the shell, like the navigation bar. A feature could not draw it above the others without
 * depending on them, which the dependency rules forbid.
 *
 * The X does not switch the card off: it hides **the transfers there are right now**. The
 * difference shows on the next download, which must bring it back — whoever closed Firefox's card
 * did not ask never to see progress again.
 *
 * The hidden set is kept by **id**, and not with a simple flag, for the case a counter would not
 * cover: two downloads in progress, the user closes, one finishes and another starts. The number is
 * still two, but the second one is new and the card must come back. Ids that are no longer in
 * progress leave the set, so it does not grow forever in a process that lives for days.
 */
@HiltViewModel
class DownloadOverlayViewModel @Inject constructor(
    downloads: DownloadRepository,
) : ViewModel() {

    private val hidden = MutableStateFlow<Set<Long>>(emptySet())

    private val active = downloads.observeActive()

    val visible: StateFlow<List<DownloadProgress>> = combine(active, hidden) { rows, hiddenIds ->
        rows.filterNot { it.id in hiddenIds }.map { status ->
            DownloadProgress(
                id = status.id,
                title = status.title,
                fraction = status.fraction,
                // The **state**, not a full bar. Four stores out of nine do not declare the size, so
                // a finished transfer among them has a `null` fraction; and one at 99.6% rounds to a
                // full bar without having finished. Only the row knows, and this is the row.
                ready = status.state == DownloadState.READY,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        initialValue = emptyList(),
    )

    /**
     * Whether the bar's Downloads tab wears its dot.
     *
     * ### It is not filtered by [hidden], and that is the point
     *
     * Hiding the card is a statement about the card, not about the download: the struck-through eye
     * says "get this out of my way", and a badge that vanished with it would turn that gesture into
     * "forget this download" — the one reading the icon was chosen to rule out. The dot is also the
     * only thing left saying anything at all once the card is gone, so tying the two together would
     * make the app silent exactly when the user has stopped watching.
     *
     * ### Which rows count
     *
     * Three branches and not one, because [DownloadRepository.observeActive] has already excluded
     * the two terminal states and what is left is not uniform:
     *
     * - a `READY` row counts **only while its file is really there**. A row that says ready with
     *   nothing on disk should not exist — every deletion path closes the row — but if the two ever
     *   disagreed, the badge would be the symptom: a dot nobody can clear, on a tab where nothing
     *   explains it;
     * - `PAUSED` does not count. The transfer stopped, in every case because somebody or something
     *   said so, and a dot for it would be a notice about a decision already taken;
     * - everything else — queued, running, ready-with-file, installing — is either moving or waiting
     *   for a tap, which is the question the dot answers.
     */
    val badge: StateFlow<Boolean> = active.map { rows ->
        rows.any { row ->
            when (row.state) {
                DownloadState.READY -> row.file != null
                DownloadState.PAUSED -> false
                else -> true
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        initialValue = false,
    )

    /** Hides the transfers in progress right now. A new one brings the card back. */
    fun dismiss() {
        hidden.value = visible.value.map { it.id }.toSet() + hidden.value
    }

    init {
        // Ids no longer in progress leave the set: without this it grows for the whole life of the
        // process, and on an app left open for days that would be a small but endless leak.
        viewModelScope.launch {
            active.map { rows -> rows.map { it.id }.toSet() }.collect { running ->
                hidden.value = hidden.value intersect running
            }
        }
    }

    private companion object {
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L
    }
}
