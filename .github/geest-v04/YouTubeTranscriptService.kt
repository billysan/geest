package com.example.youtube3bullets

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.Locale
import java.util.UUID

class YouTubeTranscriptService(context: Context) {
    private val appContext = context.applicationContext

    init {
        YoutubeDL.getInstance().init(appContext)
    }

    fun fetchTranscript(sharedTextOrUrl: String, log: (String) -> Unit = {}): String {
        val url = extractUrl(sharedTextOrUrl)
        log("url.parse OK url=$url")

        val workDir = File(appContext.cacheDir, "geest-subs/${UUID.randomUUID()}")
        check(workDir.mkdirs() || workDir.isDirectory) { "Couldn't create subtitle cache" }
        log("cache.mkdir path=${workDir.absolutePath}")

        try {
            val locale = Locale.getDefault().language.lowercase(Locale.ROOT)
            val langs = buildList {
                if (locale.isNotBlank()) {
                    add("$locale.*")
                    add(locale)
                }
                if (locale != "en") {
                    add("en.*")
                    add("en")
                }
            }.distinct().joinToString(",")

            log("yt-dlp init=OK locale=$locale sub_langs=$langs")
            log("yt-dlp argv=--skip-download --write-subs --write-auto-subs --sub-format vtt/best --no-playlist")

            val request = YoutubeDLRequest(url).apply {
                addOption("--skip-download")
                addOption("--write-subs")
                addOption("--write-auto-subs")
                addOption("--sub-format", "vtt/best")
                addOption("--sub-langs", langs)
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("-o", File(workDir, "transcript.%(ext)s").absolutePath)
            }

            log("yt-dlp execute begin")
            YoutubeDL.getInstance().execute(request)
            log("yt-dlp execute exit=0")

            val candidates = workDir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in setOf("vtt", "srt", "srv1", "srv2", "srv3", "ttml") }
                .toList()

            log("subtitle.scan candidates=${candidates.size} files=${candidates.joinToString { it.name }}")

            val subtitle = candidates
                .sortedWith(compareBy<File>({ languageRank(it.name.lowercase(Locale.ROOT), locale) }, { it.length() }))
                .firstOrNull()
                ?: error("yt-dlp found no transcript/captions for this video")

            log("subtitle.select file=${subtitle.name} bytes=${subtitle.length()}")
            val parsed = parseSubtitle(subtitle.readText())
            log("subtitle.parse OK chars=${parsed.length} words=${parsed.split(Regex("\\s+")).size}")
            return parsed
        } finally {
            val deleted = workDir.deleteRecursively()
            log("cache.cleanup deleted=$deleted")
        }
    }

    private fun extractUrl(input: String): String =
        Regex("""https?://[^\s]+""")
            .find(input.trim())
            ?.value
            ?.trimEnd('.', ',', ')', ']', '}')
            ?: input.trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: error("Couldn't find a YouTube URL")

    private fun languageRank(name: String, locale: String): Int = when {
        locale.isNotBlank() && (name.contains(".$locale.") || name.contains("-$locale.")) -> 0
        name.contains(".en.") || name.contains("-en.") -> 1
        else -> 2
    }

    private fun parseSubtitle(raw: String): String {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<String>()

        raw.lineSequence().forEach { line ->
            val clean = line
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("&nbsp;|&#160;"), " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (clean.isBlank() ||
                clean == "WEBVTT" ||
                clean.startsWith("Kind:") ||
                clean.startsWith("Language:") ||
                clean.matches(Regex("""\d+""")) ||
                "-->" in clean ||
                clean.startsWith("NOTE")
            ) return@forEach

            if (seen.add(clean)) out += clean
        }

        return out.joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.length >= 20 }
            ?: error("Transcript was empty")
    }
}
