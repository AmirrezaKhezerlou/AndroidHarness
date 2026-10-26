package com.androidharness.app.ui.chat.components

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.androidharness.app.R
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine

internal object UpiDonation {
    const val UPI_ID = "sanuj69@ybl"

    /** Publish the bundled original, without recompressing or recoloring its QR modules. */
    suspend fun saveQrToGallery(context: Context): Uri = withContext(Dispatchers.IO) {
        val name = "AndroidHarness-UPI-${System.currentTimeMillis()}.jpg"
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AndroidHarness")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            try {
                checkNotNull(resolver.openOutputStream(uri)).use { output ->
                    context.resources.openRawResource(R.drawable.donate_upi_qr).use { it.copyTo(output) }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                check(resolver.update(uri, values, null, null) > 0)
                uri
            } catch (error: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        } else {
            check(ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
            @Suppress("DEPRECATION")
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AndroidHarness")
            check(directory.isDirectory || directory.mkdirs())
            val file = File.createTempFile("AndroidHarness-UPI-", ".jpg", directory)
            try {
                file.outputStream().use { output ->
                    context.resources.openRawResource(R.drawable.donate_upi_qr).use { it.copyTo(output) }
                }
                withTimeoutOrNull(30_000) {
                    suspendCancellableCoroutine<Uri> { continuation ->
                        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg")) { _, uri ->
                            if (continuation.isActive) {
                                if (uri != null) continuation.resume(uri)
                                else continuation.resumeWithException(IllegalStateException("Gallery could not index the QR image"))
                            } else if (uri != null) {
                                runCatching { context.contentResolver.delete(uri, null, null) }
                            }
                        }
                    }
                } ?: error("Gallery indexing timed out")
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }
    }
}
