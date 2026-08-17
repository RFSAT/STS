package com.rfsat.sts.ui

import android.content.Context

/** Range-mode preferences: spoken output (default OFF) and keep-awake
 *  (default ON). Cached so views can read them cheaply while shooting. */
object RangeSettings {
    private const val PREFS = "bas_range"
    private var speak = false
    private var keepAwake = true
    private var autoReconnect = false
    private var autoShowResults = false
    private var autoCollect = false
    private var remoteTrigger = false
    private var skipConfirm = false

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        speak = p.getBoolean("speak", false)
        keepAwake = p.getBoolean("keep_awake", true)
        autoReconnect = p.getBoolean("auto_reconnect", false)
        autoShowResults = p.getBoolean("auto_show_results", false)
        autoCollect = p.getBoolean("auto_collect", false)
        remoteTrigger = p.getBoolean("remote_trigger", false)
        skipConfirm = p.getBoolean("skip_confirm", false)
    }
    fun speak(): Boolean = speak
    fun keepAwake(): Boolean = keepAwake
    fun setSpeak(c: Context, v: Boolean) { speak = v; c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("speak", v).apply() }
    fun setKeepAwake(c: Context, v: Boolean) { keepAwake = v; c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("keep_awake", v).apply() }
    fun autoReconnect(): Boolean = autoReconnect
    fun autoShowResults(): Boolean = autoShowResults
    fun setAutoReconnect(c: Context, v: Boolean) { autoReconnect = v; c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto_reconnect", v).apply() }
    fun setAutoShowResults(c: Context, v: Boolean) { autoShowResults = v; c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto_show_results", v).apply() }
    fun autoCollect(): Boolean = autoCollect
    fun remoteTrigger(): Boolean = remoteTrigger
    fun skipConfirm(): Boolean = skipConfirm
    fun setAutoCollect(c: Context, v: Boolean) { autoCollect = v; c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto_collect", v).apply() }
    fun setRemoteTrigger(c: Context, v: Boolean) { remoteTrigger = v; c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("remote_trigger", v).apply() }
    fun setSkipConfirm(c: Context, v: Boolean) { skipConfirm = v; c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("skip_confirm", v).apply() }
}
