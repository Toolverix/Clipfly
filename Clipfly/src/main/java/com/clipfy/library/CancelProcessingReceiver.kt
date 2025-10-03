package com.clipfy.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CancelProcessingReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CANCEL_PROCESSING = "com.clipfy.library.CANCEL_PROCESSING"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_CANCEL_PROCESSING) {
            ClipFyWorker.cancelVideoProcessing(context)
            ClipFyWorker.cancelImageProcessing(context)
        }
    }
}