package com.bunty.clipsync

enum class NavigationTab(
    val title: String,
    val lottieResId: Int
) {
    HOME("Home", R.raw.nav_dashboard),
    HISTORY("History", R.raw.nav_history)
}
