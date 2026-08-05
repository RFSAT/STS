package android.text.style

// Only the paragraph span the bullet lists use.
interface LeadingMarginSpan {
    class Standard(first: Int, rest: Int) : LeadingMarginSpan
}
