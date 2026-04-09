package com.fupfin.midiraja.cli;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

/**
 * Persists playback session history and bookmarks to ~/.config/midiraja/history.json.
 * Auto entries: newest-first, capped at 10.
 * Bookmarks: newest-first, capped at 50.
 */
public class SessionHistory
{
    private static final Logger log = Logger.getLogger(SessionHistory.class.getName());
    private static final int AUTO_LIMIT = 10;
    private static final int BOOKMARK_LIMIT = 50;

    private final Path filePath;
    private List<SessionEntry> auto = new ArrayList<>();
    private List<SessionEntry> bookmarks = new ArrayList<>();

    /** Production constructor: uses platform-appropriate config directory. */
    public SessionHistory()
    {
        this(defaultPath());
    }

    /** Test constructor: uses the given path. */
    public SessionHistory(Path filePath)
    {
        this.filePath = filePath;
        load();
    }

    public static Path defaultPath()
    {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path base;
        if (os.contains("win"))
        {
            String appData = System.getenv("APPDATA");
            base = appData != null ? Path.of(appData) : Path.of(System.getProperty("user.home"));
        }
        else
        {
            String xdgConfig = System.getenv("XDG_CONFIG_HOME");
            base = xdgConfig != null
                    ? Path.of(xdgConfig)
                    : Path.of(System.getProperty("user.home"), ".config");
        }
        return base.resolve("midiraja").resolve("history.json");
    }

    public void recordAuto(List<String> args)
    {
        if (bookmarks.stream().anyMatch(e -> e.args().equals(args)))
            return;
        auto.removeIf(e -> e.args().equals(args));
        auto.add(0, new SessionEntry(List.copyOf(args), Instant.now()));
        auto = trimToLimit(auto, AUTO_LIMIT);
        save();
    }

    public void saveBookmark(List<String> args)
    {
        if (args.isEmpty())
            return;
        bookmarks.removeIf(e -> e.args().equals(args));
        bookmarks.add(0, new SessionEntry(List.copyOf(args), Instant.now()));
        bookmarks = trimToLimit(bookmarks, BOOKMARK_LIMIT);
        save();
    }

    public boolean isBookmarked(List<String> args)
    {
        return bookmarks.stream().anyMatch(e -> e.args().equals(args));
    }

    public void removeBookmarkByArgs(List<String> args)
    {
        if (bookmarks.removeIf(e -> e.args().equals(args)))
            save();
    }

    public void deleteAuto(int index)
    {
        if (index >= 0 && index < auto.size())
        {
            auto.remove(index);
            save();
        }
    }

    public void deleteBookmark(int index)
    {
        if (index >= 0 && index < bookmarks.size())
        {
            bookmarks.remove(index);
            save();
        }
    }

    public void promoteToBookmark(int index)
    {
        if (index < 0 || index >= auto.size())
            return;
        var entry = auto.remove(index);
        bookmarks.add(0, new SessionEntry(entry.args(), Instant.now()));
        bookmarks = trimToLimit(bookmarks, BOOKMARK_LIMIT);
        save();
    }

    public List<SessionEntry> getAll()
    {
        var result = new ArrayList<SessionEntry>(auto.size() + bookmarks.size());
        result.addAll(auto);
        result.addAll(bookmarks);
        return Collections.unmodifiableList(result);
    }

    public int getAutoCount()
    {
        return auto.size();
    }

    public int getBookmarkCount()
    {
        return bookmarks.size();
    }

    // ── JSON serialization ────────────────────────────────────────────────────

    private void load()
    {
        if (!Files.exists(filePath))
            return;
        try
        {
            String json = Files.readString(filePath);
            auto = parseEntries(json, "auto");
            bookmarks = parseEntries(json, "bookmarks");
        }
        catch (Exception e)
        {
            log.warning("history.json unreadable, treating as empty: " + e.getMessage());
            auto = new ArrayList<>();
            bookmarks = new ArrayList<>();
        }
    }

