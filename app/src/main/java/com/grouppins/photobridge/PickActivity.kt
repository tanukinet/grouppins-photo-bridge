package com.grouppins.photobridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

class PickActivity : Activity() {

    companion object {
        private const val REQ_MEDIA_LOCATION = 1
        private const val REQ_PICK = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        PhotoBridge.cleanupSharedCache(this)
        if (checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            launchPicker()
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION), REQ_MEDIA_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MEDIA_LOCATION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            launchPicker()
        } else {
            fail(PhotoBridge.onPermissionDenied(this))
        }
    }

    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK) return
        val uris = mutableListOf<Uri>()
        data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
        if (uris.isEmpty()) data?.data?.let { uris.add(it) }
        if (resultCode != RESULT_OK || uris.isEmpty()) {
            finish()
            return
        }
        PhotoBridge.deliverAsync(this, uris) { err ->
            if (err != null) {
                fail(err)
            } else {
                finish()
            }
        }
    }

    private fun fail(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
        finish()
    }
}
