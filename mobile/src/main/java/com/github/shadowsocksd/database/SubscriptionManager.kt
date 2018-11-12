package com.github.shadowsocksd.database

import android.database.sqlite.SQLiteCantOpenDatabaseException
import com.github.shadowsocksd.ProfilesFragment
import com.github.shadowsocksd.preference.DataStore
import com.github.shadowsocksd.utils.DirectBoot
import com.github.shadowsocksd.utils.printLog
import java.io.IOException
import java.sql.SQLException

object SubscriptionManager {
    @Throws(SQLException::class)
    fun createSubscription(subscription: Subscription = Subscription()): Subscription {
        subscription.id = PrivateDatabase.subscriptionDao.create(subscription)
        //it cannot use ProfilesFragment.instance?.profilesAdapter?.add()
        //because it empty now
        //it must have subProfiles
        ProfilesFragment.instance?.checkVisible()
        return subscription
    }

    /**
     * Note: It's caller's responsibility to update DirectBoot profile if necessary.
     */
    @Throws(SQLException::class)
    fun updateSubscription(subscription: Subscription) = check(PrivateDatabase.subscriptionDao.update(subscription) == 1)

    @Throws(IOException::class)
    fun getSubscription(id: Long): Subscription? = try {
        PrivateDatabase.subscriptionDao[id]
    } catch (ex: SQLiteCantOpenDatabaseException) {
        throw IOException(ex)
    } catch (ex: SQLException) {
        printLog(ex)
        null
    }

    @Throws(SQLException::class)
    fun delSubscriptionWithProfiles(id: Long) {
        check(PrivateDatabase.subscriptionDao.delete(id) == 1)
        PrivateDatabase.profileDao.deleteSubscription(id)
        //ProfilesFragment.instance?.subscriptionsAdapter?.removeSubscriptionId(id)
        ProfilesFragment.instance?.checkVisible()
        if (id ==ProfileManager.getProfile(DataStore.profileId)?.subscription && DataStore.directBootAware) DirectBoot.clean()
    }

    @Throws(IOException::class)
    fun isNotEmpty(): Boolean = try {
        PrivateDatabase.subscriptionDao.isNotEmpty()
    } catch (ex: SQLiteCantOpenDatabaseException) {
        throw IOException(ex)
    } catch (ex: SQLException) {
        printLog(ex)
        false
    }

    @Throws(IOException::class)
    fun getAllSubscriptions(): List<Subscription>? = try {
        PrivateDatabase.subscriptionDao.list()
    } catch (ex: SQLiteCantOpenDatabaseException) {
        throw IOException(ex)
    } catch (ex: SQLException) {
        printLog(ex)
        null
    }
}