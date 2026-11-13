package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * THE VOICE LAW's merge (13/11): the transcript is the prompt's real text;
 * the caption rides first when it also exists; a missing/blank transcript
 * degrades to the caption alone — words are never invented.
 */
class VoiceTranscribeTest {

    @Test
    fun theTranscriptIsThePromptWhenThereIsNoCaption() {
        // a voice NOTE never carries a caption: the words ARE the prompt
        assertEquals("funciona esto?", VoiceTranscribe.promptOf("", "funciona esto?"))
    }

    @Test
    fun theCaptionRidesFirstWhenBothExist() {
        // an audio FILE can carry a caption alongside the spoken words
        assertEquals("transcribí esto\n\nhola hola", VoiceTranscribe.promptOf("transcribí esto", "hola hola"))
    }

    @Test
    fun aMissingTranscriptDegradesToTheCaptionAlone() {
        assertEquals("solo el caption", VoiceTranscribe.promptOf("solo el caption", null))
        assertEquals("solo el caption", VoiceTranscribe.promptOf("solo el caption", ""))
        assertEquals("solo el caption", VoiceTranscribe.promptOf("solo el caption", "   "))
    }

    @Test
    fun nothingAndNobodyInventsWords() {
        // no caption, no transcript: the old world's honest emptiness
        assertEquals("", VoiceTranscribe.promptOf("", null))
    }
}
