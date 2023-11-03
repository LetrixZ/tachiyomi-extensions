package eu.kanade.tachiyomi.extension.en.anchira

import android.app.Application
import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.ensarsarajcic.kotlinx.serialization.msgpack.MsgPack
import com.ensarsarajcic.kotlinx.serialization.msgpack.MsgPackConfiguration
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
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
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.concurrent.TimeUnit

class Anchira : HttpSource(), ConfigurableSource {
    private val msgPack = MsgPack(configuration = MsgPackConfiguration(ignoreUnknownKeys = true))

    override val name = "Anchira"

    override val baseUrl = "https://anchira.to"

    private val apiUrl = "$baseUrl/api/v1"

    private val libraryUrl = "$apiUrl/library"

    private val cdnUrl = "https://kisakisexo.xyz"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient
        .newBuilder()
        .rateLimit(2, 1, TimeUnit.SECONDS)
        .addInterceptor { apiInterceptor(it) }
        .addInterceptor { authInterceptor(it) }
        .build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private var authCookie = ""

    override fun headersBuilder() = super.headersBuilder().add("X-Requested-With", "XMLHttpRequest")

    // Latest

    override fun latestUpdatesRequest(page: Int) = GET("$libraryUrl?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val bytes = response.body.bytes()
        val data = msgPack.decodeFromByteArray<LibraryResponse>(bytes)

        return if (data.entries != null) {
            MangasPage(
                data.entries.map {
                    SManga.create().apply {
                        url = "/g/${it.id}/${it.key}"
                        title = it.title
                        thumbnail_url = "$cdnUrl/${it.id}/${it.key}/m/${it.cover.name}"
                        artist = it.tags.filter { it.namespace == 1 }.joinToString(", ") { it.name }
                        author = it.tags.filter { it.namespace == 2 }.joinToString(", ") { it.name }
                        genre = it.tags.filter { it.namespace != 1 && it.namespace != 2 }
                            .sortedBy { it.namespace }.joinToString(", ") { it.name }
                        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
                        status = SManga.COMPLETED
                        initialized = false
                    }
                }.toList(),
                data.entries.size < data.total,
            )
        } else {
            MangasPage(listOf(), false)
        }
    }

    // Popular

    override fun popularMangaRequest(page: Int) = GET("$libraryUrl?sort=32&page=$page", headers)

    override fun popularMangaParse(response: Response) = latestUpdatesParse(response)

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var url = libraryUrl.toHttpUrl().newBuilder()

        url.addQueryParameter("page", page.toString())

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

