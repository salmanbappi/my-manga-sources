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

    private val apiUrl = "https://comix.to/api/v2"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "relevance:desc")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<MangaListResponse>()
        val mangas = data.result.items.map { it.toSManga() }
        val hasNextPage = data.result.pagination.current_page < data.result.pagination.last_page
        return MangasPage(mangas, hasNextPage)
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "chapter_updated_at:desc")
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("keyword", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    url.addQueryParameter("sort", filter.toUriPart())
                }
                is StatusFilter -> {
                    url.addQueryParameter("statuses", filter.toUriPart())
                }
                is TypeFilter -> {
                    url.addQueryParameter("types", filter.toUriPart())
                }
                is DemographicFilter -> {
                    url.addQueryParameter("demographics", filter.toUriPart())
                }
                is GenreFilter -> {
                    val genres = filter.state
                        .filter { it.state }
                        .joinToString(",") { it.value }
                    if (genres.isNotEmpty()) {
                        url.addQueryParameter("genres", genres)
                    }
                }
                is ThemeFilter -> {
                    val themes = filter.state
                        .filter { it.state }
                        .joinToString(",") { it.value }
                    if (themes.isNotEmpty()) {
                        url.addQueryParameter("themes", themes)
                    }
                }
                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // Details
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

    // Chapters
    override fun chapterListRequest(manga: SManga): Request {
        return GET("$apiUrl/manga${manga.url}/chapters", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response.parseAs<ChapterListResponse>()
        return data.result.items.map { it.toSChapter() }
    }

    // Pages
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

    override fun getFilterList() = FilterList(
        SortFilter(),
        StatusFilter(),
        TypeFilter(),
        DemographicFilter(),
        Filter.Separator(),
        GenreFilter(),
        ThemeFilter()
    )

    private class SortFilter : Filter.Select<String>(
        "Sort By",
        arrayOf(
            "Best Match",
            "Updated Date",
            "Created Date",
            "Title Ascending",
            "Year Descending",
            "Average Score",
            "Most Views 7d",
            "Most Views 1mo",
            "Most Views 3mo",
            "Total Views",
            "Most Follows"
        )
    ) {
        fun toUriPart() = when (state) {
            0 -> "relevance:desc"
            1 -> "chapter_updated_at:desc"
            2 -> "created_at:desc"
            3 -> "title:asc"
            4 -> "year:desc"
            5 -> "score:desc"
            6 -> "views_7d:desc"
            7 -> "views_30d:desc"
            8 -> "views_90d:desc"
            9 -> "views_total:desc"
            10 -> "follows_total:desc"
            else -> "relevance:desc"
        }
    }

    private class StatusFilter : Filter.Select<String>(
        "Status",
        arrayOf(
            "All",
            "Releasing",
            "Finished",
            "On Hiatus",
            "Discontinued",
            "Not Yet Released"
        )
    ) {
        fun toUriPart() = when (state) {
            0 -> ""
            1 -> "releasing"
            2 -> "finished"
            3 -> "on_hiatus"
            4 -> "discontinued"
            5 -> "not_yet_released"
            else -> ""
        }
    }

    private class TypeFilter : Filter.Select<String>(
        "Type",
        arrayOf(
            "All",
            "Manga",
            "Manhwa",
            "Manhua",
            "Other"
        )
    ) {
        fun toUriPart() = when (state) {
            0 -> ""
            1 -> "manga"
            2 -> "manhwa"
            3 -> "manhua"
            4 -> "other"
            else -> ""
        }
    }

    private class DemographicFilter : Filter.Select<String>(
        "Demographic",
        arrayOf(
            "All",
            "Shounen",
            "Seinen",
            "Shoujo",
            "Josei"
        )
    ) {
        fun toUriPart() = when (state) {
            0 -> ""
            1 -> "2"
            2 -> "4"
            3 -> "1"
            4 -> "3"
            else -> ""
        }
    }

    private class GenreCheckBox(name: String, val value: String) : Filter.CheckBox(name)

    private class GenreFilter : Filter.Group<GenreCheckBox>(
        "Genres",
        listOf(
            GenreCheckBox("Action", "6"),
            GenreCheckBox("Adult", "87264"),
            GenreCheckBox("Adventure", "7"),
            GenreCheckBox("Boys Love", "8"),
            GenreCheckBox("Comedy", "9"),
            GenreCheckBox("Crime", "10"),
            GenreCheckBox("Drama", "11"),
            GenreCheckBox("Ecchi", "87265"),
            GenreCheckBox("Fantasy", "12"),
            GenreCheckBox("Girls Love", "13"),
            GenreCheckBox("Hentai", "87266"),
            GenreCheckBox("Historical", "14"),
            GenreCheckBox("Horror", "15"),
            GenreCheckBox("Isekai", "16"),
            GenreCheckBox("Magical Girls", "17"),
            GenreCheckBox("Mature", "87267"),
            GenreCheckBox("Mecha", "18"),
            GenreCheckBox("Medical", "19"),
            GenreCheckBox("Mystery", "20"),
            GenreCheckBox("Philosophical", "21"),
            GenreCheckBox("Psychological", "22"),
            GenreCheckBox("Romance", "23"),
            GenreCheckBox("Sci-Fi", "24"),
            GenreCheckBox("Slice of Life", "25"),
            GenreCheckBox("Smut", "87268"),
            GenreCheckBox("Sports", "26"),
            GenreCheckBox("Superhero", "27"),
            GenreCheckBox("Thriller", "28"),
            GenreCheckBox("Tragedy", "29"),
            GenreCheckBox("Wuxia", "30")
        )
    )

    private class ThemeFilter : Filter.Group<GenreCheckBox>(
        "Themes",
        listOf(
            GenreCheckBox("Aliens", "31"),
            GenreCheckBox("Animals", "32"),
            GenreCheckBox("Cooking", "33"),
            GenreCheckBox("Crossdressing", "34"),
            GenreCheckBox("Delinquents", "35"),
            GenreCheckBox("Demons", "36"),
            GenreCheckBox("Genderswap", "37"),
            GenreCheckBox("Ghosts", "38"),
            GenreCheckBox("Gyaru", "39"),
            GenreCheckBox("Harem", "40"),
            GenreCheckBox("Incest", "41"),
            GenreCheckBox("Loli", "42"),
            GenreCheckBox("Mafia", "43"),
            GenreCheckBox("Magic", "44"),
            GenreCheckBox("Martial Arts", "45"),
            GenreCheckBox("Military", "46"),
            GenreCheckBox("Monster Girls", "47"),
            GenreCheckBox("Monsters", "48"),
            GenreCheckBox("Music", "49"),
            GenreCheckBox("Ninja", "50"),
            GenreCheckBox("Office Workers", "51"),
            GenreCheckBox("Police", "52"),
            GenreCheckBox("Post-Apocalyptic", "53"),
            GenreCheckBox("Reincarnation", "54"),
            GenreCheckBox("Reverse Harem", "55"),
            GenreCheckBox("Samurai", "56"),
            GenreCheckBox("School Life", "57"),
            GenreCheckBox("Shota", "58"),
            GenreCheckBox("Supernatural", "59"),
            GenreCheckBox("Survival", "60"),
            GenreCheckBox("Time Travel", "61"),
            GenreCheckBox("Traditional Games", "62"),
            GenreCheckBox("Vampires", "63"),
            GenreCheckBox("Video Games", "64"),
            GenreCheckBox("Villainess", "65"),
            GenreCheckBox("Virtual Reality", "66"),
            GenreCheckBox("Zombies", "67")
        )
    )

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
        val slug: String,
        val poster: Poster? = null,
        val status: String? = null
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
        val alt_titles: List<String> = emptyList(),
        val synopsis: String? = null,
        val poster: Poster? = null,
        val status: String? = null,
        val type: String? = null
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
        val items: List<ChapterItem>
    )

    @Serializable
    data class ChapterItem(
        val chapter_id: Long,
        val number: Float,
        val name: String? = null,
        val created_at: Long? = null
    ) {
        fun toSChapter() = SChapter.create().apply {
            url = "/$chapter_id"
            name = "Chapter $number" + (if (this@ChapterItem.name.isNullOrBlank()) "" else ": ${this@ChapterItem.name}")
            date_upload = created_at?.times(1000) ?: 0L
        }
    }

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
