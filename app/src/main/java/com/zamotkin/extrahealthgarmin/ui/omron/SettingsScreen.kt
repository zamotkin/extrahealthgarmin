package com.zamotkin.extrahealthgarmin.ui.omron

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.livedata.observeAsState
import com.zamotkin.extrahealthgarmin.R

/**
 * Settings screen (task tefal2garmin-mnb): the monitor user-slot selector
 * (persisted via [OmronSettingsStore]) and the Garmin re-login entry. No
 * export UI by design (epic anti-pattern); no background-sync settings —
 * the BP flow is foreground-interactive only.
 */
@Composable
fun SettingsScreen(viewModel: OmronViewModel) {
    val userSlot by viewModel.userSlot.observeAsState(1)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.omron_title_settings),
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.omron_settings_user_section),
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = userSlot == 1,
                onClick = { viewModel.selectUserSlot(1) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) {
                Text(stringResource(R.string.omron_user1))
            }
            SegmentedButton(
                selected = userSlot == 2,
                onClick = { viewModel.selectUserSlot(2) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) {
                Text(stringResource(R.string.omron_user2))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.omron_slot_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // Garmin re-login: delegates through the ViewModel to the activity's
        // callback (the login dialog lives in MainActivity).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.reloginGarmin() }
                .padding(vertical = 12.dp)
        ) {
            Text(
                text = stringResource(R.string.omron_settings_relogin),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
