package com.google.gson

class Gson {
    fun toJson(src: Any?): String = "{}"
    fun <T> fromJson(json: String?, type: java.lang.reflect.Type): T? = null
    inline fun <reified T> fromJson(json: String?): T? = null
    fun <T> fromJson(json: String?, cls: Class<T>): T? = null
}
class GsonBuilder { fun create(): Gson = Gson(); fun setPrettyPrinting(): GsonBuilder = this }
