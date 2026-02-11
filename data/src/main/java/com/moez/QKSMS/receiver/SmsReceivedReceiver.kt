/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony.Sms
import androidx.work.BackoffPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.android.AndroidInjection
import dev.octoshrimpy.quik.worker.PersistSmsWorker
import dev.octoshrimpy.quik.worker.ReceiveSmsWorker
import timber.log.Timber
import java.util.concurrent.TimeUnit

class SmsReceivedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AndroidInjection.inject(this, context)

        val messages = Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) {
            Timber.v("onReceive() empty sms payload")
            return
        }

        val address = messages[0].displayOriginatingAddress?.takeIf { it.isNotBlank() }
        val body = messages.mapNotNull { it.displayMessageBody }.joinToString("")

        if (address == null || body.isBlank()) {
            Timber.w("onReceive() sms failed preflight validation")
            return
        }

        val subscriptionId = intent.extras?.getInt("subscription", -1) ?: -1
        val sentTime = messages[0].timestampMillis

        Timber.v("onReceive() new sms")
        val persistRequest = OneTimeWorkRequestBuilder<PersistSmsWorker>()
            .setInputData(
                workDataOf(
                    PersistSmsWorker.INPUT_DATA_ADDRESS to address,
                    PersistSmsWorker.INPUT_DATA_BODY to body,
                    PersistSmsWorker.INPUT_DATA_SUB_ID to subscriptionId,
                    PersistSmsWorker.INPUT_DATA_SENT_TIME to sentTime
                )
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                PersistSmsWorker.BACKOFF_DELAY_SECONDS,
                TimeUnit.SECONDS
            )
            .build()

        val receiveRequest = OneTimeWorkRequestBuilder<ReceiveSmsWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                ReceiveSmsWorker.BACKOFF_DELAY_SECONDS,
                TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(context)
            .beginWith(persistRequest)
            .then(receiveRequest)
            .enqueue()
    }

}
