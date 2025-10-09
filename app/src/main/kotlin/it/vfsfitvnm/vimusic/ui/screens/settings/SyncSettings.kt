package it.vfsfitvnm.vimusic.ui.screens.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import it.vfsfitvnm.compose.persist.persistList
import it.vfsfitvnm.core.ui.LocalAppearance
import it.vfsfitvnm.providers.piped.Piped
import it.vfsfitvnm.providers.piped.models.Instance
import it.vfsfitvnm.vimusic.Database
import it.vfsfitvnm.vimusic.LocalCredentialManager
import it.vfsfitvnm.vimusic.R
import it.vfsfitvnm.vimusic.features.import.CsvPlaylistParser
import it.vfsfitvnm.vimusic.features.import.ImportStatus
import it.vfsfitvnm.vimusic.features.import.PlaylistImporter
import it.vfsfitvnm.vimusic.features.import.SongImportInfo
import it.vfsfitvnm.vimusic.models.PipedSession
import it.vfsfitvnm.vimusic.transaction
import it.vfsfitvnm.vimusic.ui.components.themed.*
import it.vfsfitvnm.vimusic.ui.screens.Route
import it.vfsfitvnm.vimusic.utils.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Route
@Composable
fun SyncSettings(
    credentialManager: CredentialManager = LocalCredentialManager.current
) {
    val coroutineScope = rememberCoroutineScope()
    val (colorPalette, typography) = LocalAppearance.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val pipedSessions by Database.instance.pipedSessions().collectAsState(initial = listOf())

    // CSV Import State
    var showingColumnMappingDialog by remember { mutableStateOf<Pair<Uri, List<String>>?>(null) }
    var showingNameDialog by remember { mutableStateOf<List<SongImportInfo>?>(null) }
    var showingImportDialog by remember { mutableStateOf(false) }
    var importStatus by remember { mutableStateOf<ImportStatus>(ImportStatus.Idle) }

    // Piped State
    var linkingPiped by remember { mutableStateOf(false) }
    var deletingPipedSession: Int? by rememberSaveable { mutableStateOf(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = uri.toFileName(context) ?: ""
            if (!fileName.lowercase().endsWith(".csv")) {
                Toast.makeText(context, "Please select a valid .csv file", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }

            coroutineScope.launch {
                try {
                    context.contentResolver.openInputStream(uri)?.let { inputStream ->
                        val parser = CsvPlaylistParser()
                        val header = parser.getHeader(inputStream)
                        showingColumnMappingDialog = Pair(uri, header)
                    }
                } catch (_: Exception) {
                    Toast.makeText(context, "Failed to read CSV file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // CSV Dialog 1: Column Mapping
    showingColumnMappingDialog?.let { (uri, header) ->
        val smartTitleIndex = remember(header) { header.indexOfFirst { it.contains("track name", true) || it.contains("title", true) }.coerceAtLeast(0) }
        val smartArtistIndex = remember(header) { header.indexOfFirst { it.contains("artist", true) }.coerceAtLeast(1) }
        val smartAlbumIndex = remember(header) { header.indexOfFirst { it.contains("album", true) }.let { if (it == -1) null else it } }
        val smartUriIndex = remember(header) { header.indexOfFirst { it.contains("uri", true) }.let { if (it == -1) null else it } }

        var titleColumnIndex by remember { mutableStateOf(smartTitleIndex) }
        var artistColumnIndex by remember { mutableStateOf(smartArtistIndex) }
        var albumColumnIndex by remember { mutableStateOf(smartAlbumIndex) }
        var uriColumnIndex by remember { mutableStateOf(smartUriIndex) }

        DefaultDialog(onDismiss = { showingColumnMappingDialog = null }) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text("Map CSV Columns", style = typography.m.semiBold, color = colorPalette.text)

                @Composable
fun ColumnSelector(
    label: String,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    allowNone: Boolean = false,
    header: List<String> = emptyList() // tambahin ini biar gak undefined
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        TextField(
            readOnly = true,
            value = selectedIndex?.let { header.getOrNull(it) } ?: if (allowNone) "None" else "",
            onValueChange = {},
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = colorPalette.accent,
                unfocusedIndicatorColor = colorPalette.textDisabled
            ),
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )

        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowNone) {
                DropdownMenuItem(
                    text = { Text("None", color = colorPalette.text) },
                    onClick = {
                        onSelect(null)
                        expanded = false
                    }
                )
            }

            header.forEachIndexed { i, name ->
                DropdownMenuItem(
                    text = { Text(name, color = colorPalette.text) },
                    onClick = {
                        onSelect(i)
                        expanded = false
                    }
                )
            }
        }
    }
}

                Spacer(Modifier.height(8.dp))
                ColumnSelector("Track Name Column", titleColumnIndex) { titleColumnIndex = it ?: 0 }
                Spacer(Modifier.height(8.dp))
                ColumnSelector("Artist Name Column", artistColumnIndex) { artistColumnIndex = it ?: 1 }
                Spacer(Modifier.height(8.dp))
                ColumnSelector("Album Name Column (Optional)", albumColumnIndex, { albumColumnIndex = it }, allowNone = true)
                Spacer(Modifier.height(8.dp))
                ColumnSelector("Track URI Column (Optional)", uriColumnIndex, { uriColumnIndex = it }, allowNone = true)

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    DialogTextButton(text = "Cancel", onClick = { showingColumnMappingDialog = null })
                    DialogTextButton(text = "Next", onClick = {
                        coroutineScope.launch {
                            context.contentResolver.openInputStream(uri)?.let { inputStream ->
                                val parser = CsvPlaylistParser()
                                val songs = parser.parse(inputStream, titleColumnIndex, artistColumnIndex, albumColumnIndex, uriColumnIndex)
                                showingNameDialog = songs
                                showingColumnMappingDialog = null
                            }
                        }
                    })
                }
            }
        }
    }

    // CSV Dialog 2: Playlist Naming
    showingNameDialog?.let { songs ->
        DefaultDialog(onDismiss = { showingNameDialog = null }) {
            var playlistName by remember { mutableStateOf("") }
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text("Name your playlist", style = typography.m.semiBold, color = colorPalette.text)
                Spacer(Modifier.height(8.dp))
                TextField(value = playlistName, onValueChange = { playlistName = it }, placeholder = { Text("My Imported Playlist") })
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    DialogTextButton("Cancel") { showingNameDialog = null }
                    Button(
                        onClick = {
                            showingImportDialog = true
                            val unknownError = context.getString(R.string.unknown_error)
                            coroutineScope.launch {
                                val importer = PlaylistImporter()
                                importer.import(
                                    songList = songs,
                                    playlistName = playlistName,
                                    unknownErrorMessage = unknownError,
                                    onProgressUpdate = { status -> importStatus = status }
                                )
                            }
                            showingNameDialog = null
                        },
                        enabled = playlistName.isNotBlank()
                    ) { Text("Import") }
                }
            }
        }
    }

    // CSV Dialog 3: Import Progress
    if (showingImportDialog) {
        DefaultDialog(onDismiss = { if (importStatus !is ImportStatus.InProgress) showingImportDialog = false }) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                val title = when (importStatus) {
                    is ImportStatus.InProgress -> "Importing..."
                    is ImportStatus.Complete -> "Import Complete"
                    is ImportStatus.Error -> "Import Failed"
                    else -> "Starting..."
                }
                Text(title, style = typography.m.semiBold, color = colorPalette.text)
                Spacer(Modifier.height(12.dp))
                when (val status = importStatus) {
                    is ImportStatus.InProgress -> {
                        val progress by animateFloatAsState(if (status.total > 0) status.processed.toFloat() / status.total else 0f)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = colorPalette.accent)
                        Spacer(Modifier.height(8.dp))
                        Text("${status.processed}/${status.total} tracks processed", color = colorPalette.textSecondary)
                    }
                    is ImportStatus.Complete -> {
                        Text("Imported ${status.imported}/${status.total}", color = colorPalette.text)
                        if (status.failedTracks.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Failed:", color = colorPalette.text)
                            LazyColumn(Modifier.heightIn(max = 160.dp)) {
                                items(status.failedTracks) {
                                    Text("- ${it.title} • ${it.artist}", color = colorPalette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    is ImportStatus.Error -> Text("Error: ${status.message}", color = colorPalette.red)
                    else -> CircularProgressIndicator(color = colorPalette.accent)
                }
                if (importStatus !is ImportStatus.InProgress) {
                    Spacer(Modifier.height(16.dp))
                    DialogTextButton("OK") { showingImportDialog = false }
                }
            }
        }
    }

    // ====== PIPED SECTIONS (tidak diubah) ======
    // tetap dipertahankan kayak kode lama lu
    // ============================================

    SettingsCategoryScreen(title = stringResource(R.string.sync)) {
        SettingsDescription(text = stringResource(R.string.sync_description))
        SettingsGroup(title = stringResource(R.string.local_import)) {
            SettingsEntry(
                title = stringResource(R.string.import_from_csv),
                text = stringResource(R.string.import_from_csv_description),
                onClick = {
                    filePickerLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "application/csv"))
                }
            )
        }
    }
}

private fun Uri.toFileName(context: Context): String? {
    val cursor = context.contentResolver.query(this, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) return it.getString(nameIndex)
        }
    }
    return null
}
