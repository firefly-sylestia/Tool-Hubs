package com.sylestia.toolhubs.wallpaperstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import android.media.ExifInterface
import android.graphics.Matrix
import android.media.ExifInterface
import android.graphics.RectF
import android.net.Uri
import java.io.File
import kotlin.math.max
import kotlin.math.min

object WallpaperComposer {
    fun render(context: Context, imageUri: String, fit: WallpaperFit, focusX: Float, focusY: Float): Bitmap {
        val uri = Uri.parse(imageUri)
        val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
            runCatching {
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val metrics = context.resources.displayMetrics
        val targetWidth = metrics.widthPixels.coerceAtLeast(1)
        val targetHeight = metrics.heightPixels.coerceAtLeast(1)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sourceRatio = min(
            bounds.outWidth.coerceAtLeast(1).toFloat() / targetWidth,
            bounds.outHeight.coerceAtLeast(1).toFloat() / targetHeight,
        ).toInt().coerceAtLeast(1)
        var sampleSize = 1
        while (sampleSize * 2 <= sourceRatio) sampleSize *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            )
        } ?: error("The selected image could not be decoded.")
        val transform = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> transform.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                transform.setRotate(90f)
                transform.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> transform.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                transform.setRotate(270f)
                transform.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> transform.setRotate(270f)
        }
        val source = if (transform.isIdentity()) decoded else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true)
                .also { if (it !== decoded) decoded.recycle() }
        }

        val width = targetWidth
        val height = targetHeight
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)

        val scale = when (fit) {
            WallpaperFit.FILL -> max(width.toFloat() / source.width, height.toFloat() / source.height)
            WallpaperFit.FIT -> min(width.toFloat() / source.width, height.toFloat() / source.height)
            WallpaperFit.STRETCH -> 1f
            WallpaperFit.CENTER -> 1f
        }
        val drawWidth = if (fit == WallpaperFit.STRETCH) width.toFloat() else source.width * scale
        val drawHeight = if (fit == WallpaperFit.STRETCH) height.toFloat() else source.height * scale
        val left = (width - drawWidth) * focusX.coerceIn(0f, 1f)
        val top = (height - drawHeight) * focusY.coerceIn(0f, 1f)
        val destination = RectF(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(source, null, destination, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        source.recycle()
        return output
    }
}

/**
 * Saves the wallpaper that was already on the device before an automatic scene takes over.
 * Separate snapshots let a Home-only scene leave the Lock screen untouched and vice versa.
 */
object WallpaperBaseline {
    private fun file(context: Context, flag: Int): File {
        val folder = File(context.filesDir, "wallpaper-baseline").apply { mkdirs() }
        return File(folder, if (flag == android.app.WallpaperManager.FLAG_LOCK) "lock.bin" else "home.bin")
    }

    fun capture(context: Context, flags: Int): Boolean {
        val manager = android.app.WallpaperManager.getInstance(context)
        val targets = listOf(android.app.WallpaperManager.FLAG_SYSTEM, android.app.WallpaperManager.FLAG_LOCK)
            .filter { flags and it != 0 }
        return targets.all { flag ->
            val target = file(context, flag)
            if (target.exists() && target.length() > 0) {
                true
            } else {
                runCatching {
                    val descriptor = manager.getWallpaperFile(flag) ?: return@runCatching false
                    descriptor.use { parcel ->
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(parcel).use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    target.exists() && target.length() > 0
                }.getOrDefault(false)
            }
        }
    }

    fun restore(context: Context): Boolean {
        val manager = android.app.WallpaperManager.getInstance(context)
        val targets = listOf(android.app.WallpaperManager.FLAG_SYSTEM, android.app.WallpaperManager.FLAG_LOCK)
        var restoredAny = false
        var allRestored = true
        targets.forEach { flag ->
            val saved = file(context, flag)
            if (!saved.exists() || saved.length() == 0L) return@forEach
            val restored = runCatching {
                saved.inputStream().use { manager.setStream(it, null, true, flag) }
                true
            }.getOrDefault(false)
            restoredAny = restoredAny || restored
            allRestored = allRestored && restored
            if (restored) saved.delete()
        }
        return restoredAny && allRestored
    }

    fun hasSnapshot(context: Context): Boolean =
        file(context, android.app.WallpaperManager.FLAG_SYSTEM).exists() ||
            file(context, android.app.WallpaperManager.FLAG_LOCK).exists()
}
