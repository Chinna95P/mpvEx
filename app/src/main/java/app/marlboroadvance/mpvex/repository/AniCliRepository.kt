package app.marlboroadvance.mpvex.repository

import android.util.Log
import app.marlboroadvance.mpvex.domain.anicli.AniCliAnime
import app.marlboroadvance.mpvex.domain.anicli.AniCliStreamLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

private const val TAG = "AniCliRepository"

class AniCliRepository(
    private val client: OkHttpClient,
    private val json: Json,
) {
    // ─── GraphQL queries ────────────────────────────────────────────────────

    private val searchGql = """
        query(${"$"}search: SearchInput ${"$"}limit: Int ${"$"}page: Int ${"$"}translationType: VaildTranslationTypeEnumType ${"$"}countryOrigin: VaildCountryOriginEnumType) {
          shows(search: ${"$"}search limit: ${"$"}limit page: ${"$"}page translationType: ${"$"}translationType countryOrigin: ${"$"}countryOrigin) {
            edges { _id name availableEpisodes __typename }
          }
        }
    """.trimIndent()

    private val episodesGql = """
        query (${"$"}showId: String!) { show( _id: ${"$"}showId ) { _id availableEpisodesDetail }}
    """.trimIndent()

    private val episodeEmbedGql = """
        query (${"$"}showId: String!, ${"$"}translationType: VaildTranslationTypeEnumType!, ${"$"}episodeString: String!) {
          episode(showId: ${"$"}showId translationType: ${"$"}translationType episodeString: ${"$"}episodeString) {
            episodeString sourceUrls
          }
        }
    """.trimIndent()

    // ─── Provider hex-decode map (from ani-cli source) ──────────────────────

    private val hexDecodeMap: Map<String, Char> = mapOf(
        "79" to 'A', "7a" to 'B', "7b" to 'C', "7c" to 'D', "7d" to 'E', "7e" to 'F', "7f" to 'G',
        "70" to 'H', "71" to 'I', "72" to 'J', "73" to 'K', "74" to 'L', "75" to 'M', "76" to 'N', "77" to 'O',
        "68" to 'P', "69" to 'Q', "6a" to 'R', "6b" to 'S', "6c" to 'T', "6d" to 'U', "6e" to 'V', "6f" to 'W',
        "60" to 'X', "61" to 'Y', "62" to 'Z',
        "59" to 'a', "5a" to 'b', "5b" to 'c', "5c" to 'd', "5d" to 'e', "5e" to 'f', "5f" to 'g',
        "50" to 'h', "51" to 'i', "52" to 'j', "53" to 'k', "54" to 'l', "55" to 'm', "56" to 'n', "57" to 'o',
        "48" to 'p', "49" to 'q', "4a" to 'r', "4b" to 's', "4c" to 't', "4d" to 'u', "4e" to 'v', "4f" to 'w',
        "40" to 'x', "41" to 'y', "42" to 'z',
        "08" to '0', "09" to '1', "0a" to '2', "0b" to '3', "0c" to '4', "0d" to '5', "0e" to '6', "0f" to '7',
        "00" to '8', "01" to '9',
        "15" to '-', "16" to '.', "67" to '_', "46" to '~', "02" to ':', "17" to '/', "07" to '?',
        "1b" to '#', "63" to '[', "65" to ']', "78" to '@', "19" to '!', "1c" to '$', "1e" to '&',
        "10" to '(', "11" to ')', "12" to '*', "13" to '+', "14" to ',', "03" to ';', "05" to '=', "1d" to '%',
    )

    // ─── Serialization models ────────────────────────────────────────────────

    @Serializable
    private data class SearchResponse(val data: SearchData? = null)

    @Serializable
    private data class SearchData(val shows: ShowList? = null)

    @Serializable
    private data class ShowList(val edges: List<ShowEdge>? = null)

    @Serializable
    private data class ShowEdge(
        @SerialName("_id") val id: String = "",
        val name: String = "",
        val availableEpisodes: AvailableEpisodes? = null,
    )

    @Serializable
    private data class AvailableEpisodes(val sub: Int = 0, val dub: Int = 0)

    @Serializable
    private data class EpisodesResponse(val data: EpisodesData? = null)

    @Serializable
    private data class EpisodesData(val show: ShowDetail? = null)

    @Serializable
    private data class ShowDetail(
        @SerialName("availableEpisodesDetail") val detail: EpisodesDetail? = null,
    )

    @Serializable
    private data class EpisodesDetail(
        val sub: List<String> = emptyList(),
        val dub: List<String> = emptyList(),
    )

    @Serializable
    private data class SourceResponse(val data: SourceData? = null)

    @Serializable
    private data class SourceData(val episode: EpisodeData? = null)

    @Serializable
    private data class EpisodeData(val sourceUrls: List<SourceUrl>? = null)

    @Serializable
    private data class SourceUrl(
        val sourceUrl: String = "",
        val sourceName: String = "",
    )

    @Serializable
    private data class EmbedResponse(val links: List<EmbedLink>? = null)

    @Serializable
    private data class EmbedLink(
        val link: String? = null,
        val url: String? = null,
        @SerialName("resolutionStr") val quality: String? = null,
        val type: String? = null,
        @SerialName("hardsub_lang") val hardsubLang: String? = null,
    )

    // ─── Public API ──────────────────────────────────────────────────────────

    suspend fun searchAnime(query: String, mode: String = "sub"): Result<List<AniCliAnime>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val variables = """{"search":{"allowAdult":false,"allowUnknown":false,"query":"$query"},"limit":40,"page":1,"translationType":"$mode","countryOrigin":"ALL"}"""
                val body = apiGet(variables, searchGql)
                val response = json.decodeFromString<SearchResponse>(body)
                response.data?.shows?.edges?.map { edge ->
                    AniCliAnime(
                        id = edge.id,
                        name = edge.name,
                        subEpisodes = edge.availableEpisodes?.sub ?: 0,
                        dubEpisodes = edge.availableEpisodes?.dub ?: 0,
                    )
                }?.filter { it.id.isNotBlank() } ?: emptyList()
            }
        }

    suspend fun getEpisodes(showId: String, mode: String = "sub"): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val variables = """{"showId":"$showId"}"""
                val body = apiGet(variables, episodesGql)
                val response = json.decodeFromString<EpisodesResponse>(body)
                val detail = response.data?.show?.detail
                val list = if (mode == "dub") detail?.dub else detail?.sub
                list?.sortedWith(compareBy { it.toDoubleOrNull() ?: 0.0 }) ?: emptyList()
            }
        }

    suspend fun getStreamLinks(showId: String, mode: String, epNo: String): Result<List<AniCliStreamLink>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val variables = """{"showId":"$showId","translationType":"$mode","episodeString":"$epNo"}"""
                val body = apiGet(variables, episodeEmbedGql)
                val response = json.decodeFromString<SourceResponse>(body)
                val sourceUrls = response.data?.episode?.sourceUrls ?: emptyList()

                // Fetch all 4 providers in parallel (same order as ani-cli)
                val providerNames = listOf("Default", "Yt-mp4", "S-mp4", "Luf-Mp4")
                coroutineScope {
                    providerNames.map { providerName ->
                        async {
                            val source = sourceUrls.find { it.sourceName == providerName }
                                ?: return@async emptyList()
                            val encoded = source.sourceUrl.removePrefix("--")
                            val path = decodeProviderPath(encoded) ?: run {
                                Log.w(TAG, "Could not decode path for $providerName")
                                return@async emptyList<AniCliStreamLink>()
                            }
                            fetchEmbedLinks(providerName, path)
                        }
                    }.awaitAll()
                }.flatten()
                    .distinctBy { it.url }
                    .sortedWith(compareByDescending { it.quality.extractResolutionNumber() })
            }
        }

    // ─── Internal helpers ────────────────────────────────────────────────────

    private fun decodeProviderPath(encoded: String): String? {
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < encoded.length) {
            val hex = encoded.substring(i, i + 2).lowercase()
            val char = hexDecodeMap[hex] ?: return null
            sb.append(char)
            i += 2
        }
        return sb.toString().replace("/clock", "/clock.json")
    }

    private fun fetchEmbedLinks(providerName: String, path: String): List<AniCliStreamLink> {
        return try {
            val url = "https://allanime.day$path"
            val responseBody = get(url, referer = REFERER)
            parseEmbedLinks(providerName, responseBody)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch embed for $providerName: ${e.message}")
            emptyList()
        }
    }

    private fun parseEmbedLinks(providerName: String, body: String): List<AniCliStreamLink> {
        return try {
            val response = json.decodeFromString<EmbedResponse>(body)
            val links = response.links ?: return emptyList()
            links.flatMap { embedLink ->
                when {
                    // Direct MP4 link with quality
                    embedLink.link != null && embedLink.quality != null -> {
                        val rawUrl = embedLink.link
                        if (rawUrl.contains("repackager.wixmp.com")) {
                            expandWixmpUrl(rawUrl)
                        } else {
                            listOf(AniCliStreamLink(quality = embedLink.quality, url = rawUrl))
                        }
                    }
                    // HLS stream (type="hls" or hardsub_lang set)
                    embedLink.type == "hls" && embedLink.url != null -> {
                        listOf(
                            AniCliStreamLink(
                                quality = "HLS",
                                url = embedLink.url,
                                isM3u8 = true,
                                referer = REFERER,
                            ),
                        )
                    }
                    else -> emptyList()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse embed links from $providerName: ${e.message}")
            emptyList()
        }
    }

    /** Expands a WixMP repackager URL into individual quality MP4 links. */
    private fun expandWixmpUrl(repackagerUrl: String): List<AniCliStreamLink> {
        val base = repackagerUrl
            .replace("repackager.wixmp.com/", "")
            .replace(Regex("\\.urlset.*"), "")
        val qualityMatch = Regex("/,([^/]+),/mp4").find(base) ?: return listOf(
            AniCliStreamLink(quality = "auto", url = repackagerUrl),
        )
        val qualities = qualityMatch.groupValues[1].split(",").filter { it.isNotBlank() }
        return qualities.map { quality ->
            val url = base.replaceFirst(Regex("/,([^/]+),/mp4"), "/$quality/mp4")
            AniCliStreamLink(quality = quality, url = url)
        }
    }

    private fun apiGet(variables: String, query: String): String {
        val encodedVars = URLEncoder.encode(variables, "UTF-8")
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$API_BASE?variables=$encodedVars&query=$encodedQuery"
        return get(url, referer = REFERER)
    }

    private fun get(url: String, referer: String): String {
        val request = Request.Builder()
            .url(url)
            .addHeader("Referer", referer)
            .addHeader("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $url")
            return response.body?.string() ?: throw Exception("Empty response from $url")
        }
    }

    companion object {
        private const val API_BASE = "https://api.allanime.day/api"
        private const val REFERER = "https://allmanga.to"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/121.0"
    }
}

private fun String.extractResolutionNumber(): Int =
    Regex("(\\d+)").find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0
