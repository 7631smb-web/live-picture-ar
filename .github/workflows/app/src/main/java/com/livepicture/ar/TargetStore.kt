package com.livepicture.ar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** یک «تابلو»: تصویر هدف + ویدیویی که روی آن پخش می‌شود. */
data class ArTarget(
    val id: String,
    val name: String,
    /** عرض فیزیکی تصویر چاپ‌شده به متر؛ صفر یعنی ARCore خودش تخمین بزند. */
    val widthMeters: Float,
    val createdAt: Long,
    val dir: File,
) {
    val imageFile: File get() = File(dir, "image.jpg")
    val videoFile: File get() = File(dir, "video.mp4")
}

/** ذخیره‌سازی تابلوها در حافظهٔ داخلی برنامه (کپی فایل‌ها تا مستقل از گالری باشند). */
class TargetStore(context: Context) {

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "targets").apply { mkdirs() }

    fun list(): List<ArTarget> =
        root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { load(it) }
            ?.sortedBy { it.createdAt }
            ?: emptyList()

    private fun load(dir: File): ArTarget? {
        val meta = File(dir, "meta.json")
        if (!meta.exists()) return null
        return try {
            val j = JSONObject(meta.readText())
            ArTarget(
                id = dir.name,
                name = j.optString("name", dir.name),
                widthMeters = j.optDouble("widthMeters", 0.0).toFloat(),
                createdAt = j.optLong("createdAt", dir.lastModified()),
                dir = dir,
            )
        } catch (e: Exception) {
            null
        }
    }

    @Throws(IOException::class)
    fun create(imageUri: Uri, videoUri: Uri, name: String, widthMeters: Float): ArTarget {
        val dir = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val bitmap = decodeScaled(imageUri, MAX_IMAGE_DIM)
                ?: throw IOException("تصویر قابل خواندن نیست")
            FileOutputStream(File(dir, "image.jpg")).use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            bitmap.recycle()

            val input = appContext.contentResolver.openInputStream(videoUri)
                ?: throw IOException("ویدیو قابل خواندن نیست")
            input.use { inp -> FileOutputStream(File(dir, "video.mp4")).use { inp.copyTo(it) } }

            File(dir, "meta.json").writeText(
                JSONObject()
                    .put("name", name)
                    .put("widthMeters", widthMeters.toDouble())
                    .put("createdAt", System.currentTimeMillis())
                    .toString()
            )
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw if (e is IOException) e else IOException(e.message, e)
        }
        return load(dir) ?: throw IOException("ذخیره ناموفق")
    }

    fun delete(target: ArTarget) {
        target.dir.deleteRecursively()
    }

    /** خواندن تصویر با چرخش EXIF (عکس‌های دوربین) و کوچک‌سازی تا حداکثر ابعاد. */
    private fun decodeScaled(uri: Uri, maxDim: Int): Bitmap? {
        val cr = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val orientation = try {
            cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        if (!m.isIdentity) {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        val scale = maxDim.toFloat() / max(bmp.width, bmp.height)
        if (scale < 1f) {
            val scaled = Bitmap.createScaledBitmap(
                bmp, (bmp.width * scale).roundToInt(), (bmp.height * scale).roundToInt(), true
            )
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        return bmp
    }

    companion object {
        private const val MAX_IMAGE_DIM = 1280
    }
}
