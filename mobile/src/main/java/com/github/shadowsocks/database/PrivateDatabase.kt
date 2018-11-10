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

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.github.shadowsocks.App.Companion.app
import com.github.shadowsocks.database.migration.RecreateSchemaMigration
import com.github.shadowsocks.utils.Key

//region SSD
@Database(entities = [Profile::class, KeyValuePair::class,Subscription::class], version = 26)
//endregion
abstract class PrivateDatabase : RoomDatabase() {
    companion object {
        private val instance by lazy {
            Room.databaseBuilder(app, PrivateDatabase::class.java, Key.DB_PROFILE)
                    .addMigrations(
                            Migration26
                    )
                    .fallbackToDestructiveMigration()
                    .allowMainThreadQueries()
                    .build()
        }

        val profileDao get() = instance.profileDao()
        val kvPairDao get() = instance.keyValuePairDao()
        //region SSD
        val subscriptionDao get()= instance.subscriptionDao()
        //endregion
    }
    abstract fun profileDao(): Profile.Dao
    abstract fun keyValuePairDao(): KeyValuePair.Dao
    //region SSD
    abstract fun subscriptionDao(): Subscription.Dao
    //endregion

    private object Migration26 : RecreateSchemaMigration(25, 26, "Profile",
            //region SSD
            //schema: `subscription` INTEGER NOT NULL, `inner_id` INTEGER, `ratio` REAL NOT NULL
            //keys: `subscription`, ``inner_id`, ratio`
            //endregion
            "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT, `host` TEXT NOT NULL, `remotePort` INTEGER NOT NULL, `password` TEXT NOT NULL, `method` TEXT NOT NULL, `route` TEXT NOT NULL, `remoteDns` TEXT NOT NULL, `proxyApps` INTEGER NOT NULL, `bypass` INTEGER NOT NULL, `udpdns` INTEGER NOT NULL, `ipv6` INTEGER NOT NULL, `individual` TEXT NOT NULL, `tx` INTEGER NOT NULL, `rx` INTEGER NOT NULL, `userOrder` INTEGER NOT NULL, `plugin` TEXT, `subscription` INTEGER NOT NULL, `inner_id` INTEGER, `ratio` REAL NOT NULL)",
            "`id`, `name`, `host`, `remotePort`, `password`, `method`, `route`, `remoteDns`, `proxyApps`, `bypass`, `udpdns`, `ipv6`, `individual`, `tx`, `rx`, `userOrder`, `plugin`, `subscription`, `inner_id`, `ratio`") {
        override fun migrate(database: SupportSQLiteDatabase) {
            super.migrate(database)
            //region SSD
            val migration4=RecreateSchemaMigration(
                    3,4,
                    "Subscription",
                    "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`airport` TEXT, " +
                            "`port` INTEGER NOT NULL, " +
                            "`encryption` TEXT NOT NULL, " +
                            "`password` TEXT NOT NULL, " +
                            "`traffic_used` REAL NOT NULL, " +
                            "`traffic_total` REAL NOT NULL, " +
                            "`expiry` TEXT NOT NULL, " +
                            "`url` TEXT NOT NULL, "+
                            "`plugin` TEXT NOT NULL, "+
                            "`plugin_options` TEXT NOT NULL)",
                    "`id`, `airport`, `port`, `encryption`, `password`, "+
                            "`traffic_used`, `traffic_total`, `expiry`, `url`, "+
                            "`plugin`, `plugin_options`")
            migration4.migrate(database)
            //endregion
            PublicDatabase.Migration3.migrate(database)
        }
    }
}
