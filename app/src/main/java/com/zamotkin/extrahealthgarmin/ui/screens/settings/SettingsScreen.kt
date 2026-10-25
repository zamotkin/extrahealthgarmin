package com.zamotkin.extrahealthgarmin.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.ui.kit.components.EmptyState

/**
 * Settings tab, interim content (redesign v3, task xlg.1): an honest
 * placeholder while every service function still lives on Home (the
 * faithful migration keeps them there). The grouped Connections / Garmin /
 * Health Connect / About layout lands with task xlg.7.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    EmptyState(
        title = stringResource(R.string.nav_settings),
        text = stringResource(R.string.settings_interim_text),
        modifier = modifier
            .fillMaxSize()
            .wrapContentSize(Alignment.Center),
    )
}
