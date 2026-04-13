package com.fupfin.midiraja.midi;

import static java.lang.System.err;

import java.io.File;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import com.fupfin.midiraja.LibraryPaths;

public abstract class AbstractFFMBridge implements AutoCloseable
{
    /** Result of a non-throwing library probe. {@code resolvedPath} is null when not found. */
    public record LibProbeResult(boolean found, @Nullable String resolvedPath)
    {
    }

    protected static final FunctionDescriptor DESC_INIT = FunctionDescriptor.of(ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG);
    protected static final FunctionDescriptor DESC_VOID_PTR = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
    protected static final FunctionDescriptor DESC_PTR_INT = FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
    protected static final FunctionDescriptor DESC_PTR_STR = FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    protected static final FunctionDescriptor DESC_PTR_PTR_LONG = FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
    protected static final FunctionDescriptor DESC_GENERATE = FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
    protected static final FunctionDescriptor DESC_NOTE_ON = FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE,
            ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE);
    protected static final FunctionDescriptor DESC_NOTE_OFF = FunctionDescriptor
            .ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE);
    protected static final FunctionDescriptor DESC_CTRL_CHANGE = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS,
            ValueLayout.JAVA_BYTE,
            ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE);
    protected static final FunctionDescriptor DESC_PITCH_BEND = FunctionDescriptor
            .ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_SHORT);
    protected static final FunctionDescriptor DESC_SYS_EX = FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
    protected static final FunctionDescriptor DESC_ERROR_INFO = FunctionDescriptor.of(ValueLayout.ADDRESS,
            ValueLayout.ADDRESS);
    protected static final FunctionDescriptor DESC_NO_ARGS_INT = FunctionDescriptor.of(ValueLayout.JAVA_INT);

    protected final Arena arena;
    protected final SymbolLookup lib;
    protected final Linker linker;

    // Cached native render buffer shared by subclasses for generate() calls
    private MemorySegment renderBuffer = MemorySegment.NULL;
    private int currentRenderBufferSize = 0;

    protected AbstractFFMBridge(Arena arena, SymbolLookup lib)
    {
        this.arena = arena;
        this.lib = lib;
        this.linker = Linker.nativeLinker();
    }

    /**
     * Renders audio into the provided buffer using the given generate method handle.
     *
     * <p>
     * The generate handle must accept {@code (MemorySegment device, int sampleCount,
     * MemorySegment out)} and return {@code int}. Allocates a native render buffer lazily and
     * reuses it across calls to avoid per-frame allocation.
     *
     * <p>
     * Must be called only from a single render thread per bridge instance. The internal
     * {@code renderBuffer} and {@code currentRenderBufferSize} fields are not synchronized.
     *
     * @param generateHandle
     *            the native generate method handle
     * @param device
     *            the native device pointer
     * @param buffer
     *            the Java output buffer to fill
     */
    @SuppressWarnings({ "EmptyCatch", "UnusedVariable" })
    protected void generateInto(MethodHandle generateHandle, MemorySegment device, short[] buffer)
    {
        if (device.equals(MemorySegment.NULL) || buffer == null || buffer.length == 0)
            return;

        int requiredBytes = buffer.length * 2; // 2 bytes per short
        if (currentRenderBufferSize < requiredBytes)
        {
            try
            {
                renderBuffer = arena.allocate(requiredBytes);
                currentRenderBufferSize = requiredBytes;
            }
            catch (Throwable e)
            {
                err.println("[NativeBridge Error] " + e.getMessage());
                return;
            }
        }

        try
        {
            int ignored = (int) generateHandle.invokeExact(device, buffer.length, renderBuffer);
            MemorySegment.copy(renderBuffer, ValueLayout.JAVA_SHORT, 0, buffer, 0, buffer.length);
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
        }
    }

    protected final MethodHandle downcall(String symbol, FunctionDescriptor descriptor)
    {
        return linker.downcallHandle(
                lib.find(symbol).orElseThrow(
                        () -> new IllegalArgumentException("Symbol not found: " + symbol)),
                descriptor);
    }

    @org.jspecify.annotations.Nullable
    protected final MethodHandle optionalDowncall(String symbol, FunctionDescriptor descriptor)
    {
        return lib.find(symbol).map(addr -> linker.downcallHandle(addr, descriptor)).orElse(null);
    }

    public static SymbolLookup tryLoadLibrary(Arena arena, String fallbackDevDir, String... paths)
            throws RuntimeException
    {
        if (paths.length == 0)
            throw new IllegalArgumentException("No library paths provided");
        List<String> failedPaths = new ArrayList<>();
        String projectRoot = new File("").getAbsolutePath();

        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String osFamily = osName.contains("mac")
                ? "macos"
                : (osName.contains("linux") ? "linux" : "windows");
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (arch.equals("amd64"))
            arch = "x86_64";
        if (arch.equals("arm64"))
            arch = "aarch64";

        String[] osFallbackDirs = osName.contains("mac")
                ? LibraryPaths.DARWIN
                : (osName.contains("linux") ? LibraryPaths.LINUX : LibraryPaths.WINDOWS);

        List<String> allPaths = new ArrayList<>();

        // Dev build paths come FIRST so they always win over any system-installed version.
        // On macOS, DYLD_LIBRARY_PATH is stripped by SIP for certain binaries, so we cannot
        // rely on environment variables to control the load order.
        if (fallbackDevDir != null && !fallbackDevDir.isEmpty())
        {
            String nativeTarget = osFamily + "-" + arch;
            for (String path : paths)
            {
                allPaths.add(projectRoot + "/build/native-libs/" + nativeTarget + "/"
                        + fallbackDevDir + "/" + path);
            }
        }

        // Distribution layout: lib/ dir next to the JAR (installDist / packaged release).
        // getProtectionDomain() is non-null in both JVM and GraalVM native image.
        // JVM mode  : getCodeSource() → lib/midiraja.jar  → jarDir = lib/  → lib/libfoo.dylib ✓
        // Native img: getCodeSource() → bin/midra          → jarDir = bin/  → bin/libfoo.dylib (miss)
        //             so also probe ../lib/ to cover the packaged-release layout.
        try
        {
            var src = AbstractFFMBridge.class.getProtectionDomain().getCodeSource();
            if (src != null)
            {
                var jarDir = new File(src.getLocation().toURI()).getParentFile();
                for (String path : paths)
                    allPaths.add(jarDir.getAbsolutePath() + "/" + path);
                var siblingLib = new File(jarDir.getParentFile(), "lib");
                for (String path : paths)
                    allPaths.add(siblingLib.getAbsolutePath() + "/" + path);
            }
        }
        catch (Exception | Error ignored)
        {}

        // Then bare filenames (resolved by the OS dynamic linker, including rpath)
        allPaths.addAll(List.of(paths));

        // Finally, explicit OS-specific fallback dirs (e.g. /opt/homebrew/lib/libfoo.dylib)
        for (String path : paths)
        {
            if (!new File(path).isAbsolute())
            {
                for (String dir : osFallbackDirs)
                {
                    allPaths.add(dir + "/" + path);
                }
            }
        }

        for (String path : allPaths)
        {
            try
            {
                if (new File(path).isAbsolute())
                {
                    File f = new File(path);
                    if (f.exists())
                    {
                        return SymbolLookup.libraryLookup(f.toPath(), arena);
                    }
                    else
                    {
                        failedPaths.add(path + " (not found)");
                    }
                }
                else
                {
                    return SymbolLookup.libraryLookup(path, arena);
                }
            }
            catch (IllegalArgumentException e)
            {
                failedPaths.add(path);
            }
        }

        throw new RuntimeException("Failed to load native library. Paths tried:\n  - "
                + String.join("\n  - ", failedPaths));
    }

    /**
     * Probes whether a native library is available without throwing on failure.
     *
     * <p>
     * Uses the same path resolution logic as {@link #tryLoadLibrary} but returns a
     * {@link LibProbeResult} instead of throwing. For bare library names (non-absolute paths),
     * a temporary confined arena is used and closed immediately after the probe.
     */
    public static LibProbeResult probeLibrary(String fallbackDevDir, String... paths)
    {
        if (paths.length == 0)
            return new LibProbeResult(false, null);
        String projectRoot = new File("").getAbsolutePath();

        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String osFamily = osName.contains("mac")
                ? "macos"
                : (osName.contains("linux") ? "linux" : "windows");
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (arch.equals("amd64"))
            arch = "x86_64";
        if (arch.equals("arm64"))
            arch = "aarch64";

        String[] osFallbackDirs = osName.contains("mac")
                ? LibraryPaths.DARWIN
                : (osName.contains("linux") ? LibraryPaths.LINUX : LibraryPaths.WINDOWS);

        List<String> allPaths = new ArrayList<>(List.of(paths));
        for (String path : paths)
        {
            if (!new File(path).isAbsolute())
            {
                for (String dir : osFallbackDirs)
                {
                    allPaths.add(dir + "/" + path);
                }
            }
        }

        if (fallbackDevDir != null && !fallbackDevDir.isEmpty())
        {
            String nativeTarget = osFamily + "-" + arch;
            for (String path : paths)
            {
                allPaths.add(projectRoot + "/build/native-libs/" + nativeTarget + "/"
                        + fallbackDevDir + "/" + path);
            }
        }

        for (String path : allPaths)
        {
            if (new File(path).isAbsolute())
            {
                if (new File(path).exists())
                    return new LibProbeResult(true, path);
            }
            else
            {
                try (Arena probeArena = Arena.ofConfined())
                {
                    SymbolLookup.libraryLookup(path, probeArena);
                    return new LibProbeResult(true, path);
                }
                catch (IllegalArgumentException e)
                {}
            }
        }
        return new LibProbeResult(false, null);
    }

    @Override
    public void close() throws RuntimeException
    {
        if (arena != null && arena.scope().isAlive())
        {
            arena.close();
        }
    }
}
