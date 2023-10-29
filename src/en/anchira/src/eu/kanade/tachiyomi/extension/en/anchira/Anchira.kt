package eu.kanade.tachiyomi.extension.en.anchira

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class Anchira : HttpSource(), ConfigurableSource {
    override val name = "Anchira"

    override val baseUrl = "https://anchira.to"

    private val apiUrl = "{{API_URL}}"

    private val cdnUrl = "https://kisakisexo.xyz"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(1, 2, TimeUnit.SECONDS)
        .build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("X-Requested-With", "XMLHttpRequest")

    // Latest

    override fun latestUpdatesRequest(page: Int) = GET("$apiUrl?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val json = response.body.string()
        val data = Json.decodeFromString<LibraryResponse>(json)

        return if (data.entries != null) {
            MangasPage(
                data.entries.map {
                    SManga.create().apply {
                        url = "/g/${it.id}/${it.key}"
                        title = it.title
                        thumbnail_url = it.thumbnailUrl
                    }
                }.toList(),
                data.hasNextPage,
            )
        } else {
            MangasPage(listOf(), false)
        }
    }

    // Popular

    override fun popularMangaRequest(page: Int) = GET("$apiUrl?sort=32&page=$page", headers)

    override fun popularMangaParse(response: Response) = latestUpdatesParse(response)

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = apiUrl.toHttpUrl().newBuilder()

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

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response) = latestUpdatesParse(response)

    // Details

    override fun mangaDetailsRequest(manga: SManga) =
        GET("$apiUrl/${getPathFromUrl(manga.url)}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val json = response.body.string()
        val data = Json.decodeFromString<Entry>(json)

        return SManga.create().apply {
            url = "/g/${data.id}/${data.key}"
            title = data.title
            thumbnail_url = data.thumbnailUrl
            artist = data.artist
            author = data.circle
            genre = data.tags
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            status = SManga.COMPLETED
        }
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/${manga.url}"

    // Chapter

    override fun chapterListRequest(manga: SManga) =
        GET("$apiUrl/${getPathFromUrl(manga.url)}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val json = response.body.string()
        val data = Json.decodeFromString<Entry>(json)

        return listOf(
            SChapter.create().apply {
                url = "/g/${data.id}/${data.key}"
                name = "Chapter"
                date_upload = data.published
                chapter_number = 1f
            },
        )
    }

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/g/${getPathFromUrl(chapter.url)}"

    // Page List

    override fun pageListRequest(chapter: SChapter) =
        GET("$apiUrl/${getPathFromUrl(chapter.url)}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val json = response.body.string()
        val data = Json.decodeFromString<Entry>(json)

        return data.pages.mapIndexed { i, img ->
            Page(
                i,
                imageUrl = "$cdnUrl/${data.id}/${data.key}/${data.hash}/b/$img",
            )
        }
    }

    override fun imageRequest(page: Page): Request {
        val imageQuality = if (preferences.getBoolean(IMAGE_QUALITY_PREF, false)) "a" else "b"

        return GET(page.imageUrl!!.replace("/b/", "/$imageQuality/"), headers)
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException("Not used")

    // Settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val imageQualityPref = SwitchPreferenceCompat(screen.context).apply {
            key = IMAGE_QUALITY_PREF
            title = "Use original images"
            summary = getPrefSummary(
                preferences.getBoolean(
                    IMAGE_QUALITY_PREF,
                    false,
                ),
            ).plus("\nCurrently unavailable to avoid being blocked")
            // FIXME: Resampled images are about 500KB while originals are up to 5MB - Using only resampled for now to avoid being blocked
            setEnabled(false)
            setDefaultValue(false)

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    summary = getPrefSummary(newValue.toString().toBoolean())
                    true
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }

        screen.addPreference(imageQualityPref)
    }

    override fun getFilterList() = FilterList(
        CategoryGroup(),
        SortFilter(),
    )

    private fun getPrefSummary(value: Boolean): String {
        return if (value) {
            "Currently using original images"
        } else {
            "Currently using resampled images"
        }
    }

    private fun getPathFromUrl(url: String) =
        "${url.split("/").reversed()[1]}/${url.split("/").last()}"

    private class CategoryFilter(name: String) : Filter.CheckBox(name, false)

    private class CategoryGroup : Filter.Group<CategoryFilter>(
        "Categories",
        listOf("Manga", "Doujinshi", "Illustration").map { CategoryFilter(it) },
    )

    private class SortFilter : Filter.Sort(
        "Sort",
        arrayOf("Title", "Pages", "Date published", "Date uploaded", "Popularity"),
        Selection(2, false),
    )

    companion object {
        private const val IMAGE_QUALITY_PREF = "imageQualityPref"
    }
}
