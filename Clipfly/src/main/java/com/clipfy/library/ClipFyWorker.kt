package com.clipfy.library

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow


object ClipFyWorker {

    private val _processingStateFlow = MutableStateFlow<ProcessingState?>(null)
    val processingStateFlow: Flow<ProcessingState?> = _processingStateFlow.asStateFlow()

    private val _progressFlow = MutableStateFlow<Pair<Float, Int>?>(null)
    val progressFlow: Flow<Pair<Float, Int>?> = _progressFlow.asStateFlow()

    fun processVideos(
        context: Context,
        commands: List<String>,
        secondCommands: List<String>,
        videoPaths: List<String>,
        formats: List<String>,
        durations: List<Long>,
        folder: String,
        notificationIcon: Int,
        notificationTitle: String
    ): Operation {
        resetFlow()
        return MediaWorker.enqueue(
            context = context,
            commands = commands,
            secondCommands = secondCommands,
            videoPaths = videoPaths,
            formats = formats,
            durations = durations,
            folder = folder,
            notificationIcon = notificationIcon,
            notificationTitle = notificationTitle
        )
    }

    fun processImages(
        context: Context,
        commands: List<String>,
        imagePaths: List<String>,
        formats: List<String>,
        folder: String,
        notificationIcon: Int,
        notificationTitle: String
    ): Operation {
        resetFlow()
        return ImageWorker.enqueue(
            context = context,
            commands = commands,
            imagePaths = imagePaths,
            formats = formats,
            folder = folder,
            notificationIcon = notificationIcon,
            notificationTitle = notificationTitle,
        )
    }

    fun cancelVideoProcessing(context: Context) {
        MediaWorker.cancel(context)
    }

    fun cancelImageProcessing(context: Context) {
        ImageWorker.cancel(context)
    }

    fun emitState(state: ProcessingState) {
        _processingStateFlow.value = state
    }

    fun emitProgress(progress: Float, index: Int) {
        _progressFlow.value = progress to index
    }

    fun resetFlow() {
        _progressFlow.value = null
        _processingStateFlow.value = null
    }

    sealed class ProcessingState {
        data class Processed(val paths: ArrayList<String>) : ProcessingState()
        data class Completed(val paths: ArrayList<String>) : ProcessingState()
        data class Error(val message: String, val ffmpegLogs: String? = null) : ProcessingState()
    }
}