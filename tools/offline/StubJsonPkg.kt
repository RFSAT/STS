package org.json

// Narrow hand-written stand-ins for the org.json classes Android ships.
// Only the members SecondOpinion actually uses, so an unlisted method is a
// compile error here rather than a surprise on the device.

class JSONObject {
    constructor()
    constructor(source: String)
    fun put(name: String, value: Any?): JSONObject = this
    fun put(name: String, value: Int): JSONObject = this
    fun put(name: String, value: Boolean): JSONObject = this
    fun getJSONObject(name: String): JSONObject = JSONObject()
    fun optJSONObject(name: String): JSONObject? = null
    fun optJSONArray(name: String): JSONArray? = null
    fun getString(name: String): String = ""
    fun optString(name: String): String = ""
    fun optString(name: String, fallback: String): String = fallback
    fun optDouble(name: String, fallback: Double): Double = fallback
    fun optInt(name: String): Int = 0
    fun optInt(name: String, fallback: Int): Int = fallback
    fun optBoolean(name: String, fallback: Boolean): Boolean = fallback
    override fun toString(): String = ""
}

class JSONArray {
    constructor()
    constructor(source: String)
    fun put(value: Any?): JSONArray = this
    fun length(): Int = 0
    fun getJSONObject(index: Int): JSONObject = JSONObject()
    fun optJSONObject(index: Int): JSONObject? = null
}
