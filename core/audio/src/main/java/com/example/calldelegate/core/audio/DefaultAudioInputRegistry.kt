package com.example.calldelegate.core.audio

import com.example.calldelegate.domain.api.AudioInputRegistry
import com.example.calldelegate.domain.api.AudioInputSource
import com.example.calldelegate.domain.model.InputMode

class DefaultAudioInputRegistry(sources: Set<AudioInputSource>) : AudioInputRegistry {
    private val byMode = sources.associateBy { it.mode }
    override fun sourceFor(mode: InputMode): AudioInputSource? = byMode[mode]
}
