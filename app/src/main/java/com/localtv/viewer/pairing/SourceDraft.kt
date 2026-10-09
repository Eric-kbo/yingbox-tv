package com.localtv.viewer.pairing

import com.localtv.viewer.data.*
import java.net.URI

data class SourceDraft(val kind: SourceKind, val address: String, val username: String = "", val password: String = "",
    val name: String = "", val domain: String = "") {
    fun normalized(id: String? = null): Source {
        // Normalize once before deriving a readable name from a validated directory address.
        val value = SourceValidation.normalized(name.ifBlank { "媒体源" }, kind, address, username, password, domain, id)
        val label = name.trim().ifBlank {
            val uri = URI(value.address)
            decodePath(uri.rawPath.orEmpty()).trimEnd('/').substringAfterLast('/').ifBlank { uri.host ?: "媒体源" }.take(60)
        }
        return value.copy(name = label)
    }
}

data class PairingResponse(val success: Boolean, val message: String)

/** CountDownLatch also works on Android 6, unlike CompletableFuture. */
class PairingReply {
    private val latch = java.util.concurrent.CountDownLatch(1)
    private val result = java.util.concurrent.atomic.AtomicReference<PairingResponse?>()
    val isDone get() = result.get() != null
    fun complete(value: PairingResponse) {
        if (result.compareAndSet(null, value)) latch.countDown()
    }
    fun get(timeout: Long, unit: java.util.concurrent.TimeUnit): PairingResponse {
        if (!latch.await(timeout, unit)) throw java.util.concurrent.TimeoutException()
        return requireNotNull(result.get())
    }
    companion object {
        fun completed(value: PairingResponse) = PairingReply().apply { complete(value) }
    }
}
