package io.github.estiaksoyeb.autodrop

data class DropboxItem(
    val name: String,
    val pathDisplay: String,
    val pathLower: String,
    val isFolder: Boolean
)

data class DropboxListResponse(
    val entries: List<DropboxItem>,
    val cursor: String,
    val hasMore: Boolean
)
