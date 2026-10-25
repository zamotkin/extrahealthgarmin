package com.zamotkin.extrahealthgarmin.ui.screens.measurements

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.ui.kit.components.EmptyState

/**
 * Measurements tab, interim content (redesign v3, task xlg.1): an honest
 * empty state until blood-pressure sources exist. The real page — day-
 * grouped readings, source card with "Read now" and the autosync toggle —
 * lands with task xlg.6, gated on at least one connected source.
 */
@Composable
fun MeasurementsScreen(modifier: Modifier = Modifier) {
    EmptyState(
        title = stringResource(R.string.measurements_empty_title),
        text = stringResource(R.string.measurements_empty_text),
        modifier = modifier
            .fillMaxSize()
            .wrapContentSize(Alignment.Center),
    )
}
