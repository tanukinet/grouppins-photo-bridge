package com.grouppins.photobridge

import android.content.ContentUris
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Point
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.provider.MediaStore
import android.util.Size
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class PhotosDocumentsProvider : DocumentsProvider() {

    companion object {
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
    }

    private data class BucketEntry(val bucketId: Long, val name: String?, val lastModifiedSec: Long)

    private var bucketCache: List<BucketEntry>? = null
    private var bucketCacheAt = 0L

    override fun onCreate(): Boolean = true

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
        sortOrder: String?,
    ): Cursor {
        val result = MatrixCursor(projection ?: DOC_PROJECTION)
        if (parentDocumentId == ROOT_DOC_ID) {
            loadBuckets().forEach { addBucketRow(result, it) }
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

    private fun loadBuckets(): List<BucketEntry> {
        synchronized(this) {
            bucketCache?.let {
                if (System.currentTimeMillis() - bucketCacheAt < BUCKET_CACHE_TTL_MS) return it
            }
        }
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
        val buckets = seen.values.toList()
        if (buckets.isNotEmpty()) {
            synchronized(this) {
                bucketCache = buckets
                bucketCacheAt = System.currentTimeMillis()
            }
        }
        return buckets
    }

    private fun translateSortOrder(sortOrder: String?): String {
        val default = "${MediaStore.Images.Media.DATE_MODIFIED} DESC, ${MediaStore.Images.Media._ID} DESC"
        if (sortOrder.isNullOrBlank()) return default
        val desc = sortOrder.contains("DESC", ignoreCase = true)
        val dir = if (desc) "DESC" else "ASC"
        return when {
            sortOrder.contains(Document.COLUMN_DISPLAY_NAME) ->
                "${MediaStore.Images.Media.DISPLAY_NAME} $dir"
            sortOrder.contains(Document.COLUMN_LAST_MODIFIED) ->
                "${MediaStore.Images.Media.DATE_MODIFIED} $dir, ${MediaStore.Images.Media._ID} $dir"
            sortOrder.contains(Document.COLUMN_SIZE) ->
                "${MediaStore.Images.Media.SIZE} $dir"
            else -> default
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read-only provider (mode=$mode)")
        val uri = mediaUri(documentId)
        val resolver = context!!.contentResolver
        try {
            resolver.openFileDescriptor(MediaStore.setRequireOriginal(uri), "r", signal)?.let { return it }
        } catch (_: Exception) {
        }
        return resolver.openFileDescriptor(uri, "r", signal)
            ?: throw FileNotFoundException(documentId)
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
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            } finally {
                file.delete()
            }
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: IOException) {
            throw FileNotFoundException("thumbnail write failed: $documentId (${e.message})")
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
        try {
            context!!.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                sortOrder,
            )?.use(block)
        } catch (_: SecurityException) {
        }
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
