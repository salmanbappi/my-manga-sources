package eu.kanade.tachiyomi.extension.en.likemanga

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class LikeManga : ParsedHttpSource() {

    override val id: Long = 411833355147795520L

    override val name = "Like Manga"

    override val baseUrl = "https://mgread.io"

    override val lang = "en"

    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request {
        val pageSlug = if (page > 1) "page/$page/" else ""
        return GET("$baseUrl/manga-ranking/$pageSlug", headers)
    }

    override fun popularMangaSelector() = "div.manga-item-grid > div.uk-panel, div.manga-card"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val titleElement = element.selectFirst("h2 a, h3 a, a.uk-link-toggle")!!
        manga.setUrlWithoutDomain(titleElement.attr("href"))
        manga.title = titleElement.text().trim()
        manga.thumbnail_url = element.selectFirst("img")?.let { img ->
            img.attr("data-original-src")
                .ifEmpty { img.attr("data-src") }
                .ifEmpty { img.attr("src") }
        }?.trim()
        return manga
    }

    override fun popularMangaNextPageSelector() = "ul.uk-pagination li:not(.uk-disabled) a:has([uk-pagination-next]), ul.uk-pagination a[aria-label='Next page']"

    override fun popularMangaParse(response: Response): MangasPage {
        val page = super.popularMangaParse(response)
        return page.copy(mangas = page.mangas.filterNot { it.isAnime() })
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val pageSlug = if (page > 1) "page/$page/" else ""
        return GET("$baseUrl/recently-updated/$pageSlug", headers)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun latestUpdatesParse(response: Response): MangasPage {
        val page = super.latestUpdatesParse(response)
        return page.copy(mangas = page.mangas.filterNot { it.isAnime() })
    }

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            return GET("$baseUrl/wp-json/initlise/v1/search?term=$encodedQuery&page=$page", headers)
        }

        val url = baseUrl.toHttpUrl().newBuilder().addPathSegment("advanced-filter")
        if (page > 1) {
            url.addPathSegment("page").addPathSegment(page.toString())
        }
        url.addPathSegment("")

        filters.forEach { filter ->
            when (filter) {
                is UriFilter -> filter.addToUri(url)
                else -> {}
            }
        }

        return GET(url.build().toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val requestUrl = response.request.url.toString()
        if (requestUrl.contains("/wp-json/initlise/v1/search")) {
            val results = json.decodeFromString<List<MgSearchDto>>(response.body.string())
            val mangas = results
                .filter { it.postType == "manga" && !it.url.removePrefix("$baseUrl/manga/").removePrefix("/manga/").startsWith("anime-") }
                .map { item ->
                    val manga = SManga.create()
                    manga.setUrlWithoutDomain(item.url)
                    manga.title = Jsoup.parseBodyFragment(item.title).text().trim()
                    manga.thumbnail_url = item.thumb?.trim()
                    manga
                }
            return MangasPage(mangas, results.size >= 10)
        }

        val document = Jsoup.parse(response.body.string(), requestUrl)
        val mangas = document.select("div.manga-item-details, div.manga-card, div.manga-item-grid > div.uk-panel")
            .mapNotNull { element ->
                val link = element.selectFirst("h2 a, h3 a, a.uk-link-toggle, a.uk-link-heading") ?: return@mapNotNull null
                val url = link.attr("href")
                if (url.removePrefix("$baseUrl/manga/").removePrefix("/manga/").startsWith("anime-")) {
                    return@mapNotNull null
                }
                val manga = SManga.create()
                manga.setUrlWithoutDomain(url)
                manga.title = link.text().trim()
                manga.thumbnail_url = element.selectFirst("img")?.let { img ->
                    img.attr("data-original-src")
                        .ifEmpty { img.attr("data-src") }
                        .ifEmpty { img.attr("src") }
                }?.trim()
                manga
            }
        val hasNextPage = document.selectFirst("ul.uk-pagination li:not(.uk-disabled) a:has([uk-pagination-next])") != null ||
            document.selectFirst("ul.uk-pagination a[aria-label='Next page']") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun searchMangaSelector() = throw UnsupportedOperationException("Not used")

    override fun searchMangaFromElement(element: Element): SManga = throw UnsupportedOperationException("Not used")

    override fun searchMangaNextPageSelector(): String? = throw UnsupportedOperationException("Not used")

    // ============================== Details ===============================

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        manga.title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"

        val desc = document.selectFirst("div#manga-description")?.let { el ->
            el.select("p, h1, h2, h3, h4, h5, h6, li").ifEmpty { listOf(el) }
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n\n")
        }
        val altTitle = document.selectFirst("span#comic-othername")?.text()?.trim()
        manga.description = buildString {
            if (!desc.isNullOrEmpty()) append(desc)
            if (!altTitle.isNullOrEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative title: ").append(altTitle)
            }
        }.ifEmpty { null }

        manga.genre = document.select("div#genre-tags a:not(.uk-disabled)").joinToString { it.text().trim() }.ifEmpty { null }

        val statusText = document.selectFirst("#manga-status")?.text()?.trim()?.lowercase()
        manga.status = when (statusText) {
            "ongoing", "caught_up", "caught up" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "season_end", "source_hiatus", "season end", "hiatus" -> SManga.ON_HIATUS
            "dropped", "cancelled", "canceled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        manga.thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("a.story-cover img, div.story-cover img, img.image-3-4")?.let { img ->
                img.attr("data-original-src")
                    .ifEmpty { img.attr("data-src") }
                    .ifEmpty { img.attr("src") }
            }

        return manga
    }

    // ============================== Chapters ==============================

    override fun chapterListSelector() = throw UnsupportedOperationException("Not used")

    override fun chapterFromElement(element: Element): SChapter = throw UnsupportedOperationException("Not used")

    override fun chapterListParse(response: Response): List<SChapter> {
        val html = response.body.string()
        val mangaId = Regex("""postid-(\d+)""").find(html)?.groupValues?.get(1)
            ?: throw Exception("Could not find manga ID")

        val mangaPath = response.request.url.encodedPath.trimEnd('/')

        val firstPageUrl = "$baseUrl/wp-json/initmanga/v1/chapters?manga_id=$mangaId&per_page=50&paged=1"
        val firstPageRes = client.newCall(GET(firstPageUrl, headers)).execute()
        if (!firstPageRes.isSuccessful) {
            firstPageRes.close()
            throw Exception("Failed to load chapters: HTTP ${firstPageRes.code}")
        }

        val firstPageDto = json.decodeFromString<ChapterListDto>(firstPageRes.body.string())
        val allChapters = mutableListOf<ChapterDto>()
        allChapters.addAll(firstPageDto.items)

        val totalPages = firstPageDto.totalPages
        if (totalPages > 1) {
            runBlocking(Dispatchers.IO) {
                val batchSize = 5
                var currentBatchStart = 2
                while (currentBatchStart <= totalPages) {
                    val endBatch = minOf(currentBatchStart + batchSize - 1, totalPages)
                    val deferreds = (currentBatchStart..endBatch).map { page ->
                        async {
                            try {
                                val pageUrl = "$baseUrl/wp-json/initmanga/v1/chapters?manga_id=$mangaId&per_page=50&paged=$page"
                                val res = client.newCall(GET(pageUrl, headers)).execute()
                                if (res.isSuccessful) {
                                    json.decodeFromString<ChapterListDto>(res.body.string()).items
                                } else {
                                    res.close()
                                    emptyList()
                                }
                            } catch (e: Exception) {
                                emptyList()
                            }
                        }
                    }
                    deferreds.awaitAll().forEach { allChapters.addAll(it) }
                    currentBatchStart += batchSize
                }
            }
        }

        return allChapters.map { item ->
            val chapter = SChapter.create()
            chapter.url = "$mangaPath/${item.slug.trim('/')}/"
            chapter.chapter_number = item.number
            val numStr = item.number.toString().removeSuffix(".0")
            val title = item.title?.trim().orEmpty()
            val isLocked = item.lockType != null && item.lockType != "none" && !item.isPurchased

            chapter.name = buildString {
                if (isLocked) append("🔒 ")
                val lower = title.lowercase()
                if (lower.startsWith("chapter") || lower.startsWith("bölüm")) {
                    append(title)
                } else {
                    append("Chapter ").append(numStr)
                    if (title.isNotEmpty()) append(" - ").append(title)
                }
            }
            chapter.date_upload = parseDate(item.createdAt)
            chapter
        }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            dateFormat.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // =============================== Pages ================================

    override fun pageListParse(document: Document) = throw UnsupportedOperationException("Not used")

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        val chapterUrl = response.request.url.toString()
        val document = Jsoup.parse(html, chapterUrl)

        if (document.selectFirst("div.lock-card") != null) {
            throw Exception("Chapter is locked. Login required.")
        }

        val encryptedMatch = Regex("""var\s+InitMangaEncryptedChapter\s*=\s*(\{.*?\});""").find(html)
        if (encryptedMatch != null) {
            val payload = json.decodeFromString<EncryptedPayloadDto>(encryptedMatch.groupValues[1])
            val key = if (payload.salt != null) {
                val keyMatch = Regex("""["']decryption_key["']\s*:\s*["']([^"']+)["']""").find(html)
                    ?: throw Exception("Could not find decryption key")
                passphraseKey(keyMatch.groupValues[1], payload.salt)
            } else {
                fetchChapterKey(payload, chapterUrl)
            }
            val iv = payload.iv.decodeHex()
            val ciphertext = Base64.decode(payload.ciphertext, Base64.DEFAULT)
            val decryptedHtml = String(aesDecrypt(key, iv, ciphertext), Charsets.UTF_8)
            val decryptedDoc = Jsoup.parseBodyFragment(decryptedHtml, baseUrl)

            return decryptedDoc.select("img, canvas[data-enc]").mapNotNull { el ->
                if (el.tagName() == "canvas") {
                    val enc = el.attr("data-enc")
                    if (enc.isNotBlank()) {
                        Base64.decode(enc, Base64.DEFAULT).reversedArray().decodeToString()
                    } else {
                        null
                    }
                } else {
                    val src = el.attr("data-original-src")
                        .ifEmpty { el.attr("data-src") }
                        .ifEmpty { el.attr("src") }
                    if (src.isNotBlank() && !src.startsWith("data:")) el.absUrl(src) else null
                }
            }.mapIndexed { index, imgUrl ->
                Page(index, "", imgUrl)
            }
        }

        return document.select("div#chapter-content img").mapNotNull { img ->
            val src = img.attr("data-original-src")
                .ifEmpty { img.attr("data-src") }
                .ifEmpty { img.attr("src") }
            if (src.isNotBlank() && !src.startsWith("data:")) img.absUrl(src) else null
        }.mapIndexed { index, imgUrl ->
            Page(index, "", imgUrl)
        }
    }

    override fun imageUrlParse(document: Document) = ""

    override fun imageRequest(page: Page): Request {
        val newHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }

    // ============================= Encryption =============================

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        val result = ByteArray(length / 2)
        for (i in result.indices) {
            val index = i * 2
            result[i] = substring(index, index + 2).toInt(16).toByte()
        }
        return result
    }

    private fun passphraseKey(decryptionKey: String, salt: String): ByteArray {
        val passphrase = String(Base64.decode(decryptionKey, Base64.DEFAULT), Charsets.UTF_8)
        val spec = PBEKeySpec(passphrase.toCharArray(), salt.decodeHex(), 999, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
    }

    private fun fetchChapterKey(payload: EncryptedPayloadDto, chapterUrl: String): ByteArray {
        val cid = payload.cid ?: throw Exception("Missing chapter id for key")
        val e = payload.e ?: throw Exception("Missing epoch for key")
        val g = payload.g ?: throw Exception("Missing grant for key")

        val requestBody = """{"chapter_id":$cid,"epoch":$e,"grant":"$g"}"""
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val keyHeaders = headers.newBuilder()
            .set("Referer", chapterUrl)
            .set("Origin", baseUrl)
            .build()

        val keyRequest = Request.Builder()
            .url("$baseUrl/wp-json/initmanga/v1/chapter-key")
            .headers(keyHeaders)
            .post(requestBody)
            .build()

        val response = client.newCall(keyRequest).execute()
        if (!response.isSuccessful) {
            response.close()
            throw Exception("Failed to fetch chapter key: HTTP ${response.code}")
        }

        val resString = response.body.string()
        val keyResponse = json.decodeFromString<ChapterKeyResponseDto>(resString)
        return keyResponse.key.decodeHex()
    }

    private fun aesDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    private fun SManga.isAnime(): Boolean =
        url.removePrefix("$baseUrl/manga/").removePrefix("/manga/").startsWith("anime-")

    // ============================== Filters ===============================

    override fun getFilterList() = FilterList(
        Filter.Header("Search query ignores filters"),
        TypeFilter(),
        StatusFilter(),
        AgeRatingFilter(),
        RatingMinFilter(),
        RatingMaxFilter(),
        SortFilter(),
        Filter.Separator(),
        GenreFilter()
    )

    private interface UriFilter {
        fun addToUri(builder: HttpUrl.Builder)
    }

    private open class UriSelectFilter(
        name: String,
        private val queryName: String,
        private val vals: Array<Pair<String, String>>
    ) : Filter.Select<String>(name, vals.map { it.first }.toTypedArray()), UriFilter {
        override fun addToUri(builder: HttpUrl.Builder) {
            val value = vals[state].second
            if (value.isNotEmpty()) {
                builder.addQueryParameter(queryName, value)
            }
        }
    }

    private class TypeFilter : UriSelectFilter(
        "Type",
        "type",
        arrayOf(
            Pair("All Types", ""),
            Pair("Comic", "comic"),
            Pair("Oneshot", "oneshot")
        )
    )

    private class StatusFilter : UriSelectFilter(
        "Status",
        "status",
        arrayOf(
            Pair("All Status", ""),
            Pair("Ongoing", "ongoing"),
            Pair("Season End", "season_end"),
            Pair("Completed", "completed"),
            Pair("Source Hiatus", "source_hiatus"),
            Pair("Caught Up", "caught_up"),
            Pair("Dropped", "dropped")
        )
    )

    private class AgeRatingFilter : UriSelectFilter(
        "Age Rating",
        "age_rating",
        arrayOf(
            Pair("All Ages", ""),
            Pair("All ages", "all"),
            Pair("13+", "13+"),
            Pair("16+", "16+"),
            Pair("18+", "18+")
        )
    )

    private class RatingMinFilter : UriSelectFilter(
        "Minimum Rating",
        "rating_min",
        arrayOf(
            Pair("Min", "0"),
            Pair("1 star", "1"),
            Pair("2 stars", "2"),
            Pair("3 stars", "3"),
            Pair("4 stars", "4"),
            Pair("5 stars", "5")
        )
    )

    private class RatingMaxFilter : UriSelectFilter(
        "Maximum Rating",
        "rating_max",
        arrayOf(
            Pair("Max", "6"),
            Pair("1 star", "1"),
            Pair("2 stars", "2"),
            Pair("3 stars", "3"),
            Pair("4 stars", "4"),
            Pair("5 stars", "5")
        )
    )

    private class SortFilter : UriSelectFilter(
        "Sort By",
        "sort",
        arrayOf(
            Pair("Latest Updated", "updated"),
            Pair("Newest", "new"),
            Pair("Oldest", "old"),
            Pair("Most Views", "views"),
            Pair("Daily Views", "views_day"),
            Pair("Weekly Views", "views_week"),
            Pair("Monthly Views", "views_month"),
            Pair("Highest Rating", "rating"),
            Pair("Most Power Stone", "power"),
            Pair("Most Followers", "follow")
        )
    )

    private class GenreCheckBox(name: String, val value: String) : Filter.CheckBox(name)

    private class GenreFilter :
        Filter.Group<GenreCheckBox>(
            "Genres",
            GENRES.map { GenreCheckBox(it.first, it.second) }
        ),
        UriFilter {
        override fun addToUri(builder: HttpUrl.Builder) {
            state.filter { it.state }.forEach {
                builder.addQueryParameter("genre[]", it.value)
            }
        }

        companion object {
            private val GENRES = arrayOf(
                Pair("Action", "action"),
                Pair("Adaptation", "adaptation"),
                Pair("Adventure", "adventure"),
                Pair("Comedy", "comedy"),
                Pair("Cooking", "cooking"),
                Pair("Crime", "crime"),
                Pair("Drama", "drama"),
                Pair("Ecchi", "ecchi"),
                Pair("Fantasy", "fantasy"),
                Pair("Harem", "harem"),
                Pair("Historical", "historical"),
                Pair("Horror", "horror"),
                Pair("Isekai", "isekai"),
                Pair("Josei", "josei"),
                Pair("Martial Arts", "martial-arts"),
                Pair("Mature", "mature"),
                Pair("Mecha", "mecha"),
                Pair("Medical", "medical"),
                Pair("Music", "music"),
                Pair("Mystery", "mystery"),
                Pair("Romance", "romance"),
                Pair("School Life", "school-life"),
                Pair("Shoujo", "shoujo"),
                Pair("Shounen", "shounen"),
                Pair("Slice of life", "slice-of-life"),
                Pair("Smut", "smut"),
                Pair("Sports", "sports"),
                Pair("Supernatural", "supernatural"),
                Pair("Webtoons", "webtoons")
            )
        }
    }
}

@Serializable
private class MgSearchDto(
    val title: String,
    val url: String,
    val thumb: String? = null,
    @SerialName("post_type") val postType: String? = null
)

@Serializable
private class EncryptedPayloadDto(
    val ciphertext: String,
    val iv: String,
    val salt: String? = null,
    val cid: Long? = null,
    val e: Long? = null,
    val g: String? = null
)

@Serializable
private class ChapterKeyResponseDto(
    val key: String
)

@Serializable
private class ChapterListDto(
    val items: List<ChapterDto> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 1
)

@Serializable
private class ChapterDto(
    val title: String? = null,
    val number: Float = 0f,
    val slug: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("lock_type") val lockType: String? = null,
    @SerialName("is_purchased") val isPurchased: Boolean = false
)