    private void save()
    {
        try
        {
            Files.createDirectories(filePath.getParent());
            String json = toJson();
            Path tmp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            Files.move(tmp, filePath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException e)
        {
            log.warning("Failed to save history: " + e.getMessage());
        }
    }

    /** Minimal hand-rolled JSON writer — no third-party dependency. */
    private String toJson()
    {
        var sb = new StringBuilder("{\n");
        sb.append("  \"auto\": [\n");
        appendEntries(sb, auto);
        sb.append("  ],\n  \"bookmarks\": [\n");
        appendEntries(sb, bookmarks);
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    private void appendEntries(StringBuilder sb, List<SessionEntry> entries)
    {
        for (int i = 0; i < entries.size(); i++)
        {
            var e = entries.get(i);
            sb.append("    {\"args\":[");
            for (int j = 0; j < e.args().size(); j++)
            {
                if (j > 0)
                    sb.append(',');
                sb.append('"').append(jsonEscape(e.args().get(j))).append('"');
            }
            sb.append("],\"savedAt\":\"").append(e.savedAt()).append("\"}");
            if (i < entries.size() - 1)
                sb.append(',');
            sb.append('\n');
        }
    }

    private static <T> List<T> trimToLimit(List<T> list, int limit)
    {
        return list.size() > limit ? new ArrayList<>(list.subList(0, limit)) : list;
    }

    private static String jsonEscape(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Minimal hand-rolled JSON parser for the two known array fields. */
    private static List<SessionEntry> parseEntries(String json, String field)
    {
        List<SessionEntry> result = new ArrayList<>();
        String marker = "\"" + field + "\"";
        int start = json.indexOf(marker);
        if (start < 0)
            return result;
        int arrStart = json.indexOf('[', start);
        int arrEnd = json.indexOf(']', arrStart);
        if (arrStart < 0 || arrEnd < 0)
            return result;

        arrEnd = findMatchingDelimiter(json, arrStart, '[', ']');
        if (arrEnd < 0)
            return result;
        String section = json.substring(arrStart + 1, arrEnd);
        // Each entry: {"args":[...],"savedAt":"..."}
        int pos = 0;
        while (pos < section.length())
        {
            int objStart = section.indexOf('{', pos);
            if (objStart < 0)
                break;
            int objEnd = findMatchingDelimiter(section, objStart, '{', '}');
            if (objEnd < 0)
                break;
            String obj = section.substring(objStart, objEnd + 1);
            try
            {
                List<String> args = parseStringArray(obj, "args");
                String savedAtStr = parseStringValue(obj, "savedAt");
                Instant savedAt = savedAtStr != null ? Instant.parse(savedAtStr) : Instant.now();
                if (args != null)
                    result.add(new SessionEntry(args, savedAt));
            }
            catch (Exception _)
            {}
            pos = objEnd + 1;
        }
        return result;
    }

    /**
     * Returns the index of {@code close} matching the {@code open} at {@code openPos},
     * skipping characters inside JSON string literals. Returns -1 if not found.
     */
    private static int findMatchingDelimiter(String s, int openPos, char open, char close)
    {
        int depth = 0;
        boolean inStr = false;
        for (int i = openPos; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (inStr)
            {
                if (c == '\\')
                {
                    i++;
                    continue;
                }
                if (c == '"')
                    inStr = false;
            }
            else
            {
                if (c == '"')
                {
                    inStr = true;
                    continue;
                }
                if (c == open)
                    depth++;
                else if (c == close)
                {
                    if (--depth == 0)
                        return i;
                }
            }
        }
        return -1;
    }

    private static @Nullable List<String> parseStringArray(String obj, String field)
    {
        String marker = "\"" + field + "\":[";
        int start = obj.indexOf(marker);
        if (start < 0)
            return null;
        int bracketPos = start + marker.length() - 1; // points to '['
        int arrEnd = findMatchingDelimiter(obj, bracketPos, '[', ']');
        if (arrEnd < 0)
            return null;
        int arrStart = bracketPos + 1;
        String content = obj.substring(arrStart, arrEnd);
        List<String> items = new ArrayList<>();
        int pos = 0;
        while (pos < content.length())
        {
            int q1 = content.indexOf('"', pos);
            if (q1 < 0)
                break;
            int q2 = q1 + 1;
            while (q2 < content.length())
            {
                if (content.charAt(q2) == '"' && content.charAt(q2 - 1) != '\\')
                    break;
                q2++;
            }
            if (q2 >= content.length())
                break;
            items.add(content.substring(q1 + 1, q2)
                    .replace("\\\\", "\\").replace("\\\"", "\""));
            pos = q2 + 1;
        }
        return items;
    }

    private static @Nullable String parseStringValue(String obj, String field)
    {
        String marker = "\"" + field + "\":\"";
        int start = obj.indexOf(marker);
        if (start < 0)
            return null;
        int valStart = start + marker.length();
        int valEnd = obj.indexOf('"', valStart);
        if (valEnd < 0)
            return null;
        return obj.substring(valStart, valEnd);
    }
}
