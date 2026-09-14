package com.modnite.cuppa

import android.graphics.Bitmap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

enum class JobStatus {
    PENDING,
    HELD,
    PRINTING,
    COMPLETED,
    FAILED
}

class JobQueueManager(
    private val printManager: UsbPrintManager,
    private val logger: (String) -> Unit,
    private val onQueueChanged: (Int) -> Unit
) {
    data class PrintJob(
        val id: Int,
        val bitmap: Bitmap,
        val name: String,
        var status: JobStatus = JobStatus.PENDING
    )

    private val queue = ConcurrentLinkedQueue<PrintJob>()
    @Volatile
    private var isProcessing = false

    var onQueueJobsChanged: ((List<PrintJob>) -> Unit)? = null

    companion object {
        private val globalJobIdCounter = AtomicInteger(101)

        @JvmStatic
        fun getNextJobId(): Int = globalJobIdCounter.getAndIncrement()
    }

    private fun notifyQueueChanged() {
        val jobs = queue.toList()
        onQueueChanged(jobs.size)
        onQueueJobsChanged?.invoke(jobs)
    }

    @JvmOverloads
    fun addJob(bitmap: Bitmap, name: String, forceHold: Boolean = false, assignedJobId: Int = getNextJobId()) {
        val initialStatus = if (forceHold) JobStatus.HELD else JobStatus.PENDING
        val job = PrintJob(assignedJobId, bitmap, name, initialStatus)
        queue.add(job)
        val modeStr = if (forceHold) "HELD (manual hold enabled)" else "PENDING"
        logger("[QUEUE] Job '$name' (#$assignedJobId) added to queue [$modeStr]. Total in queue: ${queue.size}")
        notifyQueueChanged()
        if (!forceHold) {
            processNextJob()
        }
    }

    private fun processNextJob() {
        synchronized(this) {
            if (isProcessing) return
            val nextJob = queue.find { it.status == JobStatus.PENDING } ?: run {
                notifyQueueChanged()
                return
            }
            isProcessing = true
            nextJob.status = JobStatus.PRINTING
            notifyQueueChanged()

            logger("[QUEUE] Printing job '${nextJob.name}' (#${nextJob.id})...")

            printManager.printBitmapAsync(nextJob.bitmap) { success ->
                synchronized(this@JobQueueManager) {
                    isProcessing = false
                    if (success) {
                        nextJob.status = JobStatus.COMPLETED
                        queue.remove(nextJob)
                        notifyQueueChanged()
                        logger("[QUEUE] Completed job #${nextJob.id}. Remaining in queue: ${queue.size}")
                        if (queue.isNotEmpty()) {
                            processNextJob()
                        }
                    } else {
                        nextJob.status = JobStatus.HELD
                        notifyQueueChanged()
                        logger("[QUEUE] Job #${nextJob.id} HELD (Hardware error / Out of paper). Remaining in queue: ${queue.size}")
                    }
                }
            }
        }
    }

    // Print all jobs is purged for CUPS authenticity.

    fun holdJob(jobId: Int) {
        synchronized(this) {
            val job = queue.find { it.id == jobId }
            if (job != null && job.status == JobStatus.PENDING) {
                job.status = JobStatus.HELD
                logger("[QUEUE] Job #${job.id} held manually.")
                notifyQueueChanged()
            }
        }
    }

    fun releaseJob(jobId: Int) {
        synchronized(this) {
            val job = queue.find { it.id == jobId }
            if (job != null && (job.status == JobStatus.HELD || job.status == JobStatus.FAILED)) {
                job.status = JobStatus.PENDING
                logger("[QUEUE] Job #${job.id} released...")
                notifyQueueChanged()
                processNextJob()
            }
        }
    }

    fun cancelJob(jobId: Int) {
        synchronized(this) {
            val removed = queue.removeAll { it.id == jobId }
            if (removed) {
                logger("[QUEUE] Cancelled job #$jobId.")
                notifyQueueChanged()
            }
        }
    }

    // clearQueue is purged for CUPS authenticity.

    fun getQueuedJobs(): List<PrintJob> = queue.toList()

    fun getQueueSize(): Int = queue.size
}
