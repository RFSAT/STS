package androidx.security.crypto

import android.content.Context
import android.content.SharedPreferences

// androidx.security.crypto stand-ins. The real classes come from a library
// the offline type-check does not resolve; only what CloudSettings uses.

class MasterKey {
    enum class KeyScheme { AES256_GCM }
    class Builder(context: Context) {
        fun setKeyScheme(scheme: KeyScheme): Builder = this
        fun build(): MasterKey = MasterKey()
    }
}

object EncryptedSharedPreferences {
    enum class PrefKeyEncryptionScheme { AES256_SIV }
    enum class PrefValueEncryptionScheme { AES256_GCM }
    fun create(
        context: Context,
        fileName: String,
        masterKey: MasterKey,
        keyScheme: PrefKeyEncryptionScheme,
        valueScheme: PrefValueEncryptionScheme
    ): SharedPreferences = throw UnsupportedOperationException()
}
