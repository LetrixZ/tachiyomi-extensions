package eu.kanade.tachiyomi.extension.en.anchira

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ListEntry(
    val id: Int,
    val key: String,
    val hash: String,
    val title: String,
    val pages: Int,
    val cover: Image,
    val tags: List<Tag>,
)

@Serializable
data class Image(
    @SerialName("n") val name: String,
)

@Serializable
data class Tag(
    val name: String,
    val namespace: Int? = null,
)

@Serializable
data class LibraryResponse(
    val entries: List<ListEntry>? = null,
    val total: Int,
)

@Serializable
data class Entry(
    val id: Int,
    val key: String,
    @SerialName("published_at") val publishedAt: Long,
    val title: String,
    @SerialName("thumb_index") val thumbnailIndex: Int,
    val hash: String,
    val data: List<Image>,
    val tags: List<Tag>,
    val url: String? = null,
)
