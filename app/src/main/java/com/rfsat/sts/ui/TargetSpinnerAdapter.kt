package com.rfsat.sts.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.rfsat.sts.R
import com.rfsat.sts.targets.TargetFace

/**
 * The target picker on the detection screens, with a drawing of each face.
 *
 * This is the spinner that matters. The Targets tab is where a face is
 * browsed; THIS is where one is chosen, moments before registering and
 * scoring against it, and choosing the wrong one has silently rescaled entire
 * score sheets more than once. "ISSF 25/50 m Precision Pistol" and "ISSF 10 m
 * Air Pistol" are both black circles with rings printed on them; the names do
 * not distinguish them to anyone who has not memorised the ring diameters,
 * and the pictures do it instantly.
 *
 * Both the closed spinner and the dropdown use the same row, so the choice is
 * visible after it has been made and not only while making it.
 */
class TargetSpinnerAdapter(
    private val context: Context,
    private val faces: List<TargetFace>
) : BaseAdapter() {

    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private val thumbPx = (36 * context.resources.displayMetrics.density).toInt()

    override fun getCount() = faces.size
    override fun getItem(position: Int) = faces[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        bind(convertView, parent, faces[position], compact = true)

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
        bind(convertView, parent, faces[position], compact = false)

    private fun bind(convertView: View?, parent: ViewGroup?, face: TargetFace, compact: Boolean): View {
        val view = convertView ?: inflater.inflate(R.layout.item_target_spinner, parent, false)
        view.findViewById<ImageView>(R.id.imgFace).setImageBitmap(TargetThumbnail.of(face, thumbPx))
        view.findViewById<TextView>(R.id.tvFaceName).text = buildString {
            append(face.name)
            if (face.custom) append("  [mine]")
            if (!face.verified) append("  ⚠")
        }
        val detail = view.findViewById<TextView>(R.id.tvFaceDetail)
        // The closed spinner has one line of room; the dropdown has more, and
        // the ring geometry is exactly what tells two similar faces apart.
        detail.visibility = if (compact) View.GONE else View.VISIBLE
        if (!compact) {
            detail.text = buildString {
                append(face.summary())
                face.ringPitchMm?.let { append(" · ring pitch ${"%.1f".format(it)} mm") }
                if (face.blackDiameterMm > 0) append(" · black ${"%.0f".format(face.blackDiameterMm)} mm")
            }
        }
        return view
    }

    fun indexOf(faceId: String): Int = faces.indexOfFirst { it.id == faceId }
}
