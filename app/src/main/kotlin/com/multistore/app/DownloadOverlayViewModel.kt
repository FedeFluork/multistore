package com.multistore.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multistore.core.data.repository.DownloadRepository
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
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        initialValue = emptyList(),
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
