/*
 *             Twidere - Twitter client for Android
 *
 *  Copyright (C) 2012-2017 Mariotaku Lee <mariotaku.lee@gmail.com>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mariotaku.twidere.promise

import android.app.Application
import android.content.ContentValues
import android.content.SharedPreferences
import android.widget.Toast
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.then
import nl.komponents.kovenant.ui.successUi
import org.mariotaku.kpreferences.get
import org.mariotaku.microblog.library.MicroBlog
import org.mariotaku.microblog.library.mastodon.Mastodon
import org.mariotaku.sqliteqb.library.Expression
import org.mariotaku.twidere.R
import org.mariotaku.twidere.annotation.AccountType
import org.mariotaku.twidere.constant.nameFirstKey
import org.mariotaku.twidere.exception.APINotSupportedException
import org.mariotaku.twidere.extension.get
import org.mariotaku.twidere.extension.model.api.mastodon.toParcelable
import org.mariotaku.twidere.extension.model.api.toParcelable
import org.mariotaku.twidere.extension.model.newMicroBlogInstance
import org.mariotaku.twidere.model.ParcelableUser
import org.mariotaku.twidere.model.UserKey
import org.mariotaku.twidere.provider.TwidereDataStore.*
import org.mariotaku.twidere.util.DataStoreUtils
import org.mariotaku.twidere.util.UserColorNameManager
import org.mariotaku.twidere.util.Utils
import org.mariotaku.twidere.util.dagger.PromisesComponent
import org.mariotaku.twidere.util.lang.ApplicationContextSingletonHolder
import javax.inject.Inject

class MutePromises(private val application: Application) {

    private val profileImageSize: String = application.getString(R.string.profile_image_size)

    @Inject
    lateinit var preferences: SharedPreferences
    @Inject
    lateinit var manager: UserColorNameManager

    init {
        PromisesComponent.get(application).inject(this)
    }

    fun mute(accountKey: UserKey, userKey: UserKey, filterEverywhere: Boolean):
            Promise<ParcelableUser, Exception> = accountTask(application, accountKey) { account ->
        when (account.type) {
            AccountType.TWITTER -> {
                val twitter = account.newMicroBlogInstance(application, MicroBlog::class.java)
                return@accountTask twitter.createMute(userKey.id).toParcelable(account,
                        profileImageSize = profileImageSize)
            }
            AccountType.MASTODON -> {
                val mastodon = account.newMicroBlogInstance(application, Mastodon::class.java)
                mastodon.muteUser(userKey.id)
                return@accountTask mastodon.getAccount(userKey.id).toParcelable(account)
            }
            else -> throw APINotSupportedException("Muting", account.type)
        }
    }.then { user ->
        val resolver = application.contentResolver
        Utils.setLastSeen(application, userKey, -1)
        for (uri in DataStoreUtils.STATUSES_URIS) {
            val where = Expression.and(
                    Expression.equalsArgs(Statuses.ACCOUNT_KEY),
                    Expression.equalsArgs(Statuses.USER_KEY)
            )
            val whereArgs = arrayOf(accountKey.toString(), userKey.toString())
            resolver.delete(uri, where.sql, whereArgs)
        }
        if (!user.is_following) {
            for (uri in DataStoreUtils.ACTIVITIES_URIS) {
                val where = Expression.and(
                        Expression.equalsArgs(Activities.ACCOUNT_KEY),
                        Expression.equalsArgs(Activities.USER_KEY)
                )
                val whereArgs = arrayOf(accountKey.toString(), userKey.toString())
                resolver.delete(uri, where.sql, whereArgs)
            }
        }
        // I bet you don't want to see this user in your auto complete list.
        val values = ContentValues()
        values.put(CachedRelationships.ACCOUNT_KEY, accountKey.toString())
        values.put(CachedRelationships.USER_KEY, userKey.toString())
        values.put(CachedRelationships.MUTING, true)
        resolver.insert(CachedRelationships.CONTENT_URI, values)
        if (filterEverywhere) {
            DataStoreUtils.addToFilter(application, listOf(user), true)
        }
        return@then user
    }.successUi { user ->
        val nameFirst = preferences[nameFirstKey]
        val message = application.getString(R.string.muted_user, manager.getDisplayName(user,
                nameFirst))
        Toast.makeText(application, message, Toast.LENGTH_SHORT).show()
    }.toastOnFail(application)

    companion object : ApplicationContextSingletonHolder<MutePromises>(::MutePromises)
}
