package com.rfsat.sts.profiles

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.rfsat.sts.detect.ScaleMode
import android.text.InputType
import com.rfsat.sts.cloud.CloudSettings
import com.rfsat.sts.cloud.ScoringSource
import com.rfsat.sts.detect.ScaleSettings
import com.rfsat.sts.R
import com.rfsat.sts.ui.WrappingNameAdapter
import com.rfsat.sts.backup.AppBackup
import com.rfsat.sts.databinding.ActivityProfileBinding
import com.rfsat.sts.log.LogActivity
import com.rfsat.sts.rules.RulesActivity
import com.rfsat.sts.ui.BaseActivity
import com.rfsat.sts.ui.ThemeManager
import com.rfsat.sts.ui.ThemeMode
import com.rfsat.sts.ui.UnitSystem
import com.rfsat.sts.ui.UnitsManager

/**
 * Settings: display, the active profile set, and the equipment behind it.
 *
 * ONE THING WORTH EXPLAINING. Editing any of the equipment fields clears the
 * active profile-set NAME (see ProfileRepository.saveRifle and friends). That
 * is on purpose. A set is a snapshot; once the live profiles no longer match
 * it, continuing to display its name would be a lie, and a shooter reading
 * "50 m Smallbore" on the Home screen has every right to assume the rifle
 * under it is the one that set describes. Save the edit as a new set, or
 * re-apply the old one.
 */
