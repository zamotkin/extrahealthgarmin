package com.zamotkin.extrahealthgarmin.ui.screens.devices

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.ui.kit.components.EhgButton
import com.zamotkin.extrahealthgarmin.ui.kit.components.EmptyState
import com.zamotkin.extrahealthgarmin.ui.kit.components.SectionCard
import com.zamotkin.extrahealthgarmin.ui.kit.components.SectionHeader
import com.zamotkin.extrahealthgarmin.ui.kit.components.StatusRow

/**
 * Connected blood-pressure sources (task
 * tefal2garmin-app-redesign-v3-xlg.3): one row per stored source (name +
 * type) leading to the detail screen, plus the add-source entry. All logic
 * stays in [SourcesViewModel]; the screen renders its LiveData and
 * forwards taps, refreshing on every (re)entry so sources added or removed
 * elsewhere show up.
 */
@Composable
fun SourceListScreen(
    onBack: () -> Unit,
    onOpenSource: (String) -> Unit,
    onAddSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: SourcesViewModel = viewModel(factory = SourcesViewModelFactory(application))
    // Re-entering the destination (back from add flow or detail) recreates
    // the screen - the refresh picks up sources added or removed meanwhile.
    LaunchedEffect(Unit) { viewModel.refresh() }
    val sources by viewModel.sources.observeAsState(emptyList())

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        BackHeader(title = stringResource(R.string.sources_title), onBack = onBack)
        Spacer(modifier = Modifier.height(16.dp))
        if (sources.isEmpty()) {
            SectionCard(modifier = Modifier.fillMaxWidth()) {
                EmptyState(
                    title = stringResource(R.string.sources_empty_title),
                    text = stringResource(R.string.sources_empty_text),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            SectionCard(modifier = Modifier.fillMaxWidth()) {
                sources.forEach { source ->
                    val type = viewModel.typeOf(source.sourceTypeId)
                    StatusRow(
                        // Singleton sources have no device name (their
                        // stored name is the type id) - render the type's
                        // display name instead.
                        label = if (source.address == null) {
                            type?.let { stringResource(it.displayNameRes) } ?: source.name
                        } else {
                            source.name
                        },
                        value = type?.let { stringResource(it.displayNameRes) },
                        onClick = { onOpenSource(source.id) },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        EhgButton(
            text = stringResource(R.string.add_source),
            onClick = onAddSource,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Screen-level header with a back arrow - the shape every non-tab screen
 * in the devices package uses (the app has no top app bar; the bottom
 * NavigationBar covers the tabs only).
 */
@Composable
internal fun BackHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        SectionHeader(text = title)
    }
}
