package com.rfsat.sts.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.rfsat.sts.R

/**
 * List adapter for catalogue names, breaking each at its dash when it will
 * not fit on one line.
 *
 * The measurement is done in [getView] rather than up front because that is
 * the first point at which the row's real width and the real text size are
 * both known — they depend on the dialog's width and the user's font scale,
 * neither of which this code should try to predict. The original strings are
 * kept, so [getItem] still returns what was put in and selection by position
 * continues to index the caller's own list.
 */
class WrappingNameAdapter(
    context: Context,
    private val names: List<String>
) : ArrayAdapter<String>(context, R.layout.list_item, names) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent) as TextView
        val raw = names.getOrNull(position) ?: return view
        val available = (parent.width - view.paddingLeft - view.paddingRight).toFloat()
        view.text = NameWrap.wrapAtDash(raw, view.paint, available)
        return view
    }
}
