package com.grouppins.photobridge

import android.net.Uri

class PickActivity : BridgeActivity() {

    override fun onFirstCreate() {
        PhotoBridge.cleanupSharedCache(this)
        proceedWithPermissions(missingMediaLocation())
    }

    override fun onPermissionsReady() = launchPicker()

    override fun onPhotosPicked(uris: List<Uri>) = PhotoBridge.deliverAsync(this, uris, ::deliver)
}
