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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
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
import io.ktor.http.Url
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
                Toast.makeText(
                    context,
                    "Please select a valid .csv file",
                    Toast.LENGTH_SHORT
                ).show()
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
                    Toast.makeText(
                        context,
                        "Failed to read CSV file",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // CSV Dialog 1: Column Mapping
    showingColumnMappingDialog?.let { (uri, header) ->
        val smartTitleIndex = remember(header) { header.indexOfFirst { it.contains("name", ignoreCase = true) || it.contains("title", ignoreCase = true) }.coerceAtLeast(0) }
        val smartArtistIndex = remember(header) { header.indexOfFirst { it.contains("artist", ignoreCase = true) }.coerceAtLeast(if (smartTitleIndex == 1) 0 else 1) }
        val smartAlbumIndex = remember(header) { header.indexOfFirst { it.contains("album", ignoreCase = true) }.let { if (it == -1) null else it } }

        var titleColumnIndex by remember { mutableStateOf(smartTitleIndex) }
        var artistColumnIndex by remember { mutableStateOf(smartArtistIndex) }
        var albumColumnIndex by remember { mutableStateOf(smartAlbumIndex) }

        var isTitleExpanded by remember { mutableStateOf(false) }
        var isArtistExpanded by remember { mutableStateOf(false) }
        var isAlbumExpanded by remember { mutableStateOf(false) }

        DefaultDialog(onDismiss = { showingColumnMappingDialog = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(all = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(text = stringResource(R.string.map_csv_columns), style = typography.m.semiBold, color = colorPalette.text)

                ExposedDropdownMenuBox(expanded = isTitleExpanded, onExpandedChange = { isTitleExpanded = it }) {
                    TextField(
                        readOnly = true, value = header.getOrElse(titleColumnIndex) { "" }, onValueChange = {},
                        label = { Text(stringResource(R.string.song_title_column)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isTitleExpanded) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = colorPalette.accent, unfocusedIndicatorColor = colorPalette.textDisabled,
                            focusedLabelColor = colorPalette.accent, unfocusedLabelColor = colorPalette.textSecondary,
                            focusedTextColor = colorPalette.text, unfocusedTextColor = colorPalette.text,
                        ),
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = isTitleExpanded, onDismissRequest = { isTitleExpanded = false }, modifier = Modifier.background(colorPalette.background2)) {
                        header.forEachIndexed { index, column ->
                            DropdownMenuItem(text = { Text(column, color = colorPalette.text) }, onClick = {
                                titleColumnIndex = index
                                isTitleExpanded = false
                            })
                        }
                    }
                }

                ExposedDropdownMenuBox(expanded = isArtistExpanded, onExpandedChange = { isArtistExpanded = it }) {
                    TextField(
                        readOnly = true, value = header.getOrElse(artistColumnIndex) { "" }, onValueChange = {},
                        label = { Text(stringResource(R.string.artist_name_column)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isArtistExpanded) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = colorPalette.accent, unfocusedIndicatorColor = colorPalette.textDisabled,
                            focusedLabelColor = colorPalette.accent, unfocusedLabelColor = colorPalette.textSecondary,
                            focusedTextColor = colorPalette.text, unfocusedTextColor = colorPalette.text,
                        ),
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = isArtistExpanded, onDismissRequest = { isArtistExpanded = false }, modifier = Modifier.background(colorPalette.background2)) {
                        header.forEachIndexed { index, column ->
                            DropdownMenuItem(text = { Text(column, color = colorPalette.text) }, onClick = {
                                artistColumnIndex = index
                                isArtistExpanded = false
                            })
                        }
                    }
                }

                // New dropdown for Album Column (Optional)
                ExposedDropdownMenuBox(expanded = isAlbumExpanded, onExpandedChange = { isAlbumExpanded = it }) {
                    TextField(
                        readOnly = true, value = albumColumnIndex?.let { header.getOrNull(it) } ?: "None", onValueChange = {},
                        label = { Text("Album Name Column (Optional)") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isAlbumExpanded) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = colorPalette.accent, unfocusedIndicatorColor = colorPalette.textDisabled,
                            focusedLabelColor = colorPalette.accent, unfocusedLabelColor = colorPalette.textSecondary,
                            focusedTextColor = colorPalette.text, unfocusedTextColor = colorPalette.text,
                        ),
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = isAlbumExpanded, onDismissRequest = { isAlbumExpanded = false }, modifier = Modifier.background(colorPalette.background2)) {
                        DropdownMenuItem(text = { Text("None", color = colorPalette.text) }, onClick = {
                            albumColumnIndex = null
                            isAlbumExpanded = false
                        })
                        header.forEachIndexed { index, column ->
                            DropdownMenuItem(text = { Text(column, color = colorPalette.text) }, onClick = {
                                albumColumnIndex = index
                                isAlbumExpanded = false
                            })
                        }
                    }
                }


                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    DialogTextButton(text = stringResource(R.string.cancel), onClick = { showingColumnMappingDialog = null }, modifier = Modifier.align(Alignment.CenterStart))
                    DialogTextButton(text = stringResource(R.string.next), onClick = {
                        coroutineScope.launch {
                            context.contentResolver.openInputStream(uri)?.let { inputStream ->
                                val parser = CsvPlaylistParser()
                                val songList = parser.parse(inputStream, titleColumnIndex, artistColumnIndex, albumColumnIndex)
                                showingNameDialog = songList
                                showingColumnMappingDialog = null
                            }
                        }
                    }, modifier = Modifier.align(Alignment.CenterEnd))
                }
            }
        }
    }

    // CSV Dialog 2: Name Playlist
    if (showingNameDialog != null) {
        DefaultDialog(onDismiss = { showingNameDialog = null }) {
            var playlistName by remember { mutableStateOf("") }
            Column(modifier = Modifier
                .fillMaxWidth()
                .padding(all = 24.dp)) {
                Text(text = stringResource(R.string.name_your_playlist_title), style = typography.m.semiBold, modifier = Modifier.padding(bottom = 16.dp))
                TextField(value = playlistName, onValueChange = { playlistName = it }, hintText = stringResource(R.string.playlist_name_hint))
                Spacer(modifier = Modifier.height(24.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    DialogTextButton(text = stringResource(R.string.cancel), onClick = { showingNameDialog = null }, modifier = Modifier.align(Alignment.CenterStart))
                    Button(
                        onClick = {
                            showingImportDialog = true
                            val songList = showingNameDialog ?: emptyList()
                            val unknownErrorString = context.getString(R.string.unknown_error)
                            showingNameDialog = null

                            coroutineScope.launch {
                                val importer = PlaylistImporter()
                                importer.import(
                                    songList = songList,
                                    playlistName = playlistName,
                                    unknownErrorMessage = unknownErrorString,
                                    onProgressUpdate = { status -> importStatus = status }
                                )
                            }
                        },
                        enabled = playlistName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorPalette.accent, contentColor = colorPalette.text,
                            disabledContainerColor = Color.Transparent, disabledContentColor = colorPalette.textDisabled
                        ),
                        elevation = ButtonDefaults.buttonElevation(0.dp),
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Text(stringResource(R.string.create_playlist_button))
                    }
                }
            }
        }
    }

    // CSV Dialog 3: Import Progress
    if (showingImportDialog) {
        DefaultDialog(onDismiss = {
            if (importStatus !is ImportStatus.InProgress) {
                showingImportDialog = false
            }
        }) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .padding(all = 24.dp)) {
                val title = when (importStatus) {
                    is ImportStatus.InProgress -> stringResource(R.string.import_title_in_progress)
                    is ImportStatus.Complete -> stringResource(R.string.import_title_complete)
                    is ImportStatus.Error -> stringResource(R.string.import_title_failed)
                    is ImportStatus.Idle -> stringResource(R.string.import_title_starting)
                }
                Text(text = title, style = typography.m.semiBold, color = colorPalette.text)
                Spacer(modifier = Modifier.height(16.dp))

                when (val status = importStatus) {
                    is ImportStatus.InProgress -> {
                        val progress by animateFloatAsState(targetValue = if (status.total > 0) status.processed.toFloat() / status.total else 0f, label = "ImportProgress")
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = colorPalette.accent, trackColor = colorPalette.accent.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = stringResource(R.string.import_progress, status.processed, status.total), style = typography.s, color = colorPalette.textSecondary)
                    }
                    is ImportStatus.Complete -> {
                        Text(text = stringResource(R.string.import_complete_summary, status.imported, status.total), style = typography.s, color = colorPalette.text)
                        if (status.failedTracks.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(text = stringResource(R.string.import_title_failed), style = typography.s.semiBold, color = colorPalette.text)
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).border(1.dp, colorPalette.background2, RoundedCornerShape(8.dp))) {
                                items(status.failedTracks) { failedTrack ->
                                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                                        Text(text = failedTrack.title, style = typography.s, color = colorPalette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(text = failedTrack.artist, style = typography.xs, color = colorPalette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                    is ImportStatus.Error -> Text(text = stringResource(R.string.import_error_message, status.message), color = colorPalette.red, style = typography.s)
                    is ImportStatus.Idle -> {
                        CircularProgressIndicator(color = colorPalette.accent)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = stringResource(R.string.import_initializing), style = typography.s, color = colorPalette.textSecondary)
                    }
                }

                if (importStatus !is ImportStatus.InProgress) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        DialogTextButton(text = stringResource(R.string.dialog_ok), onClick = { showingImportDialog = false }, modifier = Modifier.align(Alignment.CenterEnd))
                    }
                }
            }
        }
    }

    // Piped Dialog 1: Linking
    if (linkingPiped) DefaultDialog(
        onDismiss = { linkingPiped = false },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        var isLoading by rememberSaveable { mutableStateOf(false) }
        var hasError by rememberSaveable { mutableStateOf(false) }
        var successful by remember { mutableStateOf(false) }

        when {
            successful -> BasicText(
                text = stringResource(R.string.piped_session_created_successfully),
                style = typography.xs.semiBold.center,
                modifier = Modifier.padding(all = 24.dp)
            )

            hasError -> ConfirmationDialogBody(
                text = stringResource(R.string.error_piped_link),
                onDismiss = { },
                onCancel = { linkingPiped = false },
                onConfirm = { hasError = false }
            )

            isLoading -> CircularProgressIndicator(modifier = Modifier.padding(all = 8.dp))

            else -> Box(modifier = Modifier.fillMaxWidth()) {
                var backgroundLoading by rememberSaveable { mutableStateOf(false) }
                if (backgroundLoading) CircularProgressIndicator(modifier = Modifier.align(Alignment.TopEnd))

                Column(modifier = Modifier.fillMaxWidth()) {
                    var instances by persistList<Instance>(tag = "settings/sync/piped/instances")
                    var loadingInstances by rememberSaveable { mutableStateOf(true) }
                    var selectedInstance: Int? by rememberSaveable { mutableStateOf(null) }
                    var username by rememberSaveable { mutableStateOf("") }
                    var password by rememberSaveable { mutableStateOf("") }
                    var canSelect by rememberSaveable { mutableStateOf(true) }
                    var instancesUnavailable by rememberSaveable { mutableStateOf(false) }
                    var customInstance: String? by rememberSaveable { mutableStateOf(null) }

                    LaunchedEffect(Unit) {
                        Piped.getInstances()?.getOrNull()?.let {
                            selectedInstance = null
                            instances = it.toImmutableList()
                            canSelect = true
                        } ?: run { instancesUnavailable = true }
                        loadingInstances = false

                        backgroundLoading = true
                        runCatching {
                            credentialManager.get(context)?.let {
                                username = it.id
                                password = it.password
                            }
                        }.getOrNull()
                        backgroundLoading = false
                    }

                    BasicText(
                        text = stringResource(R.string.piped),
                        style = typography.m.semiBold
                    )

                    if (customInstance == null) ValueSelectorSettingsEntry(
                        title = stringResource(R.string.instance),
                        selectedValue = selectedInstance,
                        values = instances.indices.toImmutableList(),
                        onValueSelect = { selectedInstance = it },
                        valueText = { idx ->
                            idx?.let { instances.getOrNull(it)?.name }
                                ?: if (instancesUnavailable) stringResource(R.string.error_piped_instances_unavailable)
                                else stringResource(R.string.click_to_select)
                        },
                        isEnabled = !instancesUnavailable && canSelect,
                        usePadding = false,
                        trailingContent = if (loadingInstances) {
                            { CircularProgressIndicator() }
                        } else null
                    )
                    SwitchSettingsEntry(
                        title = stringResource(R.string.custom_instance),
                        text = null,
                        isChecked = customInstance != null,
                        onCheckedChange = {
                            customInstance = if (customInstance == null) "" else null
                        },
                        usePadding = false
                    )
                    customInstance?.let { instance ->
                        Spacer(modifier = Modifier.height(8.dp))
                        TextField(
                            value = instance,
                            onValueChange = { customInstance = it },
                            hintText = stringResource(R.string.base_api_url),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = username,
                        onValueChange = { username = it },
                        hintText = stringResource(R.string.username),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    TextField(
                        value = password,
                        onValueChange = { password = it },
                        hintText = stringResource(R.string.password),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                password()
                            }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    DialogTextButton(
                        text = stringResource(R.string.login),
                        primary = true,
                        enabled = (customInstance?.isNotBlank() == true || selectedInstance != null) &&
                            username.isNotBlank() && password.isNotBlank(),
                        onClick = {
                            @Suppress("Wrapping")
                            (customInstance?.let {
                                runCatching {
                                    Url(it)
                                }.getOrNull() ?: runCatching {
                                    Url("https://$it")
                                }.getOrNull()
                            } ?: selectedInstance?.let { instances[it].apiBaseUrl })?.let { url ->
                                coroutineScope.launch {
                                    isLoading = true
                                    val session = Piped.login(
                                        apiBaseUrl = url,
                                        username = username,
                                        password = password
                                    )?.getOrNull()
                                    isLoading = false
                                    if (session == null) {
                                        hasError = true
                                        return@launch
                                    }

                                    transaction {
                                        Database.instance.insert(
                                            PipedSession(
                                                apiBaseUrl = session.apiBaseUrl,
                                                username = username,
                                                token = session.token
                                            )
                                        )
                                    }

                                    successful = true

                                    runCatching {
                                        credentialManager.upsert(
                                            context = context,
                                            username = username,
                                            password = password
                                        )
                                    }

                                    linkingPiped = false
                                }
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }

    // Piped Dialog 2: Deleting
    if (deletingPipedSession != null) ConfirmationDialog(
        text = stringResource(R.string.confirm_delete_piped_session),
        onDismiss = {
            deletingPipedSession = null
        },
        onConfirm = {
            deletingPipedSession?.let {
                transaction { Database.instance.delete(pipedSessions[it]) }
            }
        }
    )

    SettingsCategoryScreen(title = stringResource(R.string.sync)) {
        SettingsDescription(text = stringResource(R.string.sync_description))
        SettingsGroup(title = stringResource(R.string.local_import)) {
            SettingsEntry(
                title = stringResource(R.string.import_from_csv),
                text = stringResource(R.string.import_from_csv_description),
                onClick = {
                    filePickerLauncher.launch(
                        arrayOf(
                            "text/csv",
                            "text/comma-separated-values",
                            "application/csv",
                            "application/vnd.ms-excel"
                        )
                    )
                }
            )
        }
        SettingsGroup(title = stringResource(R.string.piped)) {
            SettingsEntry(
                title = stringResource(R.string.add_account),
                text = stringResource(R.string.add_account_description),
                onClick = { linkingPiped = true }
            )
            SettingsEntry(
                title = stringResource(R.string.learn_more),
                text = stringResource(R.string.learn_more_description),
                onClick = { uriHandler.openUri(context.getString(R.string.piped_learn_more_url)) }
            )
        }
        SettingsGroup(title = stringResource(R.string.piped_sessions)) {
            if (pipedSessions.isEmpty()) {
                SettingsGroupSpacer()
                BasicText(
                    text = stringResource(R.string.no_items_found),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    style = typography.s.semiBold.center
                )
            } else pipedSessions.fastForEachIndexed { i, session ->
                SettingsEntry(
                    title = session.username,
                    text = session.apiBaseUrl.toString(),
                    onClick = { },
                    trailingContent = {
                        IconButton(
                            onClick = { deletingPipedSession = i },
                            icon = R.drawable.delete,
                            color = colorPalette.text
                        )
                    }
                )
            }
        }
    }
}

private fun Uri.toFileName(context: Context): String? {
    val cursor = context.contentResolver.query(this, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return it.getString(index)
        }
    }
    return null
}
