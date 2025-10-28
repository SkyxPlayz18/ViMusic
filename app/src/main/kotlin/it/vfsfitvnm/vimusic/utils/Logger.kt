package it.vfsfitvnm.vimusic.utils

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility untuk menyimpan log debug ke file storage
 * Lokasi file: /storage/emulated/0/ViMusic_log.txt
 */
fun logDebug(context: Context, message: String) {
    try {
        // Format waktu biar log rapi
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date())

        // Format pesan log
        val logMessage = "[$timestamp] [${Thread.currentThread().name}] $message\n"

        // File log di storage utama
        val logFile = File("/storage/emulated/0/ViMusic_log.txt")

        // Pastikan file bisa ditulis
        if (!logFile.exists()) logFile.createNewFile()

        // Tambahkan log ke file
        logFile.appendText(logMessage)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
