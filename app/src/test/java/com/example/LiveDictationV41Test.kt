package com.example

import com.example.ink.voice.LiveDictationBuffer
import com.example.viewmodel.WhiteboardViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveDictationV41Test {

    @Test
    fun `live dictation buffer streams multi-word bursts incrementally one word at a time`() {
        val buffer = LiveDictationBuffer()

        // First partial word appears immediately
        assertEquals("hola", buffer.onPartialUtterance("hola"))
        assertFalse(buffer.hasPendingWords())

        // Recognizer bursts 3 new words at once -> only next word appears immediately, rest queue up
        assertEquals("hola cómo", buffer.onPartialUtterance("hola cómo estás hoy"))
        assertTrue(buffer.hasPendingWords())

        assertEquals("hola cómo estás", buffer.stepRevealNextWord())
        assertTrue(buffer.hasPendingWords())

        assertEquals("hola cómo estás hoy", buffer.stepRevealNextWord())
        assertFalse(buffer.hasPendingWords())
        assertNull(buffer.stepRevealNextWord())
    }

    @Test
    fun `live dictation buffer handles ASR revisions and multi-phrase continuous commits cleanly`() {
        val buffer = LiveDictationBuffer(initialSeed = "Notas:")

        // ASR initially guesses "como esta", then corrects to "cómo está todo"
        assertEquals("Notas: como", buffer.onPartialUtterance("como esta"))
        assertEquals("Notas: cómo", buffer.onPartialUtterance("cómo está todo"))
        assertEquals("Notas: cómo está", buffer.stepRevealNextWord())
        assertEquals("Notas: cómo está todo", buffer.stepRevealNextWord())

        // Commit first phrase
        assertEquals("Notas: cómo está todo", buffer.onFinalUtterance("cómo está todo"))
        assertFalse(buffer.hasPendingWords())

        // Second phrase streams right after committed prefix
        assertEquals("Notas: cómo está todo bien", buffer.onPartialUtterance("bien gracias"))
        assertEquals("Notas: cómo está todo bien gracias", buffer.stepRevealNextWord())
        assertEquals("Notas: cómo está todo bien gracias", buffer.flushPendingUtterance())
    }

    @Test
    fun `final burst streams word by word and flushes cleanly without losing words`() {
        val buffer = LiveDictationBuffer()
        val firstStep = buffer.onFinalUtterance("uno dos tres cuatro")
        assertEquals("uno", firstStep)
        assertTrue(buffer.hasPendingWords())

        assertEquals("uno dos", buffer.stepRevealNextWord())
        // Stopping early flushes remaining tail so nothing is lost
        assertEquals("uno dos tres cuatro", buffer.flushPendingUtterance())
        assertFalse(buffer.hasPendingWords())
    }

    @Test
    fun `viewmodel streams dictated words without spamming undo snapshots and cleans empty cancelled card`() {
        val vm = WhiteboardViewModel()

        // Case 1: Cancelled dictation with empty text removes the temporary empty card
        val (emptyId, emptySeed) = vm.prepareDictationTarget()
        assertEquals("", emptySeed)
        vm.startVoiceDictationState(emptyId)
        assertTrue(vm.uiState.value.isVoiceDictating)
        assertEquals(emptyId, vm.uiState.value.dictatingTextItemId)

        vm.stopVoiceDictationState(persist = false, statusMessage = null)
        assertFalse(vm.uiState.value.isVoiceDictating)
        assertNull(vm.uiState.value.board.textItems.firstOrNull { it.id == emptyId })

        // Case 2: Streaming words into a card keeps a single clean undo step
        vm.insertTextAtCenter(initialText = "Texto")
        val selectedId = vm.uiState.value.selectedTextId
        assertNotNull(selectedId)
        val (targetId, seed) = vm.prepareDictationTarget()
        assertEquals(selectedId, targetId)
        assertEquals("", seed) // Default "Texto" placeholder cleared automatically
        vm.startVoiceDictationState(targetId)

        vm.streamDictatedTextToItem(targetId, "Hola")
        vm.streamDictatedTextToItem(targetId, "Hola mundo")
        vm.streamDictatedTextToItem(targetId, "Hola mundo en")
        vm.commitDictationPhrase(targetId, "Hola mundo en vivo")
        vm.stopVoiceDictationState(persist = false, statusMessage = null)

        val finalItem = vm.uiState.value.board.textItems.firstOrNull { it.id == targetId }
        assertNotNull(finalItem)
        assertEquals("Hola mundo en vivo", finalItem!!.text)
        assertFalse(vm.uiState.value.isVoiceDictating)
    }
}
