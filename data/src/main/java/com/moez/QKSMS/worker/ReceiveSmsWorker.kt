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

import android.app.ActivityManager
import android.content.Context
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.octoshrimpy.quik.blocking.BlockingClient
import dev.octoshrimpy.quik.interactor.UpdateBadge
import dev.octoshrimpy.quik.manager.NotificationManager
import dev.octoshrimpy.quik.manager.ShortcutManager
import dev.octoshrimpy.quik.repository.ContactRepository
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageContentFilterRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.util.Preferences
import timber.log.Timber
import javax.inject.Inject

class ReceiveSmsWorker(appContext: Context, workerParams: WorkerParameters)
    : Worker(appContext, workerParams) {
    companion object {
        const val INPUT_DATA_KEY_MESSAGE_ID = "messageId"
    }

    private enum class ExitReason(val value: String) {
        SUCCESS("success"),
        FILTERED("filtered"),
        BLOCKED("blocked"),
        TRANSIENT_ERROR("transient error"),
        PERMANENT_ERROR("permanent error")
    }

    @Inject lateinit var conversationRepo: ConversationRepository
    @Inject lateinit var blockingClient: BlockingClient
    @Inject lateinit var prefs: Preferences
    @Inject lateinit var messageRepo: MessageRepository
    @Inject lateinit var notificationManager: NotificationManager
    @Inject lateinit var updateBadge: UpdateBadge
    @Inject lateinit var shortcutManager: ShortcutManager
    @Inject lateinit var filterRepo: MessageContentFilterRepository
    @Inject lateinit var contactsRepo: ContactRepository

    override fun doWork(): Result {
        val startTimeMs = System.currentTimeMillis()
        val memoryInfo = buildMemoryInfo()
        Timber.i(
            "worker_start name=ReceiveSmsWorker timestampMs=$startTimeMs " +
                "memoryClass=${memoryInfo.memoryClass} " +
                "largeMemoryClass=${memoryInfo.largeMemoryClass} " +
                "lowRam=${memoryInfo.isLowRamDevice} " +
                "ramTier=${memoryInfo.ramTier}"
        )

        val messageId = inputData.getLong(INPUT_DATA_KEY_MESSAGE_ID, -1)
        if (messageId < 0) {
            Timber.v("failed. message id was $messageId")
            return finishWork(Result.failure(inputData), startTimeMs, ExitReason.PERMANENT_ERROR)
        }

        val message = messageRepo.getMessage(messageId)
            ?: return finishWork(Result.failure(inputData), startTimeMs, ExitReason.PERMANENT_ERROR)

        val action = blockingClient.shouldBlock(message.address).blockingGet()

        when {
            ((action is BlockingClient.Action.Block) && prefs.drop.get()) -> {
                // blocked and 'drop blocked' remove from db and don't continue
                Timber.v("address is blocked and drop blocked is on. dropped")
                messageRepo.deleteMessages(listOf(message.id))
                return finishWork(Result.failure(inputData), startTimeMs, ExitReason.BLOCKED)
            }

            action is BlockingClient.Action.Block -> {
                // blocked
                Timber.v("address is blocked")
                messageRepo.markRead(listOf(message.threadId))
                conversationRepo.markBlocked(
                    listOf(message.threadId),
                    prefs.blockingManager.get(),
                    action.reason
                )
            }

            action is BlockingClient.Action.Unblock -> {
                // unblock
                Timber.v("unblock conversation if blocked")
                conversationRepo.markUnblocked(message.threadId)
            }
        }

        val messageFilterAction = filterRepo.isBlocked(message.getText(), message.address, contactsRepo)
        if (messageFilterAction) {
            Timber.v("message dropped based on content filters")
            messageRepo.deleteMessages(listOf(message.id))
            return finishWork(Result.failure(inputData), startTimeMs, ExitReason.FILTERED)
        }

        // update and fetch conversation
        conversationRepo.updateConversations(listOf(message.threadId))
        val conversation = conversationRepo.getOrCreateConversation(message.threadId)
            ?: return finishWork(Result.failure(inputData), startTimeMs, ExitReason.TRANSIENT_ERROR)

        // don't notify (continue) for blocked conversations
        if (conversation.blocked) {
            Timber.v("no notifications for blocked")
            return finishWork(Result.failure(inputData), startTimeMs, ExitReason.BLOCKED)
        }

        // unarchive conversation if necessary
        if (conversation.archived) {
            Timber.v("conversation unarchived")
            conversationRepo.markUnarchived(listOf(conversation.id))
        }

        // update/create notification
        Timber.v("update/create notification")
        notificationManager.update(conversation.id)

        // update shortcuts
        Timber.v("update shortcuts")
        shortcutManager.updateShortcuts()
        shortcutManager.reportShortcutUsed(conversation.id)

        // update the badge and widget
        Timber.v("update badge and widget")
        updateBadge.execute(Unit)

        Timber.v("finished")

        return finishWork(Result.success(), startTimeMs, ExitReason.SUCCESS)
    }

    private fun finishWork(result: Result, startTimeMs: Long, exitReason: ExitReason): Result {
        val endTimeMs = System.currentTimeMillis()
        val memoryInfo = buildMemoryInfo()
        Timber.i(
            "worker_end name=ReceiveSmsWorker timestampMs=$endTimeMs durationMs=${endTimeMs - startTimeMs} " +
                "exitReason=${exitReason.value} " +
                "memoryClass=${memoryInfo.memoryClass} " +
                "largeMemoryClass=${memoryInfo.largeMemoryClass} " +
                "lowRam=${memoryInfo.isLowRamDevice} " +
                "ramTier=${memoryInfo.ramTier}"
        )
        return result
    }

    private data class MemoryInfo(
        val memoryClass: Int,
        val largeMemoryClass: Int,
        val isLowRamDevice: Boolean,
        val ramTier: String
    )

    private fun buildMemoryInfo(): MemoryInfo {
        val activityManager =
            applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryClass = activityManager.memoryClass
        val largeMemoryClass = activityManager.largeMemoryClass
        val isLowRamDevice = activityManager.isLowRamDevice
        val ramTier = when {
            isLowRamDevice || memoryClass <= 128 -> "low"
            memoryClass <= 256 -> "medium"
            else -> "high"
        }
        return MemoryInfo(memoryClass, largeMemoryClass, isLowRamDevice, ramTier)
    }

    override fun getForegroundInfo() = ForegroundInfo(
        0,
        notificationManager.getForegroundNotificationForWorkersOnOlderAndroids()
    )

}
