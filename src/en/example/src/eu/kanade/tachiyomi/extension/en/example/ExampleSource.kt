package eu.kanade.tachiyomi.extension.en.example

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response

class ExampleSource : HttpSource() {
    override val name = "Example Extension"
    override val baseUrl = "https://example.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/popular", headers)
    override fun popularMangaParse(response: Response) = throw UnsupportedOperationException("Not implemented")

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest", headers)
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException("Not implemented")

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/search?q=$query", headers)
    override fun searchMangaParse(response: Response) = throw UnsupportedOperationException("Not implemented")

    override fun mangaDetailsParse(response: Response) = throw UnsupportedOperationException("Not implemented")

    override fun chapterListParse(response: Response) = throw UnsupportedOperationException("Not implemented")

    override fun pageListParse(response: Response) = throw UnsupportedOperationException("Not implemented")
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException("Not implemented")
}