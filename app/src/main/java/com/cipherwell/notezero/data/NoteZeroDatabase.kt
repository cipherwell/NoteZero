package com.cipherwell.notezero.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The single Room database of the vault. It contains no plaintext content: see
 * [ItemEntity], [FolderEntity] and [VaultMetaEntity] for the redacted schema.
 *
 * Migration policy: there is no destructive fallback on purpose — losing the key-wrapper
 * rows silently would destroy a vault. Bump [SCHEMA_VERSION] and add an explicit,
 * non-destructive `Migration` for every schema change.
 */
@Database(
    entities = [ItemEntity::class, FolderEntity::class, VaultMetaEntity::class],
    version = NoteZeroDatabase.SCHEMA_VERSION,
    exportSchema = false,
)
abstract class NoteZeroDatabase : RoomDatabase() {

    abstract fun itemDao(): ItemDao

    abstract fun folderDao(): FolderDao

    abstract fun vaultMetaDao(): VaultMetaDao

    companion object {
        const val SCHEMA_VERSION: Int = 1
        const val DATABASE_NAME: String = "notezero-vault.db"

        /**
         * Production database. Callers pass the application context; Room owns the file and
         * all DAO work happens on Room's own thread pool from IO-dispatched coroutines.
         */
        fun create(context: Context): NoteZeroDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                NoteZeroDatabase::class.java,
                DATABASE_NAME,
            ).build()
    }
}
