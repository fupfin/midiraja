/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.io;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;

/**
 * Configures application-wide file logging via {@code java.util.logging}.
 *
 * <ul>
 *   <li>Default (no flags): WARNING and above → log file only
 *   <li>{@code --verbose}: INFO and above → log file only
 *   <li>{@code --debug}: FINE and above → log file + stderr echo
 * </ul>
 *
 * <p>Log file location:
 * <ul>
 *   <li>macOS: {@code ~/Library/Logs/midiraja/midiraja.log}
 *   <li>Linux: {@code ~/.local/share/midiraja/midiraja.log}
 *   <li>Windows: {@code %LOCALAPPDATA%\midiraja\midiraja.log}
 * </ul>
 */
public final class AppLogger
{
    private AppLogger()
    {}

    /**
     * Configure logging. Call once at the start of each command's {@code call()} method.
     *
     * @param verbose {@code true} for INFO-level logging
     * @param debug   {@code true} for FINE-level logging with stderr echo (implies verbose)
     */
    public static void configure(boolean verbose, boolean debug)
    {
        Level level = debug ? Level.FINE : verbose ? Level.INFO : Level.WARNING;

        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers())
            root.removeHandler(h);
        root.setLevel(level);

        // Suppress noisy third-party loggers
        Logger.getLogger("org.jline").setLevel(Level.WARNING);

        try
        {
            Path logDir = resolveLogDir();
            Files.createDirectories(logDir);
            var fh = new FileHandler(logDir.resolve("midiraja.log").toString(),
                    1_000_000, 3, true); // 1 MB, 3 rotations
            fh.setLevel(level);
            fh.setFormatter(new LogFormatter());
            root.addHandler(fh);
        }
        catch (IOException e)
        {
            // Cannot log a logging failure — silently ignore
        }

        if (debug)
        {
            var sh = new StreamHandler(System.err, new LogFormatter())
            {
                @Override
                public void publish(LogRecord r)
                {
                    super.publish(r);
                    flush();
                }
            };
            sh.setLevel(Level.FINE);
            root.addHandler(sh);
        }
    }

    private static Path resolveLogDir()
    {
        String home = System.getProperty("user.home", ".");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("windows"))
        {
            String local = System.getenv("LOCALAPPDATA");
            return Path.of(local != null ? local : home).resolve("midiraja");
        }
        else if (os.contains("mac"))
        {
            return Path.of(home, "Library", "Logs", "midiraja");
        }
        else
        {
            return Path.of(home, ".local", "share", "midiraja");
        }
    }

    private static final class LogFormatter extends Formatter
    {
        @Override
        public String format(LogRecord r)
        {
            String lvl = switch (r.getLevel().intValue())
            {
                case 1000 -> "ERROR";
                case 900 -> "WARN ";
                case 800 -> "INFO ";
                default -> "DEBUG";
            };
            String shortName = r.getLoggerName();
            int dot = shortName.lastIndexOf('.');
            if (dot >= 0) shortName = shortName.substring(dot + 1);

            String thrown = "";
            if (r.getThrown() != null)
            {
                var sw = new StringWriter();
                r.getThrown().printStackTrace(new PrintWriter(sw));
                thrown = System.lineSeparator() + sw;
            }
            return String.format("%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %2$s [%3$s] %4$s%5$s%n",
                    r.getMillis(), lvl, shortName, r.getMessage(), thrown);
        }
    }
}
