package com.rfsat.sts.targets

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.rfsat.sts.R
import com.rfsat.sts.databinding.ActivityTargetsBinding
import com.rfsat.sts.log.Logger
import com.rfsat.sts.ui.BaseActivity
import com.rfsat.sts.ui.TargetThumbnail

/**
 * Browse, adopt, copy and create target faces.
 *
 * THE EDIT MODEL, WHICH IS THE WHOLE POINT OF THIS SCREEN. A built-in face is
 * read-only. "Copy & edit" produces a custom face with a new id, and the
 * original stays exactly where it was. That is not caution for its own sake:
 * a session records the FACE ID it was scored against, so if editing a
 * built-in were allowed, correcting a ring diameter today would silently
 * change what every past session claims to have measured. Copying makes the
 * old sessions keep pointing at the old geometry, which is the only version
 * of events that is true.
 */
class TargetActivity : BaseActivity() {

    private lateinit var binding: ActivityTargetsBinding
    private lateinit var repo: TargetRepository

    private var shown: List<TargetFace> = emptyList()
    private var selected: TargetFace? = null

    /** Photo picker for a user-supplied face image. */
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } // best effort: not every provider grants persistable permission
            promptForCustomFace(uri.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTargetsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = TargetRepository(this)

        binding.spDiscipline.adapter = adapter(TargetCatalog.disciplines())
        binding.spBody.adapter = adapter(TargetCatalog.bodies())
        binding.spDiscipline.onItemSelectedListener = onSelected { refreshList() }
        binding.spBody.onItemSelectedListener = onSelected { refreshList() }

        binding.list.setOnItemClickListener { _, _, position, _ -> select(shown.getOrNull(position)) }

        binding.btnUse.setOnClickListener {
            selected?.let { repo.setActiveFace(it.id); notifyUser("${it.name} is now the active target.") }
        }
        binding.btnEdit.setOnClickListener { selected?.let { editFace(it) } }
        binding.btnDelete.setOnClickListener { selected?.let { deleteFace(it) } }
        binding.btnImport.setOnClickListener { pickImage.launch("image/*") }

        refreshList()
        setupBottomNav(R.id.nav_targets)
    }

    override fun onResume() {
        super.onResume()
        setupBottomNav(R.id.nav_targets)
    }

    private fun refreshList() {
        val all = repo.allFaces()
        shown = TargetCatalog.filter(
            binding.spDiscipline.selectedItem?.toString() ?: TargetCatalog.ALL,
            binding.spBody.selectedItem?.toString() ?: TargetCatalog.ALL,
            all
        )
        binding.list.adapter = FaceAdapter(shown)
        select(shown.firstOrNull { it.id == repo.activeFaceId() } ?: shown.firstOrNull())
    }

    private fun select(face: TargetFace?) {
        selected = face
        binding.plot.face = face
        binding.plot.shots = emptyList()
        binding.btnDelete.isEnabled = face?.custom == true
        val f = face
        if (f == null) {
            binding.tvDetailHead.text = "No target selected."
            binding.tblFace.removeAllViews()
            binding.tvDetailFoot.text = ""
            binding.tvDetailFoot.visibility = View.GONE
            return
        }

        binding.tvDetailHead.text = f.summary()

        val params = buildList {
            add("Card" to "${fmt(f.faceWidthMm)} \u00d7 ${fmt(f.faceHeightMm)} mm")
            add("Outer ring" to "${fmt(f.outerRadiusMm * 2)} mm")
            if (f.blackDiameterMm > 0) add("Aiming black" to "${fmt(f.blackDiameterMm)} mm")
            if (f.hasInnerTen) add(f.innerTenLabel to "${fmt(f.innerTenDiameterMm)} mm")
            f.ringPitchMm?.let {
                add("Ring pitch" to "${fmt(it)} mm \u2014 decimal scoring available")
            }
        }
        binding.tblFace.removeAllViews()
        for ((name, value) in params) {
            val row = layoutInflater.inflate(R.layout.item_param_row, binding.tblFace, false)
            row.findViewById<TextView>(R.id.tvParamName).text = name
            row.findViewById<TextView>(R.id.tvParamValue).text = value
            binding.tblFace.addView(row)
        }

        binding.tvDetailFoot.text = buildString {
            if (!f.verified) {
                append(
                    "\u26a0 Nominal published figures, not a governing body's own table. Verify " +
                        "before quoting a score from this face."
                )
                if (f.notes.isNotBlank()) { appendLine(); appendLine() }
            }
            if (f.notes.isNotBlank()) append(f.notes)
        }
        binding.tvDetailFoot.visibility =
            if (binding.tvDetailFoot.text.isBlank()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------

    /**
     * The picker list, with a drawing of each face beside its name.
     *
     * A plain list of names asks the shooter to know that "ISSF 25/50 m
     * Precision Pistol" is the one with the 50 mm ten ring and "ISSF 10 m Air
     * Pistol" the one with the 11.5 mm one. Both are black circles with rings
     * printed on them, both are plausible, and picking the wrong one rescales
     * every score silently. A picture settles it before the mistake is made.
     */
    private inner class FaceAdapter(private val items: List<TargetFace>) :
        android.widget.BaseAdapter() {

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(
            position: Int, convertView: android.view.View?, parent: android.view.ViewGroup
        ): android.view.View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_target, parent, false)
            val face = items[position]
            val img = view.findViewById<android.widget.ImageView>(R.id.imgFace)
            val name = view.findViewById<android.widget.TextView>(R.id.tvFaceName)
            val detail = view.findViewById<android.widget.TextView>(R.id.tvFaceDetail)

            val px = (44 * resources.displayMetrics.density).toInt()
            img.setImageBitmap(TargetThumbnail.of(face, px))
            name.text = buildString {
                append(face.name)
                if (face.custom) append("  [mine]")
                if (!face.verified) append("  ⚠")
            }
            detail.text = face.summary()
            return view
        }
    }

    private fun deleteFace(face: TargetFace) {
        if (!face.custom) { notifyUser("Built-in faces cannot be deleted — copy one instead."); return }
        AlertDialog.Builder(this)
            .setTitle("Delete ${face.name}?")
            .setMessage(
                "Sessions already scored against this face keep their scores, but the plot will " +
                    "fall back to the default face when you reopen them."
            )
            .setPositiveButton("Delete") { _, _ -> repo.deleteCustom(face.id); refreshList() }
            .setNegativeButton("Keep", null)
            .show()
    }

    /**
     * Edits a face by way of a copy. Only the fields a shooter can measure
     * with a rule are exposed: the ring geometry is entered as a ten-ring
     * diameter plus a pitch, which is how every evenly pitched face is
     * actually specified and is far harder to get wrong than ten diameters.
     */
    private fun editFace(face: TargetFace) {
        val nameF = field("Name", face.name)
        val widthF = field("Card width (mm)", fmt(face.faceWidthMm))
        val heightF = field("Card height (mm)", fmt(face.faceHeightMm))
        val tenF = field("Ten-ring diameter (mm)", fmt(face.rings.firstOrNull { it.value == 10 }?.diameterMm ?: 0.0))
        val pitchF = field("Ring pitch, radial (mm)", fmt(face.ringPitchMm ?: 0.0))
        val blackF = field("Aiming black diameter (mm)", fmt(face.blackDiameterMm))
        val innerF = field("Inner ten / X diameter (mm), 0 for none", fmt(face.innerTenDiameterMm))
        val distF = field("Nominal distance (m)", fmt(face.nominalDistanceM))

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            listOf(nameF, widthF, heightF, tenF, pitchF, blackF, innerF, distF).forEach { addView(it) }
        }

        AlertDialog.Builder(this)
            .setTitle(if (face.custom) "Edit ${face.name}" else "Copy and edit ${face.name}")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val ten = tenF.value()
                val pitch = pitchF.value()
                if (ten <= 0.0 || pitch <= 0.0) {
                    notifyUser("The ten-ring diameter and the ring pitch must both be greater than zero.")
                    return@setPositiveButton
                }
                val edited = face.copy(
                    id = if (face.custom) face.id else "",
                    name = nameF.text.toString().ifBlank { face.name + " (copy)" },
                    faceWidthMm = widthF.value().coerceAtLeast(1.0),
                    faceHeightMm = heightF.value().coerceAtLeast(1.0),
                    rings = TargetFace.evenRings(ten, pitch),
                    blackDiameterMm = blackF.value(),
                    innerTenDiameterMm = innerF.value(),
                    nominalDistanceM = distF.value(),
                    custom = true,
                    verified = false,
                    notes = "Edited copy of ${face.name}."
                )
                val saved = repo.saveCustom(edited)
                TargetThumbnail.invalidate(saved.id)
                Logger.i("TargetActivity", "Saved custom face ${saved.id}")
                refreshList()
                notifyUser("Saved as '${saved.name}'.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Creates a face from a photograph or scan the user supplies.
     *
     * WHAT THE APP CANNOT DO, AND SAYS SO. It cannot read ring values off a
     * picture. It can find circles, but it has no way to know that the third
     * one out is the eight ring, or what the card measures in millimetres.
     * So the image is stored for display and the SCORING geometry is entered
     * by the user in the same two numbers every other face uses. That is a
     * smaller ask than it sounds — those two numbers are printed on the back
     * of most target cards — and it is honest about where the authority for a
     * score comes from.
     */
    private fun promptForCustomFace(imageUri: String) {
        val nameF = field("Name", "My target")
        val widthF = field("Card width (mm)", "170")
        val heightF = field("Card height (mm)", "170")
        val tenF = field("Ten-ring diameter (mm)", "11.5")
        val pitchF = field("Ring pitch, radial (mm)", "8.0")
        val blackF = field("Aiming black diameter (mm), 0 for none", "59.5")
        val distF = field("Distance (m)", "10")

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(android.widget.TextView(this@TargetActivity).apply {
                text = "The picture is stored so the face can be shown as you know it. Scoring uses " +
                    "the dimensions below, because no program can read a ring's VALUE off a " +
                    "photograph — measure the ten ring and the spacing between two adjacent rings " +
                    "with a rule, or take them from the card's own specification."
                textSize = 13f
                setPadding(0, 0, 0, 20)
            })
            listOf(nameF, widthF, heightF, tenF, pitchF, blackF, distF).forEach { addView(it) }
        }

        AlertDialog.Builder(this)
            .setTitle("Add my own target")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val ten = tenF.value()
                val pitch = pitchF.value()
                if (ten <= 0.0 || pitch <= 0.0) {
                    notifyUser("The ten-ring diameter and the ring pitch must both be greater than zero.")
                    return@setPositiveButton
                }
                val face = TargetFace(
                    id = "",
                    name = nameF.text.toString().ifBlank { "My target" },
                    governingBody = "Custom",
                    discipline = "Any",
                    nominalDistanceM = distF.value(),
                    faceWidthMm = widthF.value().coerceAtLeast(1.0),
                    faceHeightMm = heightF.value().coerceAtLeast(1.0),
                    rings = TargetFace.evenRings(ten, pitch),
                    blackDiameterMm = blackF.value(),
                    scoringMode = ScoringMode.RING_INTEGER,
                    verified = false,
                    custom = true,
                    imageUri = imageUri,
                    notes = "Created from a user-supplied image."
                )
                val saved = repo.saveCustom(face)
                refreshList()
                notifyUser("Added '${saved.name}'.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------

    private fun field(label: String, initial: String): EditText =
        EditText(this).apply {
            hint = label
            setText(initial)
            setSingleLine()
            setHintTextColor(0xFF888888.toInt())
        }

    private fun EditText.value(): Double = text.toString().trim().toDoubleOrNull() ?: 0.0

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, R.layout.spinner_item, items).also {
            it.setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

    private fun onSelected(block: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) = block()
        override fun onNothingSelected(p: AdapterView<*>?) = Unit
    }

    private fun fmt(v: Double) =
        if (v == Math.floor(v)) "%.0f".format(v) else "%.2f".format(v).trimEnd('0').trimEnd('.')

    override fun swipeExemptViews(): List<View> =
        listOf(binding.plot, binding.list, binding.spBody, binding.spDiscipline)
}
