package eu.kanade.tachiyomi.extension.en.likemangain

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import kotlinx.serialization.json.Json
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

    override val id: Long = 411833355147795520L

    private val json = Json { ignoreUnknownKeys = true }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/manga/page/$page/?m_orderby=views", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val page = super.popularMangaParse(response)
        return page.copy(mangas = page.mangas.distinctBy { it.url })
    }

    override fun popularMangaSelector() = "div.page-item-detail, div.c-tabs-item__content, div.row.c-tabs-item__content"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val titleElement = element.selectFirst("div.item-thumb a, div.tab-thumb a, div.post-title a, h3 a, h4 a")!!
        manga.setUrlWithoutDomain(titleElement.attr("href"))
        manga.title = titleElement.attr("title").ifEmpty { titleElement.text() }.trim()
        manga.thumbnail_url = element.select("img").firstOrNull { it.hasAttr("src") || it.hasAttr("data-src") }?.let { img ->
            val url = img.attr("data-src").ifEmpty { img.attr("src") }
            url.trim()
        }
        return manga
    }

    override fun popularMangaNextPageSelector() = "a.nextpostslink"

    // Latest
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/manga/page/$page/?m_orderby=latest", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val page = super.latestUpdatesParse(response)
        return page.copy(mangas = page.mangas.distinctBy { it.url })
    }

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/page/$page/".toHttpUrl().newBuilder().apply {
                addQueryParameter("s", query)
                addQueryParameter("post_type", "wp-manga")
            }
        } else {
            "$baseUrl/manga/page/$page/".toHttpUrl().newBuilder()
        }

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

    override fun searchMangaParse(response: Response): MangasPage {
        val mangasPage = super.searchMangaParse(response)
        val query = response.request.url.queryParameter("s")

        val distinctMangas = mangasPage.mangas.distinctBy { it.url }

        if (query.isNullOrBlank()) return mangasPage.copy(mangas = distinctMangas)

        return mangasPage.copy(mangas = SearchUtils.rank(distinctMangas, query))
    }

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
        // Site uses POST request to the /ajax/chapters/ endpoint
        return POST(baseUrl + manga.url + "ajax/chapters/", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()

        // Accurate slug extraction
        val urlStr = response.request.url.toString()
        val mangaSlug = urlStr.substringAfter("/manga/").substringBefore("/")

        document.select("div.chapter-item, li.wp-manga-chapter").forEach {
            val chapter = chapterFromElement(it)
            // Normalize URL and ensure it belongs to the current manga
            if (chapter.url.contains("/manga/$mangaSlug/")) {
                chapter.url = chapter.url.substringBefore("?").removeSuffix("/")
                chapters.add(chapter)
            }
        }

        return chapters.distinctBy { it.url }
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

private object SearchUtils {
    fun rank(mangas: List<SManga>, query: String): List<SManga> {
        if (query.isBlank()) return mangas

        val normalizedQuery = normalize(query)
        val queryTokens = getTokens(normalizedQuery)

        return mangas.map { manga ->
            val normalizedTitle = normalize(manga.title)
            val score = calculateScore(normalizedTitle, normalizedQuery, queryTokens)
            manga to score
        }.filter { it.second >= 0.25 } // Lowered threshold from 0.4
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun calculateScore(title: String, query: String, queryTokens: List<String>): Double {
        if (title.equals(query, ignoreCase = true)) return 1.0 // Exact match
        if (title.startsWith(query, ignoreCase = true)) return 0.9 // Prefix match
        if (title.contains(query, ignoreCase = true)) return 0.8 // Substring match

        // Fuzzy match (Sørensen–Dice)
        val dice = diceCoefficient(title, query)

        // Token match
        val titleTokens = getTokens(title)

        // If no tokens, rely purely on fuzzy/substring logic above
        if (titleTokens.isEmpty() || queryTokens.isEmpty()) return dice

        // Calculate token overlap
        val tokenOverlap = queryTokens.count { qt -> titleTokens.any { tt -> tt == qt } }
        val tokenScore = tokenOverlap.toDouble() / queryTokens.size

        // Combined score: Token (70%) + Fuzzy (30%)
        return (tokenScore * 0.7) + (dice * 0.3)
    }

    private fun normalize(text: String): String {
        val builder = StringBuilder(text.length)
        var lastWasSpace = false
        for (char in text) {
            // Keep letters and digits
            if (char.isLetterOrDigit()) {
                builder.append(char.lowercaseChar())
                lastWasSpace = false
            } else if (char.isWhitespace()) {
                if (!lastWasSpace) {
                    builder.append(' ')
                    lastWasSpace = true
                }
            }
            // Ignore punctuation but don't replace with space unless necessary
        }
        return builder.toString().trim()
    }

    private fun getTokens(text: String): List<String> {
        // Split by spaces, allow 2-char tokens (e.g. "my", "no", "go")
        return text.split(' ').filter { it.length >= 2 }
    }

    private fun diceCoefficient(s1: String, s2: String): Double {
        val n1 = s1.length
        val n2 = s2.length
        if (n1 == 0 || n2 == 0) return 0.0

        val bigrams1 = HashSet<String>()
        for (i in 0 until n1 - 1) {
            bigrams1.add(s1.substring(i, i + 2))
        }

        val bigrams2 = HashSet<String>()
        for (i in 0 until n2 - 1) {
            bigrams2.add(s2.substring(i, i + 2))
        }

        val intersection = bigrams1.count { bigrams2.contains(it) }
        return (2.0 * intersection) / (bigrams1.size + bigrams2.size)
    }
}
