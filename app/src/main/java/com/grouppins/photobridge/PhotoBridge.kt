package com.grouppins.photobridge

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

object PhotoBridge {

    private const val TAG = "PhotoBridge"

    private const val TARGET_URL = "https://grouppins.com/"

    private const val MAX_PHOTOS = 50

    private const val OPEN_TIMEOUT_MS = 5_000L
    private const val OPEN_TIMEOUT_MIN_MS = 1_000L
    private const val OPEN_TIMEOUT_BUDGET_MS = 30_000L

    internal const val CACHE_DIR = "shared"

    private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000

    private const val SHARE_MAX_DIM = 2048
    private const val SHARE_JPEG_QUALITY = 85

    private const val WEBAPK_SIGNER_CERT_SHA256 =
        "f9a8f75a7f0b5d2ccae8c2b570855640e709995558cd9706af74b84e68962faa"

    private data class PhotoMeta(val lat: Double?, val lng: Double?, val time: String?)

    sealed class Outcome {
        class Launch(val intent: Intent, val failureRes: Int) : Outcome()
        class Error(val messageRes: Int) : Outcome()
    }

    internal class ExtractStats {
        var timedOut = 0
            private set
        private val timedOutUris = mutableSetOf<Uri>()
        private var budgetMs = OPEN_TIMEOUT_BUDGET_MS

        fun openTimeoutMs(uri: Uri): Long? {
            if (uri in timedOutUris) return null
            return maxOf(OPEN_TIMEOUT_MIN_MS, minOf(OPEN_TIMEOUT_MS, budgetMs))
        }

        fun recordTimeout(uri: Uri, waitedMs: Long) {
            timedOut++
            timedOutUris += uri
            budgetMs -= waitedMs
        }
    }

    internal fun openPreferOriginal(activity: Activity, uri: Uri, stats: ExtractStats): InputStream? {
        val mediaUri: Uri? = try {
            if (uri.authority == MediaStore.AUTHORITY) uri else MediaStore.getMediaUri(activity, uri)
        } catch (_: Exception) {
            null
        }
        if (mediaUri != null) {
            try {
                activity.contentResolver.openInputStream(MediaStore.setRequireOriginal(mediaUri))?.let { return it }
            } catch (_: Exception) {
            }
        }
        val timeoutMs = stats.openTimeoutMs(uri) ?: return null
        val signal = CancellationSignal()
        val handler = Handler(Looper.getMainLooper())
        val cancel = Runnable { signal.cancel() }
        val startedAt = SystemClock.elapsedRealtime()
        handler.postDelayed(cancel, timeoutMs)
        return try {
            val pfd = activity.contentResolver.openFileDescriptor(uri, "r", signal)
            pfd?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
        } catch (_: OperationCanceledException) {
            stats.recordTimeout(uri, SystemClock.elapsedRealtime() - startedAt)
            null
        } catch (_: Exception) {
            null
        } finally {
            handler.removeCallbacks(cancel)
        }
    }

    private fun readExif(activity: Activity, uri: Uri, stats: ExtractStats): ExifInterface? {
        val input = openPreferOriginal(activity, uri, stats) ?: return null
        return try {
            input.use { ExifInterface(it) }
        } catch (e: Exception) {
            Log.w(TAG, "EXIF read failed: $uri", e)
            null
        }
    }

    private fun extract(activity: Activity, uri: Uri, stats: ExtractStats): PhotoMeta? {
        val exif = readExif(activity, uri, stats) ?: return null

        val latLong: DoubleArray? = exif.latLong

        val takenAt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        val isoTime = takenAt?.let {
            try {
                val parsed = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                    .apply { isLenient = false }
                    .parse(it)
                parsed?.let { d -> SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(d) }
            } catch (_: Exception) {
                null
            }
        }

        if (latLong == null && isoTime == null) return null
        return PhotoMeta(
            lat = latLong?.get(0),
            lng = latLong?.get(1),
            time = isoTime,
        )
    }

