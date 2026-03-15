/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.io;

import static java.lang.IO.*;

import java.io.IOException;
import org.jline.terminal.*;
import org.jline.utils.NonBlockingReader;
import org.jspecify.annotations.Nullable;

public class JLineTerminalIO implements TerminalIO
{
    @Nullable
    private Terminal terminal;
    @Nullable
    private NonBlockingReader reader;

    @Override
    public boolean isInteractive()
    {
        if (terminal == null) return false;
        String type = terminal.getType();
        return !Terminal.TYPE_DUMB.equals(type) && !Terminal.TYPE_DUMB_COLOR.equals(type);
    }

    @Override
    public void init() throws IOException
    {
        // Create terminal in raw mode
        terminal = TerminalBuilder.builder().system(true).build();
        terminal.enterRawMode();
        Attributes attr = terminal.getAttributes();
        attr.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        terminal.setAttributes(attr);
        reader = terminal.reader();
    }

    @Override
    public void close() throws IOException
    {
        if (terminal != null)
        {
            try
            {
                terminal.close();
            }
            catch (IOException _)
            {
                // Ignore errors during terminal cleanup to prevent masking main
                // exceptions
            }
        }
    }

  @Override
  public TerminalKey readKey() throws IOException {
    if (reader == null)
      return TerminalKey.NONE;

    // Non-blocking read (returns -2 if no input is available)
    int ch = reader.read(10); // small timeout to avoid tight loop
    if (ch <= 0)
      return TerminalKey.NONE;

    return switch (ch) {
      case ' ' -> TerminalKey.PAUSE;
      case 'q', 'Q' -> TerminalKey.QUIT;

      // Track Navigation (Main: Arrows, Aux: n/p)
      case 'n', 'N' -> TerminalKey.NEXT_TRACK;
      case 'p', 'P' -> TerminalKey.PREV_TRACK;

      // Volume (Main: +/-, Aux: u/d)
      case '+', '=', 'u', 'U' -> TerminalKey.VOLUME_UP;
      case '-', '_', 'd', 'D' -> TerminalKey.VOLUME_DOWN;

      // Speed (Main: [ / ])

      // Seek (Main: Left/Right Arrows, Aux: f/b)
      case 'f', 'F' -> TerminalKey.SEEK_FORWARD;
      case '\'' -> TerminalKey.TRANSPOSE_UP;
      case '/' -> TerminalKey.TRANSPOSE_DOWN;
      case 'b', 'B' -> TerminalKey.SEEK_BACKWARD;

      // Transpose (Main: < / >)
      case '.', '>' -> TerminalKey.SPEED_UP;
      case ',', '<' -> TerminalKey.SPEED_DOWN;

      // Handle ESC and Arrow Keys (typically ESC [ A, B, C, D)
      case 27 -> {
        int next1 = reader.read(50);
        if (next1 == '[') {
          int next2 = reader.read(50);
          yield switch (next2) {
            case 'A' -> TerminalKey.PREV_TRACK;
            case 'B' -> TerminalKey.NEXT_TRACK;
            case 'C' -> TerminalKey.SEEK_FORWARD;
            case 'D' -> TerminalKey.SEEK_BACKWARD;
            default -> TerminalKey.NONE;
          };
        } else if (next1 <= 0) {
          // Pure ESC key press
          yield TerminalKey.QUIT;
        }
        yield TerminalKey.NONE;
      }
      default -> TerminalKey.NONE;
    };
  }

    @Override
    public void print(String str)
    {
        if (terminal != null)
        {
            terminal.writer().print(str);
            terminal.writer().flush();
        }
        else
        {
            print(str);
        }
    }

    @Override
    public void println(String str)
    {
        if (terminal != null)
        {
            terminal.writer().println(str);
            terminal.writer().flush();
        }
        else
        {
            println(str);
        }
    }

    @Override
    public int getWidth()
    {
        return terminal != null && terminal.getWidth() > 0 ? terminal.getWidth() : 80;
    }

    @Override
    public int getHeight()
    {
        return terminal != null && terminal.getHeight() > 0 ? terminal.getHeight() : 24;
    }
}
