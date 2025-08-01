package com.midiraja.io;

import java.io.IOException;

/**
 * Dependency Inversion Principle (DIP) abstraction for terminal interaction.
 * Allows decoupling the playback engine from standard system streams and JLine,
 * facilitating easy unit testing with Mock implementations.
 */
public interface TerminalIO {
    
    /**
     * Enum representing high-level interactive commands triggered by the user.
     */
    enum TerminalKey {
        NONE,
        SEEK_FORWARD,
        SEEK_BACKWARD,
        VOLUME_UP,
        VOLUME_DOWN,
        QUIT
    }

    /**
     * Initializes the terminal in raw mode for non-blocking key reads.
     */
    void init() throws IOException;

    /**
     * Restores the terminal to its original mode.
     */
    void close() throws IOException;

    /**
     * Reads a single keystroke and maps it to a TerminalKey.
     * Non-blocking if the underlying implementation supports it.
     */
    TerminalKey readKey() throws IOException;

    /**
     * Prints a string to the console.
     */
    void print(String str);

    /**
     * Prints a string to the console with a newline.
     */
    void println(String str);
}