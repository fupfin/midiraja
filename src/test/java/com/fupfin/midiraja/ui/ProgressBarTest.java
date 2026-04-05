package com.fupfin.midiraja.ui;

import static com.fupfin.midiraja.ui.ProgressBar.Style.DOTTED_BACKGROUND;
import static com.fupfin.midiraja.ui.ProgressBar.Style.SOLID_BACKGROUND;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProgressBarTest
{
    private static String stripAnsi(String s)
    {
        return s.replaceAll("\033\\[[^m]*m", "");
    }

    @Test
    void render_zeroFilled_bracketedResult_startsWithOpenAndEndsWithClose()
    {
        String result = stripAnsi(ProgressBar.render(0, 10, SOLID_BACKGROUND, true));
        assertTrue(result.startsWith("["), "should start with '['");
        assertTrue(result.endsWith("]"), "should end with ']'");
    }

    @Test
    void render_zeroFilled_noBlockCharsBeforeBackground()
    {
        String result = stripAnsi(ProgressBar.render(0, 10, SOLID_BACKGROUND, true));
        // No filled block characters — only the 10 background chars between the brackets
        String inner = result.substring(1, result.length() - 1);
        assertFalse(inner.contains(Theme.CHAR_BLOCK_FULL),
                "zero-filled bar should contain no filled block char");
    }

    @Test
    void render_fullyFilled_tenBlockCharsInResult()
    {
        String result = stripAnsi(ProgressBar.render(10, 10, SOLID_BACKGROUND, true));
        assertEquals("[" + Theme.CHAR_BLOCK_FULL.repeat(10) + "]", result);
    }

    @Test
    void render_filledOne_exactlyOneBlockChar()
    {
        String result = stripAnsi(ProgressBar.render(1, 10, SOLID_BACKGROUND, true));
        String inner = result.substring(1, result.length() - 1);
        long filledCount = inner.chars()
                .filter(c -> String.valueOf((char) c).equals(Theme.CHAR_BLOCK_FULL))
                .count();
        assertEquals(1, filledCount);
    }

    @Test
    void render_filledFive_exactlyFiveBlockCharsBeforeBackground()
    {
        String result = stripAnsi(ProgressBar.render(5, 10, SOLID_BACKGROUND, true));
        String inner = result.substring(1, result.length() - 1); // strip brackets
        // Filled blocks come first; count them from the start
        int filled = 0;
        int i = 0;
        while (i < inner.length())
        {
            // Theme.CHAR_BLOCK_FULL is a single Unicode code point represented as one char "█"
            String ch = String.valueOf(inner.charAt(i));
            if (ch.equals(Theme.CHAR_BLOCK_FULL))
            {
                filled++;
                i++;
            }
            else
            {
                break;
            }
        }
        assertEquals(5, filled);
    }

    @Test
    void render_negativeFilledLength_clampedToZero()
    {
        String clamped = stripAnsi(ProgressBar.render(-1, 10, SOLID_BACKGROUND, true));
        String zero = stripAnsi(ProgressBar.render(0, 10, SOLID_BACKGROUND, true));
        assertEquals(zero, clamped);
    }

    @Test
    void render_filledExceedsTotalLength_clampedToTotal()
    {
        String clamped = stripAnsi(ProgressBar.render(11, 10, SOLID_BACKGROUND, true));
        String full = stripAnsi(ProgressBar.render(10, 10, SOLID_BACKGROUND, true));
        assertEquals(full, clamped);
    }

    @Test
    void render_showBracketsFalse_noBracketsInResult()
    {
        String result = stripAnsi(ProgressBar.render(5, 10, SOLID_BACKGROUND, false));
        assertFalse(result.contains("["), "should not contain '['");
        assertFalse(result.contains("]"), "should not contain ']'");
    }

    @Test
    void render_dottedBackground_usesGridDotCharacterInBackground()
    {
        String dottedResult = stripAnsi(ProgressBar.render(5, 10, DOTTED_BACKGROUND, true));
        String solidResult = stripAnsi(ProgressBar.render(5, 10, SOLID_BACKGROUND, true));
        // The background characters differ between the two styles
        assertNotEquals(solidResult, dottedResult,
                "DOTTED_BACKGROUND result should differ from SOLID_BACKGROUND result");
        // Dotted style uses CHAR_GRID_DOT for background
        assertTrue(dottedResult.contains(Theme.CHAR_GRID_DOT),
                "DOTTED_BACKGROUND bar should contain grid-dot background character");
    }

    @Test
    void render_solidBackground_usesBlockEmptyCharacterInBackground()
    {
        String result = stripAnsi(ProgressBar.render(0, 5, SOLID_BACKGROUND, false));
        assertEquals(Theme.CHAR_BLOCK_EMPTY.repeat(5), result);
    }
}
