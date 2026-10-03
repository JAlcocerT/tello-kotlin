package tech.jalcocer.tello.video

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object MediaCapture {
    suspend fun savePhoto(context: Context, view: SurfaceView): String {
        check(view.width > 0 && view.height > 0) { "Video surface is not ready" }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        suspendCancellableCoroutine { continuation ->
            PixelCopy.request(view, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) continuation.resume(Unit)
                else continuation.resumeWithException(IllegalStateException("Photo capture failed ($result)"))
            }, Handler(Looper.getMainLooper()))
        }
        return withContext(Dispatchers.IO) {
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val name = "tello_photo_$stamp.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Tello Native")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create photo")
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) { "JPEG encoding failed" }
                } ?: error("Could not open photo")
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
                name
            } catch (error: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw error
            } finally {
                bitmap.recycle()
            }
        }
    }
}
