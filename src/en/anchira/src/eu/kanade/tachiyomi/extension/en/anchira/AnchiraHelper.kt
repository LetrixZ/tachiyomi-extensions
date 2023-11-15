package eu.kanade.tachiyomi.extension.en.anchira

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.math.floor

object AnchiraHelper {
    val json = Json { ignoreUnknownKeys = true }

    fun getPathFromUrl(url: String) = "${url.split("/").reversed()[1]}/${url.split("/").last()}"

    inline fun <reified T> decodeBytes(bytes: ByteArray, useExternalApi: Boolean): T =
        if (useExternalApi) {
            json.decodeFromString(String(bytes, Charsets.UTF_8))
        } else {
            json.decodeFromString(decodeResponse(bytes))
        }

    fun decodeResponse(buf: ByteArray): String {
        val array = buf.copyOfRange(1, buf.size)
        val halfLength = array.size / 2
        val firstHalf = array.copyOfRange(0, floor(halfLength.toDouble()).toInt())
        val secondHalf = array.copyOfRange(floor(halfLength.toDouble()).toInt(), array.size)
        for (i in firstHalf.indices) {
            secondHalf[i] = (firstHalf[i].toInt() xor secondHalf[i].toInt()).toByte()
        }
        return secondHalf.decodeToString()
    }

    fun prepareTags(tags: List<Tag>, group: Boolean) = tags.map {
        if (it.namespace == null) {
            it.namespace = 6
        }
        it
    }
        .sortedBy { it.namespace }
        .map {
            val tag = it.name.lowercase()
            return@map if (group) {
                when (it.namespace) {
                    1 -> "artist:$tag"
                    2 -> "circle:$tag"
                    3 -> "parody:$tag"
                    4 -> "magazine:$tag"
                    else -> "tag:$tag"
                }
            } else {
                tag
            }
        }
        .joinToString(", ") { it }
}
