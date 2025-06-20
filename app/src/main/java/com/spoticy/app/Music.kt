package com.spoticy.app

import android.net.Uri

data class Music(
    val title: String,
    val uri: Uri,
    val lastModified: Long
)