    internal fun copyOriginalsToCache(activity: Activity, uris: List<Uri>, stats: ExtractStats): List<Uri> {
        val dir = File(activity.cacheDir, CACHE_DIR).apply { mkdirs() }
        val out = mutableListOf<Uri>()
        uris.take(MAX_PHOTOS).forEachIndexed { i, uri ->
            val file = try {
                downscaleWithExif(activity, uri, dir, i, stats) ?: copyRaw(activity, uri, dir, i, stats)
            } catch (e: Exception) {
                Log.w(TAG, "could not prepare $uri for sharing", e)
                null
            } ?: return@forEachIndexed
            out.add(FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file))
        }
        return out
    }

    private fun newCacheFile(dir: File, index: Int, ext: String): File =
        File(dir, "share-${UUID.randomUUID()}-$index.$ext")

    private fun copyRaw(activity: Activity, uri: Uri, dir: File, index: Int, stats: ExtractStats): File? {
        val input = openPreferOriginal(activity, uri, stats) ?: return null
        val file = newCacheFile(dir, index, fallbackExtension(activity, uri))
        try {
            input.use { src -> file.outputStream().use { dst -> src.copyTo(dst) } }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return file
    }

    private fun fallbackExtension(activity: Activity, uri: Uri): String {
        activity.contentResolver.getType(uri)
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?.let { return it }
        val name = try {
            activity.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
            )?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Exception) {
            null
        }
        val ext = name?.substringAfterLast('.', "")?.lowercase(Locale.US)
        return ext?.takeIf { it.isNotEmpty() && it.length <= 8 && it.all { ch -> ch in 'a'..'z' || ch in '0'..'9' } }
            ?: "bin"
    }

    private fun downscaleWithExif(activity: Activity, uri: Uri, dir: File, index: Int, stats: ExtractStats): File? {
        val exif = readExif(activity, uri, stats) ?: return null
        val latLong: DoubleArray? = exif.latLong
        val takenAt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        val orientation =
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = openPreferOriginal(activity, uri, stats) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SHARE_MAX_DIM || bounds.outHeight / (sample * 2) >= SHARE_MAX_DIM) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = openPreferOriginal(activity, uri, stats)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        val scale = minOf(1f, SHARE_MAX_DIM.toFloat() / maxOf(decoded.width, decoded.height))
        val needsTransform = orientation != ExifInterface.ORIENTATION_NORMAL &&
            orientation != ExifInterface.ORIENTATION_UNDEFINED
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                    postRotate(180f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    postRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    postRotate(-90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(-90f)
            }
        }
        val keepAlpha = decoded.hasAlpha()
        val finalBitmap = if (scale < 1f || needsTransform) {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } else {
            decoded
        }
        val file = newCacheFile(dir, index, if (keepAlpha) "png" else "jpg")
        val format = if (keepAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val written = try {
            file.outputStream().use { finalBitmap.compress(format, SHARE_JPEG_QUALITY, it) }
        } finally {
            if (finalBitmap !== decoded) finalBitmap.recycle()
            decoded.recycle()
        }
        if (!written) {
            Log.w(TAG, "bitmap encode failed: $uri")
            file.delete()
            return null
        }

        if (latLong != null || takenAt != null) {
            try {
                val out = ExifInterface(file.absolutePath)
                if (latLong != null) out.setLatLong(latLong[0], latLong[1])
                if (takenAt != null) {
                    out.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, takenAt)
                    out.setAttribute(ExifInterface.TAG_DATETIME, takenAt)
                }
                out.saveAttributes()
            } catch (e: Exception) {
                Log.w(TAG, "EXIF write failed: $uri", e)
                file.delete()
                return null
            }
        }
        return file
    }

    internal fun cleanupSharedCache(activity: Activity) {
        val dir = File(activity.cacheDir, CACHE_DIR)
        Thread {
            val cutoff = System.currentTimeMillis() - CACHE_TTL_MS
            dir.listFiles()?.forEach {
                if (it.lastModified() < cutoff) it.delete()
            }
        }.start()
    }

    private fun shareAction(count: Int): String =
        if (count == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE

    internal fun buildShareIntent(activity: Activity, uris: List<Uri>, action: String): Intent {
        val send = if (action == Intent.ACTION_SEND) {
            Intent(action).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
        } else {
            Intent(action).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
        }
        val types = uris.mapTo(mutableSetOf()) { activity.contentResolver.getType(it) ?: "image/jpeg" }
        send.type = types.singleOrNull()?.takeIf { it.startsWith("image/") } ?: "image/*"
        send.clipData = ClipData.newUri(activity.contentResolver, "photos", uris[0]).apply {
            for (i in 1 until uris.size) addItem(ClipData.Item(uris[i]))
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return send
    }

    private fun isSignedByWebApkServer(activity: Activity, pkg: String): Boolean {
        return try {
            val signingInfo = activity.packageManager
                .getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo ?: return false
            val signers = (
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
                ) ?: return false
            val md = MessageDigest.getInstance("SHA-256")
            signers.any { sig ->
                md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } ==
                    WEBAPK_SIGNER_CERT_SHA256
            }
        } catch (_: Exception) {
            false
        }
    }

    internal fun findWebApkShareActivity(activity: Activity, action: String): ComponentName? {
        val host = Uri.parse(TARGET_URL).host ?: return null
        val probe = Intent(action).setType("image/jpeg")
        val candidates = try {
            activity.packageManager.queryIntentActivities(probe, 0)
        } catch (_: Exception) {
            return null
        }
        for (info in candidates) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (!pkg.startsWith("org.chromium.webapk.")) continue
            if (!isSignedByWebApkServer(activity, pkg)) continue
            try {
                val meta = activity.packageManager
                    .getApplicationInfo(pkg, PackageManager.GET_META_DATA)
                    .metaData ?: continue
                val urlKeys = listOf(
                    "org.chromium.webapk.shell_apk.startUrl",
                    "org.chromium.webapk.shell_apk.scope",
                    "org.chromium.webapk.shell_apk.webManifestUrl",
                )
                val matched = urlKeys.any { key ->
                    meta.getString(key)?.let { Uri.parse(it).host == host } == true
                }
                if (matched) return ComponentName(pkg, info.activityInfo.name)
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun runAsync(activity: Activity, onDone: (Outcome) -> Unit, work: () -> Outcome) {
        Toast.makeText(activity, R.string.msg_loading, Toast.LENGTH_SHORT).show()
        Thread {
            val outcome = try {
                work()
            } catch (t: Throwable) {
                Log.e(TAG, "photo processing failed", t)
                Outcome.Error(R.string.err_no_image)
            }
            activity.runOnUiThread { onDone(outcome) }
        }.start()
    }

    private fun notifyPartial(activity: Activity, requested: Int, delivered: Int) {
        if (delivered >= requested) return
        activity.runOnUiThread {
            Toast.makeText(
                activity,
                activity.getString(R.string.msg_partial_load, requested, delivered),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun launch(activity: Activity, intent: Intent, failureRes: Int): Int? {
        return try {
            activity.startActivity(intent)
            null
        } catch (e: Exception) {
            Log.w(TAG, "could not start $intent", e)
            failureRes
        }
    }

    fun deliverAsync(activity: Activity, uris: List<Uri>, onDone: (Outcome) -> Unit) = runAsync(activity, onDone) {
        val action = shareAction(minOf(uris.size, MAX_PHOTOS))
        val component = findWebApkShareActivity(activity, action)
        if (component != null) {
            val stats = ExtractStats()
            val shared = copyOriginalsToCache(activity, uris, stats)
            if (shared.isNotEmpty()) {
                notifyPartial(activity, uris.size, shared.size)
                val send = buildShareIntent(activity, shared, action).setComponent(component)
                return@runAsync Outcome.Launch(send, R.string.err_launch_failed)
            }
            if (stats.timedOut > 0) return@runAsync Outcome.Error(R.string.err_cloud_timeout)
        }
        extractAndOpenAll(activity, uris)
    }

    fun shareSheetAsync(activity: Activity, uris: List<Uri>, onDone: (Outcome) -> Unit) = runAsync(activity, onDone) {
        val stats = ExtractStats()
        val shared = copyOriginalsToCache(activity, uris, stats)
        if (shared.isEmpty()) {
            return@runAsync Outcome.Error(if (stats.timedOut > 0) R.string.err_cloud_timeout else R.string.err_no_image)
        }
        notifyPartial(activity, uris.size, shared.size)
        val send = buildShareIntent(activity, shared, shareAction(shared.size))
        Outcome.Launch(
            Intent.createChooser(send, activity.getString(R.string.share_chooser_title)),
            R.string.err_launch_failed,
        )
    }

    fun extractAndOpenAllAsync(activity: Activity, uris: List<Uri>, onDone: (Outcome) -> Unit) =
        runAsync(activity, onDone) { extractAndOpenAll(activity, uris) }

    fun onPermissionDenied(activity: Activity): Int {
        if (activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_MEDIA_LOCATION)) {
            return R.string.err_no_permission
        }
        val settings = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.packageName, null),
        )
        launch(activity, settings, R.string.err_no_permission_settings)
        return R.string.err_no_permission_settings
    }

    private fun openUrl(url: Uri): Outcome =
        Outcome.Launch(Intent(Intent.ACTION_VIEW, url), R.string.err_no_browser)

    private fun extractAndOpen(activity: Activity, uri: Uri, stats: ExtractStats): Outcome {
        val meta = extract(activity, uri, stats)
            ?: return Outcome.Error(if (stats.timedOut > 0) R.string.err_cloud_timeout else R.string.err_no_image)
        if (meta.lat == null || meta.lng == null) {
            return Outcome.Error(R.string.err_no_gps)
        }
        val url = Uri.parse(TARGET_URL).buildUpon()
            .appendQueryParameter("photo_lat", meta.lat.toString())
            .appendQueryParameter("photo_lng", meta.lng.toString())
            .apply { if (meta.time != null) appendQueryParameter("photo_time", meta.time) }
            .build()
        return openUrl(url)
    }

    private fun extractAndOpenAll(activity: Activity, uris: List<Uri>): Outcome {
        val stats = ExtractStats()
        if (uris.size == 1) return extractAndOpen(activity, uris[0], stats)
        var readableCount = 0
        val metas = uris.asSequence()
            .mapNotNull { extract(activity, it, stats) }
            .onEach { readableCount++ }
            .filter { it.lat != null && it.lng != null }
            .take(MAX_PHOTOS)
            .toList()
        if (metas.isEmpty()) {
            return Outcome.Error(
                when {
                    readableCount > 0 -> R.string.err_no_gps
                    stats.timedOut > 0 -> R.string.err_cloud_timeout
                    else -> R.string.err_no_image
                }
            )
        }
        notifyPartial(activity, uris.size, metas.size)
        val batch = metas.joinToString(";") { m ->
            "${m.lat ?: ""},${m.lng ?: ""},${m.time ?: ""}"
        }
        val url = Uri.parse(TARGET_URL).buildUpon()
            .appendQueryParameter("photo_batch", batch)
            .build()
        return openUrl(url)
    }
}
