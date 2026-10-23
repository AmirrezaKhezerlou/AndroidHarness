package com.androidharness.app.ui.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity

/** Chat's disclosure owns its gestures, including drags/flings at the ends of its content. */
internal class ChatDetailScroll(private val pauseTranscriptFollow: () -> Unit) {
    private val readers = mutableStateMapOf<Any, Boolean>()
    val reading: Boolean get() = readers.isNotEmpty()
    fun pause() = pauseTranscriptFollow()
    fun begin(key: Any) { readers[key] = true; pauseTranscriptFollow() }
    fun end(key: Any) { readers.remove(key) }
}
internal val LocalChatDetailScroll = compositionLocalOf<ChatDetailScroll?> { null }

internal object ContainDetailScroll : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = Offset(0f, available.y)
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = Velocity(0f, available.y)
}

@Composable
internal fun Modifier.containChatDetailScroll(): Modifier {
    val owner = LocalChatDetailScroll.current ?: return this
    val key = remember { Any() }
    DisposableEffect(owner, key) { onDispose { owner.end(key) } }
    return this.nestedScroll(ContainDetailScroll).pointerInput(owner, key) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            owner.begin(key)
            try {
                do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed })
            } finally { owner.end(key) }
        }
    }
}
