package com.cipherwell.notezero.ui.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

/** The SAF seam the UI calls before the system picker is launched. */
interface BackupFilePicker {
    /** Launch SAF `CreateDocument` with this suggested file name. */
    fun createDocument(suggestedName: String)

    /** Launch SAF `OpenDocument` for a `content://` backup file. */
    fun openDocument()
}

/**
 * Activity-scoped owner of the [BackupFlowController].
 *
 * It must outlive the Settings screen: launching SAF backgrounds the Activity and the repair
 * path starts with the vault possibly LOCKED, which swaps the whole Settings subtree for the
 * unlock screen. A `remember`ed controller would be disposed with it and the user's chosen
 * file would be lost, so the pending mode + uri live in this ViewModel instead.
 *
 * It is also the wire between the repository lock state and the controller: when the vault
 * unlocks, [BackupFlowController.onVaultUnlocked] resumes an operation that the idle timeout
 * interrupted.
 */
class BackupFlowViewModel(
    repository: NoteRepository,
    openOut: (Uri) -> OutputStream?,
    openIn: (Uri) -> InputStream?,
    clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /** Exposed for the UI (and tests); all operations go through it. */
    val controller = BackupFlowController(
        repository = repository,
        openOut = openOut,
        openIn = openIn,
        clock = clock,
        // The viewModelScope survives a SettingsScreen unmount (lock) and config changes, so
        // a pending resume still completes after the user unlocks.
        scope = viewModelScope,
    )

    val state: StateFlow<BackupUiState> = controller.state

    init {
        viewModelScope.launch {
            repository.status.collect { status ->
                if (status == VaultStatus.UNLOCKED) controller.onVaultUnlocked()
            }
        }
    }

    /**
     * Arms the external-activity deferral, then launches SAF `CreateDocument`.
     * Returns false when the flow could not start (locked/empty vault, picker unavailable).
     */
    fun requestExport(mode: BackupMode, password: CharArray?, picker: BackupFilePicker?): Boolean {
        val target = picker ?: return false
        if (!controller.startExport(mode, password)) return false
        return launchPicker(target) { it.createDocument(controller.suggestedFileName()) }
    }

    /** Arms the external-activity deferral, then launches SAF `OpenDocument`. */
    fun requestImport(picker: BackupFilePicker?): Boolean {
        val target = picker ?: return false
        if (!controller.startImport()) return false
        return launchPicker(target) { it.openDocument() }
    }

    /** Result from either SAF launcher; null means the user cancelled. */
    fun onPickerResult(uri: Uri?) {
        if (uri == null) controller.onLocationCancelled() else controller.onLocationChosen(uri)
    }

    fun reset() = controller.reset()

    override fun onCleared() {
        controller.close()
    }

    private inline fun launchPicker(
        target: BackupFilePicker,
        action: (BackupFilePicker) -> Unit,
    ): Boolean = try {
        action(target)
        true
    } catch (t: Throwable) {
        // No SAF handler / registry unavailable: settle the repository's external bracket.
        controller.onPickerLaunchFailed()
        false
    }

    companion object {
        /** Production factory: resolves SAF streams through the application ContentResolver. */
        fun factory(repository: NoteRepository, context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext ?: context
            val resolver = appContext.contentResolver
            return viewModelFactory {
                initializer {
                    BackupFlowViewModel(
                        repository = repository,
                        openOut = { uri ->
                            // "wt" truncates so a re-export never leaves stale trailing bytes;
                            // some providers only support the default "w" mode.
                            runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
                                ?: runCatching { resolver.openOutputStream(uri) }.getOrNull()
                        },
                        openIn = { uri -> runCatching { resolver.openInputStream(uri) }.getOrNull() },
                    )
                }
            }
        }
    }
}
