package eu.kanade.tachiyomi.extension.en.likemanga

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class LikeManga : ParsedHttpSource() {

    override val id: Long = 411833355147795520L

    override val name = "Like Manga"

    override val baseUrl = "https://likemanga.in"

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/?act=search&f[status]=all&f[sortby]=hot&pageNum=$page", headers)
    }

    override fun popularMangaSelector() = "div.video.position-relative"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val titleElement = element.selectFirst("p.title-manga a")!!
        manga.setUrlWithoutDomain(titleElement.attr("href"))
        manga.title = titleElement.text()
        manga.thumbnail_url = element.selectFirst("img.card-img-top")?.attr("abs:src")
        return manga
    }

    override fun popularMangaNextPageSelector() = "li.page-item.active + li a"

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/?act=search&f[status]=all&f[sortby]=lastest-chap&pageNum=$page", headers)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
        url.addQueryParameter("pageNum", page.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("act", "search")
            url.addQueryParameter("f[status]", "all")
            url.addQueryParameter("f[sortby]", "lastest-chap")
            url.addQueryParameter("f[keyword]", query.trim())
        } else {
            url.addQueryParameter("act", "searchadvance")
            filters.forEach { filter ->
                when (filter) {
                    is GenreGroup -> {
                        filter.state.filter { it.state }.forEach {
                            url.addQueryParameter("f[genres][]", it.value)
                        }
                    }
                    is StatusFilter -> {
                        url.addQueryParameter("f[status]", filter.toUriPart())
                    }
                    is SortFilter -> {
                        url.addQueryParameter("f[sortby]", filter.toUriPart())
                    }
                    else -> {}
                }
            }
        }

        return GET(url.build().toString(), headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        manga.title = document.selectFirst("h1.title-detail")?.text() ?: "Unknown"
        manga.author = document.select("li.author p.col-8").text()
        manga.description = document.select("div#summary_shortened, div.detail-content p").text()
        manga.genre = document.select("li.kind p.col-8 a").joinToString { it.text() }

        val statusText = document.select("li.status p.col-8").text()
        manga.status = when {
            statusText.contains("Completed", true) -> SManga.COMPLETED
            statusText.contains("Ongoing", true) -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }

        manga.thumbnail_url = document.selectFirst("div.col-image img")?.attr("abs:src")
        return manga
    }

    override fun chapterListSelector() = "li.wp-manga-chapter"

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body.string(), response.request.url.toString())
        val mangaId = document.selectFirst("#title-detail-manga")?.attr("data-manga")
            ?: throw Exception("Could not find manga ID")

        return runBlocking(Dispatchers.IO) {
            val chapters = mutableListOf<SChapter>()

            // Fetch first page to see if we have anything
            val page1 = getChapters(mangaId, 1)
            chapters.addAll(page1)

            if (page1.isNotEmpty()) {
                val batchSize = 5
                var currentBatchStart = 2

                while (true) {
                    val deferreds = (currentBatchStart until currentBatchStart + batchSize).map { page ->
                        async { page to getChapters(mangaId, page) }
                    }

                    val results = deferreds.awaitAll().sortedBy { it.first }

                    results.forEach { (_, pageChapters) ->
                        chapters.addAll(pageChapters)
                    }

                    if (results.any { it.second.isEmpty() }) {
                        break
                    }

                    currentBatchStart += batchSize
                    if (currentBatchStart > 500) break
                }
            }

            chapters
        }
    }

    private fun getChapters(mangaId: String, page: Int): List<SChapter> {
        val ajaxUrl = "$baseUrl/?act=ajax&code=load_list_chapter&manga_id=$mangaId&page_num=$page"
        val response = client.newCall(GET(ajaxUrl, headers)).execute()
        val jsonString = response.body.string()

        val jsonObject = Json.parseToJsonElement(jsonString).jsonObject
        val ajaxHtml = jsonObject["list_chap"]?.jsonPrimitive?.content ?: ""

        if (ajaxHtml.isBlank()) return emptyList()

        val ajaxDoc = Document.createShell(baseUrl).apply { body().append(ajaxHtml) }
        val elements = ajaxDoc.select(chapterListSelector())

        return elements.map { chapterFromElement(it) }
    }

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val linkElement = element.selectFirst("a")!!
        chapter.setUrlWithoutDomain(linkElement.attr("href"))
        chapter.name = linkElement.text()
        chapter.date_upload = parseDate(element.select("span.chapter-release-date").text())
        return chapter
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            if (dateStr.contains("New", true)) {
                System.currentTimeMillis()
            } else {
                val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.US)
                dateFormat.parse(dateStr)?.time ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        document.select("div.page-chapter img").forEachIndexed { index, img ->
            pages.add(Page(index, "", img.attr("abs:src")))
        }
        return pages
    }

    override fun imageUrlParse(document: Document) = ""

    override fun getFilterList() = FilterList(
        Filter.Header("Search query ignores filters"),
        StatusFilter(),
        SortFilter(),
        Filter.Separator(),
        GenreGroup()
    )

    private class GenreCheckBox(name: String, val value: String) : Filter.CheckBox(name)

    private class GenreGroup : Filter.Group<GenreCheckBox>("Genres", VALS.map { GenreCheckBox(it.first, it.second) }) {
        companion object {
            private val VALS = arrayOf(
                Pair("Action", "action"),
                Pair("Adult", "adult"),
                Pair("Adaptation", "adaptation"),
                Pair("Adventure", "adventure"),
                Pair("Anime", "anime"),
                Pair("Comedy", "comedy"),
                Pair("Completed", "completed"),
                Pair("Cooking", "cooking"),
                Pair("Crime", "crime"),
                Pair("Crossdressin", "crossdressin"),
                Pair("Delinquents", "delinquents"),
                Pair("Demons", "demons"),
                Pair("Detective", "detective"),
                Pair("Drama", "drama"),
                Pair("Ecchi", "ecchi"),
                Pair("Fantasy", "fantasy"),
                Pair("Game", "game"),
                Pair("Ghosts", "ghosts"),
                Pair("Harem", "harem"),
                Pair("Historical", "historical"),
                Pair("Horror", "horror"),
                Pair("Isekai", "isekai"),
                Pair("Josei", "josei"),
                Pair("Magic", "magic"),
                Pair("Magical", "magical"),
                Pair("Manhua", "manhua"),
                Pair("Manhwa", "manhwa"),
                Pair("Martial Arts", "martial-arts"),
                Pair("Mature", "mature"),
                Pair("Mecha", "mecha"),
                Pair("Medical", "medical"),
                Pair("Military", "military"),
                Pair("Moder", "moder"),
                Pair("Monsters", "monsters"),
                Pair("Music", "music"),
                Pair("Mystery", "mystery"),
                Pair("Office Workers", "office-workers"),
                Pair("One shot", "one-shot"),
                Pair("Philosophical", "philosophical"),
                Pair("Police", "police"),
                Pair("Reincarnation", "reincarnation"),
                Pair("Reverse", "reverse"),
                Pair("Reverse harem", "reverse-harem"),
                Pair("Romance", "romance"),
                Pair("Royal family", "royal-family"),
                Pair("School Life", "school-life"),
                Pair("Sci-fi", "scifi"),
                Pair("Seinen", "seinen"),
                Pair("Shoujo", "shoujo"),
                Pair("Smut", "smut"),
                Pair("Shoujo Ai", "shoujo-ai"),
                Pair("Shounen", "shounen"),
                Pair("Shounen Ai", "shounen-ai"),
                Pair("Slice of Life", "slice-of-life"),
                Pair("Sports", "sports"),
                Pair("Super power", "super-power"),
                Pair("Superhero", "superhero"),
                Pair("Supernatural", "supernatural"),
                Pair("Survival", "survival"),
                Pair("Thriller", "thriller"),
                Pair("Time Travel", "time-travel"),
                Pair("Tragedy", "tragedy"),
                Pair("Vampire", "vampire"),
                Pair("Villainess", "villainess"),
                Pair("Webtoons", "webtoons"),
                Pair("Yaoi", "yaoi"),
                Pair("Yuri", "yuri"),
                Pair("Zombies", "zombies")
            )
        }
    }

    private class StatusFilter : Filter.Select<String>("Status", arrayOf("All", "Ongoing", "Completed")) {
        fun toUriPart() = when (state) {
            1 -> "ongoing"
            2 -> "completed"
            else -> "all"
        }
    }

    private class SortFilter : Filter.Select<String>(
        "Sort By",
        arrayOf("Latest Chap", "New Manga", "Hot", "Top All", "Top Month", "Top Week", "Top Day")
    ) {
        fun toUriPart() = when (state) {
            0 -> "lastest-chap"
            1 -> "lastest-manga"
            2 -> "hot"
            3 -> "top-all"
            4 -> "top-month"
            5 -> "top-week"
            6 -> "top-day"
            else -> "lastest-chap"
        }
    }
}
