package com.cipherwell.notezero.ui.code

/**
 * Stable Compose test tags for the code workspace. The E2E suite (and Robolectric
 * semantics tests) address these strings only, so they must never be renamed or reused
 * for a different node. Contract: docs/CONTRACTS-PHASE3.md section C.
 *
 * Security: tags are structural, never derived from file names or content.
 */
object CodeTags {
    // ---- Workspace / tree ---------------------------------------------------
    const val WORKSPACE = "code_workspace"
    const val BACK = "code_back"
    const val FILE_TREE = "code_file_tree"
    const val NEW_FILE = "code_new_file"
    const val NEW_FOLDER = "code_new_folder"
    const val FILE_ROW = "code_file_row"
    const val FOLDER_ROW = "code_folder_row"
    const val EMPTY_STATE = "code_empty_state"
    /** Shown when locked+unverified code files/folders are hidden from the workspace. */
    const val LOCKED_HIDDEN = "code_locked_hidden"
    const val FILE_MORE = "code_file_more"
    const val FOLDER_MORE = "code_folder_more"

    // ---- Editor chrome ------------------------------------------------------
    const val EDITOR = "code_editor"
    const val LINE_NUMBERS = "code_line_numbers"
    const val SAVE_STATE = "code_save_state"
    const val STATUS = "code_status"
    const val FIND_TOGGLE = "code_find_toggle"
    const val FIND_QUERY = "code_find_query"
    const val FIND_NEXT = "code_find_next"
    const val FIND_PREV = "code_find_prev"
    const val FIND_CASE = "code_find_case"
    const val FIND_MATCH_COUNT = "code_find_match_count"
    const val REPLACE_FIELD = "code_replace_field"
    const val REPLACE = "code_replace"
    const val REPLACE_ALL = "code_replace_all"
    const val GOTO_LINE = "code_goto_line"
    const val FONT_INCREASE = "code_font_increase"
    const val FONT_DECREASE = "code_font_decrease"
    const val WRAP_TOGGLE = "code_wrap_toggle"
    const val PREVIEW_TOGGLE = "code_preview_toggle"
    const val PREVIEW = "code_markdown_preview"
    const val TOO_LARGE = "code_too_large"
    /** Shown when a lock/re-arm dropped the draft: the source and history were cleared. */
    const val SESSION_CLEARED = "code_session_cleared"

    // ---- Editor extra states ------------------------------------------------
    const val CONFLICT_BANNER = "code_conflict_banner"
    const val CONFLICT_KEEP = "code_conflict_keep"
    const val CONFLICT_RELOAD = "code_conflict_reload"
    const val UNDO = "code_undo"
    const val REDO = "code_redo"
    const val MORE = "code_more"

    // ---- Workspace search ---------------------------------------------------
    const val SEARCH_TOGGLE = "code_search_toggle"
    const val SEARCH_QUERY = "code_search_query"
    const val SEARCH_CASE = "code_search_case"
    const val SEARCH_RESULTS = "code_search_results"
    const val SEARCH_RESULT_ROW = "code_search_result_row"
    const val SEARCH_STATUS = "code_search_status"

    // ---- Copy / export / indentation ----------------------------------------
    const val COPY = "code_copy"
    const val EXPORT = "code_export"
    const val EXPORT_CONFIRM = "code_export_confirm"
    const val EXPORT_CANCEL = "code_export_cancel"
    const val EXPORT_DIALOG = "code_export_dialog"
    const val INDENT_INCREASE = "code_indent_increase"
    const val INDENT_DECREASE = "code_indent_decrease"

    // ---- Shared dialogs -----------------------------------------------------
    /** Shared by the create-file, create-folder, rename and go-to-line dialogs. */
    const val DIALOG_FIELD = "code_dialog_field"
    const val DIALOG_CONFIRM = "code_dialog_confirm"
    const val DIALOG_CANCEL = "code_dialog_cancel"
    const val DIALOG_ERROR = "code_dialog_error"
    const val MOVE_DIALOG = "code_move_dialog"
    const val MOVE_ROOT = "code_move_root"
    const val MOVE_TARGET = "code_move_target"
    const val DELETE_DIALOG = "code_delete_dialog"
}
