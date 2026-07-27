package com.rfsat.sts.log

import com.rfsat.sts.R
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.rfsat.sts.databinding.ActivityLogBinding
import com.rfsat.sts.ui.BaseActivity

/**
 * The diagnostic log. Reached from Settings rather than being a tab: it is
 * for working out why something went wrong, not part of shooting.
 */
class LogActivity : BaseActivity() {

    private lateinit var binding: ActivityLogBinding
    private var minLevel = LogLevel.INFO
    private val listener: () -> Unit = { runOnUiThread { runCatching { refresh() } } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.spLevel.adapter = ArrayAdapter(
            this, R.layout.spinner_item,
            listOf("All (info and above)", "Warnings and errors", "Errors only")
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }

        binding.spLevel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                minLevel = when (position) {
                    1 -> LogLevel.WARNING
                    2 -> LogLevel.ERROR
                    else -> LogLevel.INFO
                }
                refresh()
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

        binding.btnShare.setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "STS diagnostic log")
                putExtra(Intent.EXTRA_TEXT, Logger.asText(minLevel))
            }
            startActivity(Intent.createChooser(send, "Share the log"))
        }
        binding.btnShareAll.setOnClickListener { shareFullReport() }
        binding.btnClear.setOnClickListener { Logger.clear(); refresh() }

        setupBottomNav(0)
        refresh()
    }

    /**
     * The log plus the state needed to interpret it: build, device, and which
     * face, rules and equipment were selected. A log on its own usually is not
     * enough — the failure that prompted this screen was a target face chosen
     * in a menu, which no amount of detection logging would have revealed
     * without knowing what had been picked.
     */
    private fun shareFullReport() {
        val text = buildString {
            appendLine("STS diagnostic report")
            appendLine("=".repeat(52))
            appendLine("App      : ${com.rfsat.sts.BuildConfig.VERSION_NAME} " +
                "(build ${com.rfsat.sts.BuildConfig.VERSION_CODE}, ${com.rfsat.sts.BuildConfig.BUILD_TYPE})")
            appendLine("Device   : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            runCatching {
                val targets = com.rfsat.sts.targets.TargetRepository(this@LogActivity)
                val rules = com.rfsat.sts.rules.RuleRepository(this@LogActivity)
                val profiles = com.rfsat.sts.profiles.ProfileRepository(this@LogActivity)
                val face = targets.activeFace()
                val rule = rules.activeSet()
                appendLine()
                appendLine("Target   : ${face.name}")
                appendLine("           ${face.summary()}")
                appendLine("           black ${face.blackDiameterMm} mm, outer " +
                    "${"%.1f".format(face.outerRadiusMm * 2)} mm, " +
                    "ratio ${"%.2f".format(
                        if (face.blackDiameterMm > 0) face.outerRadiusMm * 2 / face.blackDiameterMm else 0.0
                    )}")
                appendLine("Rules    : ${rule.name} (${rule.governingBody}), " +
                    "gauge ${rule.gaugeDiameterMm} mm, ${"%.0f".format(rule.distanceM)} m")
                appendLine("Firearm  : ${profiles.getRifle().label()}")
                appendLine("Load     : ${profiles.getBullet().name}")
                appendLine("Sight    : ${profiles.getScope().label()}")
                appendLine("Session  : ${com.rfsat.sts.scoring.ScoringSession.state.shots.size} shot(s) recorded")
            }.onFailure { appendLine("(could not read the active setup: ${it.message})") }
            appendLine()
            appendLine("LOG")
            appendLine("-".repeat(52))
            append(Logger.asText(minLevel))
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "STS diagnostic report")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Share the diagnostic report"))
    }

    private fun refresh() {
        binding.tvLog.text = Logger.asText(minLevel).ifBlank { "The log is empty." }
        binding.scroll.post { binding.scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onResume() {
        super.onResume()
        Logger.addListener(listener)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        Logger.removeListener(listener)
    }

    override fun swipeExemptViews(): List<View> = listOf(binding.spLevel)
}
