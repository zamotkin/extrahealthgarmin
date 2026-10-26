package com.zamotkin.extrahealthgarmin.ui.screens.measurements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSource
import com.zamotkin.extrahealthgarmin.ui.kit.components.EhgButton
import com.zamotkin.extrahealthgarmin.ui.kit.components.EmptyState
import com.zamotkin.extrahealthgarmin.ui.kit.components.SectionCard
import com.zamotkin.extrahealthgarmin.ui.kit.components.SectionHeader
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

/**
 * The Measurements page (task tefal2garmin-app-redesign-v3-xlg.6, epic R6):
 * one blood-pressure page absorbing the old Omron dashboard - one card per
 * connected source (name/type, last-read summary, the per-source autosync
 * toggle, Read now) over the day-grouped readings of every source (date/
 * time, sys/dia, pulse, synced-to-Garmin state, source attribution, MOV/IHB
 * badges). Large histories open on a capped window with "show more".
 *
 * The page exists only while a source is connected (epic R8): the scaffold
 * hides the tab when [MeasurementsViewModel.tabVisible] is false, so the
 * no-source empty state below is the defensive fallback for a stale
 * navigation, while a connected-but-empty source set shows the readings
 * empty state instead. All logic stays in [MeasurementsViewModel]; this
 * file renders its LiveData and forwards taps.
 */
