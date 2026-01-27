package it.vfsfitvnm.vimusic.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
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
            // ✅ MIUI FIX: Take persistent permission
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            
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
}

@Composable
fun rememberPlaylistCoverPicker(
    playlistId: Long,
    onCoverSelected: (String) -> Unit
): () -> Unit {
    val context = LocalContext.current
    
    val launcher = rememberLauncherForActivityResult(
        contract = object : ActivityResultContracts.GetContent() {
            // ✅ MIUI FIX: Override to add persistent permission flag
            override fun createIntent(context: Context, input: String): Intent {
                return super.createIntent(context, input).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                }
            }
        }
    ) { uri: Uri? ->
        if (uri != null) {
            GlobalScope.launch(Dispatchers.IO) {
                val result = PlaylistCoverManager.saveCover(context, playlistId, uri)
                
                withContext(Dispatchers.Main) {
                    result.onSuccess { path ->
                        onCoverSelected(path)
                        context.toast(context.getString(R.string.playlist_cover_updated))
                    }.onFailure { error ->
                        error.printStackTrace()
                        context.toast("Error: ${error.message}")
                    }
                }
            }
        }
    }
    
    return remember(playlistId) {
        { launcher.launch("image/*") }
    }
}
