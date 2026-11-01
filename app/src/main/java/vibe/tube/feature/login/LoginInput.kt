package vibe.tube.feature.login

import android.view.View
import android.view.inputmethod.InputMethodManager

internal fun releaseLoginInput(page: View?, window: View): Boolean {
    page ?: return false
    val imm = window.context.getSystemService(InputMethodManager::class.java) ?: return false
    val owns = page.hasFocus() || imm.isActive(page)
    if (!owns) return false
    
    (page.windowToken ?: window.rootView.windowToken)?.let { imm.hideSoftInputFromWindow(it, 0) }

    page.isFocusable = false
    page.isFocusableInTouchMode = false
    (page as? android.view.ViewGroup)?.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
    page.clearFocus()
    return true
}