@Composable
fun MeasurementsScreen(
    viewModel: MeasurementsViewModel,
    modifier: Modifier = Modifier,
) {
    val cards by viewModel.sourceCards.observeAsState(emptyList())
    val records by viewModel.records.observeAsState(MeasurementsListState(emptyList(), 0))

    if (cards.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.measurements_empty_title),
            text = stringResource(R.string.measurements_empty_text),
            modifier = modifier
                .fillMaxSize()
                .wrapContentSize(Alignment.Center),
        )
        return
    }

    val readingsCount = records.entries.count { it is MeasurementsEntry.Reading } +
        records.hiddenReadingsCount

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = KEY_SCREEN_HEADER, contentType = CONTENT_TYPE_HEADER) {
            SectionHeader(text = stringResource(R.string.nav_measurements))
        }
        items(
            items = cards,
            key = { "${KEY_SOURCE_CARD}-${it.source.id}" },
            contentType = { CONTENT_TYPE_SOURCE_CARD },
        ) { card ->
            SourceCard(card = card, viewModel = viewModel)
        }
        item(key = KEY_COUNT_LINE, contentType = CONTENT_TYPE_HEADER) {
            Text(
                text = stringResource(R.string.measurement_readings_count, readingsCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (records.entries.isEmpty()) {
            // Sources are connected but nothing was read yet - distinct from
            // the no-source state above (there the tab is hidden entirely).
            item(key = KEY_EMPTY_READINGS, contentType = CONTENT_TYPE_HEADER) {
                EmptyState(
                    title = stringResource(R.string.measurements_empty_title),
                    text = stringResource(R.string.measurement_readings_empty_text),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            items(
                items = records.entries,
                key = { entry -> entry.key() },
                contentType = { entry -> entry.contentType() },
            ) { entry ->
                when (entry) {
                    is MeasurementsEntry.DayHeader -> DayHeaderText(entry.epochDay)

                    is MeasurementsEntry.Reading -> ReadingRow(
                        entry = entry,
                        sourceLabel = sourceLabel(entry.source, viewModel),
                    )
                }
            }
            if (records.hiddenReadingsCount > 0) {
                item(key = KEY_SHOW_MORE, contentType = CONTENT_TYPE_SHOW_MORE) {
                    EhgButton(
                        text = stringResource(
                            R.string.measurement_show_more,
                            records.hiddenReadingsCount
                        ),
                        onClick = viewModel::showMore,
                        tonal = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** One connected source: identity, last-read summary, autosync, Read now. */
@Composable
private fun SourceCard(card: SourceCardState, viewModel: MeasurementsViewModel) {
    val source = card.source
    val type = viewModel.typeOf(source.sourceTypeId)
    SectionCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = sourceLabel(source, viewModel),
            style = MaterialTheme.typography.titleMedium,
        )
        type?.let {
            Text(
                text = stringResource(it.displayNameRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = source.lastReadAnchor?.let { anchor ->
                stringResource(R.string.measurement_source_last_reading, formatDateTime(anchor))
            } ?: stringResource(R.string.measurement_source_never_read),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.measurement_autosync),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = source.autoSync,
                onCheckedChange = { enabled -> viewModel.setAutoSync(source.id, enabled) }
            )
        }
        Text(
            text = stringResource(R.string.measurement_autosync_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        // Haptic confirm on the primary action (task xlg.8): the tap that
        // starts the read pass acknowledges itself.
        val haptics = LocalHapticFeedback.current
        EhgButton(
            text = stringResource(R.string.measurement_read_now),
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                viewModel.readNow(source.id)
            },
            enabled = card.status != ReadNowState.Reading,
            modifier = Modifier.fillMaxWidth(),
        )
        StatusLine(card.status)
    }
}

/**
 * The source card's status line - the old dashboard's state-line pattern:
 * a progress line while a pass runs, the mapped terminal message after it.
 */
@Composable
private fun StatusLine(status: ReadNowState) {
    when (status) {
        ReadNowState.Idle -> Unit

        ReadNowState.Reading -> {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.measurement_read_running),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        is ReadNowState.Finished -> {
            Spacer(modifier = Modifier.height(8.dp))
            val message = buildString {
                val counters = status.counters
                append(
                    when {
                        counters != null -> stringResource(
                            R.string.measurement_read_pipeline_summary,
                            counters.uploaded,
                            counters.waiting,
                            counters.dropped
                        )

                        status.count != null ->
                            stringResource(status.messageRes, status.count)

                        else -> stringResource(status.messageRes)
                    }
                )
                status.detail?.let { append("\n").append(it) }
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (status.isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

/**
 * One reading line (absorbed from the old dashboard's ReadingRow): sys/dia
 * + pulse and the time with the source attribution on the left, MOV/IHB
 * badges and the Garmin sync state on the right - the sync states carry
 * text labels, never color alone (WCAG 1.4.1).
 */
@Composable
private fun ReadingRow(entry: MeasurementsEntry.Reading, sourceLabel: String) {
    val reading = entry.reading
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val headline = stringResource(
                R.string.measurement_reading_sys_dia,
                reading.systolic,
                reading.diastolic
            )
            Text(
                text = if (reading.pulse > 0) {
                    headline + "  ·  " +
                        stringResource(R.string.measurement_reading_pulse, reading.pulse)
                } else {
                    headline
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = formatTime(reading.timestampEpochSec) + "  ·  " + sourceLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (reading.mov) {
            ReadingBadge(R.string.measurement_badge_mov)
            Spacer(modifier = Modifier.width(4.dp))
        }
        if (reading.ihb) {
            ReadingBadge(R.string.measurement_badge_ihb)
            Spacer(modifier = Modifier.width(4.dp))
        }
        SyncStateBadge(synced = reading.synced)
    }
}

@Composable
private fun ReadingBadge(labelRes: Int) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/** The Garmin upload state of one reading - text-labeled, color-supported. */
@Composable
private fun SyncStateBadge(synced: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (synced) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Text(
            text = stringResource(
                if (synced) R.string.measurement_state_synced
                else R.string.measurement_state_unsynced
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (synced) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun DayHeaderText(epochDay: Long) {
    val formatter = remember {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
    }
    Text(
        text = remember(epochDay) { LocalDate.ofEpochDay(epochDay).format(formatter) },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * The user-visible name of a source: BLE sources are named after their
 * device; singleton sources (Health Connect) take the type's display name
 * - the same resolution as the source detail screen.
 */
@Composable
private fun sourceLabel(source: ConnectedSource, viewModel: MeasurementsViewModel): String =
    when {
        source.address != null -> source.name
        else -> viewModel.typeOf(source.sourceTypeId)
            ?.let { type -> stringResource(type.displayNameRes) }
            ?: source.name
    }

@Composable
private fun formatDateTime(timestampEpochSec: Long): String {
    val formatter = remember {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
    }
    return remember(timestampEpochSec) { formatter.format(Date(timestampEpochSec * 1000L)) }
}

@Composable
private fun formatTime(timestampEpochSec: Long): String {
    val formatter = remember {
        DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())
    }
    return remember(timestampEpochSec) { formatter.format(Date(timestampEpochSec * 1000L)) }
}

/** Stable LazyColumn key of one entry: the row's unique (source, timestamp, slot). */
private fun MeasurementsEntry.key(): String = when (this) {
    is MeasurementsEntry.DayHeader -> "${KEY_DAY}-$epochDay"
    is MeasurementsEntry.Reading ->
        "${KEY_READING}-${reading.sourceId}-${reading.timestampEpochSec}-${reading.userSlot}"
}

/** LazyColumn content type of one entry (composition reuse hint). */
private fun MeasurementsEntry.contentType(): String = when (this) {
    is MeasurementsEntry.DayHeader -> CONTENT_TYPE_DAY_HEADER
    is MeasurementsEntry.Reading -> CONTENT_TYPE_READING_ROW
}

private const val KEY_SCREEN_HEADER = "screen-header"
private const val KEY_SOURCE_CARD = "source-card"
private const val KEY_COUNT_LINE = "count-line"
private const val KEY_EMPTY_READINGS = "empty-readings"
private const val KEY_SHOW_MORE = "show-more"
private const val KEY_DAY = "day"
private const val KEY_READING = "reading"
private const val CONTENT_TYPE_HEADER = "header"
private const val CONTENT_TYPE_SOURCE_CARD = "source-card"
private const val CONTENT_TYPE_DAY_HEADER = "day-header"
private const val CONTENT_TYPE_READING_ROW = "reading-row"
private const val CONTENT_TYPE_SHOW_MORE = "show-more"
