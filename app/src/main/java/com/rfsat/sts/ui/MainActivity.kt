package com.rfsat.sts.ui

import android.content.Intent
import android.os.Bundle
import com.rfsat.sts.BuildConfig
import com.rfsat.sts.R
import com.rfsat.sts.StsApp
import com.rfsat.sts.databinding.ActivityMainBinding
import com.rfsat.sts.detect.SessionActivity
import com.rfsat.sts.profiles.ProfileRepository
import com.rfsat.sts.results.ResultsActivity
import com.rfsat.sts.rules.RuleRepository
import com.rfsat.sts.scoring.ScoringSession
import com.rfsat.sts.targets.TargetRepository

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Report FIRST, then initialise. If this were last, a crash anywhere
        // in the screen's own startup would record a stack that no subsequent
        // launch could ever display. Everything after it is guarded, so if
        // init throws, Home still comes up — degraded — and shows THAT stack.
        maybeShowCrashReport()
        runCatching { initHome() }.onFailure {
            showStack("STS startup error", "thread main\n" + android.util.Log.getStackTraceString(it))
        }
    }

    private fun initHome() {
        ScoringSession.attach(this)

        binding.tvVersion.text =
            "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})"

        binding.tvClaudeCredit.text = androidx.core.text.HtmlCompat.fromHtml(
            "with support from <a href=\"https://claude.ai\">Claude AI</a>",
            androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        binding.tvClaudeCredit.movementMethod = android.text.method.LinkMovementMethod.getInstance()

        binding.btnStart.setOnClickListener {
            startActivity(Intent(this, SessionActivity::class.java).putExtra(SessionActivity.EXTRA_NEW, true))
        }
        binding.btnImport.setOnClickListener {
            startActivity(Intent(this, com.rfsat.sts.detect.ImportActivity::class.java))
        }
        binding.btnResume.setOnClickListener {
            startActivity(Intent(this, ResultsActivity::class.java))
        }

        refreshSetup()
        setupBottomNav(R.id.nav_home)
    }

    override fun onResume() {
        super.onResume()
        runCatching { refreshSetup() }
        setupBottomNav(R.id.nav_home)
    }

    private fun refreshSetup() {
        val profiles = ProfileRepository(this)
        val targets = TargetRepository(this)
        val rules = RuleRepository(this)

        val face = targets.activeFace()
        val rule = rules.activeSet()
        val setName = profiles.getActiveSetName() ?: "custom (edited)"

        binding.tvSetup.text = buildString {
            appendLine("Profile set : $setName")
            appendLine("Firearm     : ${profiles.getRifle().label()}")
            appendLine("Load        : ${profiles.getBullet().name}")
            appendLine("Sight       : ${profiles.getScope().label()}")
            appendLine("Target      : ${face.name}")
            appendLine("Rules       : ${rule.name}")
            append("Distance    : ${UnitsManager.formatDistance(rule.distanceM)}")
            if (!face.verified || !rule.verified) {
                append("\n\nThis combination uses figures that are the commonly published ones " +
                    "rather than a governing body's own table. Check them before quoting a score.")
            }
        }

        val resumable = ScoringSession.hasShots
        binding.btnResume.visibility = if (resumable) android.view.View.VISIBLE else android.view.View.GONE
        if (resumable) {
            binding.btnResume.text = "Resume — ${ScoringSession.state.shots.size} shot(s) recorded"
        }
    }

    /** If the previous launch died, show the recorded stack in a shareable
     *  dialog. Dismissing clears the record, which also re-enables the stored
     *  session restore on the next launch. */
    private fun maybeShowCrashReport() {
        val prefs = getSharedPreferences(StsApp.CRASH_PREFS, MODE_PRIVATE)
        val stack = prefs.getString(StsApp.KEY_STACK, null) ?: return
        prefs.edit().clear().apply()
        showStack("STS crashed on the previous launch", stack)
    }

    private fun showStack(title: String, stack: String) {
        runCatching {
            val tv = android.widget.TextView(this).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 10f
                setPadding(32, 16, 32, 0)
                text = stack
                setTextIsSelectable(true)
            }
            val scroll = android.widget.ScrollView(this).apply { addView(tv) }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("Share") { _, _ ->
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "STS crash report")
                        putExtra(Intent.EXTRA_TEXT, stack)
                    }
                    startActivity(Intent.createChooser(send, "Share crash report"))
                }
                .setNegativeButton("Dismiss", null)
                .setCancelable(false)
                .show()
        }
    }
}
