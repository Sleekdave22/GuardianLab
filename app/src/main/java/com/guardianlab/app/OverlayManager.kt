package com.guardianlab.app

import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView

class OverlayManager(
    private val warningText: TextView
) {
    private var isAnimatingHide = false

    init {
        // Keep overlay text above other views
        warningText.bringToFront()
    }

    fun showWarning(message: String) {
        warningText.text = message
        warningText.bringToFront()

        if (warningText.visibility != View.VISIBLE) {
            isAnimatingHide = false
            warningText.visibility = View.VISIBLE
            warningText.translationY = -400f
            warningText.animate()
                .translationY(0f)
                .setDuration(400)
                .setInterpolator(DecelerateInterpolator())
                .setListener(null)
                .start()
        } else if (isAnimatingHide || warningText.translationY != 0f) {
            isAnimatingHide = false
            warningText.animate()
                .translationY(0f)
                .setDuration(400)
                .setInterpolator(DecelerateInterpolator())
                .setListener(null)
                .start()
        }
    }

    fun clearWarning() {
        if (warningText.visibility == View.GONE || isAnimatingHide) {
            return
        }
        isAnimatingHide = true
        warningText.animate()
            .translationY(-400f)
            .setDuration(400)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (isAnimatingHide) {
                        warningText.text = ""
                        warningText.visibility = View.GONE
                        isAnimatingHide = false
                    }
                }
            })
            .start()
    }
}
