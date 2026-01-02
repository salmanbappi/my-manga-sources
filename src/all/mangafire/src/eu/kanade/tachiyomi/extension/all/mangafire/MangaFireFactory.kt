package eu.kanade.tachiyomi.extension.all.mangafire

import eu.kanade.tachiyomi.source.SourceFactory

class MangaFireFactory : SourceFactory {
    override fun createSources() = listOf(
        MangaFire("en"),
    )
}
