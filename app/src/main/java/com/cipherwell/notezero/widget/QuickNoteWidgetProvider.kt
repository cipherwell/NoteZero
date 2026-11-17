package com.cipherwell.notezero.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.cipherwell.notezero.MainActivity
import com.cipherwell.notezero.R

/**
 * Home-screen tile whose only job is to open **quick capture**.
 *
 * Security by design: a home-screen widget is visible while the vault is locked, so this widget
 * never reads the vault, never renders a note title/body/count and never receives content. The
 * whole tile is a single [PendingIntent] that launches [MainActivity] with the existing
 * `SHORTCUT_NEW_NOTE` action; the app's normal handling then shows the unlock screen and creates
 * the note only after a successful unlock (the request is pending, never bypassing the lock).
 *
 * No periodic updates ([R.xml.quick_note_widget_info] `updatePeriodMillis = 0`): the tile is
 * static, so it cannot wake the process or leak timing information. Receiver is not exported
 * (only the system AppWidget service may address it).
 */
class QuickNoteWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { appWidgetId ->
            appWidgetManager.updateAppWidget(appWidgetId, buildRemoteViews(context))
        }
    }

    companion object {
        /** Same action the launcher shortcut sends; handled by MainActivity/AppRoot. */
        const val ACTION_QUICK_CAPTURE = "com.cipherwell.notezero.SHORTCUT_NEW_NOTE"

        /**
         * Intent used by the widget tile. `NEW_TASK` is required from a widget host and
         * `CLEAR_TOP` reuses an existing task instead of stacking activities; the pending
         * request itself survives a locked vault via the shell's pending-request plumbing.
         */
        fun quickCaptureIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_QUICK_CAPTURE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        fun quickCapturePendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                /* requestCode = */ 0,
                quickCaptureIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /** Builds the tile; exposed for the Robolectric provider test. */
        internal fun buildRemoteViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_note)
            views.setOnClickPendingIntent(
                R.id.widget_quick_note_root,
                quickCapturePendingIntent(context),
            )
            views.setContentDescription(
                R.id.widget_quick_note_root,
                context.getString(R.string.widget_quick_note_description),
            )
            return views
        }
    }
}
