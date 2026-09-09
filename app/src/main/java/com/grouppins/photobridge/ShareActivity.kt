package com.grouppins.photobridge

import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process

class ShareActivity : BridgeActivity() {

    override fun onFirstCreate() {
        if (grantedUris() != null) proceedWithPermissions(missingMediaLocation())
    }

    override fun onPermissionsReady() {
        val uris = grantedUris() ?: return
        PhotoBridge.extractAndOpenAllAsync(this, uris, ::deliver)
    }

    private fun grantedUris(): List<Uri>? {
        val uris = receivedUris()
        return when {
            uris.isEmpty() -> {
                fail(R.string.err_no_image)
                null
            }
            uris.any { !isGrantedToThisApp(it) } -> {
                fail(R.string.err_uri_not_granted)
                null
            }
            else -> uris
        }
    }

    private fun isGrantedToThisApp(uri: Uri): Boolean =
        uri.scheme == ContentResolver.SCHEME_CONTENT &&
            checkUriPermission(
                uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ) == PackageManager.PERMISSION_GRANTED

    private fun receivedUris(): List<Uri> {
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val fromExtra: List<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: arrayListOf()
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM) ?: arrayListOf()
            }
            return fromExtra.ifEmpty { clipDataUris() }
        }
        val single: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        return listOfNotNull(single ?: clipDataUris().firstOrNull())
    }

    private fun clipDataUris(): List<Uri> {
        val clip = intent.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }
}
