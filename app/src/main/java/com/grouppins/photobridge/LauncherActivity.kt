package com.grouppins.photobridge

import android.Manifest
import android.net.Uri
import android.os.Build

class LauncherActivity : BridgeActivity() {

    override fun onFirstCreate() {
        PhotoBridge.cleanupSharedCache(this)
        proceedWithPermissions()
    }

    @Suppress("DEPRECATION")
    override fun requiredPermissions(): List<String> = listOf(
        Manifest.permission.ACCESS_MEDIA_LOCATION,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        },
    )

    override fun alsoRequestedPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            listOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else {
            emptyList()
        }

    override fun onPermissionsReady() = launchPicker()

    override fun onPhotosPicked(uris: List<Uri>) = PhotoBridge.shareSheetAsync(this, uris, ::deliver)
}
