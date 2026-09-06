package com.multistore.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.multistore.core.model.MyAppsSort
import com.multistore.core.ui.R

/**
 * What each ordering criterion is called.
 *
 * It lives here for the reason the error vocabulary does: **two** surfaces name these four criteria —
 * the Settings entry that fixes the default, and the control on "My apps" that changes it — and the
 * same criterion has to be called the same thing in both. Four strings copied into two feature
 * modules are four pairs somebody eventually edits one half of, and the divergence would be invisible
 * until two screens disagreed about what the list was sorted by.
 *
 * An exhaustive `when` with no `else`: a fifth criterion must not compile until somebody has decided
 * what to call it in all five languages.
 */
@Composable
fun myAppsSortLabel(sort: MyAppsSort): String = stringResource(
    when (sort) {
        MyAppsSort.NAME -> R.string.myapps_sort_name
        MyAppsSort.UPDATABLE_FIRST -> R.string.myapps_sort_updatable
        MyAppsSort.RECENTLY_INSTALLED -> R.string.myapps_sort_recent
        MyAppsSort.STORE -> R.string.myapps_sort_store
    },
)
