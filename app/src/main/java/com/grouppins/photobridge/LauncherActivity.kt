package com.grouppins.photobridge

import android.net.Uri

class LauncherActivity : BridgeActivity() {

    override fun onFirstCreate() {
        PhotoBridge.cleanupSharedCache(this)
        proceedWithPermissions()
    }

    override fun onPermissionsReady() = launchPicker()

    override fun process(uris: List<Uri>) = PhotoBridge.shareSheetAsync(this, uris, ::deliver)
}
