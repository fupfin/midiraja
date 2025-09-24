package com.midiraja.ui;

/**
 * An abstraction over the raw string building process for terminal UI rendering.
 * Encapsulates the underlying buffer and provides semantic methods for drawing,
 * appending, and formatting ANSI-aware strings.
 */
public class ScreenBuffer {
    private final StringBuilder sb;

    public ScreenBuffer() {
        // Pre-allocate a reasonable size to avoid resizing during fast render loops
        this.sb = new StringBuilder(4096);
    }

    public ScreenBuffer(int capacity) {
        this.sb = new StringBuilder(capacity);
    }

    /**
     * Appends a raw string directly to the buffer.
     */
    public ScreenBuffer append(String text) {
        sb.append(text);
        return this;
    }

    /**
     * Appends a character directly to the buffer.
     */
    public ScreenBuffer append(char c) {
        sb.append(c);
        return this;
    }
    
    /**
     * Appends a newline character to the buffer.
     */
    public ScreenBuffer appendLine() {
        sb.append('\n');
        return this;
    }

    /**
     * Appends a formatted string to the buffer, similar to String.format.
     */
    @com.google.errorprone.annotations.FormatMethod
    public ScreenBuffer format(String format, Object... args) {
        sb.append(String.format(format, args));
        return this;
    }

    /**
     * Appends a repeating sequence of a specific string.
     */
    public ScreenBuffer repeat(String text, int count) {
        if (count > 0) {
            sb.append(text.repeat(count));
        }
        return this;
    }

    /**
     * Appends a repeating sequence of a specific character.
     */
    public ScreenBuffer repeat(char c, int count) {
        if (count > 0) {
            sb.append(String.valueOf(c).repeat(count));
        }
        return this;
    }

    /**
     * Returns the current raw string representation and clears the internal state if needed,
     * though typically instances are discarded per frame in simple TUI loops.
     */
    @Override
    public String toString() {
        return sb.toString();
    }
    
    /**
     * Splits the current buffer content into an array of lines.
     * Useful for layout managers that need to zip columns together.
     */
    public String[] toLines() {
        return sb.toString().split("\n", -1);
    }
}
