package com.github.shadowsocks.database

import androidx.room.*
import java.io.Serializable

@Entity
class Subscription : Serializable {
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0
    var airport: String = ""
    var port: Int = -1
    var encryption: String = ""
    var password: String = ""

    var trafficUsed: Double = -1.0
    var trafficTotal: Double = -1.0
    var expiry: String = ""
    var url: String = ""

    var plugin: String = ""
    var pluginOptions: String = ""

    var selectedProfileId: Long = 0

    @androidx.room.Dao
    interface Dao {
        @Query("SELECT * FROM `Subscription` WHERE `id` = :id")
        operator fun get(id: Long): Subscription?

        @Query("SELECT * FROM `Subscription`")
        fun list(): List<Subscription>

        @Query("SELECT 1 FROM `Subscription` LIMIT 1")
        fun isNotEmpty(): Boolean

        @Insert
        fun create(value: Subscription): Long

        @Update
        fun update(value: Subscription): Int

        @Query("DELETE FROM `Subscription` WHERE `id` = :id")
        fun delete(id: Long): Int
    }
}