package eu.kanade.tachiyomi.extension.en.likemangain

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class LikeMangaIn : ParsedHttpSource() {

    override val name = "Like Manga In"

    override val baseUrl = "https://likemanga.in"

    override val lang = "en"

    override val supportsLatest = true

    override val id: Long = 611833355147795521L

    private val json = Json { ignoreUnknownKeys = true }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/manga/?m_orderby=views&page=$page", headers)
    }

    override fun popularMangaSelector() = "div.page-item-detail"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val titleElement = element.selectFirst("div.item-thumb a")!!
        manga.setUrlWithoutDomain(titleElement.attr("href"))
        manga.title = titleElement.attr("title").ifEmpty { titleElement.text() }.trim()
        manga.thumbnail_url = element.selectFirst("img")?.let { img ->
            val url = img.attr("data-src").ifEmpty { img.attr("src") }
            url.trim()
        }
        return manga
    }

    override fun popularMangaNextPageSelector() = "a.nextpostslink"

    // Latest
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/manga/?m_orderby=latest&page=$page", headers)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/page/$page/".toHttpUrl().newBuilder()
        url.addQueryParameter("s", query)
        url.addQueryParameter("post_type", "wp-manga")

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    filter.state.filter { it.state }.forEach {
                        url.addQueryParameter("genre[]", it.value)
                    }
                }
                is StatusFilter -> {
                    val status = filter.toUriPart()
                    if (status.isNotEmpty()) {
                        url.addQueryParameter("status[]", status)
                    }
                }
                is SortFilter -> {
                    val sort = filter.toUriPart()
                    if (sort.isNotEmpty()) {
                        url.addQueryParameter("m_orderby", sort)
                    }
                }
                else -> {}
            }
        }

        return GET(url.build().toString(), headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // Details
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        manga.title = document.selectFirst("div.post-title h1")?.text()?.trim() ?: "Unknown"
        manga.author = document.select("div.author-content a").joinToString { it.text() }
        manga.artist = document.select("div.artist-content a").joinToString { it.text() }
        manga.description = document.select("div.description-summary div.summary__content").text().trim()
        manga.genre = document.select("div.genres-content a").joinToString { it.text() }
        manga.status = parseStatus(document.select("div.post-status div.summary-content").text())
        manga.thumbnail_url = document.selectFirst("div.summary_image img")?.let { img ->
            val url = img.attr("data-src").ifEmpty { img.attr("src") }
            url.trim()
        }
        return manga
    }

    private fun parseStatus(status: String) = when {
        status.contains("Ongoing", true) -> SManga.ONGOING
        status.contains("Completed", true) -> SManga.COMPLETED
        status.contains("On Hold", true) -> SManga.ON_HIATUS
        status.contains("Canceled", true) -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    // Chapters
    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()
        
        // Extract manga slug to filter out sidebar chapters
        val mangaSlug = response.request.url.pathSegments.filter { it.isNotEmpty() }.last()

        // Check if chapters are loaded via AJAX
        val mangaId = document.selectFirst("div#manga-chapters-holder")?.attr("data-id")
            ?: document.selectFirst("input[name=wp-manga-data-id]")?.attr("value")
            ?: document.selectFirst("a.wp-manga-action-button[data-post]")?.attr("data-post")
            ?: document.selectFirst("div[data-post-id]")?.attr("data-post-id")

        if (mangaId != null) {
            val xhrHeaders = headersBuilder()
                .add("X-Requested-With", "XMLHttpRequest")
                .add("Referer", response.request.url.toString())
                .build()

            val formBody = FormBody.Builder()
                .add("action", "manga_get_chapters")
                .add("manga", mangaId)
                .build()

            try {
                val ajaxResponse = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", xhrHeaders, formBody)).execute()
                val ajaxDoc = ajaxResponse.asJsoup()

                ajaxDoc.select("div.chapter-item, li.wp-manga-chapter").forEach {
                    chapters.add(chapterFromElement(it))
                }
            } catch (e: Exception) {}
        }

        // Fallback for non-AJAX
        if (chapters.isEmpty()) {
            document.select("div.chapter-item, li.wp-manga-chapter").forEach {
                chapters.add(chapterFromElement(it))
            }
        }

        return chapters
            .filter { it.url.contains(mangaSlug) } // Crucial: Remove sidebar items
            .distinctBy { it.url }
    }

    override fun chapterListSelector() = "div.chapter-item, li.wp-manga-chapter"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val link = element.selectFirst("a.btn-link, a")!!
        chapter.setUrlWithoutDomain(link.attr("href"))
        chapter.name = link.text().trim()
        chapter.date_upload = parseDate(element.select("span.chapter-release-date i, span.chapter-release-date").text())
        return chapter
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.US)
            dateFormat.parse(dateStr.trim())?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // Pages
    override fun pageListParse(document: Document): List<Page> {
        return document.select("div.reading-content img, .wp-manga-chapter-img").mapIndexed { index, img ->
            val url = img.attr("data-src").ifEmpty { img.attr("src") }
            Page(index, "", url.trim())
        }
    }

    override fun imageUrlParse(document: Document) = ""

    // Extensions:
    private fun Response.asJsoup(): Document = org.jsoup.Jsoup.parse(body.string(), request.url.toString())

    // Filters
    override fun getFilterList() = FilterList(
        Filter.Header("Search query ignores filters"),
        StatusFilter(),
        SortFilter(),
        Filter.Separator(),
        GenreFilter()
    )

    private class GenreFilter : Filter.Group<GenreCheckBox>("Genres", getGenreList())
    private class GenreCheckBox(name: String, val value: String) : Filter.CheckBox(name)

    private class StatusFilter : Filter.Select<String>("Status", arrayOf("All", "Ongoing", "Completed", "On Hold", "Canceled", "Upcoming"), 0) {
        fun toUriPart() = when (state) {
            1 -> "on-going"
            2 -> "end"
            3 -> "on-hold"
            4 -> "canceled"
            5 -> "upcoming"
            else -> ""
        }
    }

    private class SortFilter : Filter.Select<String>("Sort By", arrayOf("Relevance", "Latest", "Trending", "Most Views", "New", "A-Z", "Rating"), 0) {
        fun toUriPart() = when (state) {
            1 -> "latest"
            2 -> "trending"
            3 -> "views"
            4 -> "new-manga"
            5 -> "alphabet"
            6 -> "rating"
            else -> ""
        }
    }

    companion object {
        private fun getGenreList() = listOf(
            GenreCheckBox("Action", "action"),
            GenreCheckBox("Adaptation", "adaptation"),
            GenreCheckBox("Adult", "adult"),
            GenreCheckBox("Adventure", "adventure"),
            GenreCheckBox("Anime", "anime"),
            GenreCheckBox("Comedy", "comedy"),
            GenreCheckBox("Completed", "completed"),
            GenreCheckBox("Cooking", "cooking"),
            GenreCheckBox("Crime", "crime"),
            GenreCheckBox("Crossdressin", "crossdressin"),
            GenreCheckBox("Delinquents", "delinquents"),
            GenreCheckBox("Demons", "demons"),
            GenreCheckBox("Detective", "detective"),
            GenreCheckBox("Drama", "drama"),
            GenreCheckBox("Ecchi", "ecchi"),
            GenreCheckBox("Fantasy", "fantasy"),
            GenreCheckBox("Game", "game"),
            GenreCheckBox("Ghosts", "ghosts"),
            GenreCheckBox("Harem", "harem"),
            GenreCheckBox("Historical", "historical"),
            GenreCheckBox("Horror", "horror"),
            GenreCheckBox("Isekai", "isekai"),
            GenreCheckBox("Josei", "josei"),
            GenreCheckBox("Magic", "magic"),
            GenreCheckBox("Manhua", "manhua"),
            GenreCheckBox("Manhwa", "manhwa"),
            GenreCheckBox("Martial Arts", "martial-arts"),
            GenreCheckBox("Mature", "mature"),
            GenreCheckBox("Mecha", "mecha"),
            GenreCheckBox("Medical", "medical"),
            GenreCheckBox("Military", "military"),
            GenreCheckBox("Monsters", "monsters"),
            GenreCheckBox("Music", "music"),
            GenreCheckBox("Mystery", "mystery"),
            GenreCheckBox("Office Workers", "office-workers"),
            GenreCheckBox("One Shot", "one-shot"),
            GenreCheckBox("Philosophical", "philosophical"),
            GenreCheckBox("Police", "police"),
            GenreCheckBox("Psychological", "psychological"),
            GenreCheckBox("Reincarnation", "reincarnation"),
            GenreCheckBox("Reverse Harem", "reverse-harem"),
            GenreCheckBox("Romance", "romance"),
            GenreCheckBox("School Life", "school-life"),
            GenreCheckBox("Sci-fi", "sci-fi"),
            GenreCheckBox("Seinen", "seinen"),
            GenreCheckBox("Shoujo", "shoujo"),
            GenreCheckBox("Shoujo Ai", "shoujo-ai"),
            GenreCheckBox("Shounen", "shounen"),
            GenreCheckBox("Shounen Ai", "shounen-ai"),
            GenreCheckBox("Slice of Life", "slice-of-life"),
            GenreCheckBox("Smut", "smut"),
            GenreCheckBox("Sports", "sports"),
            GenreCheckBox("Superhero", "superhero"),
            GenreCheckBox("Supernatural", "supernatural"),
            GenreCheckBox("Survival", "survival"),
            GenreCheckBox("Thriller", "thriller"),
            GenreCheckBox("Time Travel", "time-travel"),
            GenreCheckBox("Tragedy", "tragedy"),
            GenreCheckBox("Vampire", "vampire"),
            GenreCheckBox("Villainess", "villainess"),
            GenreCheckBox("Webtoons", "webtoons"),
            GenreCheckBox("Yaoi", "yaoi"),
            GenreCheckBox("Yuri", "yuri"),
            GenreCheckBox("Zombies", "zombies")
        )
    }
}
