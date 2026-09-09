package com.grouppins.photobridge

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.database.ContentObserver
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class PhotosDocumentsProvider : DocumentsProvider() {

    companion object {
        private const val TAG = "PhotosDocumentsProvider"
        private const val AUTHORITY = "com.grouppins.photobridge.documents"
        private const val ROOT_ID = "grouppins-photos"
        private const val ROOT_DOC_ID = "root"
        private const val DOC_PREFIX = "img:"
        private const val BUCKET_PREFIX = "bucket:"

        private const val MAX_ITEMS = 50_000

        private const val BUCKET_CACHE_TTL_MS = 30_000L

        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_MIME_TYPES,
        )
        private val DOC_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
        )

        private val MEDIA_PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )

        private val BUCKET_PROJECTION = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED,
        )

        private val CHILDREN_URI: Uri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT_DOC_ID)

        private val BUCKET_SORT_COLUMNS = setOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED)
        private val IMAGE_SORT_COLUMNS = BUCKET_SORT_COLUMNS + Document.COLUMN_SIZE
    }

    private data class BucketEntry(val bucketId: Long, val name: String?, val lastModifiedSec: Long)

    private data class BucketCacheKey(val generation: Long?, val mediaAccess: Int)

    private var bucketCache: List<BucketEntry>? = null
    private var bucketCacheAt = 0L
    private var bucketCacheKey: BucketCacheKey? = null

    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            context?.contentResolver?.notifyChange(CHILDREN_URI, null)
        }
    }

    override fun onCreate(): Boolean {
        context!!.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver,
        )
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: ROOT_PROJECTION)
        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
            add(Root.COLUMN_ICON, android.R.drawable.ic_menu_camera)
            add(Root.COLUMN_TITLE, context!!.getString(R.string.app_name))
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            add(Root.COLUMN_MIME_TYPES, "image/*")
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DOC_PROJECTION)
        if (documentId != ROOT_DOC_ID) requireMediaAccess()
        if (documentId == ROOT_DOC_ID) {
            result.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_DISPLAY_NAME, context!!.getString(R.string.app_name))
                add(Document.COLUMN_LAST_MODIFIED, null)
                add(Document.COLUMN_FLAGS, 0)
                add(Document.COLUMN_SIZE, null)
            }
            return result
        }
        if (documentId.startsWith(BUCKET_PREFIX)) {
            val bucketId = bucketId(documentId)
            var found = false
            queryMediaStore("${MediaStore.Images.Media.BUCKET_ID} = ?", arrayOf(bucketId.toString())) { cursor ->
                if (cursor.moveToNext()) {
                    addBucketRow(result, cursor)
                    found = true
                }
            }
            if (!found) throw FileNotFoundException(documentId)
            return result
        }
        val id = mediaId(documentId)
        var found = false
        queryMediaStore("${MediaStore.Images.Media._ID} = ?", arrayOf(id.toString())) { cursor ->
            if (cursor.moveToNext()) {
                addImageRow(result, cursor)
                found = true
            }
        }
        if (!found) throw FileNotFoundException(documentId)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        queryArgs: Bundle?,
    ): Cursor {
        val rawSortOrder = queryArgs?.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER)
        val requested = if (rawSortOrder == null) requestedSort(queryArgs) else null
        val sortOrder = rawSortOrder ?: requested?.let { "${it.column} ${if (it.desc) "DESC" else "ASC"}" }
        val result = queryChildren(parentDocumentId, projection, sortOrder)
        if (requested != null && requested.column in sortableColumns(parentDocumentId)) {
            result.extras = Bundle().apply {
                putStringArray(
                    ContentResolver.EXTRA_HONORED_ARGS,
                    arrayOf(ContentResolver.QUERY_ARG_SORT_COLUMNS, ContentResolver.QUERY_ARG_SORT_DIRECTION),
                )
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(requested.column))
                putInt(
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    if (requested.desc) {
                        ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                    } else {
                        ContentResolver.QUERY_SORT_DIRECTION_ASCENDING
                    },
                )
            }
        }
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = queryChildren(parentDocumentId, projection, sortOrder)

    private fun requestedSort(queryArgs: Bundle?): SortSpec? {
        if (queryArgs == null || queryArgs.containsKey(ContentResolver.QUERY_ARG_SORT_COLLATION)) return null
        val column = queryArgs.getStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS)?.singleOrNull() ?: return null
        val direction = queryArgs.getInt(
            ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_ASCENDING,
        )
        return SortSpec(column, direction == ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
    }

    private fun sortableColumns(parentDocumentId: String): Set<String> =
        if (parentDocumentId == ROOT_DOC_ID) BUCKET_SORT_COLUMNS else IMAGE_SORT_COLUMNS

    private fun queryChildren(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): MatrixCursor {
        requireMediaAccess()
        val result = MatrixCursor(projection ?: DOC_PROJECTION)
        result.setNotificationUri(context!!.contentResolver, CHILDREN_URI)
        if (parentDocumentId == ROOT_DOC_ID) {
            sortBuckets(loadBuckets(), sortOrder).forEach { addBucketRow(result, it) }
            return result
        }
        if (parentDocumentId.startsWith(BUCKET_PREFIX)) {
            val bucketId = bucketId(parentDocumentId)
            queryMediaStore(
                "${MediaStore.Images.Media.BUCKET_ID} = ?",
                arrayOf(bucketId.toString()),
                translateSortOrder(sortOrder),
            ) { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < MAX_ITEMS) {
                    addImageRow(result, cursor)
                    count++
                }
            }
            return result
        }
        throw FileNotFoundException(parentDocumentId)
    }

    private fun granted(permission: String): Boolean =
        context!!.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun hasMediaAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> granted(Manifest.permission.READ_MEDIA_IMAGES)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun requireMediaAccess() {
        if (!hasMediaAccess()) {
            throw SecurityException("photo access has not been granted to ${context!!.packageName}; launch the app once to grant it")
        }
    }

    @Suppress("DEPRECATION")
    private fun mediaAccessState(): Int {
        var state = 0
        if (granted(Manifest.permission.ACCESS_MEDIA_LOCATION)) state = state or 1
        val read = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (granted(read)) state = state or 2
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        ) {
            state = state or 4
        }
        return state
    }

    private fun loadBuckets(): List<BucketEntry> {
        val key = BucketCacheKey(mediaGeneration(), mediaAccessState())
        synchronized(this) {
            bucketCache?.let {
                val fresh = key == bucketCacheKey &&
                    (key.generation != null || System.currentTimeMillis() - bucketCacheAt < BUCKET_CACHE_TTL_MS)
                if (fresh) return it
            }
            val buckets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) groupedBuckets() else scannedBuckets()
            if (buckets.isNotEmpty()) {
                bucketCache = buckets
                bucketCacheAt = System.currentTimeMillis()
                bucketCacheKey = key
            }
            return buckets
        }
    }

    private fun mediaGeneration(): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            MediaStore.getGeneration(context!!, MediaStore.VOLUME_EXTERNAL)
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore.getGeneration failed; using the time-based bucket cache", e)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun groupedBuckets(): List<BucketEntry> {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_GROUP_BY, MediaStore.Images.Media.BUCKET_ID)
            putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_ITEMS)
        }
        val names = LinkedHashMap<Long, String?>()
        val cursor = context!!.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            args,
            null,
        ) ?: throw FileNotFoundException("MediaStore returned no cursor for the folder list")
        cursor.use {
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) names[cursor.getLong(idIdx)] = cursor.getString(nameIdx)
        }
        return names
            .map { (id, name) -> BucketEntry(id, name, newestModifiedSec(id)) }
            .sortedByDescending { it.lastModifiedSec }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun newestModifiedSec(bucketId: Long): Long {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.BUCKET_ID} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(bucketId.toString()))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Images.Media.DATE_MODIFIED} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        val cursor = context!!.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.DATE_MODIFIED),
            args,
            null,
        ) ?: throw FileNotFoundException("MediaStore returned no cursor for bucket $bucketId")
        cursor.use { if (it.moveToNext()) return it.getLong(0) }
        return 0L
    }

    private fun scannedBuckets(): List<BucketEntry> {
        val seen = LinkedHashMap<Long, BucketEntry>()
        queryMediaStore(null, null, projection = BUCKET_PROJECTION) { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (cursor.moveToNext() && seen.size < MAX_ITEMS) {
                val id = cursor.getLong(idIdx)
                if (!seen.containsKey(id)) {
                    seen[id] = BucketEntry(id, cursor.getString(nameIdx), cursor.getLong(dateIdx))
                }
            }
        }
        return seen.values.toList()
    }

    private data class SortSpec(val column: String, val desc: Boolean)

    private fun parseSortOrder(sortOrder: String?): SortSpec? {
        val first = sortOrder?.substringBefore(',')?.trim().orEmpty()
        if (first.isEmpty()) return null
        val parts = first.split(Regex("\\s+"))
        val desc = parts.size > 1 && parts.last().equals("DESC", ignoreCase = true)
        return SortSpec(parts[0], desc)
    }

    private fun sortBuckets(buckets: List<BucketEntry>, sortOrder: String?): List<BucketEntry> {
        val spec = parseSortOrder(sortOrder) ?: return buckets
        val comparator: Comparator<BucketEntry> = when (spec.column) {
            Document.COLUMN_DISPLAY_NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name ?: "" }
            Document.COLUMN_LAST_MODIFIED -> compareBy { it.lastModifiedSec }
            else -> return buckets
        }
        return buckets.sortedWith(if (spec.desc) comparator.reversed() else comparator)
    }

    private fun translateSortOrder(sortOrder: String?): String {
        val default = "${MediaStore.Images.Media.DATE_MODIFIED} DESC, ${MediaStore.Images.Media._ID} DESC"
        val spec = parseSortOrder(sortOrder) ?: return default
        val dir = if (spec.desc) "DESC" else "ASC"
        return when (spec.column) {
            Document.COLUMN_DISPLAY_NAME -> "${MediaStore.Images.Media.DISPLAY_NAME} $dir"
            Document.COLUMN_LAST_MODIFIED ->
                "${MediaStore.Images.Media.DATE_MODIFIED} $dir, ${MediaStore.Images.Media._ID} $dir"
            Document.COLUMN_SIZE -> "${MediaStore.Images.Media.SIZE} $dir"
            else -> default
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read-only provider (mode=$mode)")
        val original = MediaStore.setRequireOriginal(mediaUri(documentId))
        try {
            return context!!.contentResolver.openFileDescriptor(original, "r", signal)
                ?: throw FileNotFoundException(documentId)
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: OperationCanceledException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "original (with GPS) unavailable for $documentId; not falling back to a stripped copy", e)
            throw FileNotFoundException("original unavailable for $documentId (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        val bitmap = try {
            context!!.contentResolver.loadThumbnail(
                mediaUri(documentId),
                Size(sizeHint.x.coerceAtLeast(64), sizeHint.y.coerceAtLeast(64)),
                signal,
            )
        } catch (e: OperationCanceledException) {
            throw e
        } catch (e: Exception) {
            throw FileNotFoundException("thumbnail failed: $documentId (${e.message})")
        }
        try {
            val file = File.createTempFile("thumb", ".jpg", context!!.cacheDir)
            try {
                val written = file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                if (!written) throw FileNotFoundException("thumbnail encode failed: $documentId")
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            } finally {
                file.delete()
            }
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: IOException) {
            throw FileNotFoundException("thumbnail write failed: $documentId (${e.message})")
        } finally {
            bitmap.recycle()
        }
    }

    private fun mediaId(documentId: String): Long =
        documentId.removePrefix(DOC_PREFIX).toLongOrNull() ?: throw FileNotFoundException(documentId)

    private fun bucketId(documentId: String): Long =
        documentId.removePrefix(BUCKET_PREFIX).toLongOrNull() ?: throw FileNotFoundException(documentId)

    private fun mediaUri(documentId: String): Uri =
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId(documentId))

    private fun queryMediaStore(
        selection: String?,
        args: Array<String>?,
        sortOrder: String = "${MediaStore.Images.Media.DATE_MODIFIED} DESC, ${MediaStore.Images.Media._ID} DESC",
        projection: Array<String> = MEDIA_PROJECTION,
        block: (Cursor) -> Unit,
    ) {
        val cursor = context!!.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            sortOrder,
        ) ?: throw FileNotFoundException("MediaStore returned no cursor")
        cursor.use(block)
    }

    private fun addImageRow(result: MatrixCursor, cursor: Cursor) {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
        result.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, "$DOC_PREFIX$id")
            add(
                Document.COLUMN_MIME_TYPE,
                cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)) ?: "image/jpeg",
            )
            add(
                Document.COLUMN_DISPLAY_NAME,
                cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: "$id.jpg",
            )
            add(
                Document.COLUMN_LAST_MODIFIED,
                cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)) * 1000,
            )
            add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_THUMBNAIL)
            add(Document.COLUMN_SIZE, cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)))
        }
    }

    private fun addBucketRow(result: MatrixCursor, cursor: Cursor) {
        addBucketRow(
            result,
            BucketEntry(
                cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)),
                cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)),
            ),
        )
    }

    private fun addBucketRow(result: MatrixCursor, bucket: BucketEntry) {
        result.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, "$BUCKET_PREFIX${bucket.bucketId}")
            add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            add(
                Document.COLUMN_DISPLAY_NAME,
                bucket.name ?: context!!.getString(R.string.bucket_no_folder),
            )
            add(Document.COLUMN_LAST_MODIFIED, bucket.lastModifiedSec * 1000)
            add(Document.COLUMN_FLAGS, 0)
            add(Document.COLUMN_SIZE, null)
        }
    }
}