class ProfileActivity : BaseActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var repo: ProfileRepository

    private var suppressThemeCallback = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = ProfileRepository(this)

        runCatching { initScreen() }.onFailure {
            notifyUser("Settings failed to load: ${it.message}")
        }
        setupBottomNav(R.id.nav_settings)
    }

    private fun initScreen() {
        // ---- display ----
        binding.spTheme.adapter = adapter(ThemeMode.values().map { it.label })
        binding.spTheme.setSelection(ThemeMode.values().indexOf(ThemeManager.mode()))
        binding.spTheme.onItemSelectedListener = onSelectedIndex { i ->
            if (suppressThemeCallback) return@onSelectedIndex
            val mode = ThemeMode.values()[i]
            if (mode != ThemeManager.mode()) {
                ThemeManager.setMode(this, mode)
                recreate() // the theme is applied in onCreate, so restart the screen
            }
        }

        binding.spUnits.adapter = adapter(UnitSystem.values().map { it.label })
        binding.spUnits.setSelection(UnitSystem.values().indexOf(UnitsManager.system()))
        binding.spUnits.onItemSelectedListener = onSelectedIndex { i ->
            UnitsManager.setSystem(this, UnitSystem.values()[i])
        }

        // ---- second opinion ----
        fun refreshCloud() {
            binding.cbCloud.isChecked = CloudSettings.enabled(this)
            binding.tvCloudKey.text = "API key: ${CloudSettings.maskedKey(this)}"
        }
        refreshCloud()
        binding.spCloudModel.adapter = android.widget.ArrayAdapter(
            this, R.layout.spinner_item, CloudSettings.MODELS.map { it.second }
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        binding.spCloudModel.setSelection(
            CloudSettings.MODELS.indexOfFirst { it.first == CloudSettings.model(this) }
                .coerceAtLeast(0))
        binding.spCloudModel.onItemSelectedListener = onSelectedIndex { i ->
            CloudSettings.MODELS.getOrNull(i)?.let { CloudSettings.setModel(this, it.first) }
        }
        binding.cbCloud.setOnClickListener {
            val want = binding.cbCloud.isChecked
            if (want && CloudSettings.apiKey(this).isBlank()) {
                binding.cbCloud.isChecked = false
                notifyUser("Set an API key first — the button has nothing to call without one.")
            } else {
                CloudSettings.setEnabled(this, want)
                notifyUser(
                    if (want) "A \u201cSecond opinion\u201d button will appear on the Results screen."
                    else "The second opinion button is hidden."
                )
            }
        }
        binding.cbCloudOverride.isChecked = CloudSettings.overrideApp(this)
        binding.cbCloudOverride.setOnClickListener {
            val on = binding.cbCloudOverride.isChecked
            CloudSettings.setOverrideApp(this, on)
            notifyUser(
                if (on) "Claude's answer will be applied without asking. Its positions carry " +
                    "several millimetres, so added shots are marked hand-placed \u2014 check them."
                else "The second opinion will offer changes rather than make them."
            )
        }
        wireMoreInfo()
        binding.spEngine.adapter = android.widget.ArrayAdapter(
            this, R.layout.spinner_item, ScoringSource.values().map { it.label }
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        binding.spEngine.setSelection(ScoringSource.values().indexOf(CloudSettings.engine(this)))
        binding.spEngine.onItemSelectedListener = onSelectedIndex { i ->
            val chosen = ScoringSource.values().getOrNull(i) ?: return@onSelectedIndex
            CloudSettings.setEngine(this, chosen)
            if (chosen == ScoringSource.CLOUD && CloudSettings.apiKey(this).isBlank()) {
                notifyUser("Cloud AI needs an API key. Until one is set, imports use the embedded algorithms.")
            } else notifyUser("Imports will be scored by: ${chosen.label}")
        }
        binding.btnCloudKey.setOnClickListener {
            val input = android.widget.EditText(this).apply {
                hint = "sk-ant-…"
                // Visible, not masked: a key pasted blind is a key typed
                // wrong, and the dialog is dismissed the moment it is saved.
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Claude API key")
                .setMessage(
                    "From console.anthropic.com, not your Claude.ai password \u2014 the two are " +
                        "different and the password will not work. It is stored encrypted on this " +
                        "device and never written to the log."
                )
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val v = input.text.toString().trim()
                    if (v.isBlank()) { notifyUser("Nothing entered."); return@setPositiveButton }
                    if (CloudSettings.setApiKey(this, v)) {
                        refreshCloud()
                        notifyUser("Key stored. Tick the box above to switch the feature on.")
                    } else {
                        // Not stored anywhere else: a credential that can spend
                        // money does not go into a plain file as a fallback.
                        notifyUser(
                            "This device would not give the app encrypted storage, so the key has " +
                                "NOT been saved. It will not be kept in plain text as a fallback."
                        )
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        binding.btnCloudClear.setOnClickListener {
            CloudSettings.setApiKey(this, "")
            CloudSettings.setEnabled(this, false)
            refreshCloud()
            notifyUser("Key forgotten and the feature switched off.")
        }

        binding.cbSourceDetect.isChecked = ScaleSettings.sourceDetector()
        binding.cbSourceDetect.setOnClickListener {
            ScaleSettings.setSourceDetector(this, binding.cbSourceDetect.isChecked)
            notifyUser(
                if (binding.cbSourceDetect.isChecked)
                    "Shots will be found in the photograph itself, including inside the black."
                else "Shots will be found in the flattened copy again — shots inside the black " +
                    "aiming mark are likely to be missed."
            )
        }
        binding.cbPuncture.isChecked = ScaleSettings.punctureCheck()
        binding.cbPuncture.setOnClickListener {
            ScaleSettings.setPunctureCheck(this, binding.cbPuncture.isChecked)
            notifyUser(
                if (binding.cbPuncture.isChecked)
                    "A candidate must now get lighter outwards from its centre to count as a " +
                        "shot. Re-detect to see the difference."
                else "Shots will be accepted on size, roundness and contrast alone again."
            )
        }
        binding.cbOutside.isChecked = ScaleSettings.scoreOutsideArea()
        binding.cbOutside.setOnClickListener {
            val on = binding.cbOutside.isChecked
            ScaleSettings.setScoreOutsideArea(this, on)
            if (on && !ScaleSettings.punctureCheck()) {
                // Not a preference to be quietly overridden, but this one
                // combination is genuinely unsafe: the region outside the
                // rings is entirely print, and without the profile test it is
                // exactly where false shots come from.
                ScaleSettings.setPunctureCheck(this, true)
                binding.cbPuncture.isChecked = true
                notifyUser(
                    "Misses will be reported. The puncture test has been switched on with it: " +
                        "everything outside the rings is print, and without that test it is " +
                        "where false shots come from."
                )
            } else {
                notifyUser(
                    if (on) "Shots outside the outermost ring will be reported as misses."
                    else "Only shots inside the scoring area will be reported."
                )
            }
        }
        binding.cbFamily.isChecked = ScaleSettings.ringFamilyFit()
        binding.cbFamily.setOnClickListener {
            ScaleSettings.setRingFamilyFit(this, binding.cbFamily.isChecked)
            notifyUser(
                if (binding.cbFamily.isChecked)
                    "Scale will come from a circle fitted to each ring. Re-register any target " +
                        "already open."
                else "Scale will come from the averaged ring ladder again."
            )
        }
        binding.cbWedge.isChecked = ScaleSettings.wedgeEnabled()
        binding.cbWedge.setOnClickListener {
            ScaleSettings.setWedge(this, binding.cbWedge.isChecked)
            notifyUser(
                if (binding.cbWedge.isChecked)
                    "Ring spacing will be measured along the tilt axis only. Re-register any " +
                        "target already open."
                else "Ring spacing will be measured over the whole ring again."
            )
        }

        binding.spScaleMode.adapter = adapter(ScaleMode.values().map { it.label })
        binding.spScaleMode.setSelection(ScaleMode.values().indexOf(ScaleSettings.mode()))
        binding.spScaleMode.onItemSelectedListener = onSelectedIndex { i ->
            val chosen = ScaleMode.values()[i]
            if (chosen != ScaleSettings.mode()) {
                ScaleSettings.setMode(this, chosen)
                notifyUser(
                    "Scale source set to \u201c${chosen.label}\u201d. Re-register any target " +
                        "already open for it to take effect."
                )
            }
        }

        binding.cbShowLog.isChecked = getSharedPreferences(BaseActivity.PREFS, MODE_PRIVATE)
            .getBoolean(com.rfsat.sts.ui.MainActivity.KEY_SHOW_LOG, true)
        binding.cbShowLog.setOnCheckedChangeListener { _, on ->
            getSharedPreferences(BaseActivity.PREFS, MODE_PRIVATE).edit()
                .putBoolean(com.rfsat.sts.ui.MainActivity.KEY_SHOW_LOG, on).apply()
        }

        binding.cbFullScreen.isChecked = fullScreenEnabled()
        binding.cbFullScreen.setOnCheckedChangeListener { _, on ->
            getSharedPreferences(BaseActivity.PREFS, MODE_PRIVATE).edit().putBoolean("full_screen", on).apply()
            recreate()
        }

        // ---- sets ----
        refreshSets()
        binding.btnApplySet.setOnClickListener { applySelectedSet() }
        binding.btnSaveSet.setOnClickListener { saveCurrentAsSet() }
        binding.btnDeleteSet.setOnClickListener { deleteSelectedSet() }

        // ---- catalogues ----
        binding.btnRifleCatalog.setOnClickListener { showRifleCatalog() }
        binding.btnAmmoCatalog.setOnClickListener { showAmmoCatalog() }
        binding.btnScopeCatalog.setOnClickListener { showScopeCatalog() }

        // ---- enums ----
        binding.spFirearmType.adapter = adapter(FirearmType.values().map { it.label })
        binding.spSightType.adapter = adapter(SightType.values().map { it.label })
        binding.spClickUnit.adapter = adapter(ClickUnit.values().map { it.label })
        binding.spClickUnit.onItemSelectedListener = onSelected { updateClickFieldVisibility() }

        // ---- actions ----
        binding.btnSave.setOnClickListener { saveActiveProfiles() }
        binding.btnRules.setOnClickListener { startActivity(Intent(this, RulesActivity::class.java)) }
        binding.btnLog.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        binding.btnBackup.setOnClickListener { exportBackup() }
        binding.btnRestore.setOnClickListener { importBackup() }
        binding.btnReset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset the active profiles?")
                .setMessage("Your saved profile sets, custom targets and custom rules are NOT touched — " +
                    "only the firearm, load and sight currently in use.")
                .setPositiveButton("Reset") { _, _ ->
                    repo.resetToDefaults(); loadProfilesIntoFields(); notifyUser("Reset.")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        loadProfilesIntoFields()
        suppressThemeCallback = false
    }

    override fun onResume() {
        super.onResume()
        setupBottomNav(R.id.nav_settings)
    }

    // ------------------------------------------------------------------
    //  Fields
    // ------------------------------------------------------------------

    private fun loadProfilesIntoFields() {
        val r = repo.getRifle()
        val b = repo.getBullet()
        val s = repo.getScope()

        binding.etRifleName.setText(r.name)
        binding.spFirearmType.setSelection(FirearmType.values().indexOf(r.firearmType))
        binding.etCaliber.setText(r.caliberLabel)
        binding.etBarrel.setText(num(r.barrelLengthIn))
        binding.etTwist.setText(num(r.twistRateInPerTurn))
        binding.etSightHeight.setText(num(r.sightHeightIn))
        binding.etZero.setText(num(r.zeroDistanceM))

        binding.etBulletName.setText(b.name)
        binding.etDiameter.setText(num(b.caliberDiameterIn))
        binding.etWeight.setText(num(b.weightGrains))
        binding.etMv.setText(num(b.muzzleVelocityFps))
        binding.etBc.setText(num(b.ballisticCoefficientG1))
        binding.tvPowerFactor.text =
            "Power factor ${"%.0f".format(b.powerFactor)} — IPSC Major starts at 320 for handgun."

        binding.etScopeName.setText(s.name)
        binding.spSightType.setSelection(SightType.values().indexOf(s.sightType))
        binding.spClickUnit.setSelection(ClickUnit.values().indexOf(s.clickUnit))
        binding.etClickMm.setText(num(s.clickMmAtReference))
        binding.etClickRef.setText(num(s.clickReferenceDistanceM))
        binding.etSightRadius.setText(num(s.sightRadiusMm))
        binding.etElevTravel.setText(num(s.maxElevationTravelMoa))
        binding.etWindTravel.setText(num(s.maxWindageTravelMoa))
        binding.cbInvertElev.isChecked = s.invertElevationDirection
        binding.cbInvertWind.isChecked = s.invertWindageDirection

        updateClickFieldVisibility()
        updateClickSummary()
    }

    /** The mm/reference pair is meaningless for an angular click unit, so it
     *  is hidden rather than left on screen inviting a value that will be
     *  ignored. */
    private fun updateClickFieldVisibility() {
        val unit = ClickUnit.values().getOrNull(binding.spClickUnit.selectedItemPosition)
        val show = unit == ClickUnit.MM_AT_REFERENCE
        val vis = if (show) View.VISIBLE else View.GONE
        binding.lblClickMm.visibility = vis
        binding.etClickMm.visibility = vis
        binding.lblClickRef.visibility = vis
        binding.etClickRef.visibility = vis
        updateClickSummary()
    }

    private fun updateClickSummary() {
        val s = buildScopeFromFields()
        binding.tvClickSummary.text = if (!s.hasClicks) {
            "This sight is recorded as having no usable clicks, so corrections will be given as a " +
                "physical sight movement (if a sight radius is set) or as a distance on the target."
        } else {
            "One click = %.4f MRAD = %.4f MOA. At 10 m that moves the impact %.2f mm; at 50 m, %.1f mm; at 100 m, %.1f mm."
                .format(
                    s.clickMrad, s.clickMrad * ScopeProfile.MOA_PER_MRAD,
                    s.clickMrad * 10.0, s.clickMrad * 50.0, s.clickMrad * 100.0
                )
        }
    }

    private fun buildRifleFromFields(): RifleProfile {
        val current = repo.getRifle()
        return current.copy(
            name = binding.etRifleName.text.toString().ifBlank { current.name },
            firearmTypeName = FirearmType.values()
                .getOrElse(binding.spFirearmType.selectedItemPosition) { current.firearmType }.name,
            caliberLabel = binding.etCaliber.text.toString().ifBlank { current.caliberLabel },
            barrelLengthIn = binding.etBarrel.dbl(current.barrelLengthIn),
            twistRateInPerTurn = binding.etTwist.dbl(current.twistRateInPerTurn),
            sightHeightIn = binding.etSightHeight.dbl(current.sightHeightIn),
            zeroDistanceM = binding.etZero.dbl(current.zeroDistanceM).coerceAtLeast(0.1)
        )
    }

    private fun buildBulletFromFields(): BulletProfile {
        val current = repo.getBullet()
        return current.copy(
            name = binding.etBulletName.text.toString().ifBlank { current.name },
            caliberDiameterIn = binding.etDiameter.dbl(current.caliberDiameterIn),
            weightGrains = binding.etWeight.dbl(current.weightGrains),
            muzzleVelocityFps = binding.etMv.dbl(current.muzzleVelocityFps),
            ballisticCoefficientG1 = binding.etBc.dbl(current.ballisticCoefficientG1)
        )
    }

    private fun buildScopeFromFields(): ScopeProfile {
        val current = repo.getScope()
        return current.copy(
            name = binding.etScopeName.text.toString().ifBlank { current.name },
            sightTypeName = SightType.values()
                .getOrElse(binding.spSightType.selectedItemPosition) { current.sightType }.name,
            clickUnit = ClickUnit.values()
                .getOrElse(binding.spClickUnit.selectedItemPosition) { current.clickUnit },
            clickMmAtReference = binding.etClickMm.dbl(current.clickMmAtReference),
            // Never allow zero: clickMrad divides by it.
            clickReferenceDistanceM = binding.etClickRef.dbl(current.clickReferenceDistanceM)
                .coerceAtLeast(0.1),
            sightRadiusMm = binding.etSightRadius.dbl(current.sightRadiusMm),
            maxElevationTravelMoa = binding.etElevTravel.dbl(current.maxElevationTravelMoa),
            maxWindageTravelMoa = binding.etWindTravel.dbl(current.maxWindageTravelMoa),
            invertElevationDirection = binding.cbInvertElev.isChecked,
            invertWindageDirection = binding.cbInvertWind.isChecked
        )
    }

    private fun saveActiveProfiles() {
        repo.saveRifle(buildRifleFromFields())
        repo.saveBullet(buildBulletFromFields())
        repo.saveScope(buildScopeFromFields())
        loadProfilesIntoFields()
        refreshSets()
        notifyUser("Saved. The active profile set is now shown as edited — use 'Save as…' to keep it.")
    }

    // ------------------------------------------------------------------
    //  Sets
    // ------------------------------------------------------------------

    private fun sets() = repo.getSets()

    private fun refreshSets() {
        val names = sets().map { it.name }
        binding.spSets.adapter = adapter(if (names.isEmpty()) listOf("(no saved sets)") else names)
        repo.getActiveSetName()?.let { active ->
            names.indexOf(active).takeIf { it >= 0 }?.let { binding.spSets.setSelection(it) }
        }
    }

    private fun applySelectedSet() {
        val set = sets().getOrNull(binding.spSets.selectedItemPosition) ?: return
        repo.applySet(set)
        loadProfilesIntoFields()
        notifyUser("Applied '${set.name}'.")
    }

    private fun saveCurrentAsSet() {
        val input = EditText(this).apply {
            hint = "Name for this set"
            setText(repo.getActiveSetName() ?: buildRifleFromFields().name)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Save the current firearm, load and sight as a set")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) { notifyUser("A set needs a name."); return@setPositiveButton }
                repo.saveSet(ProfileSet(name, buildRifleFromFields(), buildBulletFromFields(), buildScopeFromFields()))
                repo.setActiveSetName(name)
                refreshSets()
                notifyUser("Saved '$name'.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSelectedSet() {
        val set = sets().getOrNull(binding.spSets.selectedItemPosition) ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete '${set.name}'?")
            .setPositiveButton("Delete") { _, _ -> repo.deleteSet(set.name); refreshSets() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------
    //  Catalogues
    // ------------------------------------------------------------------

    /**
     * The three catalogue pickers, with VTB's filters.
     *
     * A flat list of 41 firearms, 68 loads or 51 sights is unusable on a
     * phone — which is what these were before, a bare setItems() dialog. VTB
     * solved it with filter spinners above a results list and a live count,
     * and the same layouts are reused here so the two apps behave
     * identically: brand and type for a firearm; manufacturer, calibre,
     * velocity class, weight and bullet type for a load; brand, click value,
     * magnification class and family for a sight.
     */
    private fun showRifleCatalog() {
        val view = layoutInflater.inflate(R.layout.dialog_rifle_catalog, null)
        val spBrand = view.findViewById<android.widget.Spinner>(R.id.spRifBrand)
        val spType = view.findViewById<android.widget.Spinner>(R.id.spRifType)
        val tvCount = view.findViewById<android.widget.TextView>(R.id.tvRifCount)
        val list = view.findViewById<android.widget.ListView>(R.id.lvRifResults)

        spBrand.adapter = adapter(RifleCatalog.brands())
        spType.adapter = adapter(RifleCatalog.types())

        var shown: List<RifleCatalog.Entry> = emptyList()
        fun refilter() {
            shown = RifleCatalog.filter(
                spBrand.selectedItem?.toString() ?: RifleCatalog.ALL,
                spType.selectedItem?.toString() ?: RifleCatalog.ALL
            )
            list.adapter = WrappingNameAdapter(this, shown.map { it.label() })
            tvCount.text = "${shown.size} of ${RifleCatalog.all.size} firearms"
        }
        val onFilter = onSelected { refilter() }
        spBrand.onItemSelectedListener = onFilter
        spType.onItemSelectedListener = onFilter
        refilter()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Firearm catalogue")
            .setView(view)
            .setNegativeButton("Cancel", null)
            .create()
        list.setOnItemClickListener { _, _, i, _ ->
            shown.getOrNull(i)?.let { e ->
                repo.saveRifle(e.toRifleProfile())
                loadProfilesIntoFields()
                notifyUser("Loaded ${e.brand} ${e.model}. Adjust the fields for your own rifle.")
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showAmmoCatalog() {
        val view = layoutInflater.inflate(R.layout.dialog_ammo_catalog, null)
        val spMfr = view.findViewById<android.widget.Spinner>(R.id.spCatMfr)
        val spCal = view.findViewById<android.widget.Spinner>(R.id.spCatCal)
        val spVel = view.findViewById<android.widget.Spinner>(R.id.spCatVel)
        val spWeight = view.findViewById<android.widget.Spinner>(R.id.spCatWeight)
        val spType = view.findViewById<android.widget.Spinner>(R.id.spCatType)
        val tvCount = view.findViewById<android.widget.TextView>(R.id.tvCatCount)
        val list = view.findViewById<android.widget.ListView>(R.id.lvCatResults)

        spMfr.adapter = adapter(AmmoCatalog.manufacturers())
        spCal.adapter = adapter(AmmoCatalog.calibers())
        spVel.adapter = adapter(AmmoCatalog.velocityClasses())
        spWeight.adapter = adapter(AmmoCatalog.weights())
        spType.adapter = adapter(AmmoCatalog.types())

        var shown: List<AmmoCatalog.Entry> = emptyList()
        fun refilter() {
            shown = AmmoCatalog.filter(
                spMfr.selectedItem?.toString() ?: AmmoCatalog.ALL,
                spCal.selectedItem?.toString() ?: AmmoCatalog.ALL,
                spVel.selectedItem?.toString() ?: AmmoCatalog.ALL,
                spWeight.selectedItem?.toString() ?: AmmoCatalog.ALL,
                spType.selectedItem?.toString() ?: AmmoCatalog.ALL
            )
            list.adapter = WrappingNameAdapter(this, shown.map { it.label() })
            tvCount.text = "${shown.size} of ${AmmoCatalog.all.size} loads"
        }
        val onFilter = onSelected { refilter() }
        listOf(spMfr, spCal, spVel, spWeight, spType).forEach { it.onItemSelectedListener = onFilter }
        refilter()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Ammunition catalogue")
            .setView(view)
            .setNegativeButton("Cancel", null)
            .create()
        list.setOnItemClickListener { _, _, i, _ ->
            shown.getOrNull(i)?.let { e ->
                repo.saveBullet(e.toBulletProfile())
                loadProfilesIntoFields()
                notifyUser("Loaded ${e.manufacturer} ${e.product}. Published figures — refine them " +
                    "against your own chronograph.")
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showScopeCatalog() {
        val view = layoutInflater.inflate(R.layout.dialog_scope_catalog, null)
        val spBrand = view.findViewById<android.widget.Spinner>(R.id.spScBrand)
        val spClick = view.findViewById<android.widget.Spinner>(R.id.spScClick)
        val spMag = view.findViewById<android.widget.Spinner>(R.id.spScMag)
        val spFamily = view.findViewById<android.widget.Spinner>(R.id.spScFamily)
        val tvCount = view.findViewById<android.widget.TextView>(R.id.tvScCount)
        val list = view.findViewById<android.widget.ListView>(R.id.lvScResults)

        spBrand.adapter = adapter(ScopeCatalog.brands())
        spClick.adapter = adapter(ScopeCatalog.clickUnits())
        spMag.adapter = adapter(ScopeCatalog.magClasses())
        spFamily.adapter = adapter(ScopeCatalog.families())

        var shown: List<ScopeCatalog.Entry> = emptyList()
        fun refilter() {
            shown = ScopeCatalog.filter(
                spBrand.selectedItem?.toString() ?: ScopeCatalog.ALL,
                spClick.selectedItem?.toString() ?: ScopeCatalog.ALL,
                spMag.selectedItem?.toString() ?: ScopeCatalog.ALL,
                spFamily.selectedItem?.toString() ?: ScopeCatalog.ALL
            )
            list.adapter = WrappingNameAdapter(this, shown.map { it.label() })
            tvCount.text = "${shown.size} of ${ScopeCatalog.all.size} sights"
        }
        val onFilter = onSelected { refilter() }
        listOf(spBrand, spClick, spMag, spFamily).forEach { it.onItemSelectedListener = onFilter }
        refilter()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Sight catalogue")
            .setView(view)
            .setNegativeButton("Cancel", null)
            .create()
        list.setOnItemClickListener { _, _, i, _ ->
            shown.getOrNull(i)?.let { e ->
                repo.saveScope(e.toScopeProfile())
                loadProfilesIntoFields()
                notifyUser("Loaded ${e.brand} ${e.model}.")
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    // ------------------------------------------------------------------
    //  Backup
    // ------------------------------------------------------------------

    private fun exportBackup() {
        val json = AppBackup.export(this)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "STS backup")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(send, "Export the STS backup"))
    }

    private fun importBackup() {
        val input = EditText(this).apply {
            hint = "Paste the backup JSON here"
            setLines(6)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        AlertDialog.Builder(this)
            .setTitle("Restore from a backup")
            .setMessage("This replaces your profile sets, custom targets and custom rules with the " +
                "contents of the backup. Sessions already recorded are not affected.")
            .setView(input)
            .setPositiveButton("Restore") { _, _ ->
                val result = AppBackup.import(this, input.text.toString())
                notifyUser(result)
                loadProfilesIntoFields()
                refreshSets()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, R.layout.spinner_item, items).also {
            it.setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

    /** Filter spinners only ever need "something changed". */

    /**
     * Puts the long explanation behind a link instead of under the switch.
     *
     * The settings screen had grown a paragraph per option — mechanism,
     * measured evidence, the reasoning behind a default. All true, none of it
     * what someone deciding whether to tick a box needs to read. The line
     * under each option now says what to expect; the paragraph is one tap
     * away for anyone who wants to know why.
     */
    private fun moreInfo(view: android.widget.TextView, title: String, body: String) {
        view.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton("Close", null)
                .show()
        }
    }

    private fun wireMoreInfo() {
        moreInfo(binding.infoScale, "Scale",
            "Millimetres per pixel can be measured from the spacing of the printed rings, or " +
            "from the radius of the black aiming mark against the ratio the catalogue gives for " +
            "this face. The two are independent. Cross-checking averages them when they agree " +
            "and reports it when they do not, which usually means the wrong face is selected.")
        moreInfo(binding.infoWedge, "Tilt-axis ring spacing",
            "Along the axis a card tilts about, the distance to the camera does not change, so " +
            "the scale there is exact. Measuring only in that direction should remove the drift " +
            "seen on angled cards. On the images tested it helped on some and hurt on others, " +
            "so it needs real range photographs before it can be trusted.")
        moreInfo(binding.infoSource, "Find shots in the photograph",
            "Looks for holes in the picture as it arrived rather than in the flattened copy, and " +
            "reads plain brightness inside the black aiming mark — where the colour comparison " +
            "used elsewhere has nothing left to measure, because black ink and the grey fibres " +
            "of a hole through it are equally unlike the paper. On the test card this is the " +
            "difference between a score of 10 and the correct 19.")
        moreInfo(binding.infoPuncture, "Puncture test",
            "A hole takes the most material out of its centre, so it gets steadily lighter " +
            "outwards until it reaches the paper. A printed roundel or a letter does not. On " +
            "the test card this removed a piece of the maker's footer that was being reported " +
            "as a shot, and kept every real hole.")
        moreInfo(binding.infoOutside, "Shots that missed",
            "Shots outside the outermost ring score nothing, so this cannot change a total. It " +
            "is for seeing where a flyer went: a plot that quietly omits the worst shots of a " +
            "string misrepresents the group. Everything out there is print, so this needs the " +
            "puncture test and applies it more strictly — and on the test card it still marks " +
            "some of the footer and the logos.")
        moreInfo(binding.infoFamily, "Scale from fitted ring circles",
            "Fits a circle to every visible ring instead of reading their radii off one averaged " +
            "radial profile. On a flat scan both recover the true ring pitch to a thousandth of " +
            "a millimetre. It is here because it reports how far each ring individually sits " +
            "from where the catalogue puts it, which says whether a card is flat.")
        moreInfo(binding.infoCloud, "Second opinion",
            "Claude looks at the photograph and reports how many holes it can see and roughly " +
            "where. It does not score: a position read off a picture carries several " +
            "millimetres, while the app measures a hole it has found to under two. Anything it " +
            "sees that the app did not is offered as a suggestion, and the app measures the " +
            "position before any shot is placed.")
        moreInfo(binding.infoOverride, "Claude overrides the app",
            "Marks Claude does not see are removed and shots it sees that the app missed are " +
            "added, without asking. It uses Claude's positions, which carry several millimetres " +
            "against the 0.2 to 1.7 mm the app measures for a hole it can see — on a 10 m face " +
            "the rings are 8 mm apart, so a shot placed this way can be a ring out. Added shots " +
            "are marked hand-placed for that reason.")
        moreInfo(binding.infoEngine, "What scores a card",
            "Embedded runs the app's own detection, with Claude available afterwards as a " +
            "second opinion. Cloud AI does not run it at all — Claude finds and scores the " +
            "shots, and the app only works out where the card is, because without that nothing " +
            "can be drawn in the right place. The picture sent is the flattened card, so the " +
            "marks land exactly where you see them. Falls back to Embedded if no key is set.")
        moreInfo(binding.infoKey, "API key",
            "The key comes from the Anthropic Console and bills your own account. It is not " +
            "your Claude.ai password, which will not work here. It is kept encrypted on this " +
            "device and is never written to the log. A card costs a fraction of a penny to " +
            "check, and needs a connection, which most ranges do not have.")
    }

    private fun onSelected(block: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) = block()
        override fun onNothingSelected(p: AdapterView<*>?) = Unit
    }

    private fun onSelectedIndex(block: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) = block(position)
        override fun onNothingSelected(p: AdapterView<*>?) = Unit
    }

    private fun EditText.dbl(fallback: Double): Double =
        text.toString().trim().replace(',', '.').toDoubleOrNull() ?: fallback

    private fun num(v: Double): String =
        if (v == Math.floor(v) && Math.abs(v) < 1e9) "%.0f".format(v)
        else "%.4f".format(v).trimEnd('0').trimEnd('.')

    override fun swipeExemptViews(): List<View> = listOf(
        binding.spTheme, binding.spUnits, binding.spSets,
        binding.spFirearmType, binding.spSightType, binding.spClickUnit
    )
}
