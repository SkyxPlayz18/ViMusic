package it.vfsfitvnm.vimusic.features.import

import java.io.InputStream
import kotlin.math.max

class CsvPlaylistParser {

    companion object {
        // Regex untuk pecah CSV aman (support tanda kutip dan koma di dalam cell)
        private const val CSV_SPLIT_REGEX = ",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)"
        private val csvSplitter = CSV_SPLIT_REGEX.toRegex()
    }

    // Ambil header CSV (baris pertama)
    fun getHeader(inputStream: InputStream): List<String> {
        return inputStream.bufferedReader().useLines { lines ->
            lines.firstOrNull()?.splitCsvLine() ?: emptyList()
        }
    }

    // Parse CSV menjadi list lagu (SongImportInfo)
    fun parse(
        inputStream: InputStream,
        titleColumnIndex: Int,
        artistColumnIndex: Int,
        albumColumnIndex: Int? // optional, tapi tetap diambil kalau ada
    ): List<SongImportInfo> {
        val songList = mutableListOf<SongImportInfo>()
        val reader = inputStream.bufferedReader()

        reader.useLines { lines ->
            val dataLines = lines.drop(1) // skip header CSV

            dataLines.forEach { line ->
                if (line.isBlank()) return@forEach

                val columns = line.splitCsvLine()
                val maxRequiredIndex = max(titleColumnIndex, artistColumnIndex)
                if (columns.size <= maxRequiredIndex) return@forEach

                val title = columns.getOrNull(titleColumnIndex)?.trim()?.removeSurrounding("\"") ?: ""
                val artist = columns.getOrNull(artistColumnIndex)?.trim()?.removeSurrounding("\"") ?: ""
                val album = albumColumnIndex?.let {
                    columns.getOrNull(it)?.trim()?.removeSurrounding("\"")?.ifBlank { null }
                }

                // Hanya ambil lagu dengan title & artist valid, dan bukan header palsu
                if (title.isNotEmpty() && artist.isNotEmpty() && !title.equals("Track Name", true)) {
                    songList.add(
                        SongImportInfo(
                            title = normalizeField(title),
                            artist = normalizeField(artist),
                            album = album?.let { normalizeField(it) }
                        )
                    )
                }
            }
        }
        return songList
    }

    // Fungsi bantu buat pecah baris CSV
    private fun String.splitCsvLine(): List<String> {
        return this.split(csvSplitter)
            .map { it.trim().removeSurrounding("\"") }
    }

    // Normalisasi field biar gak nyasar (hapus spasi ganda, trim ujung)
    private fun normalizeField(value: String): String {
        return value.replace(Regex("\\s+"), " ").trim()
    }
}
