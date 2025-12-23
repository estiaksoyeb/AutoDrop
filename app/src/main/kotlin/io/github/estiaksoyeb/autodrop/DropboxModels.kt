package io.github.estiaksoyeb.autodrop

data class DropboxItem(
    val name: String,
    val pathDisplay: String,
    val pathLower: String,
    val isFolder: Boolean,
    val contentHash: String? = null,
    val size: Long = 0
)

data class DropboxListResponse(
    val entries: List<DropboxItem>,
    val cursor: String,
    val hasMore: Boolean
)
