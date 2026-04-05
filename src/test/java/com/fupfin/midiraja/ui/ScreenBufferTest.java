package com.fupfin.midiraja.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ScreenBufferTest
{
    // --- append(String) ---

    @Test
    void appendString_returnsSameInstance()
    {
        var buf = new ScreenBuffer();
        assertSame(buf, buf.append("hello"));
    }

    @Test
    void appendString_accumulatesContent()
    {
        var buf = new ScreenBuffer();
        buf.append("foo").append("bar");
        assertEquals("foobar", buf.toString());
    }

    // --- append(char) ---

    @Test
    void appendChar_returnsSameInstance()
    {
        var buf = new ScreenBuffer();
        assertSame(buf, buf.append('x'));
    }

    @Test
    void appendChar_accumulatesSingleCharacter()
    {
        var buf = new ScreenBuffer();
        buf.append('a').append('b').append('c');
        assertEquals("abc", buf.toString());
    }

    // --- appendLine() ---

    @Test
    void appendLine_appendsNewline()
    {
        var buf = new ScreenBuffer();
        buf.append("hello").appendLine();
        assertEquals("hello\n", buf.toString());
    }

    @Test
    void appendLine_returnsSameInstance()
    {
        var buf = new ScreenBuffer();
        assertSame(buf, buf.appendLine());
    }

    // --- format(String, Object...) ---

    @Test
    void format_appendsFormattedString()
    {
        var buf = new ScreenBuffer();
        buf.format("track %d of %d", 3, 10);
        assertEquals("track 3 of 10", buf.toString());
    }

    @Test
    void format_returnsSameInstance()
    {
        var buf = new ScreenBuffer();
        assertSame(buf, buf.format("%s", "x"));
    }

    // --- repeat(String, int) ---

    @Test
    void repeatString_appendsNTimes()
    {
        var buf = new ScreenBuffer();
        buf.repeat("ab", 3);
        assertEquals("ababab", buf.toString());
    }

    @Test
    void repeatString_countZero_appendsNothing()
    {
        var buf = new ScreenBuffer();
        buf.repeat("ab", 0);
        assertEquals("", buf.toString());
    }

    @Test
    void repeatString_countOne_appendsOnce()
    {
        var buf = new ScreenBuffer();
        buf.repeat("ab", 1);
        assertEquals("ab", buf.toString());
    }

    // --- repeat(char, int) ---

    @Test
    void repeatChar_appendsNTimes()
    {
        var buf = new ScreenBuffer();
        buf.repeat('*', 4);
        assertEquals("****", buf.toString());
    }

    @Test
    void repeatChar_countZero_appendsNothing()
    {
        var buf = new ScreenBuffer();
        buf.repeat('*', 0);
        assertEquals("", buf.toString());
    }

    @Test
    void repeatChar_countOne_appendsOnce()
    {
        var buf = new ScreenBuffer();
        buf.repeat('!', 1);
        assertEquals("!", buf.toString());
    }

    // --- toString() ---

    @Test
    void toString_returnsAccumulatedContent()
    {
        var buf = new ScreenBuffer();
        buf.append("hello ").append("world");
        assertEquals("hello world", buf.toString());
    }

    // --- toLines() ---

    @Test
    void toLines_singleLine_returnsOneElement()
    {
        var buf = new ScreenBuffer();
        buf.append("only line");
        assertArrayEquals(new String[] { "only line" }, buf.toLines());
    }

    @Test
    void toLines_multiLine_splitsOnNewline()
    {
        var buf = new ScreenBuffer();
        buf.append("line1\nline2\nline3");
        assertArrayEquals(new String[] { "line1", "line2", "line3" }, buf.toLines());
    }

    @Test
    void toLines_trailingNewline_producesEmptyLastElement()
    {
        var buf = new ScreenBuffer();
        buf.append("line1\nline2\n");
        var lines = buf.toLines();
        assertEquals(3, lines.length);
        assertEquals("line1", lines[0]);
        assertEquals("line2", lines[1]);
        assertEquals("", lines[2]);
    }

    // --- constructor with capacity ---

    @Test
    void constructorWithCapacity_behavesLikeDefaultConstructor()
    {
        var buf = new ScreenBuffer(128);
        buf.append("test");
        assertEquals("test", buf.toString());
    }

    // --- method chaining ---

    @Test
    void methodChaining_producesCorrectResult()
    {
        var buf = new ScreenBuffer();
        String result = buf.append("a").appendLine().append("b").toString();
        assertEquals("a\nb", result);
    }
}
