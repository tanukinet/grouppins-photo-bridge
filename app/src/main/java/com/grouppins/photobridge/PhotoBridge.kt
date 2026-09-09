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
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

object PhotoBridge {

    private const val TAG = "PhotoBridge"

    private const val TARGET_URL = "https://grouppins.com/"

    internal const val MAX_PHOTOS = 50

    private const val OPEN_TIMEOUT_MS = 5_000L
    private const val OPEN_TIMEOUT_MIN_MS = 1_000L
    private const val OPEN_TIMEOUT_BUDGET_MS = 30_000L

    internal const val CACHE_DIR = "shared"

    private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000

    private const val SHARE_MAX_DIM = 4096
    private const val SHARE_SAMPLE_FLOOR = 4000
    private const val SHARE_JPEG_QUALITY = 85

    private const val WEBAPK_SIGNER_CERT_SHA256 =
        "f9a8f75a7f0b5d2ccae8c2b570855640e709995558cd9706af74b84e68962faa"

    private data class PhotoMeta(val lat: Double?, val lng: Double?, val time: String?)

    private val timeoutExecutor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "PhotoBridge-timeout").apply { isDaemon = true } }
    }

    sealed class Outcome {
        class Launch(val intent: Intent, val failureRes: Int, val notice: String? = null) : Outcome()
        class Error(val messageRes: Int) : Outcome()
    }

    internal class ExtractStats {
        var timedOut = 0
            private set
        var originalUnavailable = 0
            private set
        private val failedUris = mutableSetOf<Uri>()
        private var budgetMs = OPEN_TIMEOUT_BUDGET_MS

        fun openTimeoutMs(uri: Uri): Long? {
            if (uri in failedUris) return null
            return maxOf(OPEN_TIMEOUT_MIN_MS, minOf(OPEN_TIMEOUT_MS, budgetMs))
        }

        fun recordTimeout(uri: Uri, waitedMs: Long) {
            timedOut++
            failedUris += uri
            budgetMs -= waitedMs
        }

        fun recordOriginalUnavailable(uri: Uri) {
            originalUnavailable++
            failedUris += uri
        }

        fun recordOpenFailure(uri: Uri) {
            failedUris += uri
        }
    }

    internal fun openPreferOriginal(activity: Activity, uri: Uri, stats: ExtractStats): InputStream? =
        openPreferOriginalFd(activity, uri, stats)?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }

    internal fun openPreferOriginalFd(activity: Activity, uri: Uri, stats: ExtractStats): ParcelFileDescriptor? {
        val timeoutMs = stats.openTimeoutMs(uri) ?: return null
        val mediaUri: Uri? = try {
            if (uri.authority == MediaStore.AUTHORITY) uri else MediaStore.getMediaUri(activity, uri)
        } catch (_: Exception) {
            null
        }
        val target = if (mediaUri != null) MediaStore.setRequireOriginal(mediaUri) else uri
        val signal = CancellationSignal()
        val startedAt = SystemClock.elapsedRealtime()
        val cancel = timeoutExecutor.schedule({ signal.cancel() }, timeoutMs, TimeUnit.MILLISECONDS)
        return try {
            activity.contentResolver.openFileDescriptor(target, "r", signal)?.let { return it }
            Log.w(TAG, "no descriptor for $target")
            if (mediaUri != null) stats.recordOriginalUnavailable(uri) else stats.recordOpenFailure(uri)
            null
        } catch (_: OperationCanceledException) {
            stats.recordTimeout(uri, SystemClock.elapsedRealtime() - startedAt)
            null
        } catch (e: Exception) {
            if (mediaUri != null) {
                Log.w(TAG, "original (with GPS) unavailable for $uri; not falling back to a stripped copy", e)
                stats.recordOriginalUnavailable(uri)
            } else {
                Log.w(TAG, "could not open $uri", e)
                stats.recordOpenFailure(uri)
            }
            null
        } finally {
            cancel.cancel(false)
        }
    }

    private fun readExif(activity: Activity, uri: Uri, stats: ExtractStats): ExifInterface? =
        openPreferOriginal(activity, uri, stats)?.let { readExif(it, uri) }

    private fun readExif(input: InputStream, uri: Uri): ExifInterface? = try {
        input.use { ExifInterface(it) }
    } catch (e: Exception) {
        Log.w(TAG, "EXIF read failed: $uri", e)
        null
    }

    private fun rewind(pfd: ParcelFileDescriptor): Boolean = try {
        Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_SET)
        true
    } catch (_: ErrnoException) {
        false
    }

    internal class PhotoSource(
        private val activity: Activity,
        val uri: Uri,
        private val stats: ExtractStats,
    ) : Closeable {
        private var pfd: ParcelFileDescriptor? = null

        fun open(): InputStream? {
            pfd?.let { held ->
                if (rewind(held)) return FileInputStream(held.fileDescriptor)
                held.close()
                pfd = null
            }
            val fresh = openPreferOriginalFd(activity, uri, stats) ?: return null
            if (!rewind(fresh)) return ParcelFileDescriptor.AutoCloseInputStream(fresh)
            pfd = fresh
            return FileInputStream(fresh.fileDescriptor)
        }

        override fun close() {
            pfd?.close()
            pfd = null
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
                PhotoSource(activity, uri, stats).use { src ->
                    downscaleWithExif(activity, src, dir, i) ?: copyRaw(activity, src, dir, i)
                }
            } catch (e: Exception) {
                logSkipped(uri, e)
                null
            } catch (e: OutOfMemoryError) {
                logSkipped(uri, e)
                null
            } ?: return@forEachIndexed
            out.add(FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file))
        }
        return out
    }

    private fun logSkipped(uri: Uri, cause: Throwable) {
        Log.w(TAG, "could not prepare $uri for sharing", cause)
    }

    private fun newCacheFile(dir: File, index: Int, ext: String): File =
        File(dir, "share-${UUID.randomUUID()}-$index.$ext")

    private fun copyRaw(activity: Activity, src: PhotoSource, dir: File, index: Int): File? {
        val input = src.open() ?: return null
        val file = newCacheFile(dir, index, fallbackExtension(activity, src.uri))
        try {
            input.use { src -> file.outputStream().use { dst -> src.copyTo(dst) } }
        } catch (t: Throwable) {
            file.delete()
            throw t
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

    private fun downscaleWithExif(activity: Activity, src: PhotoSource, dir: File, index: Int): File? {
        val exif = src.open()?.let { readExif(it, src.uri) } ?: throw IOException("EXIF unreadable")
        val latLong: DoubleArray? = exif.latLong
        val dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        val dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME)
        val orientation =
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = src.open() ?: throw IOException("could not reopen for bounds")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / SHARE_SAMPLE_FLOOR)

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decodeStream = src.open() ?: throw IOException("could not reopen for decode")
        val decoded = decodeStream.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("bitmap decode failed (${bounds.outWidth}x${bounds.outHeight}, sample $sample)")
        val keepAlpha = decoded.hasAlpha()
        val scale = minOf(1f, SHARE_MAX_DIM.toFloat() / maxOf(decoded.width, decoded.height))
        val needsTransform = orientation in ExifInterface.ORIENTATION_FLIP_HORIZONTAL..ExifInterface.ORIENTATION_ROTATE_270
        val bakeTransform = needsTransform && (scale < 1f || keepAlpha)
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
        val file = newCacheFile(dir, index, if (keepAlpha) "png" else "jpg")
        val format = if (keepAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val orientationTag = if (needsTransform && !bakeTransform) orientation else null
        val written = try {
            val finalBitmap = if (scale < 1f || bakeTransform) {
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            } else {
                decoded
            }
            try {
                file.outputStream().use { finalBitmap.compress(format, SHARE_JPEG_QUALITY, it) }
            } finally {
                if (finalBitmap !== decoded) finalBitmap.recycle()
            }
        } catch (t: Throwable) {
            file.delete()
            throw t
        } finally {
            decoded.recycle()
        }
        if (!written) {
            file.delete()
            throw IOException("bitmap encode failed")
        }

        if (latLong != null || dateTimeOriginal != null || dateTime != null || orientationTag != null) {
            try {
                val out = ExifInterface(file.absolutePath)
                if (latLong != null) out.setLatLong(latLong[0], latLong[1])
                dateTimeOriginal?.let { out.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, it) }
                dateTime?.let { out.setAttribute(ExifInterface.TAG_DATETIME, it) }
                orientationTag?.let { out.setAttribute(ExifInterface.TAG_ORIENTATION, it.toString()) }
                out.saveAttributes()
            } catch (e: Exception) {
                file.delete()
                throw IOException("EXIF write failed", e)
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

    internal fun shareMimeTypes(activity: Activity, uris: List<Uri>): Set<String> =
        uris.mapTo(mutableSetOf()) { activity.contentResolver.getType(it) ?: "image/jpeg" }

    internal fun buildShareIntent(activity: Activity, uris: List<Uri>, action: String, types: Set<String>): Intent {
        val send = if (action == Intent.ACTION_SEND) {
            Intent(action).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
        } else {
            Intent(action).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
        }
        send.type = types.singleOrNull()?.takeIf { it.startsWith("image/") } ?: "image/*"
        send.clipData = ClipData.newUri(activity.contentResolver, "photos", uris[0]).apply {
            for (i in 1 until uris.size) addItem(activity.contentResolver, ClipData.Item(uris[i]))
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return send
    }

    private fun isSignedByWebApkServer(activity: Activity, pkg: String): Boolean {
        return try {
            val signingInfo = activity.packageManager
                .getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo ?: return false
            val signers = signingInfo.apkContentsSigners ?: return false
            val md = MessageDigest.getInstance("SHA-256")
            signers.any { sig ->
                md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } ==
                    WEBAPK_SIGNER_CERT_SHA256
            }
        } catch (_: Exception) {
            false
        }
    }

    internal class WebApkTargets(private val activity: Activity, private val packages: Set<String>) {
        fun isEmpty(): Boolean = packages.isEmpty()

        fun find(action: String, type: String): ComponentName? {
            val probe = Intent(action).setType(type)
            val candidates = try {
                activity.packageManager.queryIntentActivities(probe, 0)
            } catch (_: Exception) {
                return null
            }
            for (info in candidates) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg in packages) return ComponentName(pkg, info.activityInfo.name)
            }
            return null
        }
    }

    internal fun webApkTargets(activity: Activity): WebApkTargets {
        val host = Uri.parse(TARGET_URL).host
        val packages = mutableSetOf<String>()
        if (host != null) {
            val candidates = listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE).flatMap { action ->
                try {
                    activity.packageManager.queryIntentActivities(Intent(action).setType("image/*"), 0)
                } catch (_: Exception) {
                    emptyList()
                }
            }
            for (info in candidates) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg in packages || !pkg.startsWith("org.chromium.webapk.")) continue
                if (isSignedByWebApkServer(activity, pkg) && servesHost(activity, pkg, host)) packages += pkg
            }
        }
        return WebApkTargets(activity, packages)
    }

    private fun servesHost(activity: Activity, pkg: String, host: String): Boolean {
        val meta = try {
            activity.packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA).metaData
        } catch (_: Exception) {
            null
        } ?: return false
        val urlKeys = listOf(
            "org.chromium.webapk.shell_apk.startUrl",
            "org.chromium.webapk.shell_apk.scope",
            "org.chromium.webapk.shell_apk.webManifestUrl",
        )
        return urlKeys.any { key -> meta.getString(key)?.let { Uri.parse(it).host == host } == true }
    }

    private fun webApkComponentFor(targets: WebApkTargets, action: String, types: Set<String>): ComponentName? {
        if (types.any { !it.startsWith("image/") }) return null
        val components = types.map { targets.find(action, it) }
        val first = components.firstOrNull() ?: return null
        return first.takeIf { c -> components.all { it == c } }
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

    private fun partialNotice(activity: Activity, requested: Int, delivered: Int): String? =
        if (delivered >= requested) null else activity.getString(R.string.msg_partial_load, requested, delivered)

    private fun emptyBatchError(stats: ExtractStats): Int = when {
        stats.originalUnavailable > 0 -> R.string.err_original_unavailable
        stats.timedOut > 0 -> R.string.err_cloud_timeout
        else -> R.string.err_no_image
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
        val targets = webApkTargets(activity)
        if (!targets.isEmpty()) {
            val stats = ExtractStats()
            val shared = copyOriginalsToCache(activity, uris, stats)
            if (shared.isNotEmpty()) {
                val action = shareAction(shared.size)
                val types = shareMimeTypes(activity, shared)
                val component = webApkComponentFor(targets, action, types)
                if (component != null) {
                    val send = buildShareIntent(activity, shared, action, types)
                    return@runAsync Outcome.Launch(
                        send.setComponent(component),
                        R.string.err_launch_failed,
                        partialNotice(activity, uris.size, shared.size),
                    )
                }
                Log.w(TAG, "WebAPK does not accept $action $types; falling back to the coordinates-only URL")
            } else if (stats.timedOut > 0 || stats.originalUnavailable > 0) {
                return@runAsync Outcome.Error(emptyBatchError(stats))
            }
        }
        extractAndOpenAll(activity, uris)
    }

    fun shareSheetAsync(activity: Activity, uris: List<Uri>, onDone: (Outcome) -> Unit) = runAsync(activity, onDone) {
        val stats = ExtractStats()
        val shared = copyOriginalsToCache(activity, uris, stats)
        if (shared.isEmpty()) return@runAsync Outcome.Error(emptyBatchError(stats))
        val send = buildShareIntent(activity, shared, shareAction(shared.size), shareMimeTypes(activity, shared))
        Outcome.Launch(
            Intent.createChooser(send, activity.getString(R.string.share_chooser_title)),
            R.string.err_launch_failed,
            partialNotice(activity, uris.size, shared.size),
        )
    }

    fun extractAndOpenAllAsync(activity: Activity, uris: List<Uri>, onDone: (Outcome) -> Unit) =
        runAsync(activity, onDone) { extractAndOpenAll(activity, uris) }

    fun onPermissionDenied(activity: Activity, denied: List<String>): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            Manifest.permission.READ_MEDIA_IMAGES in denied &&
            activity.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return R.string.err_partial_media
        }
        if (denied.any { activity.shouldShowRequestPermissionRationale(it) }) {
            return R.string.err_no_permission
        }
        val settings = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.packageName, null),
        )
        return if (launch(activity, settings, R.string.err_no_permission) == null) {
            R.string.err_no_permission_settings
        } else {
            R.string.err_no_permission
        }
    }

    private fun openUrl(url: Uri, notice: String? = null): Outcome =
        Outcome.Launch(Intent(Intent.ACTION_VIEW, url), R.string.err_no_browser, notice)

    private fun extractAndOpen(activity: Activity, uri: Uri, stats: ExtractStats): Outcome {
        val meta = extract(activity, uri, stats) ?: return Outcome.Error(emptyBatchError(stats))
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
        val readable = uris.take(MAX_PHOTOS).mapNotNull { extract(activity, it, stats) }
        val metas = readable.filter { it.lat != null && it.lng != null }
        if (metas.isEmpty()) {
            return Outcome.Error(if (readable.isNotEmpty()) R.string.err_no_gps else emptyBatchError(stats))
        }
        val batch = metas.joinToString(";") { m ->
            "${m.lat ?: ""},${m.lng ?: ""},${m.time ?: ""}"
        }
        val url = Uri.parse(TARGET_URL).buildUpon()
            .appendQueryParameter("photo_batch", batch)
            .build()
        return openUrl(url, partialNotice(activity, uris.size, metas.size))
    }
}
