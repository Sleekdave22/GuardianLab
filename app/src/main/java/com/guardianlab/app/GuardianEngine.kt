package com.guardianlab.app

class GuardianEngine {

    fun shouldActivateProtection(faceCount: Int): Boolean {
        return faceCount >= 2
    }
}