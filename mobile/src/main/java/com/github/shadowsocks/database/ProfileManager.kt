/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2017 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks.database

import android.database.sqlite.SQLiteCantOpenDatabaseException
import com.github.shadowsocks.ProfilesFragment
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.utils.DirectBoot
import com.github.shadowsocks.utils.printLog
import java.io.IOException
import java.sql.SQLException

/**
 * SQLExceptions are not caught (and therefore will cause crash) for insert/update transactions
 * to ensure we are in a consistent state.
 */
object ProfileManager {
    @Throws(SQLException::class)
    fun createProfile(profile: Profile = Profile()): Profile {
        profile.id = 0
        profile.userOrder = PrivateDatabase.profileDao.nextOrder() ?: 0
        profile.id = PrivateDatabase.profileDao.create(profile)
        ProfilesFragment.instance?.profilesAdapter?.add(profile)
        //region SSD
        ProfilesFragment.instance?.checkVisible()
        //endregion
        return profile
    }

    /**
     * Note: It's caller's responsibility to update DirectBoot profile if necessary.
     */
    @Throws(SQLException::class)
    fun updateProfile(profile: Profile) = check(PrivateDatabase.profileDao.update(profile) == 1)

    @Throws(IOException::class)
    fun getProfile(id: Long): Profile? = try {
        PrivateDatabase.profileDao[id]
    } catch (ex: SQLiteCantOpenDatabaseException) {
        throw IOException(ex)
    } catch (ex: SQLException) {
        printLog(ex)
        null
    }

    //region SSD
    @Throws(SQLException::class)
    fun createSubscriptionProfile(profile: Profile = Profile()): Profile {
        profile.id = 0
        profile.userOrder = PrivateDatabase.profileDao.nextOrder() ?: 0
        profile.id = PrivateDatabase.profileDao.create(profile)
        return profile
    }

    @Throws(IOException::class)
    fun getSubscription(id:Long):List<Profile>?=try{
        PrivateDatabase.profileDao.getSubscription(id)
    }
    catch (ex: SQLiteCantOpenDatabaseException) {
        throw IOException(ex)
    } catch (ex: SQLException) {
        printLog(ex)
        null
    }

    @Throws(IOException::class)
    fun getAllProfilesWithSubscription(): List<Profile>? = try {
        PrivateDatabase.profileDao.list()
    } catch (ex: SQLiteCantOpenDatabaseException) {
        throw IOException(ex)
    } catch (ex: SQLException) {
        printLog(ex)
        null
    }

    @Throws(IOException::class)
    fun withoutSubscriptionIsNotEmpty(): Boolean = try {
        PrivateDatabase.profileDao.withoutSubscriptionIsNotEmpty()
    } catch (ex: SQLiteCantOpenDatabaseException) {
        throw IOException(ex)
    } catch (ex: SQLException) {
        printLog(ex)
        false
    }
    //endregion

    @Throws(SQLException::class)
    fun delProfile(id: Long) {
        //region SSD
        val subscription= getProfile(id)?.subscription
        //endregion
        check(PrivateDatabase.profileDao.delete(id) == 1)
        ProfilesFragment.instance?.profilesAdapter?.removeId(id)
        //region SSD
        if(subscription!=null&&subscription!=0L){
            if(getSubscription(subscription)?.size==0){
                ProfilesFragment.instance?.subscriptionsAdapter?.removeSubscriptionId(subscription)
            }
            else
            {
                ProfilesFragment.instance?.subscriptionsAdapter?.refreshSubscriptionId(subscription)
            }
        }
        ProfilesFragment.instance?.checkVisible()
        //endregion
        if (id == DataStore.profileId && DataStore.directBootAware) DirectBoot.clean()
    }

    @Throws(IOException::class)
    fun isNotEmpty(): Boolean = try {
        PrivateDatabase.profileDao.isNotEmpty()
    } catch (ex: SQLiteCantOpenDatabaseException) {
        throw IOException(ex)
    } catch (ex: SQLException) {
        printLog(ex)
        false
    }

    @Throws(IOException::class)
    fun getAllProfiles(): List<Profile>? = try {
        //region SSD
        PrivateDatabase.profileDao.getSubscription(0)
        //endregion
    } catch (ex: SQLiteCantOpenDatabaseException) {
        throw IOException(ex)
    } catch (ex: SQLException) {
        printLog(ex)
        null
    }
}
