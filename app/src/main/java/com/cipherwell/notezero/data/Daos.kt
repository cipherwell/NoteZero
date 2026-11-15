package com.cipherwell.notezero.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Room access for encrypted items. There is no plaintext query surface beyond the
 * structural columns: search/filtering happens in memory on the decrypted index.
 */
@Dao
interface ItemDao {

    @Query("SELECT * FROM items")
    suspend fun getAll(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ItemEntity>)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM items")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM items")
    suspend fun count(): Int
}

/** Room access for encrypted folders (names/tree live inside the envelope). */
@Dao
interface FolderDao {

    @Query("SELECT * FROM folders")
    suspend fun getAll(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<FolderEntity>)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM folders")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM folders")
    suspend fun count(): Int
}

/**
 * Room access for the singleton vault-meta row. Only key-wrapper blobs and non-secret
 * metadata, never the VMK or any plaintext content.
 */
@Dao
interface VaultMetaDao {

    @Query("SELECT * FROM vault_meta WHERE singleton = 1")
    suspend fun get(): VaultMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: VaultMetaEntity)

    @Query("UPDATE vault_meta SET password_envelope = :passwordEnvelope WHERE singleton = 1")
    suspend fun updatePasswordEnvelope(passwordEnvelope: ByteArray)

    @Query("UPDATE vault_meta SET recovery_envelope = :recoveryEnvelope WHERE singleton = 1")
    suspend fun updateRecoveryEnvelope(recoveryEnvelope: ByteArray)

    @Query(
        "UPDATE vault_meta SET password_envelope = :passwordEnvelope, " +
            "recovery_envelope = :recoveryEnvelope WHERE singleton = 1",
    )
    suspend fun updateEnvelopes(passwordEnvelope: ByteArray, recoveryEnvelope: ByteArray)

    @Query("UPDATE vault_meta SET biometric_enabled = :enabled WHERE singleton = 1")
    suspend fun updateBiometricEnabled(enabled: Boolean)

    @Query("DELETE FROM vault_meta")
    suspend fun deleteAll()
}
