package com.clipfy.library

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.work.*
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class MediaWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {
    private val TAG = "MediaWorker"
    private var currentSession: Session? = null
    private var currentIndex = 0
    private val processedPaths = mutableListOf<String>()
    private val tempFiles = mutableSetOf<File>()

    companion object {
        const val WORK_NAME = "ffmpeg_library_video_worker"
        const val KEY_COMMANDS = "commands"
        const val KEY_VIDEO_PATHS = "video_paths"
        const val KEY_FORMATS = "formats"
        const val KEY_DURATIONS = "durations"
        const val KEY_FOLDER = "folder"
        const val KEY_NOTIFICATION_ICON = "notification_icon"
        const val KEY_NOTIFICATION_TITLE = "notification_title"
        const val KEY_PROCESSED_PATHS = "processed_paths"
        private const val NOTIFICATION_ID = 3001

        fun enqueue(
            context: Context,
            commands: List<String>,
            secondCommands: List<String>,
            videoPaths: List<String>,
            formats: List<String>,
            durations: List<Long>,
            folder: String,
            notificationIcon: Int,
            notificationTitle: String,
        ): Operation {
            val inputData = Data.Builder()
                .putStringArray(KEY_COMMANDS, commands.toTypedArray())
                .putStringArray("second_commands", secondCommands.toTypedArray())
                .putStringArray(KEY_VIDEO_PATHS, videoPaths.toTypedArray())
                .putStringArray(KEY_FORMATS, formats.toTypedArray())
                .putLongArray(KEY_DURATIONS, durations.toLongArray())
                .putString(KEY_FOLDER, folder)
                .putInt(KEY_NOTIFICATION_ICON, notificationIcon)
                .putString(KEY_NOTIFICATION_TITLE, notificationTitle)
                .build()

            val request = OneTimeWorkRequestBuilder<MediaWorker>()
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
            val data = inputData
            val commands = data.getStringArray(KEY_COMMANDS)
            val secondCommands = data.getStringArray("second_commands")
            val videoPaths = data.getStringArray(KEY_VIDEO_PATHS)
            val formats = data.getStringArray(KEY_FORMATS)
            val durations = data.getLongArray(KEY_DURATIONS)
            val folder = data.getString(KEY_FOLDER)

            if (commands.isNullOrEmpty() || videoPaths.isNullOrEmpty() || formats.isNullOrEmpty() || durations == null) {
                return@withContext Result.failure()
            }

            val success = processVideos(commands, secondCommands, videoPaths, formats, durations, folder ?: "")

            return@withContext if (success) {
                Result.success(
                    Data.Builder()
                        .putStringArray(KEY_PROCESSED_PATHS, processedPaths.toTypedArray())
                        .build()
                )
            } else {
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Worker failed", e)
            ClipFyWorker.emitState(
                ClipFyWorker.ProcessingState.Error(
                    message = e.message ?: "Unknown error",
                    ffmpegLogs = null
                )
            )
            Result.failure()
        } finally {
            cleanupResources()
        }
    }

    private suspend fun processVideos(
        commands: Array<String>,
        secondCommands: Array<String>?,
        videoPaths: Array<String>,
        formats: Array<String>,
        durations: LongArray,
        folder: String
    ): Boolean {
        for (i in videoPaths.indices) {
            if (isStopped) break

            currentIndex = i
            val path = videoPaths[i]
            val command = commands.getOrNull(i) ?: continue
            val secondCommand = secondCommands?.getOrNull(i) ?: ""
            val format = formats.getOrNull(i) ?: "mp4"
            val duration = durations.getOrNull(i) ?: 0L

            try {
                val outputPath = createTempFile(format)
                val finalCommand = "$command -y \"$outputPath\""

                val (success, logs) = executeCommand(finalCommand, duration)

                var finalSuccess = success
                var finalLogs = logs

                if (!success && secondCommand.isNotEmpty()) {
                    val secondFinalCommand = "$secondCommand -y \"$outputPath\""
                    val (secondSuccess, secondLogs) = executeCommand(secondFinalCommand, duration)
                    finalSuccess = secondSuccess
                    finalLogs = secondLogs
                }

                if (!finalSuccess) {
                    ClipFyWorker.emitState(
                        ClipFyWorker.ProcessingState.Error(
                            message = "FFmpeg execution failed",
                            ffmpegLogs = finalLogs
                        )
                    )
                    return false
                }

                val savedPath = handleSuccessfulConversion(outputPath, path, format, folder) ?: return false
                processedPaths.add(savedPath)
                ClipFyWorker.emitState(ClipFyWorker.ProcessingState.Processed(ArrayList(processedPaths)))

            } catch (e: Exception) {
                ClipFyWorker.emitState(
                    ClipFyWorker.ProcessingState.Error(
                        message = e.message ?: "Unknown error",
                        ffmpegLogs = null
                    )
                )
                return false
            }
        }

        ClipFyWorker.emitState(ClipFyWorker.ProcessingState.Completed(ArrayList(processedPaths)))
        return true
    }

    private suspend fun createTempFile(format: String): String = withContext(Dispatchers.IO) {
        val tempDir = File(applicationContext.cacheDir, "ffmpeg_library_process").apply { mkdirs() }
        val tempFile = File(tempDir, "temp_${System.currentTimeMillis()}.${getActualFormat(format)}")
        tempFiles.add(tempFile)
        tempFile.absolutePath
    }

    private suspend fun executeCommand(command: String, duration: Long): Pair<Boolean, String> =
        suspendCancellableCoroutine { cont ->
            if (isStopped) {
                cont.resume(false to "")
                return@suspendCancellableCoroutine
            }

            Log.d(TAG, "Executing command: $command")

            currentSession = FFmpegKit.executeAsync(
                command,
                { session ->
                    val logs = session.allLogsAsString ?: ""
                    val state = session.state
                    val returnCode = session.returnCode
                    val failStack = session.failStackTrace

                    Log.d(TAG, "FFmpeg Session State: $state")
                    Log.d(TAG, "FFmpeg Return Code: $returnCode")
                    if (!returnCode.isValueSuccess) {
                        Log.e(TAG, "FFmpeg Command Failed: $command")
                        Log.e(TAG, "FFmpeg Logs:\n$logs")
                        failStack?.let { Log.e(TAG, "FFmpeg Stacktrace:\n$it") }
                    }

                    cont.resume(returnCode.isValueSuccess to logs)
                },
                { log ->
                    log.message?.takeIf { it.contains("time=") }?.let {
                        Regex("time=(\\d+):(\\d+):(\\d+\\.\\d+)").find(it)?.let { match ->
                            val processedMs = (match.groupValues[1].toInt() * 3600L +
                                    match.groupValues[2].toInt() * 60L +
                                    match.groupValues[3].toFloat()) * 1000L
                            val progress = if (duration > 0) {
                                (processedMs.toFloat() / duration * 100).coerceIn(0f, 100f)
                            } else 0f
                            updateProgress(progress)
                        }
                    }
                },
                null
            )

            cont.invokeOnCancellation { cleanupSession() }
        }

    private fun updateProgress(progress: Float) {
        setForegroundAsync(createForegroundInfo(progress))
        ClipFyWorker.emitProgress(progress, currentIndex)
    }

    private fun getActualFormat(format: String): String {
        return when (format.lowercase()) {
            "matroska" -> "mkv"
            else -> format
        }
    }

    private suspend fun handleSuccessfulConversion(
        tempPath: String,
        originalPath: String,
        format: String,
        saveFolder: String
    ): String? = withContext(Dispatchers.IO) {
        if (isStopped) return@withContext null

        File(tempPath).takeIf { it.exists() }?.let { tempFile ->
            SaveMedia.saveMediaFile(
                ctx = applicationContext,
                sourceFile = tempFile,
                format = getActualFormat(format),
                name = "${File(originalPath).nameWithoutExtension}_process",
                folder = saveFolder
            )?.absolutePath?.also {
                tempFile.delete()
                tempFiles.remove(tempFile)
            }
        }
    }

    private fun cleanupSession() {
        currentSession?.let { FFmpegKit.cancel(it.sessionId) }
        FFmpegKitConfig.clearSessions()
        currentSession = null
    }

    private fun cleanupResources() {
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
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

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }
}