package it.vfsfitvnm.vimusic.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.documentfile.provider.DocumentFile
import it.vfsfitvnm.vimusic.features.import.ImportStatus
import it.vfsfitvnm.vimusic.features.import.PlaylistImporter
import it.vfsfitvnm.vimusic.features.import.SongImportInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

object SyncSettings {
    val importStatus = mutableStateOf<ImportStatus>(ImportStatus.Idle)

    fun importCsv(context: Context, uri: Uri, playlistName: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val songs = parseExportifyCsv(context, uri)
                val importer = PlaylistImporter()
                importer.import(
                    songList = songs,
                    playlistName = playlistName,
                    unknownErrorMessage = "Gagal memproses file CSV.",
                    onProgressUpdate = { status -> importStatus.value = status }
                )
            } catch (e: Exception) {
                importStatus.value = ImportStatus.Error("Error: ${e.message}")
            }
        }
    }

    private fun parseExportifyCsv(context: Context, uri: Uri): List<SongImportInfo> {
        val list = mutableListOf<SongImportInfo>()
        val inputStream = context.contentResolver.openInputStream(uri) ?: return emptyList()
        val reader = BufferedReader(InputStreamReader(inputStream))

        val header = reader.readLine()?.split(",") ?: return emptyList()
        val nameIndex = header.indexOfFirst { it.equals("Track Name", true) }
        val artistIndex = header.indexOfFirst { it.equals("Artist Name", true) }
        val albumIndex = header.indexOfFirst { it.equals("Album Name", true) }

        if (nameIndex == -1 || artistIndex == -1 || albumIndex == -1) return emptyList()

        reader.lineSequence().forEach { line ->
            val parts = line.split(",")
            if (parts.size > maxOf(nameIndex, artistIndex, albumIndex)) {
                val title = parts[nameIndex].trim().replace("\"", "")
                val artist = parts[artistIndex].trim().replace("\"", "")
                val album = parts[albumIndex].trim().replace("\"", "")
                if (title.isNotBlank() && artist.isNotBlank()) {
                    list.add(SongImportInfo(title, artist, album))
                }
            }
        }

        reader.close()
        return list
    }
}
