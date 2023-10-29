package eu.kanade.tachiyomi.extension.en.anchira

import kotlinx.serialization.Serializable

@Serializable
data class ListEntry(
    val id: Int,
    val key: String,
    val title: String,
    val url: String,
    val thumbnailUrl: String,
)

@Serializable
data class LibraryResponse(
    val entries: List<ListEntry>,
    val hasNextPage: Boolean,
)

@Serializable
data class Entry(
    val id: Int,
    val key: String,
    val hash: String,
    val title: String,
    val artist: String,
    val circle: String? = null,
    val published: Long,
    val url: String,
    val thumbnailUrl: String,
    val pages: List<String>,
    val tags: String,
)
