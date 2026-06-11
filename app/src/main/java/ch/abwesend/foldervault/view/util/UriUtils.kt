package ch.abwesend.foldervault.view.util

import android.net.Uri

fun displayNameFromUri(uri: Uri): String =
    uri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: uri.toString()
