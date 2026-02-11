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
package dev.octoshrimpy.quik.interactor

import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.util.Preferences
import io.reactivex.Flowable
import javax.inject.Inject

class SyncMissedMessages @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val prefs: Preferences,
    private val updateBadge: UpdateBadge
) : Interactor<Unit>() {

    sealed class Result {
        data class Success(val updated: Boolean) : Result()
        data class Failure(val error: Throwable) : Result()
    }

    override fun buildObservable(params: Unit): Flowable<*> = buildObservableWithResult()

    fun buildObservableWithResult(): Flowable<Result> {
        return Flowable.fromCallable<Result> {
            val beforeUnreadCount = messageRepo.getUnreadCount()
            val beforeConversations = conversationRepo
                .getConversationsSnapshot(prefs.unreadAtTop.get())
                .associate { conversation -> conversation.id to conversation.lastMessage?.id }

            val threadIds = messageRepo.getMessageThreadIds()
            if (threadIds.isNotEmpty()) {
                conversationRepo.updateConversations(threadIds)
            }

            val afterUnreadCount = messageRepo.getUnreadCount()
            val afterConversations = conversationRepo
                .getConversationsSnapshot(prefs.unreadAtTop.get())
                .associate { conversation -> conversation.id to conversation.lastMessage?.id }

            val updated = beforeUnreadCount != afterUnreadCount ||
                beforeConversations != afterConversations
            Result.Success(updated)
        }.onErrorReturn { error -> Result.Failure(error) }
            .flatMap { result: Result ->
                when (result) {
                    is Result.Success -> updateBadge.buildObservable(Unit).map { result as Result }
                    is Result.Failure -> Flowable.just<Result>(result)
                }
            }
    }
}
