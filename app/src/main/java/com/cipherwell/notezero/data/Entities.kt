package com.cipherwell.notezero.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for one vault item. This is the entire plaintext-on-disk surface of an item:
 * a random id, its kind, the two structural timestamps and the AES-256-GCM envelope.
 *
 * The title, body, tags, folder reference and pinned/favourite/trash flags all live
 * inside [ciphertext] (see [ItemCodec]); there is intentionally no searchable plaintext
 * column. A row cannot be decrypted without the VMK, and its AAD is bound to id + kind
 * (`CryptoAad.item`) so swapping ciphertext between rows fails authentication.
 *
 * [toString] is redacted so a stray log/exception dump can never print envelope bytes.
 */
@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "ciphertext")
    val ciphertext: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ItemEntity) return false
        return id == other.id &&
            kind == other.kind &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt &&
            ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }

    override fun toString(): String =
        "ItemEntity(id=$id, kind=$kind, createdAt=$createdAt, updatedAt=$updatedAt, ciphertext=[REDACTED])"
}

/**
 * Room row for one folder. Only the folder id/created_at are structural; the name, parent
 * reference and sort order live inside [ciphertext] (see [FolderCodec]).
 */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "ciphertext")
    val ciphertext: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FolderEntity) return false
        return id == other.id &&
            createdAt == other.createdAt &&
            ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }

    override fun toString(): String =
        "FolderEntity(id=$id, createdAt=$createdAt, ciphertext=[REDACTED])"
}

/**
 * Singleton row (`singleton = 1`) holding the non-item vault state:
 *  - [vaultId]: random UUID, not secret, lets future sync/backup tooling identify a vault.
 *  - [passwordEnvelope] / [recoveryEnvelope]: [com.cipherwell.notezero.crypto.KeyEnvelope]
 *    blobs wrapping the VMK under the password-derived / recovery-phrase-derived KEK.
 *    Without the credential these are useless; the VMK itself is never stored.
 *  - [schemaVersion], [createdAt], [biometricEnabled]: non-secret metadata for [VaultInfo].
 */
@Entity(tableName = "vault_meta")
data class VaultMetaEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton")
    val singleton: Int = SINGLETON_ID,
    @ColumnInfo(name = "vault_id")
    val vaultId: String,
    @ColumnInfo(name = "password_envelope")
    val passwordEnvelope: ByteArray,
    @ColumnInfo(name = "recovery_envelope")
    val recoveryEnvelope: ByteArray,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "biometric_enabled")
    val biometricEnabled: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultMetaEntity) return false
        return singleton == other.singleton &&
            vaultId == other.vaultId &&
            schemaVersion == other.schemaVersion &&
            createdAt == other.createdAt &&
            biometricEnabled == other.biometricEnabled &&
            passwordEnvelope.contentEquals(other.passwordEnvelope) &&
            recoveryEnvelope.contentEquals(other.recoveryEnvelope)
    }

    override fun hashCode(): Int {
        var result = singleton
        result = 31 * result + vaultId.hashCode()
        result = 31 * result + passwordEnvelope.contentHashCode()
        result = 31 * result + recoveryEnvelope.contentHashCode()
        result = 31 * result + schemaVersion
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + biometricEnabled.hashCode()
        return result
    }

    override fun toString(): String =
        "VaultMetaEntity(vaultId=$vaultId, schemaVersion=$schemaVersion, createdAt=$createdAt, " +
            "biometricEnabled=$biometricEnabled, envelopes=[REDACTED])"

    companion object {
        /** Fixed primary key: the vault has exactly one meta row. */
        const val SINGLETON_ID: Int = 1
    }
}
