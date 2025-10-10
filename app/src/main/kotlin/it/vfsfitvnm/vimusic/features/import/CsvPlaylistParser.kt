package it.vfsfitvnm.vimusic.features.import

import android.util.Log
import java.io.InputStream
import kotlin.math.max

class CsvPlaylistParser {

    companion object {
        private const val CSV_SPLIT_REGEX = ",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)"
        private val csvSplitter = CSV_SPLIT_REGEX.toRegex()
    }

    fun getHeader(inputStream: InputStream): List<String> {
        return inputStream.bufferedReader().useLines { lines ->
            lines.firstOrNull()?.splitCsvLine() ?: emptyList()
        }
    }

    fun parse(
        inputStream: InputStream,
        titleColumnIndex: Int,
        artistColumnIndex: Int,
        albumColumnIndex: Int? // Optional album column index
    ): List<SongImportInfo> {
        val songList = mutableListOf<SongImportInfo>()

        inputStream.bufferedReader().useLines { lines ->
            val dataLines = lines.drop(1) // Assuming header is always present

            dataLines.forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed

                val columns = line.splitCsvLine()

                try {
                    val title = columns[titleColumnIndex]
                    val artist = columns[artistColumnIndex]
                    val album = albumColumnIndex?.let {
                        if (columns.size > it) columns[it].takeIf(String::isNotBlank) else null
                    }

                    if (title.isNotBlank() && artist.isNotBlank()) {
                        songList.add(SongImportInfo(title = title, artist = artist, album = album))
                    } else {
                        Log.w("CsvPlaylistParser", "Skipping row ${index + 2} due to blank title or artist.")
                    }
                } catch (_: IndexOutOfBoundsException) {
                    val maxIndex = max(titleColumnIndex, artistColumnIndex)
                    Log.w("CsvPlaylistParser", "Skipping malformed CSV row ${index + 2}. Expected at least ${maxIndex + 1} columns, but found ${columns.size}.")
                }
            }
        }
        return songList
    }

    private fun String.splitCsvLine(): List<String> {
        return this.split(csvSplitter)
            .map { it.trim().removeSurrounding("\"") }
    }
}
