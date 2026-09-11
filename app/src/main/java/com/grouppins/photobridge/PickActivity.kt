package com.grouppins.photobridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast

class PickActivity : Activity() {

    companion object {
        private const val TAG = "PickActivity"
        private const val STATE_AWAITING_RESULT = "awaitingResult"
        private const val STATE_PROCESSING_URIS = "processingUris"
        private const val STATE_PROCESSING_RETRIED = "processingRetried"
        private const val REQ_PERMS = 1
        private const val REQ_PICK = 2
    }

    private var awaitingResult = false
    private var processingUris: ArrayList<Uri>? = null
    private var processingRetried = false
    private var processingGeneration = 0
    private var resumed = false
    private var whenResumed: (() -> Unit)? = null
    private var notice: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            startPicking()
            return
        }
        awaitingResult = savedInstanceState.getBoolean(STATE_AWAITING_RESULT)
        if (awaitingResult) return
        val uris = savedUris(savedInstanceState)
        if (uris.isNullOrEmpty()) {
            finish()
            return
        }
        if (savedInstanceState.getBoolean(STATE_PROCESSING_RETRIED)) {
            Log.w(TAG, "processing of ${uris.size} photo(s) was interrupted twice; giving up")
            fail(R.string.err_interrupted)
            return
        }
        Log.i(TAG, "restarting interrupted processing of ${uris.size} photo(s)")
        processingRetried = true
        startProcessing(uris)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val abandoned = processingUris?.size ?: 0
        if (abandoned > 0 || whenResumed != null) {
            Log.w(TAG, "dropping $abandoned photo(s) abandoned before this request")
        }
        processingGeneration++
        whenResumed = null
        awaitingResult = false
        processingUris = null
        processingRetried = false
        // 前の要求で出した通知が残っていると、新しい選択の最中に OK を押されて finish() する
        dismissNotice()
        runWhenResumed { startPicking() }
    }

    private fun startPicking() {
        PhotoBridge.cleanupSharedCache(this)
        val missing = requiredPermissions().filterNot(::granted)
        if (missing.isEmpty()) {
            launchPicker()
        } else {
            awaitingResult = true
            requestPermissions((missing + alsoRequestedPermissions()).distinct().toTypedArray(), REQ_PERMS)
        }
    }

    private fun savedUris(state: Bundle): ArrayList<Uri>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            state.getParcelableArrayList(STATE_PROCESSING_URIS, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            state.getParcelableArrayList(STATE_PROCESSING_URIS)
        }

    @Suppress("DEPRECATION")
    private fun requiredPermissions(): List<String> = listOf(
        Manifest.permission.ACCESS_MEDIA_LOCATION,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        },
    )

    private fun alsoRequestedPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            listOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else {
            emptyList()
        }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_AWAITING_RESULT, awaitingResult)
        outState.putParcelableArrayList(STATE_PROCESSING_URIS, processingUris)
        outState.putBoolean(STATE_PROCESSING_RETRIED, processingRetried)
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

    private fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        awaitingResult = false
        val denied = requiredPermissions().filterNot(::granted)
        when {
            denied.isEmpty() -> launchPicker()
            grantResults.isEmpty() -> fail(R.string.err_no_permission)
            else -> fail(PhotoBridge.onPermissionDenied(this, denied))
        }
    }

    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        awaitingResult = true
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK)
        } catch (e: Exception) {
            Log.w(TAG, "could not open the document picker", e)
            awaitingResult = false
            fail(R.string.err_no_picker)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK) return
        awaitingResult = false
        val clipUris = data?.clipData?.let { clip ->
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }.orEmpty()
        val uris = clipUris.ifEmpty { listOfNotNull(data?.data) }
        if (resultCode != RESULT_OK || uris.isEmpty()) {
            finish()
            return
        }
        startProcessing(uris)
    }

    private fun startProcessing(uris: List<Uri>) {
        processingUris = ArrayList(uris)
        val generation = ++processingGeneration
        PhotoBridge.deliverAsync(this, uris) { deliver(generation, it) }
    }

    // ワーカーは取り消せないので、放棄されたバッチの結果が後から届く。世代番号が合わない結果は
    // whenResumed に入れる前に捨てる (入れてしまうと選び直しの startPicking を上書きし、
    // 放棄したバッチが新しい選択の代わりに配送される)
    private fun deliver(generation: Int, outcome: PhotoBridge.Outcome) {
        if (generation != processingGeneration) {
            Log.w(TAG, "dropping the outcome of an abandoned batch (generation $generation)")
            return
        }
        runWhenResumed {
            processingUris = null
            when (outcome) {
                is PhotoBridge.Outcome.Error -> fail(outcome.messageRes)
                is PhotoBridge.Outcome.Launch -> {
                    outcome.notice?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                    PhotoBridge.launch(this, outcome.intent, outcome.failureRes)?.let {
                        Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                    }
                    finish()
                }
            }
        }
    }

    private fun runWhenResumed(action: () -> Unit) {
        if (isFinishing || isDestroyed) return
        if (resumed) action() else whenResumed = action
    }

    // 権限の変更手順を読ませてから終わる必要があるものだけダイアログにする。トーストは
    // 数秒で消えるので手順が読み切れない。判断はここ 1 箇所に置き、呼び出し側は fail のまま
    private fun fail(messageRes: Int) {
        if (messageRes == R.string.err_partial_media) {
            showNoticeThenFinish(R.string.err_partial_media_title, messageRes)
            return
        }
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun showNoticeThenFinish(titleRes: Int, messageRes: Int) = runWhenResumed {
        dismissNotice()
        notice = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(titleRes)
            .setMessage(getString(messageRes, getString(R.string.app_name)))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.action_open_settings) { _, _ ->
                PhotoBridge.openAppSettings(this)?.let {
                    Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                }
            }
            // どのボタンでも Back でも外側タップでも終了させる。放置すると透明な画面が残る
            .setOnDismissListener { finish() }
            .show()
    }

    // 終了させるための listener なので、作り直しや破棄に伴う dismiss では呼ばせない
    private fun dismissNotice() {
        notice?.setOnDismissListener(null)
        notice?.dismiss()
        notice = null
    }

    override fun onDestroy() {
        dismissNotice()
        super.onDestroy()
    }
}
