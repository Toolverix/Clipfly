package com.clipfy.library

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream

object SaveMedia {
    private fun isExternalStorageWritable(): Boolean {
        return Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
    }

    fun saveMediaFile(ctx: Context, sourceFile: File, format: String, name: String, folder: String, dir: String? = null): File? {
        return when {
            isImageFormat(format) -> {
                if (format.lowercase() == "gif") {
                    saveGifFile(ctx, sourceFile, format, name, folder)
                } else {
                    saveImageFile(ctx, sourceFile, format, name, folder, dir)
                }
            }
            isVideoFormat(format) -> saveVideoFile(ctx, sourceFile, format, name, folder)
            isAudioFormat(format) -> saveAudioFile(ctx, sourceFile, format, name, folder)
            else -> throw IllegalArgumentException("Unsupported format: $format")
        }
    }

    private fun saveImageFile(ctx: Context, sourceFile: File, format: String, name: String, folder: String, dir: String?): File? {
        val isWritable = isExternalStorageWritable()
        var filePath: String? = null

        if (isWritable) {
            val path = dir?.let { "${Environment.DIRECTORY_PICTURES}${File.separator}$it" }
                ?: "${Environment.DIRECTORY_PICTURES}${File.separator}ClipFy${File.separator}$folder"

            ctx.contentResolver.let { cr ->
                val cv = getContentValues(format, name, path)
                val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                uri?.let {
                    filePath = getPath(ctx, it)
                    cr.openOutputStream(it)?.use { fos ->
                        copyFile(sourceFile, fos)
                    }
                }
            }
        }
        return filePath?.let { File(it) }
    }

    private fun saveGifFile(ctx: Context, sourceFile: File, format: String, name: String, folder: String): File? {
        val isWritable = isExternalStorageWritable()
        var filePath: String? = null

        if (isWritable) {
            val path = "${Environment.DIRECTORY_PICTURES}${File.separator}ClipFy${File.separator}$folder"

            try {
                ctx.contentResolver.let { cr ->
                    var finalName = name
                    var counter = 1
                    while (true) {
                        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}='$path/' AND ${MediaStore.MediaColumns.DISPLAY_NAME}='$finalName.$format'"
                        val cursor = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, selection, null, null)

                        if (cursor?.count == 0) {
                            cursor.close()
                            break
                        }
                        cursor?.close()

                        finalName = "${name}_${counter}"
                        counter++
                    }

                    val cv = getContentValues(format, finalName, path)
                    val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)

                    uri?.let {
                        filePath = getPath(ctx, it)
                        cr.openOutputStream(it, "wt")?.use { fos ->
                            copyFile(sourceFile, fos)
                        }
                    }
                }
            } catch (e: Exception) {
                throw IOException("Failed to save GIF file: ${e.message}")
            }
        }
        return filePath?.let { File(it) }
    }

    private fun saveVideoFile(ctx: Context, sourceFile: File, format: String, name: String, folder: String): File? {
        val isWritable = isExternalStorageWritable()
        var filePath: String? = null

        if (isWritable) {
            val path = "${Environment.DIRECTORY_MOVIES}${File.separator}ClipFy${File.separator}$folder"

            try {
                ctx.contentResolver.let { cr ->
                    var finalName = name
                    var counter = 1
                    while (true) {
                        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}='$path/' AND ${MediaStore.MediaColumns.DISPLAY_NAME}='$finalName.$format'"
                        val cursor = cr.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null, selection, null, null)

                        if (cursor?.count == 0) {
                            cursor.close()
                            break
                        }
                        cursor?.close()

                        finalName = "${name}_${counter}"
                        counter++
                    }

                    val cv = getContentValues(format, finalName, path)
                    val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)

                    uri?.let {
                        filePath = getPath(ctx, it)
                        cr.openOutputStream(it, "wt")?.use { fos ->
                            copyFile(sourceFile, fos)
                        }
                    }
                }
            } catch (e: Exception) {
                throw IOException("Failed to save video: ${e.message}")
            }
        }
        return filePath?.let { File(it) }
    }

    private fun copyFile(sourceFile: File, outputStream: OutputStream) {
        try {
            FileInputStream(sourceFile).use { inputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                outputStream.flush()
            }
        } catch (e: Exception) {
            throw IOException("Failed to copy file: ${e.message}")
        }
    }

    private fun saveAudioFile(ctx: Context, sourceFile: File, format: String, name: String, folder: String): File? {
        val isWritable = isExternalStorageWritable()
        var filePath: String? = null

        if (isWritable) {
            val path = "${Environment.DIRECTORY_MUSIC}${File.separator}ClipFy${File.separator}$folder"

            ctx.contentResolver.let { cr ->
                val cv = getContentValues(format, name, path)
                val uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv)
                uri?.let {
                    filePath = getPath(ctx, it)
                    cr.openOutputStream(it)?.use { fos ->
                        copyFile(sourceFile, fos)
                    }
                }
            }
        }
        return filePath?.let { File(it) }
    }

    private fun getContentValues(format: String, name: String, relativePath: String): ContentValues {
        return ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.${format.lowercase()}")
            put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(format))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
    }

    private fun getMimeType(format: String): String {
        return when (format.lowercase()) {
            "png" -> "image/png"
            "jpeg", "jpg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "ts" -> "video/mp2ts"
            "mts" -> "video/mp2ts"
            "m4v" -> "video/mp4"
            "ogv" -> "video/ogg"
            "mp3" -> "audio/mpeg"
            "aac" -> "audio/aac"
            "wav" -> "audio/x-wav"
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "opus" -> "audio/opus"
            "ac3" -> "audio/ac3"
            else -> throw IllegalArgumentException("Unsupported format: $format")
        }
    }

    private fun isImageFormat(format: String): Boolean {
        return format.lowercase() in setOf("webp", "jpeg", "png", "jpg", "gif")
    }

    private fun isVideoFormat(format: String): Boolean {
        return format.lowercase() in setOf(
            "mp4", "mkv", "avi", "mov", "webm", "mpeg", "ts",
            "mts", "m4v", "ogv"
        )
    }

    private fun isAudioFormat(format: String): Boolean {
        return format.lowercase() in setOf("mp3", "aac", "wav", "flac", "ogg", "m4a", "opus", "ac3", "aif", "aiff")
    }

    private fun getPath(context: Context, uri: android.net.Uri): String? {
        return try {
            val cursor = context.contentResolver.query(uri, arrayOf("_data"), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val columnIndex = it.getColumnIndexOrThrow("_data")
                    it.getString(columnIndex)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}