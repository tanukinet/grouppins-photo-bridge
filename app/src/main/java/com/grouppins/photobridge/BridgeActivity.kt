package com.grouppins.photobridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

abstract class BridgeActivity : Activity() {

    companion object {
        private const val STATE_AWAITING_RESULT = "awaitingResult"
        private const val REQ_PERMS = 1
        private const val REQ_PICK = 2
    }

    private var awaitingResult = false
    private var resumed = false
    private var whenResumed: (() -> Unit)? = null

    final override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            awaitingResult = savedInstanceState.getBoolean(STATE_AWAITING_RESULT)
            if (!awaitingResult) finish()
            return
        }
        onFirstCreate()
    }

    protected abstract fun onFirstCreate()

    protected abstract fun onPermissionsReady()

    protected open fun onPhotosPicked(uris: List<Uri>): Unit =
        throw IllegalStateException("${javaClass.simpleName} does not open the picker")

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_AWAITING_RESULT, awaitingResult)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        whenResumed?.let {
            whenResumed = null
            it()
        }
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    protected fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    protected fun missingMediaLocation(): List<String> =
        listOf(Manifest.permission.ACCESS_MEDIA_LOCATION).filterNot(::granted)

    protected fun proceedWithPermissions(missing: List<String>) {
        if (missing.isEmpty()) {
            onPermissionsReady()
        } else {
            awaitingResult = true
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
        }
    }

    final override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        awaitingResult = false
        when {
            grantResults.isEmpty() -> fail(R.string.err_no_permission)
            granted(Manifest.permission.ACCESS_MEDIA_LOCATION) -> onPermissionsReady()
            else -> fail(PhotoBridge.onPermissionDenied(this))
        }
    }

    protected fun launchPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        awaitingResult = true
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    final override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK) return
        awaitingResult = false
        val uris = mutableListOf<Uri>()
        data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
        if (uris.isEmpty()) data?.data?.let { uris.add(it) }
        if (resultCode != RESULT_OK || uris.isEmpty()) {
            finish()
            return
        }
        onPhotosPicked(uris)
    }

    protected fun deliver(outcome: PhotoBridge.Outcome) {
        when (outcome) {
            is PhotoBridge.Outcome.Error -> fail(outcome.messageRes)
            is PhotoBridge.Outcome.Launch -> runWhenResumed {
                PhotoBridge.launch(this, outcome.intent, outcome.failureRes)?.let {
                    Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }
    }

    private fun runWhenResumed(action: () -> Unit) {
        if (isFinishing || isDestroyed) return
        if (resumed) action() else whenResumed = action
    }

    protected fun fail(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
        finish()
    }
}
