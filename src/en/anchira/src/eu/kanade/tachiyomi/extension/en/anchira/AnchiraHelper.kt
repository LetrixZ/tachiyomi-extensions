package eu.kanade.tachiyomi.extension.en.anchira

import com.ensarsarajcic.kotlinx.serialization.msgpack.MsgPack
import com.ensarsarajcic.kotlinx.serialization.msgpack.MsgPackConfiguration
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object AnchiraHelper {
    private val formatterDate = SimpleDateFormat("EEE, dd-MMM-yyyy HH:mm:ss zzz", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("GMT") }

    val msgPack = MsgPack(configuration = MsgPackConfiguration(ignoreUnknownKeys = true))
    val json = Json { ignoreUnknownKeys = true }

    private fun getTomorrowDate() = Date().apply {
        time += 3600 * 1000 * 24
    }

    fun formattedTomorrowDate(): String = formatterDate.format(getTomorrowDate())

    fun getPathFromUrl(url: String) = "${url.split("/").reversed()[1]}/${url.split("/").last()}"

    inline fun <reified T> decodeBytes(bytes: ByteArray, useExternalApi: Boolean): T =
        if (useExternalApi) {
            json.decodeFromString(String(bytes, Charsets.UTF_8))
        } else {
            msgPack.decodeFromByteArray(decodeResponse(bytes))
        }

    fun decodeResponse(buf: ByteArray): ByteArray {
        val padSize = buf.size / 2
        val pad = buf.copyOfRange(0, padSize)
        val data = buf.copyOfRange(padSize, buf.size)

        for (i in 0 until padSize) {
            data[i] = (data[i].toInt() xor pad[i].toInt()).toByte()
        }

        return data
    }
}
