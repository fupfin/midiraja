package com.midiraja.io;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;

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
            terminal.close();
        }
    }

    @Override
    public TerminalKey readKey() throws IOException {
        if (reader == null) return TerminalKey.NONE;

        // Non-blocking read (returns -2 if no input is available)
        int ch = reader.read(10); // small timeout to avoid tight loop
        if (ch <= 0) return TerminalKey.NONE;

        if (ch == 'q' || ch == 'Q' || ch == 27) { // 27 is ESC
            return TerminalKey.QUIT;
        }

        // Handle Arrow Keys (typically ESC [ A, B, C, D)
        if (ch == 27) { // Possible escape sequence
            int next1 = reader.read(10);
            if (next1 == '[') {
                int next2 = reader.read(10);
                switch (next2) {
                    case 'A': return TerminalKey.VOLUME_UP;
                    case 'B': return TerminalKey.VOLUME_DOWN;
                    case 'C': return TerminalKey.SEEK_FORWARD;
                    case 'D': return TerminalKey.SEEK_BACKWARD;
                }
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
            System.out.print(str);
        }
    }

    @Override
    public void println(String str) {
        if (terminal != null) {
            terminal.writer().println(str);
            terminal.writer().flush();
        } else {
            System.out.println(str);
        }
    }
}