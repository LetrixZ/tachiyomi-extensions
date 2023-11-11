package eu.kanade.tachiyomi.extension.en.anchira

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ListEntry(
    @SerialName("id_v2") val id: Int,
    @SerialName("key_v2") val key: String,
    val title: String,
    val cover: Image,
    val tags: List<Tag> = emptyList(),
)

@Serializable
data class Image(
    @SerialName("n") val name: String,
)

@Serializable
data class Tag(
    var name: String,
    var namespace: Int? = null,
)

@Serializable
data class LibraryResponse(
    val entries: List<ListEntry>? = null,
    val total: Int,
)

@Serializable
data class Entry(
    @SerialName("id_v2") val id: Int,
    @SerialName("key_v2") val key: String,
    @SerialName("published_at") val publishedAt: Long,
    val title: String,
    @SerialName("thumb_index") val thumbnailIndex: Int,
    val hash: String,
    val data: List<Image>,
    val tags: List<Tag> = emptyList(),
    val url: String? = null,
)
