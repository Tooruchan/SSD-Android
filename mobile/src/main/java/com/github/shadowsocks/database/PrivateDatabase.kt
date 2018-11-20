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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.github.shadowsocks.App.Companion.app
import com.github.shadowsocks.database.migration.RecreateSchemaMigration
import com.github.shadowsocks.utils.Key

//region SSD
@Database(entities = [Profile::class, KeyValuePair::class, Subscription::class], version = 26 + 1)
//endregion
abstract class PrivateDatabase : RoomDatabase() {
    companion object {
        private val instance by lazy {
            Room.databaseBuilder(app, PrivateDatabase::class.java, Key.DB_PROFILE)
                    .addMigrations(Migration26)
                    //region SSD
                    .addMigrations(MigrationSSD1)
                    //endregion
                    .fallbackToDestructiveMigration()
                    .allowMainThreadQueries()
                    .build()
        }

        val profileDao get() = instance.profileDao()
        val kvPairDao get() = instance.keyValuePairDao()
        //region SSD
        val subscriptionDao get() = instance.subscriptionDao()
        //endregion
    }

    //region SSD
    abstract fun subscriptionDao(): Subscription.Dao

    private object MigrationSSD1 : Migration(26, 27) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `Profile` ADD `subscription` INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE `Profile` ADD `inner_id` INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE `Profile` ADD `ratio` REAL NOT NULL DEFAULT -1.0")
            database.execSQL("ALTER TABLE `Profile` ADD `latency` INTEGER NOT NULL DEFAULT -1")
            database.execSQL("CREATE TABLE `tmp` " +
                    "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`airport` TEXT NOT NULL, " +
                    "`port` INTEGER NOT NULL, " +
                    "`encryption` TEXT NOT NULL, " +
                    "`password` TEXT NOT NULL, " +
                    "`trafficUsed` REAL NOT NULL, " +
                    "`trafficTotal` REAL NOT NULL, " +
                    "`expiry` TEXT NOT NULL, " +
                    "`url` TEXT NOT NULL, " +
                    "`plugin` TEXT NOT NULL, " +
                    "`pluginOptions` TEXT NOT NULL, " +
                    "`selectedProfileId` INTEGER NOT NULL," +
                    "`proxy` INTEGER NOT NULL)")
            database.execSQL("INSERT INTO `tmp` " +
                    "(`id`, `airport`, `port`, `encryption`, `password`, " +
                    "`trafficUsed`, `trafficTotal`, `expiry`, `url`, " +
                    "`plugin`, `pluginOptions`, `selectedProfileId`,`proxy`) " +
                    " SELECT " +
                    "`id`, `airport`, `port`, `encryption`, `password`, " +
                    "`trafficUsed`, `trafficTotal`, `expiry`, `url`, " +
                    "`plugin`, `pluginOptions`, `selectedProfileId`, 0 as proxy " +
                    " FROM `Subscription`")
            database.execSQL("DROP TABLE `Subscription`")
            database.execSQL("ALTER TABLE `tmp` RENAME TO `Subscription`")
        }
    }
    //endregion

    abstract fun profileDao(): Profile.Dao
    abstract fun keyValuePairDao(): KeyValuePair.Dao

    private object Migration26 : RecreateSchemaMigration(25, 26, "Profile",
            "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT, `host` TEXT NOT NULL, `remotePort` INTEGER NOT NULL, `password` TEXT NOT NULL, `method` TEXT NOT NULL, `route` TEXT NOT NULL, `remoteDns` TEXT NOT NULL, `proxyApps` INTEGER NOT NULL, `bypass` INTEGER NOT NULL, `udpdns` INTEGER NOT NULL, `ipv6` INTEGER NOT NULL, `individual` TEXT NOT NULL, `tx` INTEGER NOT NULL, `rx` INTEGER NOT NULL, `userOrder` INTEGER NOT NULL, `plugin` TEXT)",
            "`id`, `name`, `host`, `remotePort`, `password`, `method`, `route`, `remoteDns`, `proxyApps`, `bypass`, `udpdns`, `ipv6`, `individual`, `tx`, `rx`, `userOrder`, `plugin`") {
        override fun migrate(database: SupportSQLiteDatabase) {
            super.migrate(database)
            PublicDatabase.Migration3.migrate(database)
        }
    }
}
