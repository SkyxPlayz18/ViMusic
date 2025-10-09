package it.vfsfitvnm.vimusic.features.import

import java.io.InputStream
import kotlin.math.max

class CsvPlaylistParser {

    companion object {
        // Regex ini biar tanda koma dalam tanda kutip gak ikut kepotong
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
        albumColumnIndex: Int? // kolom album opsional
    ): List<SongImportInfo> {
        val songList = mutableListOf<SongImportInfo>()

        inputStream.bufferedReader().useLines { lines ->
            val dataLines = lines.drop(1) // baris pertama = header

            dataLines.forEach { line ->
                if (line.isBlank()) return@forEach

                val columns = line.splitCsvLine()
                val title = columns.getOrNull(titleColumnIndex)?.trim().orEmpty()
                val artist = columns.getOrNull(artistColumnIndex)?.trim().orEmpty()
                val album = albumColumnIndex?.let { columns.getOrNull(it)?.takeIf { it.isNotBlank() } }

                if (title.isNotEmpty() && artist.isNotEmpty()) {
                    songList.add(SongImportInfo(title = title, artist = artist, album = album))
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
