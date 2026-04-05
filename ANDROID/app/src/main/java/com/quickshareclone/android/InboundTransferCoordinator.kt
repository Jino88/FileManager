package com.quickshareclone.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object InboundTransferCoordinator {
    private val pendingApprovals = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val approvedOffers = ConcurrentHashMap<String, Int>()
    private val offerStatuses = ConcurrentHashMap<String, String>()
    private val batchOfferCounts = ConcurrentHashMap<String, Int>()
    private val _prompt = MutableStateFlow<InboundTransferPrompt?>(null)
    val prompt: StateFlow<InboundTransferPrompt?> = _prompt.asStateFlow()

    fun requestApproval(fileName: String, fileSizeBytes: Long): CompletableDeferred<Boolean> {
        val prompt = InboundTransferPrompt(
            requestId = UUID.randomUUID().toString(),
            fileName = fileName,
            fileSizeBytes = fileSizeBytes,
            fileCount = 1,
            totalSizeBytes = fileSizeBytes
        )
        val deferred = CompletableDeferred<Boolean>()
        pendingApprovals[prompt.requestId] = deferred
        _prompt.value = prompt
        return deferred
    }

    fun requestBatchApproval(
        offerId: String,
        fileCount: Int,
        totalSizeBytes: Long,
        fileNames: List<String>
    ): CompletableDeferred<Boolean> {
        val prompt = InboundTransferPrompt(
            requestId = offerId,
            fileName = fileNames.firstOrNull().orEmpty(),
            fileSizeBytes = totalSizeBytes,
            fileCount = fileCount,
            totalSizeBytes = totalSizeBytes,
            previewNames = fileNames.take(3)
        )
        val deferred = CompletableDeferred<Boolean>()
        pendingApprovals[prompt.requestId] = deferred
        offerStatuses[offerId] = "pending"
        batchOfferCounts[offerId] = fileCount
        _prompt.value = prompt
        return deferred
    }

    fun approve(requestId: String) {
        val batchCount = batchOfferCounts.remove(requestId)
        if (batchCount != null && batchCount > 0) {
            approvedOffers[requestId] = batchCount
            offerStatuses[requestId] = "approved"
        }
        pendingApprovals.remove(requestId)?.complete(true)
        clear(requestId)
    }

    fun decline(requestId: String) {
        batchOfferCounts.remove(requestId)
        offerStatuses[requestId] = "declined"
        pendingApprovals.remove(requestId)?.complete(false)
        clear(requestId)
    }

    private fun clear(requestId: String) {
        if (_prompt.value?.requestId == requestId) {
            _prompt.value = null
        }
    }

    fun consumeApprovedOffer(offerId: String): Boolean {
        val remaining = approvedOffers[offerId] ?: return false
        return if (remaining <= 1) {
            approvedOffers.remove(offerId)
            offerStatuses.remove(offerId)
            true
        } else {
            approvedOffers[offerId] = remaining - 1
            true
        }
    }

    fun getOfferStatus(offerId: String): String = offerStatuses[offerId] ?: "unknown"
}

data class InboundTransferPrompt(
    val requestId: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val fileCount: Int,
    val totalSizeBytes: Long,
    val previewNames: List<String> = emptyList()
)
