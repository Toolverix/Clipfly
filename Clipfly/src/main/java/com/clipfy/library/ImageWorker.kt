package com.clipfy.library

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.*
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class ImageWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {
    private var currentSession: Session? = null
    private val processedPaths = mutableListOf<String>()
    private val tempFiles = mutableSetOf<File>()

    companion object {
        const val WORK_NAME = "ffmpeg_library_image_worker"
        const val KEY_COMMANDS = "commands"
        const val KEY_IMAGE_PATHS = "image_paths"
        const val KEY_FORMATS = "formats"
        const val KEY_PROCESSED_PATHS = "processed_paths"
        const val KEY_FOLDER = "folder"
        const val KEY_NOTIFICATION_ICON = "notification_icon"
        const val KEY_NOTIFICATION_TITLE = "notification_title"
        private const val NOTIFICATION_ID = 3002

        fun enqueue(
            context: Context,
            commands: List<String>,
            imagePaths: List<String>,
            formats: List<String>,
            folder: String,
            notificationIcon: Int,
            notificationTitle: String
        ): Operation {
            val inputData = Data.Builder()
                .putStringArray(KEY_COMMANDS, commands.toTypedArray())
                .putStringArray(KEY_IMAGE_PATHS, imagePaths.toTypedArray())
                .putStringArray(KEY_FORMATS, formats.toTypedArray())
                .putString(KEY_FOLDER, folder)
                .putInt(KEY_NOTIFICATION_ICON, notificationIcon)
                .putString(KEY_NOTIFICATION_TITLE, notificationTitle)
                .build()

            val request = OneTimeWorkRequestBuilder<ImageWorker>()
                .setInputData(inputData)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .addTag("FFmpegLibraryProcessing")
                .build()

            return WorkManager.getInstance(context)
                .beginUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
                .enqueue()
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val commands = inputData.getStringArray(KEY_COMMANDS) ?: return@withContext Result.failure()
            val imagePaths = inputData.getStringArray(KEY_IMAGE_PATHS) ?: return@withContext Result.failure()
            val formats = inputData.getStringArray(KEY_FORMATS) ?: return@withContext Result.failure()
            val folder = inputData.getString(KEY_FOLDER) ?: ""

            if (processImages(imagePaths.toList(), commands, formats, folder)) {
                Result.success(Data.Builder().putStringArray(KEY_PROCESSED_PATHS, processedPaths.toTypedArray()).build())
            } else {
                Result.failure()
            }
        } catch (e: Exception) {
            ClipFyWorker.emitState(
                ClipFyWorker.ProcessingState.Error(
                    message = e.message ?: "Error",
                    ffmpegLogs = null
                )
            )
            Result.failure()
        } finally {
            cleanup()
        }
    }

    private suspend fun processImages(
        imagePaths: List<String>,
        commands: Array<String>,
        formats: Array<String>,
        folder: String
    ): Boolean {
        val totalImages = imagePaths.size
        for (i in imagePaths.indices) {
            if (isStopped) return false

            val command = commands.getOrNull(i) ?: continue
            val format = formats.getOrNull(i) ?: "jpg"

            val progress = (i.toFloat() / totalImages) * 100
            updateProgress(progress)

            val outputPath = createTempFile(format)
            val finalCommand = "$command -y \"$outputPath\""
            val (success, logs) = executeCommand(finalCommand)

            if (!success) {
                ClipFyWorker.emitState(
                    ClipFyWorker.ProcessingState.Error(
                        message = "Error in command",
                        ffmpegLogs = logs
                    )
                )
                return false
            }

            val savedPath = saveFile(outputPath, imagePaths[i], format, folder) ?: return false
            processedPaths.add(savedPath)
            cleanupTempFiles()

            val completedProgress = ((i + 1).toFloat() / totalImages) * 100
            updateProgress(completedProgress)
            ClipFyWorker.emitState(ClipFyWorker.ProcessingState.Processed(ArrayList(processedPaths)))
        }

        updateProgress(100f)
        ClipFyWorker.emitState(ClipFyWorker.ProcessingState.Completed(ArrayList(processedPaths)))
        return true
    }

    private suspend fun executeCommand(command: String): Pair<Boolean, String> = suspendCancellableCoroutine { cont ->
        if (isStopped) return@suspendCancellableCoroutine cont.resume(false to "")

        try {
            currentSession = FFmpegKit.executeAsync(command) { session ->
                val success = session.returnCode.isValueSuccess
                val logs = session.allLogsAsString ?: ""
                cont.resume(success to logs)
                session.cancel()
            }
        } catch (e: Exception) {
            cont.resume(false to "Exception: ${e.message}")
        }

        cont.invokeOnCancellation { cleanupSession() }
    }

    private fun updateProgress(progress: Float) {
        setForegroundAsync(createForegroundInfo(progress))
        ClipFyWorker.emitProgress(progress, processedPaths.size)
    }

    private suspend fun createTempFile(format: String): String = withContext(Dispatchers.IO) {
        val tempDir = File(applicationContext.cacheDir, "ffmpeg_library_process").apply { mkdirs() }
        val tempFile = File(tempDir, "temp_${System.currentTimeMillis()}.$format")
        tempFiles.add(tempFile)
        tempFile.absolutePath
    }

    private suspend fun saveFile(tempPath: String, originalPath: String, format: String, saveFolder: String): String? = withContext(Dispatchers.IO) {
        if (isStopped) return@withContext null
        try {
            val tempFile = File(tempPath)
            if (!tempFile.exists()) return@withContext null
            val savedPath = SaveMedia.saveMediaFile(
                ctx = applicationContext,
                sourceFile = tempFile,
                format = format,
                name = "${File(originalPath).nameWithoutExtension}_process",
                folder = saveFolder
            )?.absolutePath
            tempFile.delete()
            tempFiles.remove(tempFile)
            savedPath
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanupTempFiles() {
        tempFiles.filter { it.exists() && !processedPaths.contains(it.absolutePath) }.forEach {
            it.delete()
            tempFiles.remove(it)
        }
    }

    private fun cleanupSession() {
        currentSession?.cancel()
        currentSession = null
        FFmpegKitConfig.clearSessions()
    }

    private fun cleanup() {
        cleanupTempFiles()
        cleanupSession()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo(0f)

    private fun createForegroundInfo(progress: Float): ForegroundInfo {
        val notificationIcon = inputData.getInt(KEY_NOTIFICATION_ICON, 0)
        val notificationTitle = inputData.getString(KEY_NOTIFICATION_TITLE)

        val notification = NotificationHelper.createProgressNotification(
            applicationContext,
            notificationTitle ?: "File under processing",
            progress,
            notificationIcon
        )

        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }
}