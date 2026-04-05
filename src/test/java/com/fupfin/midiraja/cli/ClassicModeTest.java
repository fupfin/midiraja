package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class ClassicModeTest
{
    // --- helpers ---

    private static InputStream inputStream(String text)
    {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static PrintStream errStream(ByteArrayOutputStream baos)
    {
        return new PrintStream(baos, true, StandardCharsets.UTF_8);
    }

    private static TerminalSelector.FullScreenConfig config()
    {
        return new TerminalSelector.FullScreenConfig("Test Title", 40, 80);
    }

    private static <T> List<TerminalSelector.Item<T>> items(T first, T second)
    {
        return List.of(
                TerminalSelector.Item.of(first, "First", "First description"),
                TerminalSelector.Item.of(second, "Second", "Second description"));
    }

    // --- select() ---

    @Test
    void select_singleItem_inputOne_returnsItemValue()
    {
        var list = List.of(TerminalSelector.Item.of("alpha", "Alpha", "Alpha desc"));
        InputStream prev = System.in;
        try
        {
            System.setIn(inputStream("1\n"));
            var result = ClassicMode.select(list, config(), errStream(new ByteArrayOutputStream()));
            assertEquals("alpha", result);
        }
        finally
        {
            System.setIn(prev);
        }
    }

    @Test
    void select_multiItem_inputTwo_returnsSecondItem()
    {
        var list = items("first", "second");
        InputStream prev = System.in;
        try
        {
            System.setIn(inputStream("2\n"));
            var result = ClassicMode.select(list, config(), errStream(new ByteArrayOutputStream()));
            assertEquals("second", result);
        }
        finally
        {
            System.setIn(prev);
        }
    }

    @Test
    void select_inputZero_returnsNull()
    {
        var list = items("first", "second");
        InputStream prev = System.in;
        try
        {
            System.setIn(inputStream("0\n"));
            var result = ClassicMode.select(list, config(), errStream(new ByteArrayOutputStream()));
            assertNull(result);
        }
        finally
        {
            System.setIn(prev);
        }
    }

    @Test
    void select_inputOutOfRange_returnsNull()
    {
        var list = items("first", "second");
        InputStream prev = System.in;
        try
        {
            System.setIn(inputStream("99\n"));
            var result = ClassicMode.select(list, config(), errStream(new ByteArrayOutputStream()));
            assertNull(result);
        }
        finally
        {
            System.setIn(prev);
        }
    }

    @Test
    void select_nonIntegerInput_returnsNull()
    {
        var list = items("first", "second");
        InputStream prev = System.in;
        try
        {
            System.setIn(inputStream("abc\n"));
            var result = ClassicMode.select(list, config(), errStream(new ByteArrayOutputStream()));
            assertNull(result);
        }
        finally
        {
            System.setIn(prev);
        }
    }

    @Test
    void select_listWithSeparator_indexingSkipsSeparator()
    {
        // Separator between item1 and item2; user enters "2" → should get item2
        List<TerminalSelector.Item<String>> list = List.of(
                TerminalSelector.Item.of("item1", "Item One", "desc1"),
                TerminalSelector.Item.separator("--- separator ---"),
                TerminalSelector.Item.of("item2", "Item Two", "desc2"));
        InputStream prev = System.in;
        try
        {
            System.setIn(inputStream("2\n"));
            var result = ClassicMode.select(list, config(), errStream(new ByteArrayOutputStream()));
            assertEquals("item2", result);
        }
        finally
        {
            System.setIn(prev);
        }
    }

    @Test
    void select_outputFormat_titleEndsWithColon()
    {
        var baos = new ByteArrayOutputStream();
        var list = List.of(TerminalSelector.Item.of("x", "X", "desc"));
        InputStream prev = System.in;
        try
        {
            System.setIn(inputStream("1\n"));
            ClassicMode.select(list, config(), errStream(baos));
        }
        finally
        {
            System.setIn(prev);
        }
        String output = baos.toString(StandardCharsets.UTF_8);
        String firstLine = output.lines().findFirst().orElse("");
        assertTrue(firstLine.endsWith(":"), "title line should end with ':'");
    }

    @Test
    void select_outputFormat_numberedItemHasExpectedFormat()
    {
        var baos = new ByteArrayOutputStream();
        var list = List.of(TerminalSelector.Item.of("x", "MyLabel", "MyDesc"));
        InputStream prev = System.in;
        try
        {
            System.setIn(inputStream("1\n"));
            ClassicMode.select(list, config(), errStream(baos));
        }
        finally
        {
            System.setIn(prev);
        }
        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[1]"), "output should contain '[1]'");
        assertTrue(output.contains("MyLabel"), "output should contain label");
        assertTrue(output.contains("MyDesc"), "output should contain description");
        assertTrue(output.contains("—"), "output should contain em-dash separator");
    }

    @Test
    void select_outputFormat_promptContainsEnterNumber()
    {
        var baos = new ByteArrayOutputStream();
        var list = List.of(TerminalSelector.Item.of("x", "X", "desc"));
        InputStream prev = System.in;
        try
        {
            System.setIn(inputStream("1\n"));
            ClassicMode.select(list, config(), errStream(baos));
        }
        finally
        {
            System.setIn(prev);
        }
        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Enter number:"), "output should contain 'Enter number:'");
    }

    // --- selectWithActions() ---

    @Test
    void selectWithActions_inputOne_returnsChosen()
    {
        var list = items("alpha", "beta");
        InputStream prev = System.in;
        try
        {
            System.setIn(inputStream("1\n"));
            var result = ClassicMode.selectWithActions(list, config(),
                    errStream(new ByteArrayOutputStream()));
            assertInstanceOf(TerminalSelector.SelectResult.Chosen.class, result);
            assertEquals("alpha", ((TerminalSelector.SelectResult.Chosen<?>) result).value());
        }
        finally
        {
            System.setIn(prev);
        }
    }

    @Test
    void selectWithActions_inputZero_returnsCancelled()
    {
        var list = items("alpha", "beta");
        InputStream prev = System.in;
        try
        {
            System.setIn(inputStream("0\n"));
            var result = ClassicMode.selectWithActions(list, config(),
                    errStream(new ByteArrayOutputStream()));
            assertInstanceOf(TerminalSelector.SelectResult.Cancelled.class, result);
        }
        finally
        {
            System.setIn(prev);
        }
    }

    @Test
    void selectWithActions_inputOutOfRange_returnsCancelled()
    {
        var list = items("alpha", "beta");
        InputStream prev = System.in;
        try
        {
            System.setIn(inputStream("99\n"));
            var result = ClassicMode.selectWithActions(list, config(),
                    errStream(new ByteArrayOutputStream()));
            assertInstanceOf(TerminalSelector.SelectResult.Cancelled.class, result);
        }
        finally
        {
            System.setIn(prev);
        }
    }

    @Test
    void selectWithActions_nonIntegerInput_returnsCancelled()
    {
        var list = items("alpha", "beta");
        InputStream prev = System.in;
        try
        {
            System.setIn(inputStream("abc\n"));
            var result = ClassicMode.selectWithActions(list, config(),
                    errStream(new ByteArrayOutputStream()));
            assertInstanceOf(TerminalSelector.SelectResult.Cancelled.class, result);
        }
        finally
        {
            System.setIn(prev);
        }
    }

    @Test
    void selectWithActions_outputContainsCancelPrompt()
    {
        var baos = new ByteArrayOutputStream();
        var list = items("alpha", "beta");
        InputStream prev = System.in;
        try
        {
            System.setIn(inputStream("1\n"));
            ClassicMode.selectWithActions(list, config(), errStream(baos));
        }
        finally
        {
            System.setIn(prev);
        }
        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Enter number (0 to cancel):"),
                "output should contain 'Enter number (0 to cancel):'");
    }
}
