/*
 * Copyright (C) 2025
 *
 * This file is part of QUIK.
 *
 * QUIK is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QUIK is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QUIK.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.repository.SyncRepository
import io.realm.Realm
import io.realm.Sort
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class CatchUpMessagesWorker(appContext: Context, workerParams: WorkerParameters)
    : Worker(appContext, workerParams) {
    companion object {
        private const val WORK_NAME = "CatchUpMessagesWorker"
        private const val MAX_CATCH_UP_MESSAGES = 50
        private const val BACKOFF_DELAY_SECONDS = 30L

        fun register(context: Context) {
            val request = OneTimeWorkRequestBuilder<CatchUpMessagesWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }

    @Inject lateinit var syncRepo: SyncRepository

    override fun doWork(): Result {
        Timber.v("catch-up worker started")
        return try {
            syncRepo.syncMessages()
            waitForSyncCompletion()
            enqueueUnreadSms()
            Result.success()
        } catch (error: Exception) {
            Timber.e(error, "catch-up worker failed")
            Result.retry()
        }
    }

    private fun enqueueUnreadSms() {
        val messageIds = Realm.getDefaultInstance().use { realm ->
            realm.where(Message::class.java)
                .equalTo("type", Message.TYPE_SMS)
                .equalTo("read", false)
                .equalTo("seen", false)
                .sort("date", Sort.DESCENDING)
                .findAll()
                .take(MAX_CATCH_UP_MESSAGES)
                .map { it.id }
        }

        if (messageIds.isEmpty()) {
            Timber.v("catch-up worker found no unread sms to enqueue")
            return
        }

        val workManager = WorkManager.getInstance(applicationContext)
        messageIds.forEach { messageId ->
            workManager.enqueue(
                OneTimeWorkRequestBuilder<ReceiveSmsWorker>()
                    .setInputData(workDataOf(ReceiveSmsWorker.INPUT_DATA_KEY_MESSAGE_ID to messageId))
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        ReceiveSmsWorker.BACKOFF_DELAY_SECONDS,
                        TimeUnit.SECONDS
                    )
                    .build()
            )
        }

        Timber.v("catch-up worker enqueued ${messageIds.size} sms work items")
    }

    private fun waitForSyncCompletion() {
        var sawRunning = false
        try {
            syncRepo.syncProgress
                .filter { progress ->
                    when (progress) {
                        is SyncRepository.SyncProgress.Running -> {
                            sawRunning = true
                            false
                        }
                        is SyncRepository.SyncProgress.ParsingEmojis -> {
                            sawRunning = true
                            false
                        }
                        is SyncRepository.SyncProgress.Idle -> sawRunning
                    }
                }
                .timeout(20, TimeUnit.SECONDS)
                .blockingFirst()
        } catch (error: Exception) {
            Timber.w(error, "catch-up worker timed out waiting for sync")
        }
    }
}
