package com.grouppins.photobridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

class ShareActivity : Activity() {

    companion object {
        private const val REQ_MEDIA_LOCATION = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            process()
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION), REQ_MEDIA_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MEDIA_LOCATION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            process()
        } else {
            fail(PhotoBridge.onPermissionDenied(this))
        }
    }

    private fun process() {
        val uris: List<Uri> = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val fromExtra: List<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: arrayListOf()
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM) ?: arrayListOf()
            }
            fromExtra.ifEmpty { clipDataUris() }
        } else {
            val single: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            listOfNotNull(single ?: clipDataUris().firstOrNull())
        }
        if (uris.isEmpty()) {
            fail(R.string.err_no_image)
            return
        }
        PhotoBridge.extractAndOpenAllAsync(this, uris) { err ->
            if (err != null) {
                fail(err)
            } else {
                finish()
            }
        }
    }

    private fun clipDataUris(): List<Uri> {
        val clip = intent.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }

    private fun fail(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
        finish()
    }
}
