package com.guardianlab.app

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView

class ShieldController(
    private val shieldView: View,
    private val blurPanel: View,
    private val warningText: TextView
) {

    private val handler = Handler(Looper.getMainLooper())

    private val hideRunnable = Runnable {
        shieldView.visibility = View.GONE
        blurPanel.visibility = View.GONE
        warningText.text = ""
    }

    fun showProtection() {
        handler.removeCallbacks(hideRunnable)

        warningText.text = "⚠ ADDITIONAL VIEWER DETECTED"

        shieldView.visibility = View.VISIBLE
        blurPanel.visibility = View.VISIBLE
    }

    fun hideProtectionWithDelay(delayMillis: Long = 3000) {
        handler.removeCallbacks(hideRunnable)

        if (shieldView.visibility == View.VISIBLE) {
            handler.postDelayed(hideRunnable, delayMillis)
        } else {
            warningText.text = ""
        }
    }

    fun clearImmediately() {
        handler.removeCallbacks(hideRunnable)
        hideRunnable.run()
    }
}