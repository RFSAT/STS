package com.google.android.material.bottomnavigation

class BottomNavigationView(c: android.content.Context? = null) : android.view.ViewGroup(c) {
    var selectedItemId: Int = 0
    val menu: android.view.Menu = android.view.Menu()
    fun setOnItemSelectedListener(l: ((android.view.MenuItem) -> Boolean)?) {}
}
