package com.localkeep.core.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Runs the 30-second clipboard clear when the alarm [ClipboardHelper.copy] schedules goes off.
 *
 * The intent carries only the clip's random id, never the copied text, and every decision —
 * whether the clip is still ours, whether a later copy or "Clear now" has superseded it — is made
 * by [ClipboardHelper.onClearTimer], exactly as when the in-process timer fires.
 */
class ClipboardClearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val clipId = intent.getStringExtra(ClipboardHelper.EXTRA_ALARM_CLIP_ID) ?: return
        ClipboardHelper.runClearTimer(context.applicationContext ?: context, clipId)
    }
}
