package eu.kanade.tachiyomi.extension.en.likemanga

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class LikeManga : ParsedHttpSource() {

    override val name = "Like Manga"

    override val baseUrl = "https://likemanga.in"

    override val lang = "en"

    override val supportsLatest = true

    override val id: Long = 411833355147795520L

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/manga/?m_orderby=views&page=$page", headers)
    }

    override fun popularMangaSelector() = "div.c-tabs-item__content"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val titleElement = element.selectFirst("div.post-title h3 a, div.post-title h4 a")!!
        manga.setUrlWithoutDomain(titleElement.attr("href"))
        manga.title = titleElement.text()
        manga.thumbnail_url = element.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }
        return manga
    }

    override fun popularMangaNextPageSelector() = "div.nav-previous, a.next.page-numbers"

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
        
        return GET(url.build().toString(), headers)
    }

    override fun searchMangaSelector() = "div.c-tabs-item__content"

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // Details
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        manga.title = document.selectFirst("div.post-title h1")?.text()?.trim() ?: "Unknown"
        manga.author = document.select("div.author-content a").joinToString { it.text() }
        manga.artist = document.select("div.artist-content a").joinToString { it.text() }
        manga.description = document.select("div.description-summary div.summary__content").text()
        manga.genre = document.select("div.genres-content a").joinToString { it.text() }
        manga.status = parseStatus(document.select("div.post-status div.summary-content").text())
        manga.thumbnail_url = document.selectFirst("div.summary_image img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
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

        // Check if chapters are loaded via AJAX
        val mangaId = document.selectFirst("div#manga-chapters-holder")?.attr("data-id") 
            ?: document.selectFirst("input[name=wp-manga-data-id]")?.attr("value")

        if (mangaId != null) {
            val xhrHeaders = headersBuilder()
                .add("X-Requested-With", "XMLHttpRequest")
                .add("Referer", baseUrl + "/")
                .build()

            val formBody = FormBody.Builder()
                .add("action", "manga_get_chapters")
                .add("manga", mangaId)
                .build()

            val ajaxResponse = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", xhrHeaders, formBody)).execute()
            val ajaxDoc = ajaxResponse.asJsoup()
            
            ajaxDoc.select(chapterListSelector()).forEach {
                chapters.add(chapterFromElement(it))
            }
        } else {
             document.select(chapterListSelector()).forEach {
                chapters.add(chapterFromElement(it))
            }
        }

        return chapters
    }

    override fun chapterListSelector() = "li.wp-manga-chapter"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val link = element.selectFirst("a")!!
        chapter.setUrlWithoutDomain(link.attr("href"))
        chapter.name = link.text()
        chapter.date_upload = parseDate(element.select("span.chapter-release-date i, span.chapter-release-date").text())
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
        return document.select("div.reading-content img").mapIndexed { index, img ->
            val url = img.attr("data-src").ifEmpty { img.attr("src") }
            Page(index, "", url.trim())
        }
    }

    override fun imageUrlParse(document: Document) = ""
    
    // Extensions:
    private fun Response.asJsoup(): Document = org.jsoup.Jsoup.parse(body.string(), request.url.toString())
}