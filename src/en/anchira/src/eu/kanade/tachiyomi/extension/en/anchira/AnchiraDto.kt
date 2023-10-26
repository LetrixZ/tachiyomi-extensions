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
    @SerialName("w") val width: Int,
    @SerialName("h") val height: Int,
)

@Serializable
data class Tag(
    val name: String,
    val namespace: Int? = null,
)

@Serializable
data class LibraryResponse(
    val entries: List<ListEntry>,
    val limit: Int,
    val page: Int,
    val total: Int,
)

@Serializable
data class Entry(
    val id: Int,
    val key: String,
    val filename: String,
    @SerialName("uploaded_at") val uploadedAt: Long,
    @SerialName("archived_at") val archivedAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("published_at") val publishedAt: Long,
    val status: Int,
    val title: String,
    val pages: Int,
    @SerialName("thumb_index") val thumbIndex: Int,
    val hash: String,
    val data: List<Image>,
    val tags: List<Tag>,
)
