package com.guardianlab.app

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

class ShieldController(
    private val shieldView: View,
    private val blurPanel: View,
    private val warningText: TextView,
    private val rootLayout: FrameLayout
) {

    private val handler = Handler(Looper.getMainLooper())

    private val protectionTargets = mutableListOf<ProtectionTarget>()

    private var hideScheduled = false

    private val hideRunnable = Runnable {

        hideScheduled = false

        shieldView.visibility = View.GONE
        blurPanel.visibility = View.GONE
        warningText.text = ""

        protectionTargets.forEach { target ->

            target.view.visibility = View.VISIBLE

            target.protectionOverlay?.let {
                rootLayout.removeView(it)
            }

            target.protectionOverlay = null
        }
    }

    fun addProtectionTarget(target: ProtectionTarget) {
        protectionTargets.add(target)
    }

    fun removeProtectionTarget(target: ProtectionTarget) {
        protectionTargets.remove(target)
    }

    fun showProtection() {

        handler.removeCallbacks(hideRunnable)
        hideScheduled = false

        warningText.text = "⚠ ADDITIONAL VIEWER DETECTED"

        shieldView.visibility = View.GONE
        blurPanel.visibility = View.GONE

        protectionTargets.forEach { target ->

            target.view.visibility = View.INVISIBLE

            // Prevent duplicate protection overlays
            if (target.protectionOverlay != null) {
                return@forEach
            }

            val location = IntArray(2)
            target.view.getLocationInWindow(location)

            val rootLocation = IntArray(2)
            rootLayout.getLocationInWindow(rootLocation)

            val overlay = TextView(target.view.context).apply {
                text = "🔒 PROTECTED"
                textSize = 18f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.BLACK)
                gravity = Gravity.CENTER
            }

            val params = FrameLayout.LayoutParams(
                target.view.width,
                target.view.height
            ).apply {
                leftMargin = location[0] - rootLocation[0]
                topMargin = location[1] - rootLocation[1]
            }

            rootLayout.addView(overlay, params)

            target.protectionOverlay = overlay
        }
    }

    fun hideProtectionWithDelay(delayMillis: Long = 3000) {

        if (
            protectionTargets.any {
                it.view.visibility == View.INVISIBLE
            } && !hideScheduled
        ) {
            hideScheduled = true
            handler.postDelayed(hideRunnable, delayMillis)
        }
    }

    fun clearImmediately() {

        handler.removeCallbacks(hideRunnable)
        hideScheduled = false
        hideRunnable.run()
    }
}