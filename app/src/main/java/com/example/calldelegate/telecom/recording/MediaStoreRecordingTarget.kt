package com.example.calldelegate.telecom.recording

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore

class MediaStoreRecordingTarget private constructor(
    private val context: Context,
    val uri: Uri,
    val displayName: String,
    val descriptor: ParcelFileDescriptor,
) {
    fun publish() {
        descriptor.close()
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.IS_PENDING, 0)
        }
        context.contentResolver.update(uri, values, null, null)
    }

    fun discard() {
        runCatching { descriptor.close() }
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    companion object {
        fun create(context: Context, displayName: String): MediaStoreRecordingTarget {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/ogg")
                put(
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MUSIC}/CallDelegate/Recordings",
                )
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = requireNotNull(
                context.contentResolver.insert(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    values,
                ),
            ) { "Unable to create MediaStore recording" }
            val descriptor = context.contentResolver.openFileDescriptor(uri, "rw")
            if (descriptor == null) {
                context.contentResolver.delete(uri, null, null)
                error("Unable to open MediaStore recording")
            }
            return MediaStoreRecordingTarget(
                context = context,
                uri = uri,
                displayName = displayName,
                descriptor = descriptor,
            )
        }
    }
}
