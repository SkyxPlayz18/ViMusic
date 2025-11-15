package it.vfsfitvnm.vimusic.utils

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import it.vfsfitvnm.vimusic.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

object PlaylistCoverManager {
    private const val COVER_DIR = "playlist_covers"
    
    fun getCoverFile(context: Context, playlistId: Long): File {
        val dir = File(context.filesDir, COVER_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "playlist_${playlistId}.jpg")
    }
    
    suspend fun saveCover(
        context: Context,
        playlistId: Long,
        uri: Uri
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val coverFile = getCoverFile(context, playlistId)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(coverFile).use { output ->
                    input.copyTo(output)
                }
            }
            "file://${coverFile.absolutePath}"  // ✅ Return with file:// prefix
        }
    }
    
    fun deleteCover(context: Context, playlistId: Long): Boolean {
        val coverFile = getCoverFile(context, playlistId)
        return if (coverFile.exists()) coverFile.delete() else false
    }
    
    fun hasCover(context: Context, playlistId: Long): Boolean {
        return getCoverFile(context, playlistId).exists()
    }
    
    fun getCoverPath(context: Context, playlistId: Long): String? {
        val coverFile = getCoverFile(context, playlistId)
        return if (coverFile.exists()) "file://${coverFile.absolutePath}" else null
    }
}

@Composable
fun rememberPlaylistCoverPicker(
    playlistId: Long,
    onCoverSelected: (String) -> Unit
): () -> Unit {
    val context = LocalContext.current
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        // ✅ FIX: Callback harus di main thread!
        if (uri != null) {
            // Show immediate feedback
            context.toast("Saving cover...")
            
            // Save in background
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                PlaylistCoverManager.saveCover(context, playlistId, uri)
                    .onSuccess { path ->
                        context.toast(context.getString(R.string.playlist_cover_updated))
                        onCoverSelected(path)  // ✅ Trigger callback
                    }
                    .onFailure { error ->
                        error.printStackTrace()
                        context.toast(
                            context.getString(R.string.error_updating_playlist_cover)
                        )
                    }
            }
        } else {
            // User cancelled
            context.toast("No image selected")
        }
    }
    
    return remember(playlistId) {
        {
            launcher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
    }
}
