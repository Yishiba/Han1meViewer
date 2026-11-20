package com.yenaly.han1meviewer.logic.model.github

/**
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2024/03/21 021 08:56
 */
data class Latest(
    val version: String,
    val changelog: String,
    val downloadLink: String,
    /**
     * Node ID for the download link
     *
     * GitHub release asset identity, used only for cache matching.
     */
    val nodeId: String,
    /** SHA-256 from GitHub's release asset metadata. */
    val sha256: String = "",
)
