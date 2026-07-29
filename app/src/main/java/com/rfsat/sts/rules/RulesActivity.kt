package com.rfsat.sts.rules

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.rfsat.sts.R
import com.rfsat.sts.databinding.ActivityRulesBinding
import com.rfsat.sts.targets.TargetRepository
import com.rfsat.sts.ui.BaseActivity

/**
 * Browse and adopt courses of fire.
 *
 * Same read-only-built-ins contract as the target screen, and for the same
 * reason: a session records the rule-set id it was scored under, so editing
 * a built-in would rewrite history rather than correct the future.
 */
class RulesActivity : BaseActivity() {

    private lateinit var binding: ActivityRulesBinding
    private lateinit var repo: RuleRepository
    private var shown: List<RuleSet> = emptyList()
    private var selected: RuleSet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = RuleRepository(this)

        binding.spBody.adapter = adapter(RuleCatalog.bodies())
        binding.spDiscipline.adapter = adapter(RuleCatalog.disciplines())
        binding.spBody.onItemSelectedListener = onSelected { refreshList() }
        binding.spDiscipline.onItemSelectedListener = onSelected { refreshList() }

        binding.list.setOnItemClickListener { _, _, i, _ -> select(shown.getOrNull(i)) }
        binding.btnUse.setOnClickListener {
            selected?.let {
                repo.setActiveSet(it.id)
                TargetRepository(this).setActiveFace(it.targetFaceId)
                notifyUser("${it.name} is now the active course of fire, with its own target face.")
            }
        }
        binding.btnEdit.setOnClickListener { selected?.let { edit(it) } }
        binding.btnDelete.setOnClickListener {
            selected?.let { s ->
                if (!s.custom) { notifyUser("Built-in rule sets cannot be deleted — copy one instead."); return@let }
                repo.deleteCustom(s.id); refreshList()
            }
        }

        refreshList()
        setupBottomNav(0) // Rules is reached from Settings, not a tab
    }

    private fun refreshList() {
        shown = RuleCatalog.filter(
            binding.spBody.selectedItem?.toString() ?: RuleCatalog.ALL,
            binding.spDiscipline.selectedItem?.toString() ?: RuleCatalog.ALL,
            repo.allSets()
        )
        binding.list.adapter = ArrayAdapter(
            this, R.layout.list_item,
            shown.map { it.name + (if (it.custom) "  [mine]" else "") + (if (!it.verified) "  ⚠" else "") }
        )
        select(shown.firstOrNull { it.id == repo.activeSetId() } ?: shown.firstOrNull())
    }

    private fun select(rules: RuleSet?) {
        selected = rules
        binding.btnDelete.isEnabled = rules?.custom == true
        val r = rules
        if (r == null) {
            binding.tvDetailHead.text = "No rule set selected."
            binding.tblParams.removeAllViews()
            binding.tvDetailFoot.text = ""
            return
        }
        val face = TargetRepository(this).byId(r.targetFaceId)

        binding.tvDetailHead.text = "${r.name}\n${r.summary()}"

        val params = buildList {
            add("Target face" to (face?.name ?: r.targetFaceId))
            add("Shots" to (
                (if (r.matchShots > 0) r.matchShots.toString() else "stage-defined") +
                    (if (r.shotsPerSeries > 0) " in series of ${r.shotsPerSeries}" else "")))
            add("Sighters" to (if (r.sighters < 0) "unlimited" else r.sighters.toString()))
            add("Scoring gauge" to "${r.gaugeDiameterMm} mm")
            add("Value scale" to (if (r.decimalScoring) "decimal (tenths)" else "whole rings"))
            add("Aggregation" to r.matchScoring.label)
            if (r.maxScore() > 0) add("Maximum" to "%.0f".format(r.maxScore()))
            if (r.tieBreak.isNotEmpty()) add("Tie-break" to r.tieBreak.joinToString(", "))
            if (r.ruleReference.isNotBlank()) add("Reference" to r.ruleReference)
        }
        fillParamTable(params)

        binding.tvDetailFoot.text = buildString {
            if (!r.verified) {
                appendLine(
                    "\u26a0 These are the commonly published figures rather than a governing " +
                        "body's own table. Check them against the rulebook in force."
                )
                if (r.notes.isNotBlank()) appendLine()
            }
            append(r.notes)
        }
        binding.tvDetailFoot.visibility =
            if (binding.tvDetailFoot.text.isBlank()) View.GONE else View.VISIBLE
    }

    /**
     * Rebuilds the parameter table.
     *
     * Built in code rather than declared in the layout because the list is
     * not fixed — a maximum, a tie-break and a rule reference each appear
     * only when the set defines one, and a table of blank rows reads worse
     * than a shorter table.
     */
    private fun fillParamTable(params: List<Pair<String, String>>) {
        binding.tblParams.removeAllViews()
        for ((name, value) in params) {
            val row = layoutInflater.inflate(R.layout.item_param_row, binding.tblParams, false)
            row.findViewById<TextView>(R.id.tvParamName).text = name
            row.findViewById<TextView>(R.id.tvParamValue).text = value
            binding.tblParams.addView(row)
        }
    }

    private fun edit(rules: RuleSet) {
        val nameF = field("Name", rules.name)
        val distF = field("Distance (m)", "%.0f".format(rules.distanceM))
        val shotsF = field("Match shots (0 = stage-defined)", rules.matchShots.toString())
        val seriesF = field("Shots per series (0 = one string)", rules.shotsPerSeries.toString())
        val sightF = field("Sighters (-1 = unlimited)", rules.sighters.toString())
        val timeF = field("Time limit (seconds, 0 = none)", rules.timeLimitSec.toString())
        val gaugeF = field("Scoring gauge diameter (mm)", rules.gaugeDiameterMm.toString())

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            listOf(nameF, distF, shotsF, seriesF, sightF, timeF, gaugeF).forEach { addView(it) }
        }

        AlertDialog.Builder(this)
            .setTitle(if (rules.custom) "Edit" else "Copy and edit")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val edited = rules.copy(
                    id = if (rules.custom) rules.id else "",
                    name = nameF.text.toString().ifBlank { rules.name + " (copy)" },
                    distanceM = distF.dbl(rules.distanceM),
                    matchShots = shotsF.int(rules.matchShots),
                    shotsPerSeries = seriesF.int(rules.shotsPerSeries),
                    sighters = sightF.int(rules.sighters),
                    timeLimitSec = timeF.int(rules.timeLimitSec),
                    gaugeDiameterMm = gaugeF.dbl(rules.gaugeDiameterMm),
                    custom = true,
                    verified = false,
                    notes = "Edited copy of ${rules.name}."
                )
                val saved = repo.saveCustom(edited)
                refreshList()
                notifyUser("Saved as '${saved.name}'.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun field(label: String, initial: String) = EditText(this).apply {
        hint = label; setText(initial); setSingleLine()
    }

    private fun EditText.dbl(fallback: Double) = text.toString().trim().toDoubleOrNull() ?: fallback
    private fun EditText.int(fallback: Int) = text.toString().trim().toIntOrNull() ?: fallback

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, R.layout.spinner_item, items).also {
            it.setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

    private fun onSelected(block: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) = block()
        override fun onNothingSelected(p: AdapterView<*>?) = Unit
    }

    override fun swipeExemptViews(): List<View> = listOf(binding.list, binding.spBody, binding.spDiscipline)
}
