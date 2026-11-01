package vibe.tube.feature

import android.app.Activity
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import vibe.tube.feature.login.releaseLoginInput

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LoginInputTest {

    private fun setUp(): Triple<Activity, EditText, EditText> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val page = EditText(activity)
        val other = EditText(activity)
        activity.setContentView(LinearLayout(activity).apply { addView(page); addView(other) })
        return Triple(activity, page, other)
    }

    private fun imm(c: Context) = c.getSystemService(InputMethodManager::class.java)

    @Test
    fun finishingSignInClosesThePagesKeyboardAndLeavesNoFocusBehind() {
        val (activity, page, _) = setUp()
        page.requestFocus()
        imm(activity).showSoftInput(page, 0)
        assertTrue(shadowOf(imm(activity)).isSoftInputVisible)
        assertTrue(releaseLoginInput(page, activity.window.decorView))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(shadowOf(imm(activity)).isSoftInputVisible)
        assertFalse(page.hasFocus())
        // Nothing can hand focus back to the finished page (e.g. when the window regains focus).
        assertFalse(page.requestFocus())
    }

    @Test
    fun aKeyboardOwnedByAnotherFieldIsLeftAlone() {
        val (activity, page, other) = setUp()
        other.requestFocus()
        imm(activity).showSoftInput(other, 0)
        assertFalse(releaseLoginInput(page, activity.window.decorView))
        assertTrue(shadowOf(imm(activity)).isSoftInputVisible)
        assertTrue(other.hasFocus())
    }

    @Test
    fun aPageAlreadyGoneIsNotAnError() {
        val (activity, _, _) = setUp()
        assertFalse(releaseLoginInput(null, activity.window.decorView))
    }
}
