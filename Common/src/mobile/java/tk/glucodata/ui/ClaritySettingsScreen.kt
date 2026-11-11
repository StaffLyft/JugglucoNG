package tk.glucodata.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import java.io.File
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import tk.glucodata.ClarityStatus
import tk.glucodata.R
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsSwitchItem

private const val CLARITY_PROVIDER_LIBRARY = "liblibre_receiver_skb.so"
private const val EARLIEST_START_SECONDS = 1598911200L

internal fun clarityProviderPackaged(context: Context): Boolean =
    File(context.applicationInfo.nativeLibraryDir, CLARITY_PROVIDER_LIBRARY).isFile

@Composable
internal fun ClarityMessage.resolve(): String = when (this) {
    is ClarityMessage.Text -> text
    is ClarityMessage.Resource -> if (detail == null) stringResource(id) else stringResource(id, detail)
}

private fun localDateOf(epochSeconds: Long): LocalDate =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalDate()

private fun pickerMillisOf(epochSeconds: Long): Long =
    localDateOf(epochSeconds).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun startSecondsOfPickerMillis(pickerMillis: Long): Long =
    Instant.ofEpochMilli(pickerMillis).atZone(ZoneOffset.UTC).toLocalDate()
        .atStartOfDay(ZoneId.systemDefault()).toEpochSecond()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaritySettingsScreen(navController: NavController) {
    val model: ClaritySettingsViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val providerPackaged = remember { clarityProviderPackaged(context) }
    var showDatePicker by remember { mutableStateOf(false) }
    var proposedStart by remember { mutableStateOf<Long?>(null) }

    val current = state.status
    val available = current?.available == true
    val signedIn = available && !current.account.isNullOrEmpty()
    val uploading = current?.enabled == true

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.clarity_title),
                onNavigateBack = { navController.popBackStack() },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!providerPackaged) {
                ClarityNoticeCard(stringResource(R.string.clarity_provider_missing))
            }

            MasterSwitchCard(
                title = stringResource(R.string.clarity_send_enabled),
                subtitle = stringResource(R.string.clarity_send_enabled_desc),
                checked = uploading,
                onCheckedChange = { enabled ->
                    current?.let { model.configure(enabled, it.libre3History, it.since) }
                },
                icon = Icons.Default.CloudUpload,
                enabled = signedIn || uploading,
            )

            if (current != null) {
                ClarityStatusCard(current)
            }

            state.message?.let {
                Text(
                    text = it.resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SectionLabel(text = stringResource(R.string.clarity_account), topPadding = 0.dp)

            SettingsItem(
                title = if (signedIn) current.account else stringResource(R.string.clarity_not_signed_in),
                subtitle = stringResource(
                    if (signedIn) R.string.clarity_signed_in_desc else R.string.clarity_sign_in_desc
                ),
                icon = Icons.Default.AccountCircle,
                iconTint = MaterialTheme.colorScheme.tertiary,
            )

            Button(
                onClick = {
                    model.clearMessage()
                    navController.navigate("settings/clarity/login")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                enabled = available && providerPackaged && !uploading,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text(
                    stringResource(
                        if (signedIn) R.string.clarity_sign_in_other else R.string.clarity_sign_in
                    )
                )
            }

            if (signedIn && uploading) {
                Text(
                    text = stringResource(R.string.clarity_turn_off_to_change_account),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionLabel(text = stringResource(R.string.clarity_upload_section), topPadding = 8.dp)

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val since = current?.since ?: 0L
                SettingsItem(
                    title = stringResource(R.string.clarity_start_date),
                    subtitle = if (since > 0L) {
                        DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                            .format(Date(since * 1000L))
                    } else {
                        null
                    },
                    icon = Icons.Default.Event,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    position = CardPosition.TOP,
                    onClick = if (available) {
                        { showDatePicker = true }
                    } else {
                        null
                    },
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.clarity_use_history),
                    subtitle = stringResource(R.string.clarity_use_history_desc),
                    checked = current?.libre3History == true,
                    onCheckedChange = { history ->
                        current?.let { model.configure(it.enabled, history, it.since) }
                    },
                    icon = Icons.Default.History,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    position = CardPosition.BOTTOM,
                    enabled = available,
                )
            }

            OutlinedButton(
                onClick = model::sendNow,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                enabled = uploading,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text(stringResource(R.string.sendnow))
            }

            Text(
                text = stringResource(R.string.clarity_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDatePicker && current != null) {
        val today = remember { LocalDate.now() }
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = pickerMillisOf(current.since),
            yearRange = localDateOf(EARLIEST_START_SECONDS).year..today.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val date = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return !date.isBefore(localDateOf(EARLIEST_START_SECONDS)) && !date.isAfter(today)
                }
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val picked = datePickerState.selectedDateMillis ?: return@TextButton
                        showDatePicker = false
                        val start = startSecondsOfPickerMillis(picked)
                            .coerceIn(EARLIEST_START_SECONDS, System.currentTimeMillis() / 1000L)
                        if (start != current.since) proposedStart = start
                    },
                    enabled = datePickerState.selectedDateMillis != null,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDatePicker = false },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    proposedStart?.let { start ->
        AlertDialog(
            onDismissRequest = { proposedStart = null },
            title = { Text(stringResource(R.string.clarity_change_start_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.clarity_change_start_message,
                        DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                            .format(Date(start * 1000L))
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        proposedStart = null
                        current?.let { model.configure(it.enabled, it.libre3History, start) }
                    },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { proposedStart = null },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ClarityNoticeCard(text: String) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun ClarityStatusCard(status: ClarityStatus) {
    val never = stringResource(R.string.clarity_never)
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()) }
    fun timestamp(epochSeconds: Long): String =
        if (epochSeconds <= 0L) never else format.format(Date(epochSeconds * 1000L))

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.status),
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            if (status.status.isNotEmpty()) {
                Text(text = status.status, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = stringResource(R.string.clarity_last_ack, timestamp(status.lastSuccess)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.clarity_ack_count, status.acknowledgedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status.acknowledgedCount > 0L) {
                Text(
                    text = stringResource(R.string.clarity_ack_latest, timestamp(status.acknowledgedLast)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (status.pendingCount > 0L) {
                Text(
                    text = stringResource(
                        R.string.clarity_pending_glucose,
                        status.pendingCount,
                        timestamp(status.pendingFirst),
                        timestamp(status.pendingLast)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
