package com.grouppins.photobridge

import android.Manifest
import android.net.Uri
import android.os.Build

class LauncherActivity : BridgeActivity() {

    override fun onFirstCreate() {
        PhotoBridge.cleanupSharedCache(this)
        proceedWithPermissions(missingPermissions())
    }

    private fun missingPermissions(): List<String> = buildList {
        addAll(missingMediaLocation())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (!granted(Manifest.permission.READ_MEDIA_IMAGES) &&
                !granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            ) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!granted(Manifest.permission.READ_MEDIA_IMAGES)) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            @Suppress("DEPRECATION")
            if (!granted(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    override fun onPermissionsReady() = launchPicker()

    override fun onPhotosPicked(uris: List<Uri>) = PhotoBridge.shareSheetAsync(this, uris, ::deliver)
}
