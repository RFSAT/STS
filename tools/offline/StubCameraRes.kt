package androidx.camera.core.resolutionselector

import android.util.Size

class ResolutionStrategy(size: Size, fallback: Int) {
    companion object {
        const val FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER = 1
        @JvmStatic val HIGHEST_AVAILABLE_STRATEGY = ResolutionStrategy(Size(0, 0), 0)
    }
}
class ResolutionSelector private constructor() {
    class Builder {
        fun setResolutionStrategy(s: ResolutionStrategy): Builder = this
        fun build(): ResolutionSelector = ResolutionSelector()
    }
}
