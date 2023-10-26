package eu.kanade.tachiyomi.extension.en.anchira

import com.ensarsarajcic.kotlinx.serialization.msgpack.MsgPack
import com.ensarsarajcic.kotlinx.serialization.msgpack.MsgPackConfiguration
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromByteArray
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class Anchira : HttpSource() {

    private val msgPack = MsgPack(configuration = MsgPackConfiguration(ignoreUnknownKeys = true))

    override val name = "Anchira"

    override val baseUrl = "https://anchira.to"

    private val apiUrl = "$baseUrl/api/v1/library"

    private val cdnUrl = "https://kisakisexo.xyz"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().add("Referer", baseUrl).add("X-Requested-With", "XMLHttpRequest")

    // Latest

    override fun latestUpdatesRequest(page: Int) = GET("$apiUrl?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val bytes = response.body.bytes()
        val data = msgPack.decodeFromByteArray<LibraryResponse>(bytes)

        return MangasPage(
            data.entries.map {
                SManga.create().apply {
                    url = "$baseUrl/${it.id}/${it.key}"
                    title = it.title
                    thumbnail_url = "$cdnUrl/${it.id}/${it.key}/m/${it.cover.name}"
                }
            }.toList(),
            data.entries.size < data.total,
        )
    }

    // Popular

    override fun popularMangaRequest(page: Int) = GET("$apiUrl?sort=32&page=$page", headers)

    override fun popularMangaParse(response: Response) = latestUpdatesParse(response)

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = apiUrl.toHttpUrlOrNull()!!.newBuilder()

        if (query.isNotBlank()) {
            url.addQueryParameter("s", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is CategoryGroup -> {
                    var sum = 0

                    filter.state.forEach { category ->
                        when (category.name) {
                            "Manga" -> if (category.state) sum += 1
                            "Doujinshi" -> if (category.state) sum += 2
                            "Illustration" -> if (category.state) sum += 4
                        }
                    }

                    if (sum > 0) url.addQueryParameter("cat", sum.toString())
                }

                is SortFilter -> {
                    val sort = when (filter.state?.index) {
                        0 -> "1"
                        1 -> "2"
                        2 -> "4"
                        4 -> "32"
                        else -> ""
                    }

                    if (sort.isNotEmpty()) url.addQueryParameter("sort", sort)
                    if (filter.state?.ascending == true) url.addQueryParameter("order", "1")
                }

                else -> {}
            }
        }

        return GET(url.toString(), headers)
    }

    override fun searchMangaParse(response: Response) = latestUpdatesParse(response)

    // Details

    override fun mangaDetailsRequest(manga: SManga) = GET("$apiUrl/${manga.url.split("/").reversed()[1]}/${manga.url.split("/").last()}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val bytes = response.body.bytes()
        val data = msgPack.decodeFromByteArray<Entry>(bytes)

        return SManga.create().apply {
            url = "$baseUrl/g/${data.id}/${data.key}"
            title = data.title
            artist = data.tags.filter { it.namespace == 1 }.joinToString(", ") { it.name }
            author = data.tags.filter { it.namespace == 2 }.joinToString(", ") { it.name }
            genre = data.tags.filter { it.namespace != 1 && it.namespace != 2 }.joinToString(", ") { it.name }
            status = SManga.UNKNOWN
            thumbnail_url = "$cdnUrl/${data.id}/${data.key}/m/${data.data[data.thumbIndex].name}"
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    override fun getMangaUrl(manga: SManga) = manga.url

    // Chapter

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val bytes = response.body.bytes()
        val data = msgPack.decodeFromByteArray<Entry>(bytes)

        return listOf(
            SChapter.create().apply {
                url = "$baseUrl/g/${data.id}/${data.key}"
                name = "Chapter"
                date_upload = data.publishedAt
                chapter_number = 1f
            },
        )
    }

    // Page List

    override fun pageListRequest(chapter: SChapter) = GET("$apiUrl/${chapter.url.split("/").reversed()[1]}/${chapter.url.split("/").last()}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val bytes = response.body.bytes()
        val data = msgPack.decodeFromByteArray<Entry>(bytes)

        return data.data.mapIndexed { i, img -> Page(i, imageUrl = "$cdnUrl/${data.id}/${data.key}/${data.hash}/b/${img.name}") }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException("Not used")

    override fun getFilterList() = FilterList(
        CategoryGroup(),
        SortFilter(),
    )

    private class CategoryFilter(name: String) : Filter.CheckBox(name, false)

    private class CategoryGroup : Filter.Group<CategoryFilter>("Categories", listOf("Manga", "Doujinshi", "Illustration").map { CategoryFilter(it) })

    private class SortFilter : Filter.Sort(
        "Sort",
        arrayOf("Title", "Pages", "Date published", "Date uploaded", "Popularity"),
        Selection(2, false),
    )
}
