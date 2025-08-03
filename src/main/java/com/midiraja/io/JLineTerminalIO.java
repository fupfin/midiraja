package com.midiraja.io;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;

import static java.lang.System.out;

public class JLineTerminalIO implements TerminalIO {
    private Terminal terminal;
    private NonBlockingReader reader;

    @Override
    public void init() throws IOException {
        // Create terminal in raw mode
        terminal = TerminalBuilder.builder()
                .system(true)
                .build();
        terminal.enterRawMode();
        reader = terminal.reader();
    }

    @Override
    public void close() throws IOException {
        if (terminal != null) {
            try {
                terminal.close();
            } catch (IOException _) {
                // Ignore errors during terminal cleanup to prevent masking main exceptions
            }
        }
    }

    @Override
    public TerminalKey readKey() throws IOException {
        if (reader == null) return TerminalKey.NONE;

        // Non-blocking read (returns -2 if no input is available)
        int ch = reader.read(10); // small timeout to avoid tight loop
        if (ch <= 0) return TerminalKey.NONE;

        if (ch == 'q' || ch == 'Q') {
            return TerminalKey.QUIT;
        }

        // Handle ESC and Arrow Keys (typically ESC [ A, B, C, D)
        if (ch == 27) { // 27 is ESC
            int next1 = reader.read(10);
            if (next1 == '[') { // It's an escape sequence!
                int next2 = reader.read(10);
                return switch (next2) {
                    case 'A' -> TerminalKey.VOLUME_UP;
                    case 'B' -> TerminalKey.VOLUME_DOWN;
                    case 'C' -> TerminalKey.SEEK_FORWARD;
                    case 'D' -> TerminalKey.SEEK_BACKWARD;
                    default  -> TerminalKey.NONE;
                };
            } else if (next1 <= 0) {
                // It was just a pure ESC key press (no sequence followed)
                return TerminalKey.QUIT;
            }
        }

        return TerminalKey.NONE;
    }

    @Override
    public void print(String str) {
        if (terminal != null) {
            terminal.writer().print(str);
            terminal.writer().flush();
        } else {
            out.print(str);
        }
    }

    @Override
    public void println(String str) {
        if (terminal != null) {
            terminal.writer().println(str);
            terminal.writer().flush();
        } else {
            out.println(str);
        }
    }
}