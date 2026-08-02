package com.guardianlab.app

import android.view.View

data class ProtectionTarget(
    val view: View,
    var protectionOverlay: View? = null
)