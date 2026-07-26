package com.rfsat.sts.log

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
            this, android.R.layout.simple_spinner_item,
            listOf("All (info and above)", "Warnings and errors", "Errors only")
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

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
        binding.btnClear.setOnClickListener { Logger.clear(); refresh() }

        setupBottomNav(0)
        refresh()
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
