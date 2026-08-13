package com.example.calldelegate.data.local

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.PrivateFileCipher

/** MVP seam: files remain in app-private storage but are not encrypted in version 0.1. */
class NoOpPrivateFileCipher : PrivateFileCipher {
    override suspend fun encrypt(path: String) = AppResult.Success(path)
    override suspend fun decrypt(path: String) = AppResult.Success(path)
}
