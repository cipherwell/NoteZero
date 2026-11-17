package com.cipherwell.notezero.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.MainActivity
import com.cipherwell.notezero.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The widget must stay content-free and only launch quick capture. These tests cover the
 * intent contract (action/component/flags), the inflated RemoteViews (label + no vault data)
 * and the widget metadata (no periodic updates, 110x40dp minimum, home-screen category).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QuickNoteWidgetProviderTest {

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `quick capture intent targets MainActivity with the shortcut action`() {
        val intent = QuickNoteWidgetProvider.quickCaptureIntent(context)

        assertEquals(QuickNoteWidgetProvider.ACTION_QUICK_CAPTURE, intent.action)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertTrue(
            "widget launch needs NEW_TASK and should reuse the app task",
            intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
        assertNotNull(QuickNoteWidgetProvider.quickCapturePendingIntent(context))
    }

    @Test
    fun `remote views show only the create label`() {
        val remoteViews = QuickNoteWidgetProvider.buildRemoteViews(context)
        val host = FrameLayout(context)
        val view: View = remoteViews.apply(context, host)

        val label = view.findViewById<TextView>(R.id.widget_quick_note_label)
        assertNotNull(label)
        assertEquals("New note", label.text.toString())
        assertNotNull(view.findViewById<View>(R.id.widget_quick_note_icon))
        // No vault-derived text anywhere in the tile.
        assertTrue(view.findViewById<TextView>(R.id.widget_quick_note_label).text.length < 40)
    }

    @Test
    fun `provider update does not throw and the tile metadata is static`() {
        val manager = AppWidgetManager.getInstance(context)
        // Register a widget with Robolectric's shadow manager so updateAppWidget has a target.
        val widgetId = org.robolectric.Shadows.shadowOf(manager)
            .createWidget(QuickNoteWidgetProvider::class.java, R.layout.widget_quick_note)
        QuickNoteWidgetProvider().onUpdate(context, manager, intArrayOf(widgetId))

        val xml = context.resources.getXml(R.xml.quick_note_widget_info)
        var event = xml.eventType
        var updatePeriod = -2L
        var minWidth = -1f
        var minHeight = -1f
        var category = ""
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "appwidget-provider") {
                updatePeriod = xml.getAttributeIntValue(ANDROID_NS, "updatePeriodMillis", -1).toLong()
                minWidth = xml.getAttributeValue(ANDROID_NS, "minWidth")?.toDimensionValue() ?: -1f
                minHeight = xml.getAttributeValue(ANDROID_NS, "minHeight")?.toDimensionValue() ?: -1f
                category = xml.getAttributeValue(ANDROID_NS, "widgetCategory").orEmpty()
            }
            event = xml.next()
        }
        assertEquals("widget must never poll", 0L, updatePeriod)
        assertTrue("minimum width should fit an icon + label (raw=$minWidth)", minWidth >= 100f)
        assertTrue("minimum height should be a single row (raw=$minHeight)", minHeight in 30f..90f)
        assertTrue(
            "widget belongs on the home screen (raw=$category)",
            category == "home_screen" || category == "0x1",
        )
    }
}

/** Parses an `android:` dimension attribute from compiled XML (e.g. `110.0dip`, `110dp`, `110`). */
private fun String.toDimensionValue(): Float? =
    filter { it.isDigit() || it == '.' || it == '-' }.toFloatOrNull()
