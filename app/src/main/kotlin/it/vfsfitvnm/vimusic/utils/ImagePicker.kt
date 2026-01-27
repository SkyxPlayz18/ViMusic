package it.vfsfitvnm.vimusic.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import it.vfsfitvnm.vimusic.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            "file://${coverFile.absolutePath}"
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
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // ✅ DEBUG: Always show what we got
        context.toast("Result code: ${result.resultCode}")
        
        when (result.resultCode) {
            Activity.RESULT_OK -> {
                val uri = result.data?.data
                
                // ✅ DEBUG: Show URI
                context.toast("URI: ${uri?.toString() ?: "NULL"}")
                
                if (uri != null) {
                    context.toast("Saving cover...")
                    
                    GlobalScope.launch(Dispatchers.Main) {
                        PlaylistCoverManager.saveCover(context, playlistId, uri)
                            .onSuccess { path ->
                                context.toast(context.getString(R.string.playlist_cover_updated))
                                onCoverSelected(path)
                            }
                            .onFailure { error ->
                                error.printStackTrace()
                                context.toast("Error: ${error.message}")
                            }
                    }
                } else {
                    context.toast("URI is null! Please try again")
                }
            }
            Activity.RESULT_CANCELED -> {
                context.toast("Selection cancelled")
            }
            else -> {
                context.toast("Unknown result: ${result.resultCode}")
            }
        }
    }
    
    return remember(playlistId) {
        {
            try {
                // ✅ Method 1: Direct Gallery Pick
                val galleryIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                launcher.launch(galleryIntent)
            } catch (e: Exception) {
                try {
                    // ✅ Method 2: Fallback to GetContent
                    val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    launcher.launch(contentIntent)
                } catch (e2: Exception) {
                    context.toast("Error: ${e2.message}")
                }
            }
        }
    }
}
