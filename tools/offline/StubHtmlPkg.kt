package androidx.core.text

object HtmlCompat {
    const val FROM_HTML_MODE_LEGACY = 0
    @JvmStatic fun fromHtml(source: String, flags: Int): CharSequence = source
}
