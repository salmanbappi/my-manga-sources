package eu.kanade.tachiyomi.extension.en.elftoon

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

class Elftoon : ParsedHttpSource() {

    override val name = "Elf Toon"

    override val baseUrl = "https://elftoon.xyz"

    override val lang = "en"

    override val supportsLatest = true

    override val id: Long = 884729104728194726L

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/manga/page/$page/?order=popular", headers)
    }

    override fun popularMangaSelector() = "div.listupd div.bs div.bsx"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val titleElement = element.selectFirst("a")!!
        manga.setUrlWithoutDomain(titleElement.attr("href"))
        manga.title = titleElement.attr("title").trim()
        manga.thumbnail_url = element.selectFirst("img")?.attr("abs:src")
        return manga
    }

    override fun popularMangaNextPageSelector() = "a.next, a.r"

    // Latest
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/manga/page/$page/?order=update", headers)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/page/$page/".toHttpUrl().newBuilder().apply {
                addQueryParameter("s", query)
            }
        } else {
            "$baseUrl/manga/page/$page/".toHttpUrl().newBuilder().apply {
                filters.forEach { filter ->
                    when (filter) {
                        is GenreGroup -> {
                            filter.state.filter { it.state }.forEach {
                                addQueryParameter("genre[]", it.value)
                            }
                        }
                        is StatusFilter -> {
                            addQueryParameter("status", filter.toUriPart())
                        }
                        is TypeFilter -> {
                            addQueryParameter("type", filter.toUriPart())
                        }
                        is OrderByFilter -> {
                            addQueryParameter("order", filter.toUriPart())
                        }
                        else -> {}
                    }
                }
            }
        }.build()

        return GET(url.toString(), headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // Details
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        manga.title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        manga.description = document.select("div.entry-content[itemprop=description] p").text().trim()
        
        val infoElement = document.selectFirst("div.tsinfo")
        manga.author = infoElement?.select(".imptdt:contains(Author) i")?.text()?.trim()
        manga.artist = infoElement?.select(".imptdt:contains(Artist) i")?.text()?.trim()
        manga.genre = document.select(".mgen a").joinToString { it.text() }
        
        val statusText = infoElement?.select(".imptdt:contains(Status) i")?.text()
        manga.status = when {
            statusText?.contains("Ongoing", true) == true -> SManga.ONGOING
            statusText?.contains("Completed", true) == true -> SManga.COMPLETED
            statusText?.contains("Hiatus", true) == true -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        
        manga.thumbnail_url = document.selectFirst("div.thumb img")?.attr("abs:src")
        return manga
    }

    // Chapters
    override fun chapterListSelector() = "div#chapterlist ul li"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val link = element.selectFirst("a")!!
        chapter.setUrlWithoutDomain(link.attr("href"))
        chapter.name = element.select("span.chapternum").text().trim()
        chapter.date_upload = parseDate(element.select("span.chapterdate").text().trim())
        return chapter
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.US)
            dateFormat.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // Pages
    override fun pageListParse(document: Document): List<Page> {
        return document.select("div#readerarea img[src]").mapIndexed { index, element ->
            val url = element.attr("abs:src")
            Page(index, "", url)
        }
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException("Not used")

    // Filters
    override fun getFilterList() = FilterList(
        OrderByFilter(),
        StatusFilter(),
        TypeFilter(),
        GenreGroup()
    )

    private class OrderByFilter : UriPartFilter(
        "Order by",
        arrayOf(
            Pair("Default", ""),
            Pair("A-Z", "title"),
            Pair("Z-A", "titlereverse"),
            Pair("Update", "update"),
            Pair("Added", "latest"),
            Pair("Popular", "popular")
        )
    )

    private class StatusFilter : UriPartFilter(
        "Status",
        arrayOf(
            Pair("All", ""),
            Pair("Ongoing", "ongoing"),
            Pair("Completed", "completed"),
            Pair("Hiatus", "hiatus")
        )
    )

    private class TypeFilter : UriPartFilter(
        "Type",
        arrayOf(
            Pair("All", ""),
            Pair("Manga", "manga"),
            Pair("Manhwa", "manhwa"),
            Pair("Manhua", "manhua"),
            Pair("MangaToon", "comic"),
            Pair("Novel", "novel")
        )
    )

    private class GenreGroup : Filter.Group<GenreCheckBox>(
        "Genres",
        arrayOf(
            Pair("ac", "134"),
            Pair("Action", "3"),
            Pair("Adventure", "19"),
            Pair("Animals", "138"),
            Pair("Apocalypse", "55"),
            Pair("Comedy", "4"),
            Pair("Cooking", "20"),
            Pair("Cultivation", "88"),
            Pair("Delinquents", "29"),
            Pair("Demons", "7"),
            Pair("Drama", "12"),
            Pair("Ecchi", "13"),
            Pair("Fantasy", "5"),
            Pair("Ghosts", "103"),
            Pair("Gore", "16"),
            Pair("Harem", "57"),
            Pair("Historical", "41"),
            Pair("Horror", "102"),
            Pair("Isekai", "6"),
            Pair("Magic", "35"),
            Pair("Martial Arts", "21"),
            Pair("Mature", "191"),
            Pair("Military", "77"),
            Pair("Modern", "116"),
            Pair("Monsters", "8"),
            Pair("Murim", "37"),
            Pair("Mystery", "14"),
            Pair("Office Workers", "26"),
            Pair("Post-Apocalyptic", "67"),
            Pair("Psychological", "42"),
            Pair("Rebirth", "112"),
            Pair("Reincarnation", "9"),
            Pair("Romance", "34"),
            Pair("Samurai", "10"),
            Pair("School Life", "44"),
            Pair("Sci-Fi", "15"),
            Pair("Shounen", "59"),
            Pair("Slice of Life", "137"),
            Pair("Sports", "51"),
            Pair("Superhero", "17"),
            Pair("Supernatural", "25"),
            Pair("Survival", "49"),
            Pair("System", "47"),
            Pair("Thriller", "142"),
            Pair("Time Travel", "52"),
            Pair("Tragedy", "30"),
            Pair("Video Games", "70"),
            Pair("Wuxia", "39"),
            Pair("Zombies", "104")
        ).map { GenreCheckBox(it.first, it.second) }
    )

    private class GenreCheckBox(name: String, val value: String) : Filter.CheckBox(name)

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}