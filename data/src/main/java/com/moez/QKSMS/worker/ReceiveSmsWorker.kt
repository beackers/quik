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
import java.io.IOException
import javax.inject.Inject

class ReceiveSmsWorker(appContext: Context, workerParams: WorkerParameters)
    : Worker(appContext, workerParams) {
    companion object {
        const val INPUT_DATA_KEY_MESSAGE_ID = "messageId"
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
        Timber.v("started")

        val messageId = inputData.getLong(INPUT_DATA_KEY_MESSAGE_ID, -1)

        return try {
            if (messageId < 0) {
                logEarlyExit("missing_message_id", messageId)
                return Result.failure(inputData)
            }

            val message = messageRepo.getMessage(messageId)
            if (message == null) {
                logEarlyExit("missing_message", messageId)
                return Result.failure(inputData)
            }

            val action = blockingClient.shouldBlock(message.address).blockingGet()

            when {
                ((action is BlockingClient.Action.Block) && prefs.drop.get()) -> {
                    // blocked and 'drop blocked' remove from db and don't continue
                    Timber.v("address is blocked and drop blocked is on. dropped")
                    logEarlyExit("blocked_drop_enabled", messageId, message.threadId)
                    messageRepo.deleteMessages(listOf(message.id))
                    return Result.failure(inputData)
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

            val messageFilterAction =
                filterRepo.isBlocked(message.getText(), message.address, contactsRepo)
            if (messageFilterAction) {
                Timber.v("message dropped based on content filters")
                logEarlyExit("content_filtered", messageId, message.threadId)
                messageRepo.deleteMessages(listOf(message.id))
                return Result.failure(inputData)
            }

            // update and fetch conversation
            conversationRepo.updateConversations(listOf(message.threadId))
            val conversation = conversationRepo.getOrCreateConversation(message.threadId)
            if (conversation == null) {
                logEarlyExit("missing_conversation", messageId, message.threadId)
                return Result.failure(inputData)
            }

            // don't notify (continue) for blocked conversations
            if (conversation.blocked) {
                Timber.v("no notifications for blocked")
                logEarlyExit("conversation_blocked", messageId, message.threadId)
                return Result.failure(inputData)
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

            Result.success()
        } catch (e: IOException) {
            Timber.e(e, "receive_sms_failed reason=io_exception")
            logEarlyExit("io_exception", messageId)
            Result.retry()
        } catch (e: Exception) {
            Timber.e(e, "receive_sms_failed reason=unexpected_exception")
            logEarlyExit("unexpected_exception", messageId)
            Result.failure(inputData)
        }
    }

    override fun getForegroundInfo() = ForegroundInfo(
        0,
        notificationManager.getForegroundNotificationForWorkersOnOlderAndroids()
    )

    private fun logEarlyExit(reason: String, messageId: Long, threadId: Long? = null) {
        Timber.i(
            "receive_sms_early_exit reason=%s messageId=%d threadId=%s",
            reason,
            messageId,
            threadId?.toString() ?: "unknown"
        )
    }
}
