package com.guardianlab.app

import android.view.View

class Guardian(
    private val shieldController: ShieldController
) {

    fun protect(view: View) {
        shieldController.addProtectionTarget(
            ProtectionTarget(view)
        )
    }

    fun unprotect(view: View) {
        shieldController.removeProtectionTarget(
            ProtectionTarget(view)
        )
    }
}