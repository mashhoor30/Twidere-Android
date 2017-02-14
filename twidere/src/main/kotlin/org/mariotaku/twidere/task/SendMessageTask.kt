package org.mariotaku.twidere.task

import android.content.Context
import org.mariotaku.microblog.library.MicroBlog
import org.mariotaku.microblog.library.MicroBlogException
import org.mariotaku.microblog.library.twitter.model.DirectMessage
import org.mariotaku.microblog.library.twitter.model.NewDm
import org.mariotaku.twidere.annotation.AccountType
import org.mariotaku.twidere.extension.model.isOfficial
import org.mariotaku.twidere.extension.model.newMicroBlogInstance
import org.mariotaku.twidere.model.AccountDetails
import org.mariotaku.twidere.model.ParcelableMessageConversation
import org.mariotaku.twidere.model.ParcelableNewMessage
import org.mariotaku.twidere.model.util.ParcelableMessageUtils
import org.mariotaku.twidere.task.GetMessagesTask.Companion.addConversation
import org.mariotaku.twidere.task.GetMessagesTask.Companion.addLocalConversations

/**
 * Created by mariotaku on 2017/2/8.
 */
class SendMessageTask(
        context: Context
) : ExceptionHandlingAbstractTask<ParcelableNewMessage, Unit, MicroBlogException, Unit>(context) {
    override fun onExecute(params: ParcelableNewMessage) {
        val account = params.account
        val microBlog = account.newMicroBlogInstance(context, cls = MicroBlog::class.java)
        val updateData = requestSendMessage(microBlog, account, params)
        GetMessagesTask.storeMessages(context, updateData, account)
    }

    fun requestSendMessage(microBlog: MicroBlog, account: AccountDetails, message: ParcelableNewMessage): GetMessagesTask.DatabaseUpdateData {
        when (account.type) {
            AccountType.TWITTER -> {
                if (account.isOfficial(context)) {
                    return sendTwitterOfficialDM(microBlog, account, message)
                }
            }
            AccountType.FANFOU -> {
                return sendFanfouDM(microBlog, account, message)
            }
        }
        return sendDefaultDM(microBlog, account, message)
    }

    private fun sendTwitterOfficialDM(microBlog: MicroBlog, account: AccountDetails, message: ParcelableNewMessage): GetMessagesTask.DatabaseUpdateData {
        val response = microBlog.sendDm(NewDm().apply {
            val conversationId = message.conversation_id
            if (conversationId != null) {
                setConversationId(conversationId)
            } else {
                setRecipientIds(message.recipient_ids)
            }
            setText(message.text)
        })
        return GetMessagesTask.createDatabaseUpdateData(context, account, response)
    }

    private fun sendFanfouDM(microBlog: MicroBlog, account: AccountDetails, message: ParcelableNewMessage): GetMessagesTask.DatabaseUpdateData {
        val recipientId = message.recipient_ids.singleOrNull() ?: throw MicroBlogException("No recipient")
        val response = microBlog.sendFanfouDirectMessage(recipientId, message.text)
        return createDatabaseUpdateData(account, response)
    }

    private fun sendDefaultDM(microBlog: MicroBlog, account: AccountDetails, message: ParcelableNewMessage): GetMessagesTask.DatabaseUpdateData {
        val recipientId = message.recipient_ids.singleOrNull() ?: throw MicroBlogException("No recipient")
        val response = microBlog.sendDirectMessage(recipientId, message.text)
        return createDatabaseUpdateData(account, response)
    }

    private fun createDatabaseUpdateData(details: AccountDetails, dm: DirectMessage): GetMessagesTask.DatabaseUpdateData {
        val accountKey = details.key
        val conversationIds = setOf(ParcelableMessageUtils.outgoingConversationId(dm.senderId, dm.recipientId))
        val conversations = hashMapOf<String, ParcelableMessageConversation>()
        conversations.addLocalConversations(context, accountKey, conversationIds)
        val message = ParcelableMessageUtils.fromMessage(accountKey, dm, true)
        conversations.addConversation(message.conversation_id, details, message, setOf(dm.sender, dm.recipient))
        return GetMessagesTask.DatabaseUpdateData(conversations.values, listOf(message))
    }
}