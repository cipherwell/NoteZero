package com.cipherwell.notezero.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import com.cipherwell.notezero.model.ThemeMode
import com.cipherwell.notezero.ui.theme.NoteZeroTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule

/**
 * Shared Robolectric + Compose scaffolding.
 *
 * The main dispatcher is replaced with an unconfined test dispatcher so ViewModel
 * coroutines (which run on `Dispatchers.Main.immediate`) execute deterministically at
 * the call site — no sleeps, no flaky polling, matching the "keep tests deterministic"
 * integration rule.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class UiTestBase {

    @get:Rule
    val composeRule: ComposeContentTestRule = createComposeRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    /** Deterministic theme wrapper: dark scheme unless a test opts into light. */
    protected fun setThemedContent(
        dark: Boolean = true,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            NoteZeroTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                content()
            }
        }
    }
}
