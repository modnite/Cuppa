package com.cuppa.app.ui.util

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch

/**
 * A drop-in replacement for [Modifier.horizontalScroll] that also works with a plain mouse wheel.
 *
 * Android's horizontalScroll only reacts to a pointer's native horizontal scroll axis, which
 * virtually no physical mouse reports (only some trackpads/horizontal wheels do) — a plain
 * vertical wheel notch does nothing over a horizontal-only row. This matters here specifically
 * because Cuppa is meant to be usable in Samsung DeX / desktop mode, where mouse-driven
 * interaction is the norm, not touch. Remaps a vertical wheel delta onto the horizontal axis so
 * a normal mouse can actually scroll these rows.
 */
@Composable
fun Modifier.horizontalScrollWithWheel(state: ScrollState): Modifier {
    val scope = rememberCoroutineScope()
    return this
        .horizontalScroll(state)
        .pointerInput(state) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Scroll) {
                        val delta = event.changes.firstOrNull()?.scrollDelta ?: continue
                        val amount = if (delta.x != 0f) delta.x else delta.y
                        if (amount != 0f) {
                            scope.launch { state.scrollBy(amount * 80f) }
                        }
                    }
                }
            }
        }
}
