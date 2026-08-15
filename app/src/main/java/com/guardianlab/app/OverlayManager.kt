package com.guardianlab.app

import android.view.View
import android.widget.TextView

class OverlayManager(
    private val faceCountText: TextView,
    private val warningText: TextView
) {

    init {
        // Keep overlay text above other views
        warningText.bringToFront()
        faceCountText.bringToFront()
    }

    fun updateFaceCount(count: Int) {
        faceCountText.visibility = View.VISIBLE
        faceCountText.text = "Faces: $count"
        faceCountText.bringToFront()
    }

    fun showWarning(message: String) {
        warningText.visibility = View.VISIBLE
        warningText.text = message
        warningText.bringToFront()
    }

    fun clearWarning() {
        warningText.text = ""
        warningText.visibility = View.GONE
    }
}