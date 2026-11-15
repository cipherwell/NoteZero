package com.cipherwell.notezero

import android.app.Application
import android.content.Context
import com.cipherwell.notezero.data.EncryptedNoteRepository
import com.cipherwell.notezero.data.NoteRepository

/**
 * Application entry point. Owns the single app-scoped [NoteRepository] instance so the
 * vault key/VMK lifetime is tied to the process — never to an Activity (rotation, task
 * restore and recents must not drop or duplicate the unlocked vault).
 */
class NoteZeroApp : Application() {

    val repository: NoteRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RepositoryProvider.create(this)
    }
}

/**
 * Single wiring point for the encrypted repository. Kept separate so instrumented tests
 * and the UI can reason about construction without an Android service locator.
 */
internal object RepositoryProvider {
    fun create(context: Context): NoteRepository = EncryptedNoteRepository(context)
}