                is FavoritesFilter -> {
                    if (filter.state) {
                        url = url.toString().replace("library", "user/favorites").toHttpUrl()
                            .newBuilder()
                    }
                }

                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response) = latestUpdatesParse(response)

    // Details

    override fun mangaDetailsRequest(manga: SManga) =
        GET("$libraryUrl/${getPathFromUrl(manga.url)}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val bytes = response.body.bytes()
        val data = msgPack.decodeFromByteArray<Entry>(bytes)

        return SManga.create().apply {
            url = "/g/${data.id}/${data.key}"
            title = data.title
            thumbnail_url =
                "$cdnUrl/${data.id}/${data.key}/m/${data.data[data.thumbnailIndex].name}"
            artist = data.tags.filter { it.namespace == 1 }.joinToString(", ") { it.name }
            author = data.tags.filter { it.namespace == 2 }.joinToString(", ") { it.name }
            genre = data.tags.filter { it.namespace != 1 && it.namespace != 2 }
                .sortedBy { it.namespace }.joinToString(", ") { it.name }
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            status = SManga.COMPLETED
        }.also {
            preferences.edit().putString(it.url, data.url).commit()
        }
    }

    override fun getMangaUrl(manga: SManga) = if (preferences.openSource) {
        getSource(manga)
    } else {
        "$baseUrl${manga.url}"
    }

    // Chapter

    override fun chapterListRequest(manga: SManga) =
        GET("$libraryUrl/${getPathFromUrl(manga.url)}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val bytes = response.body.bytes()
        val data = msgPack.decodeFromByteArray<Entry>(bytes)

        return listOf(
            SChapter.create().apply {
                url = "/g/${data.id}/${data.key}"
                name = "Chapter"
                date_upload = data.publishedAt
                chapter_number = 1f
            },
        )
    }

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/g/${getPathFromUrl(chapter.url)}"

    // Page List

    override fun pageListRequest(chapter: SChapter) =
        GET("$libraryUrl/${getPathFromUrl(chapter.url)}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val bytes = response.body.bytes()
        val data = msgPack.decodeFromByteArray<Entry>(bytes)

        return data.data.mapIndexed { i, img ->
            Page(i, imageUrl = "$cdnUrl/${data.id}/${data.key}/${data.hash}/b/${img.name}")
        }
    }

    override fun imageRequest(page: Page): Request {
        return GET(page.imageUrl!!.replace("/b/", "/${preferences.imageQuality}/"), headers)
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException("Not used")

    // Settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val imageQualityPref = ListPreference(screen.context).apply {
            key = IMAGE_QUALITY_PREF
            title = "Image quality"
            entries = arrayOf("Original", "Resampled")
            entryValues = arrayOf("a", "b")
            setDefaultValue("b")
            summary = "%s"
        }

        val openSourcePref = SwitchPreferenceCompat(screen.context).apply {
            key = OPEN_SOURCE_PREF
            title = "Open original source site in WebView"
            summary =
                "Enable to open the original source (when available) of the book when opening manga or chapter on WebView."
            setDefaultValue(false)
        }

        val usernamePref = EditTextPreference(screen.context).apply {
            key = USERNAME_PREF
            title = "Username"
            summary =
                preferences.username.ifBlank { "Enter your username" }

            setOnPreferenceChangeListener { _, newValue ->
                summary = (newValue as String).ifBlank { "Enter your username" }
                clearCookies()

                true
            }
        }

        val useEmailPref = SwitchPreferenceCompat(screen.context).apply {
            key = USE_EMAIL_PREF
            title = "Login with email"
            summary = "Enable to login with an email instead of a username."

            setOnPreferenceChangeListener { _, newValue ->
                usernamePref.apply {
                    title = if (newValue as Boolean) {
                        "Email"
                    } else {
                        "Username"
                    }
                }
                clearCookies()

                true
            }
        }

        val passwordPref = EditTextPreference(screen.context).apply {
            key = PASSWORD_PREF
            title = "Password"
            summary = if (preferences.password.isBlank()) "Enter your password" else "*".repeat(preferences.password.length)

            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }

            setOnPreferenceChangeListener { _, newValue ->
                summary = if ((newValue as String).isNotBlank()) {
                    "*".repeat(newValue.length)
                } else {
                    "Enter your password"
                }
                clearCookies()

                true
            }
        }

        screen.addPreference(imageQualityPref)
        screen.addPreference(openSourcePref)
        screen.addPreference(useEmailPref)
        screen.addPreference(usernamePref)
        screen.addPreference(passwordPref)
    }

    override fun getFilterList() = FilterList(
        CategoryGroup(),
        SortFilter(),
        FavoritesFilter(),
    )

    private val SharedPreferences.imageQuality
        get() = getString(IMAGE_QUALITY_PREF, "b")!!

    private val SharedPreferences.openSource
        get() = getBoolean(OPEN_SOURCE_PREF, false)

    private val SharedPreferences.useEmail
        get() = getBoolean(USE_EMAIL_PREF, false)

    private val SharedPreferences.username
        get() = getString(USERNAME_PREF, "")!!

    private val SharedPreferences.password
        get() = getString(PASSWORD_PREF, "")!!

    private fun getPathFromUrl(url: String) =
        "${url.split("/").reversed()[1]}/${url.split("/").last()}"

    private class CategoryFilter(name: String) : Filter.CheckBox(name, false)

    private class FavoritesFilter : Filter.CheckBox(
        "Show only my favorites",
    )

    private class CategoryGroup : Filter.Group<CategoryFilter>(
        "Categories",
        listOf("Manga", "Doujinshi", "Illustration").map { CategoryFilter(it) },
    )

    private class SortFilter : Filter.Sort(
        "Sort",
        arrayOf("Title", "Pages", "Date published", "Date uploaded", "Popularity"),
        Selection(2, false),
    )

    private fun decode(buf: ByteArray): ByteArray {
        val padSize = buf.size / 2
        val pad = buf.copyOfRange(0, padSize)
        val data = buf.copyOfRange(padSize, buf.size)

        for (i in 0 until padSize) {
            data[i] = (data[i].toInt() xor pad[i].toInt()).toByte()
        }

        return data
    }

    private fun clearCookies() {
        val baseHttpUrl = baseUrl.toHttpUrl()
        val cookies = client.cookieJar.loadForRequest(baseHttpUrl)
        val obsoletedCookies = cookies.map {
            val cookie = Cookie.parse(baseHttpUrl, "${it.name}=; Max-Age=-1")!!
            cookie
        }
        client.cookieJar.saveFromResponse(baseHttpUrl, obsoletedCookies)
        authCookie = ""
    }

    private fun getSource(manga: SManga) =
        preferences.getString(manga.url, null) ?: "$baseUrl${manga.url}"

    private fun apiInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val requestUrl = request.url.toString()

        return if (requestUrl.contains("/api/v1")) {
            val newRequestBuilder = request.newBuilder()

            if (requestUrl.contains(Regex("/\\d+/\\S+"))) {
                newRequestBuilder.header(
                    "Referer",
                    requestUrl.replace(libraryUrl, "$baseUrl/g"),
                )
            } else if (requestUrl.contains("user/favorites")) {
                newRequestBuilder.header(
                    "Referer",
                    requestUrl.replace("$apiUrl/user/favorites", "$baseUrl/favorites"),
                )
            } else {
                newRequestBuilder.header("Referer", requestUrl.replace(libraryUrl, baseUrl))
            }

            val newRequest = newRequestBuilder.build()
            val response = chain.proceed(newRequest)
            val decodedBody = decode(response.body.bytes())
            response.newBuilder().body(
                decodedBody.toResponseBody(response.body.contentType()),
            ).build()
        } else {
            chain.proceed(request)
        }
    }

    private fun authInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (!request.url.toString().contains("/user/")) {
            return chain.proceed(request)
        }

        if (authCookie.isBlank()) {
            login(chain)
        }

        val authRequest = request.newBuilder().addHeader("Cookie", authCookie).build()

        return chain.proceed(authRequest)
    }

    private fun login(chain: Interceptor.Chain) {
        if (preferences.username.isBlank() || preferences.password.isBlank()) {
            throw IOException("Credentials are empty")
        }

        val body = Json.encodeToString(
            mapOf(
                (if (preferences.useEmail) "email" else "uname") to preferences.username,
                "passwd" to preferences.password,
            ),
        ).toRequestBody()
        val request = chain.request()
        val headers = request.headers.newBuilder().add("Content-Type", "application/json").build()
        val loginRequest = POST("$apiUrl/auth/login", headers, body)
        val response = chain.proceed(loginRequest)

        if (response.code != 200 || response.header("Set-Cookie").isNullOrEmpty()) {
            throw IOException("Login failed")
        }

        authCookie = response.header("Set-Cookie")!!

        response.close()
    }

    companion object {
        private const val IMAGE_QUALITY_PREF = "image_quality"
        private const val OPEN_SOURCE_PREF = "use_manga_source"
        private const val USE_EMAIL_PREF = "use_email"
        private const val USERNAME_PREF = "username"
        private const val PASSWORD_PREF = "password"
    }
}
