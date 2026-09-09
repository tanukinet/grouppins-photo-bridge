package com.grouppins.photobridge

import android.net.Uri

class PickActivity : BridgeActivity() {

    override fun onFirstCreate() {
        PhotoBridge.cleanupSharedCache(this)
        proceedWithPermissions()
    }

    override fun onPermissionsReady() = launchPicker()

    override fun process(uris: List<Uri>) = PhotoBridge.deliverAsync(this, uris, ::deliver)
}
