package com.example.youtube3bullets

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
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
            log("yt-dlp probe begin --dump-single-json")
            val probeRequest = YoutubeDLRequest(url).apply {
                addOption("--skip-download")
                addOption("--dump-single-json")
                addOption("--no-playlist")
                addOption("--no-warnings")
            }
            val probe = YoutubeDL.getInstance().execute(probeRequest)
            val metadata = parseMetadataJson(probe.out)

            val manual = metadata.optJSONObject("subtitles") ?: JSONObject()
            val automatic = metadata.optJSONObject("automatic_captions") ?: JSONObject()
            val originalLanguage = metadata.optString("language").takeIf { it.isNotBlank() && it != "null" }

            val manualLangs = manual.keys().asSequence().filterNot { it == "live_chat" }.toList()
            val autoLangs = automatic.keys().asSequence().filterNot { it == "live_chat" }.toList()
            log("yt-dlp probe OK original_lang=${originalLanguage ?: "unknown"} manual=${manualLangs.size} auto=${autoLangs.size}")
            log("subtitle.available manual=${manualLangs.take(12)}${if (manualLangs.size > 12) "…" else ""}")
            log("subtitle.available auto=${autoLangs.take(12)}${if (autoLangs.size > 12) "…" else ""}")

            val candidatesToTry = buildSubtitleCandidates(originalLanguage, manualLangs, autoLangs)
            if (candidatesToTry.isEmpty()) error("yt-dlp reports no manual or automatic captions for this video")

            var lastError: Throwable? = null
            for ((index, selected) in candidatesToTry.withIndex()) {
                val (language, automaticTrack) = selected
                log("subtitle.select attempt=${index + 1}/${candidatesToTry.size} lang=$language source=${if (automaticTrack) "automatic" else "manual"}")

                workDir.listFiles()?.forEach { if (it.isFile) it.delete() }

                val request = YoutubeDLRequest(url).apply {
                    addOption("--skip-download")
                    if (automaticTrack) addOption("--write-auto-subs") else addOption("--write-subs")
                    addOption("--sub-format", "vtt/best")
                    addOption("--sub-langs", language)
                    addOption("--no-playlist")
                    addOption("--no-warnings")
                    addOption("-o", File(workDir, "transcript.%(ext)s").absolutePath)
                }

                try {
                    log("yt-dlp fetch begin lang=$language auto=$automaticTrack format=vtt/best")
                    val response = YoutubeDL.getInstance().execute(request)
                    log("yt-dlp fetch exit=0 stdout_chars=${response.out.length}")

                    val files = workDir.walkTopDown()
                        .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in setOf("vtt", "srt", "srv1", "srv2", "srv3", "ttml") }
                        .toList()

                    log("subtitle.scan candidates=${files.size} files=${files.joinToString { it.name }}")

                    val subtitle = files.maxByOrNull { it.length() }
                        ?: error("yt-dlp selected captions but produced no subtitle file")

                    log("subtitle.file file=${subtitle.name} bytes=${subtitle.length()}")
                    val parsed = parseSubtitle(subtitle.readText())
                    log("subtitle.parse OK lang=$language chars=${parsed.length} words=${parsed.split(Regex("\\s+")).size}")
                    return parsed
                } catch (t: Throwable) {
                    lastError = t
                    log("subtitle.fetch failed lang=$language error=${t.message ?: t::class.java.simpleName}")
                    if (index < candidatesToTry.lastIndex) log("subtitle.retry next_candidate=true")
                }
            }

            throw lastError ?: error("No subtitle candidate succeeded")
        } finally {
            val deleted = workDir.deleteRecursively()
            log("cache.cleanup deleted=$deleted")
        }
    }

    private fun parseMetadataJson(stdout: String): JSONObject {
        val trimmed = stdout.trim()
        if (trimmed.startsWith("{")) return JSONObject(trimmed)
        val jsonLine = trimmed.lineSequence().lastOrNull { it.trimStart().startsWith("{") }
            ?: error("yt-dlp metadata probe returned no JSON")
        return JSONObject(jsonLine)
    }

    private fun buildSubtitleCandidates(
        originalLanguage: String?,
        manual: List<String>,
        automatic: List<String>,
    ): List<Pair<String, Boolean>> {
        fun normalizedBase(lang: String): String =
            lang.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)

        fun rank(list: List<String>): List<String> {
            val originalBase = originalLanguage?.let(::normalizedBase)
            return list.sortedWith(
                compareBy<String> {
                    when {
                        originalLanguage != null && it.equals(originalLanguage, ignoreCase = true) -> 0
                        originalBase != null && normalizedBase(it) == originalBase && it.endsWith("-orig", ignoreCase = true) -> 1
                        originalBase != null && normalizedBase(it) == originalBase -> 2
                        it.endsWith("-orig", ignoreCase = true) -> 3
                        it.equals("en", ignoreCase = true) || it.equals("en-US", ignoreCase = true) -> 4
                        else -> 5
                    }
                }.thenBy { it }
            )
        }

        val ordered = mutableListOf<Pair<String, Boolean>>()
        ordered += rank(manual).map { it to false }
        ordered += rank(automatic).map { it to true }

        return ordered
            .distinctBy { (lang, auto) -> "${lang.lowercase(Locale.ROOT)}:$auto" }
            .take(6)
    }

    private fun extractUrl(input: String): String =
        Regex("""https?://[^\s]+""")
            .find(input.trim())
            ?.value
            ?.trimEnd('.', ',', ')', ']', '}')
            ?: input.trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: error("Couldn't find a YouTube URL")

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
