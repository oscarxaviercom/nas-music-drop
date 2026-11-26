package com.nasmusicdrop.app.data.repository

import java.text.Normalizer

/**
 * Utilidad compartida para normalizar títulos de canciones y nombres de archivo
 * al comprobar duplicados en el NAS o en el almacenamiento local.
 * Elimina sufijos de edición/remasterización ("- Edit", "- Remastered", "(2011 Remaster)", etc.)
 * para evitar descargas duplicadas cuando la plataforma añade etiquetas de versión.
 */
object DuplicateMatcher {

    private val EDITION_KEYWORDS = listOf(
        "remaster(?:ed)?",
        "edit",
        "radio\\s*edit",
        "single\\s*version",
        "album\\s*version",
        "extended(?:\\s*mix|\\s*version)?",
        "original\\s*mix",
        "club\\s*mix",
        "remix",
        "live",
        "en\\s*vivo",
        "directo",
        "acoustic",
        "ac[uú]stic[ao]",
        "unplugged",
        "mono",
        "stereo",
        "explicit",
        "clean",
        "deluxe(?:\\s*edition)?",
        "bonus\\s*track",
        "anniversary(?:\\s*edition)?",
        "instrumental",
        "karaoke",
        "demo",
        "version",
        "mix",
        "\\d{4}"
    ).joinToString("|")

    private val PAREN_EDITION_REGEX = Regex(
        """\s*[\(\[](?:[^\)\]]*(?:$EDITION_KEYWORDS|feat\.?|ft\.?)[^\)\]]*)[\)\]]""",
        RegexOption.IGNORE_CASE
    )

    private val DASH_EDITION_REGEX = Regex(
        """\s+-\s+(?:.*(?:$EDITION_KEYWORDS).*)$""",
        RegexOption.IGNORE_CASE
    )

    private val DIACRITICS_REGEX = Regex("""\p{InCombiningDiacriticalMarks}+""")
    private val NON_ALNUM_REGEX = Regex("""[^a-z0-9]""")

    /**
     * Normaliza un título de canción eliminando etiquetas de edición, acentos y puntuación.
     * Ej: "Pump Up The Jam - Edit" -> "pumpupthejam"
     *     "Beds Are Burning (2011 Remaster)" -> "bedsareburning"
     */
    fun normalizeTitle(rawTitle: String): String {
        var clean = rawTitle.trim()
        // Repetir un par de veces por si hay múltiples sufijos encadenados, ej: "Song - Edit (Remastered 2020)"
        repeat(2) {
            clean = PAREN_EDITION_REGEX.replace(clean, "")
            clean = DASH_EDITION_REGEX.replace(clean, "")
        }
        val nfd = Normalizer.normalize(clean, Normalizer.Form.NFD)
        val noAccents = DIACRITICS_REGEX.replace(nfd, "")
        return NON_ALNUM_REGEX.replace(noAccents.lowercase(), "")
    }

    /**
     * Extrae posibles variantes del artista (completo y artista principal si hay colaboraciones).
     */
    fun extractArtistVariants(artistName: String): List<String> {
        val full = artistName.trim()
        val primary = full.split(Regex(""",|&|\bfeat\.?\b|\bft\.?\b|\bx\b|\bwith\b""", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim() ?: full
        return listOf(full, primary).filter { it.isNotBlank() }.distinct()
    }

    /**
     * Normaliza un nombre de artista o token (sin acentos, sin puntuación, en minúsculas).
     * Ej: "fun." -> "fun", "Janelle Monáe" -> "janellemonae"
     */
    private fun normalizeArtistToken(raw: String): String {
        val nfd = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
        val noAccents = DIACRITICS_REGEX.replace(nfd, "")
        return NON_ALNUM_REGEX.replace(noAccents.lowercase(), "")
    }

    /**
     * Extrae los tokens normalizados de cada artista participante.
     * Ej: "fun., Janelle Monáe" -> ["fun", "janellemonae"]
     *     "fun ft. Janelle Monáe" -> ["fun", "janellemonae"]
     */
    fun extractArtistTokens(artistName: String): List<String> {
        return artistName
            .split(Regex(""",|&|/|\bfeat\.?\b|\bft\.?\b|\bx\b|\bwith\b""", RegexOption.IGNORE_CASE))
            .map { normalizeArtistToken(it) }
            .filter { it.isNotBlank() }
    }

    /**
     * Comprueba si el nombre de una carpeta de artista coincide con el artista buscado,
     * tolerando diferencias entre ",", "ft.", "feat.", "&", acentos o puntuación.
     * Ej: carpeta "fun ft. Janelle Monáe" coincide con "fun, Janelle Monáe" y con "fun".
     */
    fun doesArtistFolderMatch(folderName: String, targetArtist: String): Boolean {
        val folderNorm = normalizeArtistToken(folderName)
        val targetNorm = normalizeArtistToken(targetArtist)
        if (folderNorm.isBlank() || targetNorm.isBlank()) return false
        if (folderNorm == targetNorm) return true

        val folderTokens = extractArtistTokens(folderName)
        val targetTokens = extractArtistTokens(targetArtist)
        if (folderTokens.isEmpty() || targetTokens.isEmpty()) return false

        // Coincidencia por artista principal (primer token exacto)
        return folderTokens.first() == targetTokens.first()
    }

    /**
     * Comprueba si un nombre de archivo de audio (sin ruta) corresponde al título buscado.
     */
    fun doesFilenameMatchTitle(fileName: String, targetTitle: String): Boolean {
        if (!fileName.endsWith(".mp3", ignoreCase = true) &&
            !fileName.endsWith(".m4a", ignoreCase = true) &&
            !fileName.endsWith(".flac", ignoreCase = true)
        ) {
            return false
        }

        val normTarget = normalizeTitle(targetTitle)
        if (normTarget.isBlank()) return false

        val base = fileName.substringBeforeLast(".")
        val normFull = normalizeTitle(base)
        if (normFull == normTarget) return true

        if (base.contains(" - ")) {
            val afterFirstDash = base.substringAfter(" - ")
            val normAfterFirst = normalizeTitle(afterFirstDash)
            if (normAfterFirst == normTarget) return true

            val afterLastDash = base.substringAfterLast(" - ")
            val normAfterLast = normalizeTitle(afterLastDash)
            if (normAfterLast == normTarget) return true

            if (normTarget.length >= 6 && (normAfterFirst.contains(normTarget) || normTarget.contains(normAfterFirst))) {
                return true
            }
        } else if (normTarget.length >= 6 && (normFull.contains(normTarget) || normTarget.contains(normFull))) {
            return true
        }

        return false
    }
}
