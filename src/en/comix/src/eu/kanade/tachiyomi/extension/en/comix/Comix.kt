package eu.kanade.tachiyomi.extension.en.comix

import eu.kanade.tachiyomi.network.GET
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

    private val apiUrl = "https://comix.to/api/v2"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val filters = ComixFilters()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // Popular Section: Most Recent Popular -> views_30d:desc
    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("order[views_30d]", "desc")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<MangaListResponse>()
        val mangas = data.result.items.map { it.toSManga() }
        val hasNextPage = data.result.pagination.current_page < data.result.pagination.last_page
        return MangasPage(mangas, hasNextPage)
    }

    // Latest Section: Latest Updates New -> created_at:desc
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("order[created_at]", "desc")
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("keyword", query)
        }

        filters.forEach { filter ->
            if (filter is ComixFilters.UriFilter) {
                filter.addToUri(url)
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("$apiUrl/manga${manga.url}", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val data = response.parseAs<MangaDetailsResponse>()
        return data.result.toSManga()
    }

    override fun getMangaUrl(manga: SManga): String {
        return "$baseUrl/comic${manga.url}"
    }

    override fun chapterListRequest(manga: SManga): Request {
        return GET("$apiUrl/manga${manga.url}/chapters", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var currentPage = 1
        var hasNextPage = true

        while (hasNextPage) {
            val url = response.request.url.newBuilder()
                .setQueryParameter("page", currentPage.toString())
                .build()
            val pageResponse = client.newCall(GET(url, headers)).execute()
            val data = pageResponse.parseAs<ChapterListResponse>()
            chapters.addAll(data.result.items.map { it.toSChapter() })
            hasNextPage = data.result.pagination.current_page < data.result.pagination.last_page
            currentPage++
            if (currentPage > 500) break
        }

        return chapters
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return GET("$apiUrl/chapters${chapter.url}", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = response.parseAs<PageListResponse>()
        return data.result.images.mapIndexed { index, image ->
            Page(index, "", image.url)
        }
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("Not used")
    }

    private inline fun <reified T> Response.parseAs(): T {
        return json.decodeFromString(body.string())
    }

    override fun getFilterList() = filters.getFilterList()

    @Serializable
    data class MangaListResponse(
        val result: MangaListResult
    )

    @Serializable
    data class MangaListResult(
        val items: List<MangaItem>,
        val pagination: Pagination
    )

    @Serializable
    data class MangaItem(
        val hash_id: String,
        val title: String,
        val poster: Poster? = null
    ) {
        fun toSManga() = SManga.create().apply {
            this.url = "/$hash_id"
            this.title = this@MangaItem.title
            this.thumbnail_url = poster?.large ?: poster?.medium ?: poster?.small
        }
    }

    @Serializable
    data class Poster(
        val small: String? = null,
        val medium: String? = null,
        val large: String? = null
    )

    @Serializable
    data class Pagination(
        val current_page: Int,
        val last_page: Int
    )

    @Serializable
    data class MangaDetailsResponse(
        val result: MangaDetails
    )

    @Serializable
    data class MangaDetails(
        val hash_id: String,
        val title: String,
        val synopsis: String? = null,
        val poster: Poster? = null,
        val status: String? = null
    ) {
        fun toSManga() = SManga.create().apply {
            url = "/$hash_id"
            title = this@MangaDetails.title
            description = synopsis
            thumbnail_url = poster?.large ?: poster?.medium ?: poster?.small
            status = when (this@MangaDetails.status) {
                "finished", "completed" -> SManga.COMPLETED
                "ongoing", "releasing", "publishing" -> SManga.ONGOING
                "on_hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    @Serializable
    data class ChapterListResponse(
        val result: ChapterListResult
    )

    @Serializable
    data class ChapterListResult(
        val items: List<ChapterItem>,
        val pagination: Pagination
    )

    @Serializable
    data class ChapterItem(
        val chapter_id: Long,
        val number: Float,
        val name: String? = null,
        val created_at: Long? = null,
        val scanlation_group: ScanlationGroup? = null
    ) {
        fun toSChapter() = SChapter.create().apply {
            url = "/$chapter_id"
            name = "Chapter $number" + (if (this@ChapterItem.name.isNullOrBlank()) "" else ": ${this@ChapterItem.name}")
            date_upload = created_at?.times(1000) ?: 0L
            scanlator = scanlation_group?.name
        }
    }

    @Serializable
    data class ScanlationGroup(
        val name: String
    )

    @Serializable
    data class PageListResponse(
        val result: PageListResult
    )

    @Serializable
    data class PageListResult(
        val images: List<ImageItem>
    )

    @Serializable
    data class ImageItem(
        val url: String
    )
}