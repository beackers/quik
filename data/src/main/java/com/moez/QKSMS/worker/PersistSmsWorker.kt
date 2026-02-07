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
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.octoshrimpy.quik.repository.MessageRepository
import timber.log.Timber
import javax.inject.Inject

class PersistSmsWorker(appContext: Context, workerParams: WorkerParameters)
    : Worker(appContext, workerParams) {
    companion object {
        const val INPUT_DATA_ADDRESS = "address"
        const val INPUT_DATA_BODY = "body"
        const val INPUT_DATA_SUB_ID = "subId"
        const val INPUT_DATA_SENT_TIME = "sentTime"
        const val BACKOFF_DELAY_SECONDS = 30L
    }

    @Inject lateinit var messageRepo: MessageRepository

    override fun doWork(): Result {
        val address = inputData.getString(INPUT_DATA_ADDRESS)?.takeIf { it.isNotBlank() }
            ?: return Result.failure()
        val body = inputData.getString(INPUT_DATA_BODY)?.takeIf { it.isNotBlank() }
            ?: return Result.failure()
        val subId = inputData.getInt(INPUT_DATA_SUB_ID, -1)
        val sentTime = inputData.getLong(INPUT_DATA_SENT_TIME, 0L)
        if (sentTime <= 0L) {
            return Result.failure()
        }

        return try {
            val messageId = messageRepo.insertReceivedSms(subId, address, body, sentTime).id
            Result.success(workDataOf(ReceiveSmsWorker.INPUT_DATA_KEY_MESSAGE_ID to messageId))
        } catch (error: Exception) {
            Timber.e(error, "persist sms worker failed")
            Result.retry()
        }
    }
}
