package eu.kanade.tachiyomi.extension.en.likemanga

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class LikeManga : ParsedHttpSource() {
    override val name = "Like Manga"
    override val baseUrl = "https://likemanga.ink"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/search/?act=search&f[status]=all&f[sortby]=hot&pageNum=$page", headers)
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

    // Latest
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/search/?act=search&f[status]=all&f[sortby]=lastest-chap&pageNum=$page", headers)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
        url.addQueryParameter("act", "search")
        url.addQueryParameter("pageNum", page.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("f[status]", "all")
            url.addQueryParameter("f[sortby]", "lastest-chap")
            url.addQueryParameter("f[keyword]", query.trim())
            return GET(url.build().toString(), headers)
        }

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    if (filter.state != 0) {
                        // Genre filter uses a different endpoint structure
                        return GET("$baseUrl/genres/${filter.toUriPart()}?page=$page", headers)
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

        return GET(url.build().toString(), headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // Filters
    override fun getFilterList() = FilterList(
        Filter.Header("Search query ignores filters"),
        GenreFilter(),
        StatusFilter(),
        SortFilter()
    )

    private class GenreFilter : Filter.Select<String>(
        "Genre (Overrides Sort/Status)",
        VALS.map { it.first }.toTypedArray()
    ) {
        fun toUriPart() = VALS[state].second

        companion object {
            private val VALS = arrayOf(
                Pair("All", ""),
                Pair("Action", "action/"),
                Pair("Adult", "adult/"),
                Pair("Adaptation", "adaptation/"),
                Pair("Adventure", "adventure/"),
                Pair("Anime", "anime/"),
                Pair("Comedy", "comedy/"),
                Pair("Completed", "completed/"),
                Pair("Cooking", "cooking/"),
                Pair("Crime", "crime/"),
                Pair("Crossdressin", "crossdressin/"),
                Pair("Delinquents", "delinquents/"),
                Pair("Demons", "demons/"),
                Pair("Detective", "detective/"),
                Pair("Drama", "drama/"),
                Pair("Ecchi", "ecchi/"),
                Pair("Fantasy", "fantasy/"),
                Pair("Game", "game/"),
                Pair("Ghosts", "ghosts/"),
                Pair("Harem", "harem/"),
                Pair("Historical", "historical/"),
                Pair("Horror", "horror/"),
                Pair("Isekai", "isekai/"),
                Pair("Josei", "josei/"),
                Pair("Magic", "magic/"),
                Pair("Magical", "magical/"),
                Pair("Manhua", "manhua/"),
                Pair("Manhwa", "manhwa/"),
                Pair("Martial Arts", "martial-arts/"),
                Pair("Mature", "mature/"),
                Pair("Mecha", "mecha/"),
                Pair("Medical", "medical/"),
                Pair("Military", "military/"),
                Pair("Moder", "moder/"),
                Pair("Monsters", "monsters/"),
                Pair("Music", "music/"),
                Pair("Mystery", "mystery/"),
                Pair("Office Workers", "office-workers/"),
                Pair("One shot", "one-shot/"),
                Pair("Philosophical", "philosophical/"),
                Pair("Police", "police/"),
                Pair("Reincarnation", "reincarnation/"),
                Pair("Reverse", "reverse/"),
                Pair("Reverse harem", "reverse-harem/"),
                Pair("Romance", "romance/"),
                Pair("Royal family", "royal-family/"),
                Pair("School Life", "school-life/"),
                Pair("Sci-fi", "scifi/"),
                Pair("Seinen", "seinen/"),
                Pair("Shoujo", "shoujo/"),
                Pair("Smut", "smut/"),
                Pair("Shoujo Ai", "shoujo-ai/"),
                Pair("Shounen", "shounen/"),
                Pair("Shounen Ai", "shounen-ai/"),
                Pair("Slice of Life", "slice-of-life/"),
                Pair("Sports", "sports/"),
                Pair("Super power", "super-power/"),
                Pair("Superhero", "superhero/"),
                Pair("Supernatural", "supernatural/"),
                Pair("Survival", "survival/"),
                Pair("Thriller", "thriller/"),
                Pair("Time Travel", "time-travel/"),
                Pair("Tragedy", "tragedy/"),
                Pair("Vampire", "vampire/"),
                Pair("Villainess", "villainess/"),
                Pair("Webtoons", "webtoons/"),
                Pair("Yaoi", "yaoi/"),
                Pair("Yuri", "yuri/"),
                Pair("Zombies", "zombies/")
            )
        }
    }

    private class StatusFilter : Filter.Select<String>(
        "Status",
        VALS.map { it.first }.toTypedArray()
    ) {
        fun toUriPart() = VALS[state].second

        companion object {
            private val VALS = arrayOf(
                Pair("All", "all"),
                Pair("Complete", "complete"),
                Pair("In process", "in-process"),
                Pair("Pause", "pause")
            )
        }
    }

    private class HeaderInterceptor : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            return chain.proceed(request)
        }
    }

    private class SortFilter : Filter.Select<String>(
        "Sort by",
        VALS.map { it.first }.toTypedArray()
    ) {
        fun toUriPart() = VALS[state].second

        companion object {
            private val VALS = arrayOf(
                Pair("Hot", "hot"),
                Pair("Lastest update", "lastest-chap"),
                Pair("New", "lastest-manga"),
                Pair("Top all", "top-manga"),
                Pair("Top month", "top-month"),
                Pair("Top week", "top-week"),
                Pair("Top day", "top-day"),
                Pair("Follow", "follow"),
                Pair("Comment", "comment"),
                Pair("Num. Chapter", "num-chap")
            )
        }
    }

    // Details
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

    // Chapters
    override fun chapterListSelector() = "ul#list_chapter_id_detail li.wp-manga-chapter"

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

    // Pages
    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        document.select("div.page-chapter img").forEachIndexed { index, img ->
            pages.add(Page(index, "", img.attr("abs:src")))
        }
        return pages
    }

    override fun imageUrlParse(document: Document) = ""
}
