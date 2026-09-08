package com.grouppins.photobridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

class LauncherActivity : Activity() {

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_PICK = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        PhotoBridge.cleanupSharedCache(this)
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            launchPicker()
        } else {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
        }
    }

    private fun missingPermissions(): List<String> = buildList {
        if (!granted(Manifest.permission.ACCESS_MEDIA_LOCATION)) {
            add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
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

    private fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val mediaLocIdx = permissions.indexOf(Manifest.permission.ACCESS_MEDIA_LOCATION)
        if (mediaLocIdx >= 0 && grantResults.getOrNull(mediaLocIdx) != PackageManager.PERMISSION_GRANTED) {
            fail(PhotoBridge.onPermissionDenied(this))
            return
        }
        launchPicker()
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
        Toast.makeText(this, R.string.msg_loading, Toast.LENGTH_SHORT).show()
        Thread {
            val stats = PhotoBridge.ExtractStats()
            val shared = PhotoBridge.copyOriginalsToCache(this, uris, stats)
            runOnUiThread {
                if (shared.isEmpty()) {
                    fail(if (stats.timedOut > 0) R.string.err_cloud_timeout else R.string.err_no_image)
                } else {
                    openShareSheet(shared)
                }
            }
        }.start()
    }

    private fun openShareSheet(uris: List<Uri>) {
        try {
            startActivity(
                Intent.createChooser(PhotoBridge.buildShareIntent(this, uris), getString(R.string.share_chooser_title))
            )
        } catch (_: Exception) {
            Toast.makeText(this, R.string.err_no_browser, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun fail(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
        finish()
    }
}
