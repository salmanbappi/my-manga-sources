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
                    filter.state.filter { it.state }.forEach {
                        url.addEncodedQueryParameter("statuses[]", it.value)
                    }
                }
                is TypeFilter -> {
                    filter.state.filter { it.state }.forEach {
                        url.addEncodedQueryParameter("types[]", it.value)
                    }
                }
                is DemographicFilter -> {
                    filter.state.filter { it.state }.forEach {
                        url.addEncodedQueryParameter("demographics[]", it.value)
                    }
                }
                is GenreFilter -> {
                    filter.state.filter { it.state }.forEach {
                        url.addEncodedQueryParameter("genres[]", it.value)
                    }
                }
                is ThemeFilter -> {
                    filter.state.filter { it.state }.forEach {
                        url.addEncodedQueryParameter("themes[]", it.value)
                    }
                }
                is MinChaptersFilter -> {
                    if (filter.state.isNotBlank()) {
                        url.addQueryParameter("chapters_min", filter.state)
                    }
                }
                is YearFromFilter -> {
                    if (filter.state.isNotBlank()) {
                        url.addQueryParameter("year_from", filter.state)
                    }
                }
                is YearToFilter -> {
                    if (filter.state.isNotBlank()) {
                        url.addQueryParameter("year_to", filter.state)
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
        MinChaptersFilter(),
        YearFromFilter(),
        YearToFilter(),
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

    private class CheckBoxFilter(name: String, val value: String) : Filter.CheckBox(name)

    private class StatusFilter : Filter.Group<CheckBoxFilter>(
        "Status",
        listOf(
            CheckBoxFilter("Releasing", "releasing"),
            CheckBoxFilter("Finished", "finished"),
            CheckBoxFilter("On Hiatus", "on_hiatus"),
            CheckBoxFilter("Discontinued", "discontinued"),
            CheckBoxFilter("Not Yet Released", "not_yet_released")
        )
    )

    private class TypeFilter : Filter.Group<CheckBoxFilter>(
        "Type",
        listOf(
            CheckBoxFilter("Manga", "manga"),
            CheckBoxFilter("Manhwa", "manhwa"),
            CheckBoxFilter("Manhua", "manhua"),
            CheckBoxFilter("Other", "other")
        )
    )

    private class DemographicFilter : Filter.Group<CheckBoxFilter>(
        "Demographic",
        listOf(
            CheckBoxFilter("Shounen", "2"),
            CheckBoxFilter("Seinen", "4"),
            CheckBoxFilter("Shoujo", "1"),
            CheckBoxFilter("Josei", "3")
        )
    )

    private class MinChaptersFilter : Filter.Text("Min Chapters")
    private class YearFromFilter : Filter.Text("Year From")
    private class YearToFilter : Filter.Text("Year To")

    private class GenreFilter : Filter.Group<CheckBoxFilter>(
        "Genres (AND)",
        listOf(
            CheckBoxFilter("Action", "6"),
            CheckBoxFilter("Adult", "87264"),
            CheckBoxFilter("Adventure", "7"),
            CheckBoxFilter("Boys Love", "8"),
            CheckBoxFilter("Comedy", "9"),
            CheckBoxFilter("Crime", "10"),
            CheckBoxFilter("Drama", "11"),
            CheckBoxFilter("Ecchi", "87265"),
            CheckBoxFilter("Fantasy", "12"),
            CheckBoxFilter("Girls Love", "13"),
            CheckBoxFilter("Hentai", "87266"),
            CheckBoxFilter("Historical", "14"),
            CheckBoxFilter("Horror", "15"),
            CheckBoxFilter("Isekai", "16"),
            CheckBoxFilter("Magical Girls", "17"),
            CheckBoxFilter("Mature", "87267"),
            CheckBoxFilter("Mecha", "18"),
            CheckBoxFilter("Medical", "19"),
            CheckBoxFilter("Mystery", "20"),
            CheckBoxFilter("Philosophical", "21"),
            CheckBoxFilter("Psychological", "22"),
            CheckBoxFilter("Romance", "23"),
            CheckBoxFilter("Sci-Fi", "24"),
            CheckBoxFilter("Slice of Life", "25"),
            CheckBoxFilter("Smut", "87268"),
            CheckBoxFilter("Sports", "26"),
            CheckBoxFilter("Superhero", "27"),
            CheckBoxFilter("Thriller", "28"),
            CheckBoxFilter("Tragedy", "29"),
            CheckBoxFilter("Wuxia", "30")
        )
    )

    private class ThemeFilter : Filter.Group<CheckBoxFilter>(
        "Themes (AND)",
        listOf(
            CheckBoxFilter("Aliens", "31"),
            CheckBoxFilter("Animals", "32"),
            CheckBoxFilter("Cooking", "33"),
            CheckBoxFilter("Crossdressing", "34"),
            CheckBoxFilter("Delinquents", "35"),
            CheckBoxFilter("Demons", "36"),
            CheckBoxFilter("Genderswap", "37"),
            CheckBoxFilter("Ghosts", "38"),
            CheckBoxFilter("Gyaru", "39"),
            CheckBoxFilter("Harem", "40"),
            CheckBoxFilter("Incest", "41"),
            CheckBoxFilter("Loli", "42"),
            CheckBoxFilter("Mafia", "43"),
            CheckBoxFilter("Magic", "44"),
            CheckBoxFilter("Martial Arts", "45"),
            CheckBoxFilter("Military", "46"),
            CheckBoxFilter("Monster Girls", "47"),
            CheckBoxFilter("Monsters", "48"),
            CheckBoxFilter("Music", "49"),
            CheckBoxFilter("Ninja", "50"),
            CheckBoxFilter("Office Workers", "51"),
            CheckBoxFilter("Police", "52"),
            CheckBoxFilter("Post-Apocalyptic", "53"),
            CheckBoxFilter("Reincarnation", "54"),
            CheckBoxFilter("Reverse Harem", "55"),
            CheckBoxFilter("Samurai", "56"),
            CheckBoxFilter("School Life", "57"),
            CheckBoxFilter("Shota", "58"),
            CheckBoxFilter("Supernatural", "59"),
            CheckBoxFilter("Survival", "60"),
            CheckBoxFilter("Time Travel", "61"),
            CheckBoxFilter("Traditional Games", "62"),
            CheckBoxFilter("Vampires", "63"),
            CheckBoxFilter("Video Games", "64"),
            CheckBoxFilter("Villainess", "65"),
            CheckBoxFilter("Virtual Reality", "66"),
            CheckBoxFilter("Zombies", "67")
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