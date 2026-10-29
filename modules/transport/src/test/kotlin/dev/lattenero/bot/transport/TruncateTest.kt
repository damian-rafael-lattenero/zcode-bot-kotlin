package dev.lattenero.bot.transport

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * html_truncate (utils/formatting.py, deep audit 20/10), pinned against
 * the REAL Python (generated 2026-10-29 from ~/Projects/bot's
 * formatting.py — the cases and expected strings below are its answers,
 * byte for byte; the cap counts CODE POINTS like Python's len()).
 */
class TruncateTest {

    private val plain = "x".repeat(5000)

    @Test
    fun whatFitsIsUntouched() {
        assertEquals("hola <b>mundo</b>", htmlTruncate("hola <b>mundo</b>", CH_REPLY))
    }

    @Test
    fun chReplyIsPythons3900() {
        assertEquals(3900, CH_REPLY)
    }

    @Test
    fun aPlainOverflowCutsAtTheCap() {
        assertEquals(plain.take(3900), htmlTruncate(plain, 3900))
        assertEquals(3900, htmlTruncate(plain, 3900).length)
    }

    @Test
    fun anOpenTagIsClosedInnermostFirst() {
        // Python: html_truncate('<pre>' + 'a'*20 + '</pre>', 10) == '<pre>aaaaa</pre>'
        assertEquals("<pre>aaaaa</pre>", htmlTruncate("<pre>" + "a".repeat(20) + "</pre>", 10))
    }

    @Test
    fun nestedTagsCloseInTheRightOrder() {
        // Python: html_truncate('<b>bold <i>it ' + 't'*30, 10) == '<b>bold </b>'
        // (the cut lands inside '<i' → the half-open tag is dropped, <b> is closed)
        assertEquals("<b>bold </b>", htmlTruncate("<b>bold <i>it " + "t".repeat(30), 10))
    }

    @Test
    fun aTagLeftHalfOpenByTheCutIsDropped() {
        // Python: html_truncate('abcdefgh<pre>tail', 10) == 'abcdefgh'
        assertEquals("abcdefgh", htmlTruncate("abcdefgh<pre>tail", 10))
    }

    @Test
    fun anEntitySplitByTheCutIsDropped() {
        // Python: html_truncate('abcdef&amp;more', 8) == 'abcdef'
        assertEquals("abcdef", htmlTruncate("abcdef&amp;more", 8))
    }

    @Test
    fun selfClosingTagsDoNotNeedAClose() {
        // Python: html_truncate('<br/>' + 'z'*20, 6) == '<br/>z'
        assertEquals("<br/>z", htmlTruncate("<br/>" + "z".repeat(20), 6))
    }

    @Test
    fun aClosedTagDoesNotSurviveItsOwnClose() {
        // Python: html_truncate('<b>x</b><i>' + 'y'*20, 8) == '<b>x</b>'
        // (the cut eats '<i' whole; the matched </b> left nothing open)
        assertEquals("<b>x</b>", htmlTruncate("<b>x</b><i>" + "y".repeat(20), 8))
    }

    @Test
    fun theCapCountsCodePointsNotChars() {
        // Python: html_truncate('<b>ñañaña' + 'ü'*30, 9) == '<b>ñañaña</b>'
        assertEquals("<b>ñañaña</b>", htmlTruncate("<b>ñañaña" + "ü".repeat(30), 9))
    }
}
