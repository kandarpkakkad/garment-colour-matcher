package com.garmentcolour.app.data

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.room.withTransaction
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The only code that writes, deletes or sweeps captures — files and rows alike.
 * docs/01-platform/storage/lld.md §1.
 */
class CaptureStore(private val context: Context, private val db: AppDatabase) {
    private val dao = db.captures()
    private val root = File(context.filesDir, "captures")
    private val pendingDir = File(context.cacheDir, "pending")

    fun dir(id: String) = File(root, id)
    fun original(id: String) = File(dir(id), "original.jpg")
    /** RAW only: the DNG, the source of every re-render (storage schema). */
    fun originalDng(id: String) = File(dir(id), "original.dng")
    fun corrected(id: String) = File(dir(id), "corrected.jpg")
    fun labels(id: String) = File(dir(id), "labels.bin")

    fun newPending(extension: String = "jpg"): File {
        pendingDir.mkdirs()
        return File(pendingDir, "${UUID.randomUUID()}.$extension")
    }

    fun observeAll() = dao.observeAll()
    fun observe(id: String) = dao.observe(id)
    suspend fun get(id: String) = dao.get(id)
    suspend fun everything() = dao.everything()

    suspend fun insert(capture: CaptureEntity, garments: List<GarmentEntity>) = db.withTransaction {
        dao.insertCapture(capture)
        dao.insertGarments(garments)
    }

    suspend fun rerendered(capture: CaptureEntity, garments: List<GarmentEntity>, adjustment: AdjustmentEntity) =
        db.withTransaction {
            dao.updateCapture(capture)
            dao.deleteGarments(capture.id)
            dao.insertGarments(garments)
            dao.insertAdjustment(adjustment)
        }

    /** Rows first: if the app dies between the two, the sweep removes the orphan directory. */
    suspend fun delete(id: String) {
        dao.deleteCapture(id)
        dir(id).deleteRecursively()
    }

    /** Pipeline LLD §8: pending input, and directories no row points at. */
    suspend fun sweep() {
        pendingDir.deleteRecursively()
        val ids = dao.allIds().toSet()
        root.listFiles()?.forEach { if (it.name !in ids) it.deleteRecursively() }
    }

    /** Copies `corrected.jpg` into Pictures/Garment Colour (storage LLD §2). */
    fun saveToGallery(id: String): String {
        val name = "garment-colour-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.jpg"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Garment Colour")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("the gallery refused a new picture")
        try {
            resolver.openOutputStream(uri)?.use { out -> corrected(id).inputStream().use { it.copyTo(out) } }
                ?: throw IOException("the gallery gave no place to write")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return name
    }
}
