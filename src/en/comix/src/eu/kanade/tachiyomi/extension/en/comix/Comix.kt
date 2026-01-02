package eu.kanade.tachiyomi.extension.en.comix

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class Comix : HttpSource() {

    override val name = "Comix"

    override val baseUrl = "https://comix.to"

    private val apiUrl = "https://api.comick.io"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/top".toHttpUrl().newBuilder()
            .addQueryParameter("type", "trending")
            .addQueryParameter("comic_types", "manhwa,manhua")
            .addQueryParameter("accept_mature", "true")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<List<MangaItem>>()
        val mangas = data.map { it.toSManga() }
        return MangasPage(mangas, false)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "uploaded")
            .addQueryParameter("tachiyomi", "true")
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val data = response.parseAs<List<MangaItem>>()
        val mangas = data.map { it.toSManga() }
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/v1.0/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "30")

        if (query.isNotBlank()) {
            url.addQueryParameter("q", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    url.addQueryParameter("sort", filter.toUriPart())
                }
                is GenreFilter -> {
                    filter.state.filter { it.state }.forEach {
                        url.addQueryParameter("genres", it.value)
                    }
                }
                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val data = response.parseAs<List<MangaItem>>()
        val mangas = data.map { it.toSManga() }
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("$apiUrl/comic/${manga.url.substringAfterLast("/")}", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val data = response.parseAs<MangaDetailsResponse>()
        return data.comic.toSManga()
    }

    override fun chapterListRequest(manga: SManga): Request {
        return GET("$apiUrl/comic/${manga.url.substringAfterLast("/")}/chapters?lang=en&limit=100", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var page = 1
        var hasNextPage = true

        while (hasNextPage) {
            val url = response.request.url.newBuilder()
                .setQueryParameter("page", page.toString())
                .build()
            val pageResponse = client.newCall(GET(url, headers)).execute()
            val data = pageResponse.parseAs<ChapterListResponse>()
            chapters.addAll(data.chapters.map { it.toSChapter() })
            hasNextPage = data.chapters.isNotEmpty() && chapters.size < data.total
            page++
            if (page > 100) break
        }

        return chapters
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return GET("$apiUrl/chapter/${chapter.url.substringAfterLast("/")}", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = response.parseAs<PageListResponse>()
        return data.chapter.images.mapIndexed { index, image ->
            Page(index, "", "https://meo.comick.pictures/${image.url}")
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    private inline fun <reified T> Response.parseAs(): T {
        return json.decodeFromString(body.string())
    }

    override fun getFilterList() = FilterList(
        SortFilter(),
        GenreFilter()
    )

    private class SortFilter : Filter.Select<String>(
        "Sort By",
        arrayOf("Trending", "Most Views", "Most Follows", "Newest")
    ) {
        fun toUriPart() = when (state) {
            0 -> "trending"
            1 -> "views"
            2 -> "follow"
            3 -> "uploaded"
            else -> "trending"
        }
    }

    private class GenreFilter : Filter.Group<CheckBoxFilter>(
        "Genres",
        listOf(
            CheckBoxFilter("Action", "action"),
            CheckBoxFilter("Adventure", "adventure"),
            CheckBoxFilter("Comedy", "comedy"),
            CheckBoxFilter("Drama", "drama"),
            CheckBoxFilter("Fantasy", "fantasy"),
            CheckBoxFilter("Isekai", "isekai")
        )
    )

    private class CheckBoxFilter(name: String, val value: String) : Filter.CheckBox(name)

    @Serializable
    data class MangaItem(
        val hid: String,
        val title: String,
        val md_covers: List<Cover>? = null
    ) {
        fun toSManga() = SManga.create().apply {
            url = "/comic/$hid"
            title = this@MangaItem.title
            thumbnail_url = md_covers?.firstOrNull()?.let { "https://meo.comick.pictures/${it.b2key}" }
        }
    }

    @Serializable
    data class Cover(val b2key: String)

    @Serializable
    data class MangaDetailsResponse(val comic: MangaDetails)

    @Serializable
    data class MangaDetails(
        val hid: String,
        val title: String,
        val desc: String? = null,
        val md_covers: List<Cover>? = null,
        val status: Int? = null
    ) {
        fun toSManga() = SManga.create().apply {
            url = "/comic/$hid"
            title = this@MangaDetails.title
            description = desc
            thumbnail_url = md_covers?.firstOrNull()?.let { "https://meo.comick.pictures/${it.b2key}" }
            status = when (this@MangaDetails.status) {
                1 -> SManga.ONGOING
                2 -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    @Serializable
    data class ChapterListResponse(
        val chapters: List<ChapterItem>,
        val total: Int
    )

    @Serializable
    data class ChapterItem(
        val hid: String,
        val title: String? = null,
        val chap: String? = null,
        val created_at: String? = null,
        val group_name: List<String>? = null
    ) {
        fun toSChapter() = SChapter.create().apply {
            url = "/chapter/$hid"
            name = "Chapter $chap" + (if (title.isNullOrBlank()) "" else ": $title")
            scanlator = group_name?.joinToString()
        }
    }

    @Serializable
    data class PageListResponse(val chapter: PageList)

    @Serializable
    data class PageList(val images: List<ImageItem>)

    @Serializable
    data class ImageItem(val url: String)
}
