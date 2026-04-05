/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParentCommand;

import com.fupfin.midiraja.LibraryPaths;
import com.fupfin.midiraja.MidirajaCommand;
import com.fupfin.midiraja.Version;
import com.fupfin.midiraja.io.AppLogger;
import com.fupfin.midiraja.midi.AbstractFFMBridge;
import com.fupfin.midiraja.midi.MidiProviderFactory;
import com.fupfin.midiraja.midi.gus.GusSynthProvider;

@Command(name = "info", mixinStandardHelpOptions = true, description = "Print system and environment diagnostics for bug reports.")
public class InfoCommand implements Callable<Integer>
{
    @ParentCommand
    @Nullable
    private MidirajaCommand parent;

    @Mixin
    private final CommonOptions common = new CommonOptions();

    record LibInfo(String name, AbstractFFMBridge.LibProbeResult result)
    {
    }

    @Override
    public Integer call()
    {
        AppLogger.configure(common.logLevel.orElse(null));
        var p = requireNonNull(parent);

        String version = Version.VERSION;
        String commit = Version.COMMIT;
        boolean nativeImage = System.getProperty("org.graalvm.nativeimage.imagecode") != null;
        String osName = System.getProperty("os.name", "unknown");
        String osArch = System.getProperty("os.arch", "unknown");
        String javaVersion = System.getProperty("java.version", "unknown");

        String javaHome = System.getenv("JAVA_HOME");
        String midraData = System.getenv("MIDRA_DATA");
        String ldLibPath = System.getenv("LD_LIBRARY_PATH");

        List<LibInfo> libs = probeLibraries(osName);

        List<String> portNames;
        try
        {
            portNames = MidiProviderFactory.createProvider().getOutputPorts()
                    .stream().map(mp -> "[" + mp.index() + "] " + mp.name()).toList();
        }
        catch (Exception e)
        {
            portNames = List.of("(error: " + e.getMessage() + ")");
        }

        String sf3 = TsfCommand.findBundledSf3();
        String gusPatches = GusSynthProvider.findPatches();

        String osLower = osName.toLowerCase(Locale.ROOT);
        String[] searchPaths = osLower.contains("mac")
                ? LibraryPaths.DARWIN
                : (osLower.contains("linux") ? LibraryPaths.LINUX : LibraryPaths.WINDOWS);

        String report = buildReport(version, commit, nativeImage, osName, osArch, javaVersion,
                javaHome, midraData, ldLibPath, libs, portNames, sf3, gusPatches, searchPaths);
        p.getOut().println(report);
        return 0;
    }

    private static List<LibInfo> probeLibraries(String osName)
    {
        String osLower = osName.toLowerCase(Locale.ROOT);
        String ext = osLower.contains("mac") ? ".dylib" : (osLower.contains("win") ? ".dll" : ".so");
        return List.of(
                new LibInfo("libmidiraja_audio",
                        AbstractFFMBridge.probeLibrary("miniaudio", "libmidiraja_audio" + ext)),
                new LibInfo("libmt32emu",
                        AbstractFFMBridge.probeLibrary("munt", "libmt32emu" + ext)),
                new LibInfo("libADLMIDI",
                        AbstractFFMBridge.probeLibrary("adlmidi", "libADLMIDI" + ext)),
                new LibInfo("libOPNMIDI",
                        AbstractFFMBridge.probeLibrary("opnmidi", "libOPNMIDI" + ext)),
                new LibInfo("libtsf",
                        AbstractFFMBridge.probeLibrary("tsf", "libtsf" + ext)),
                new LibInfo("libfluidsynth",
                        probeFluidSynth(osLower)));
    }

    private static AbstractFFMBridge.LibProbeResult probeFluidSynth(String osLower)
    {
        if (osLower.contains("mac"))
            return AbstractFFMBridge.probeLibrary("", "libfluidsynth.dylib");
        if (osLower.contains("win"))
            return AbstractFFMBridge.probeLibrary("", "libfluidsynth.dll");
        return AbstractFFMBridge.probeLibrary("", "libfluidsynth.so", "libfluidsynth.so.3");
    }

    static String buildReport(
            String version, String commit, boolean nativeImage,
            String osName, String osArch, String javaVersion,
            @Nullable String javaHome, @Nullable String midraData, @Nullable String ldLibPath,
            List<LibInfo> libs,
            List<String> portNames,
            @Nullable String sf3Path,
            @Nullable String gusPatchesPath,
            String[] searchPaths)
    {
        var sb = new StringBuilder();

        sb.append("=== midra info ===\n\n");

        sb.append("Version & Runtime\n");
        sb.append("  midiraja : ").append(version)
                .append(" (commit: ").append(commit).append(")\n");
        sb.append("  runtime  : ").append(nativeImage ? "GraalVM native-image" : "JVM").append("\n");
        sb.append("  os       : ").append(osName).append(" / ").append(osArch).append("\n");
        sb.append("  java     : ").append(javaVersion).append("\n");

        sb.append("\nEnvironment\n");
        sb.append("  JAVA_HOME        : ").append(javaHome != null ? javaHome : "(not set)").append("\n");
        sb.append("  MIDRA_DATA       : ").append(midraData != null ? midraData : "(not set)").append("\n");
        sb.append("  LD_LIBRARY_PATH  : ").append(ldLibPath != null ? ldLibPath : "(not set)").append("\n");

        sb.append("\nNative Libraries\n");
        for (var lib : libs)
        {
            String status = lib.result().found() ? "\u2713" : "\u2717";
            String path = lib.result().resolvedPath() != null ? lib.result().resolvedPath() : "not found";
            sb.append("  ").append(status).append(" ").append(lib.name())
                    .append(" : ").append(path).append("\n");
        }

        sb.append("\nLibrary Search Paths\n");
        for (String sp : searchPaths)
        {
            sb.append("  ").append(sp).append("\n");
        }
        if (searchPaths.length == 0)
        {
            sb.append("  (none)\n");
        }

        sb.append("\nMIDI Ports\n");
        if (portNames.isEmpty())
        {
            sb.append("  (none found)\n");
        }
        else
        {
            for (String port : portNames)
            {
                sb.append("  ").append(port).append("\n");
            }
        }

        sb.append("\nSoundFont\n");
        sb.append("  FluidR3_GM.sf3 : ")
                .append(sf3Path != null ? sf3Path : "not found").append("\n");

        sb.append("\nGUS Patches\n");
        sb.append("  patches : ")
                .append(gusPatchesPath != null ? gusPatchesPath : "not found").append("\n");

        return sb.toString();
    }
}
