package com.bunty.clipsync

data class ToolbarItem(
    val lottieResId: Int,
    val label: String,
    val onClick: () -> Unit,
    val hasBadge: Boolean = false
)
