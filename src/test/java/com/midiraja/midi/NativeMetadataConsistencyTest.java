/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.midi;

import java.io.IOException;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that every FFM downcall {@link FunctionDescriptor} used in
 * {@link FFMMuntNativeBridge} has a matching entry in
 * {@code reachability-metadata.json}.
 *
 * <p>GraalVM native image requires all FFM downcall descriptor types to be
 * pre-registered in the metadata file before {@code nativeCompile}. Without
 * registration, the binary throws {@code MissingForeignRegistrationError} at
 * startup — a silent failure that only surfaces after the slow (~30s) native
 * compile cycle.
 *
 * <p>This test runs in JVM mode (no native library or ROM files needed) and
 * fails immediately with a clear message when a new downcall handle is added
 * to {@link FFMMuntNativeBridge} but its descriptor is not registered in the
 * metadata.
 *
 * <h3>Type mapping: FunctionDescriptor → metadata</h3>
 * <ul>
 *   <li>{@code ADDRESS}     → {@code "void*"}
 *   <li>{@code JAVA_INT}    → {@code "jint"}
 *   <li>{@code JAVA_BYTE}   → {@code "jint"} — C ABI widens byte to int in registers
 *   <li>{@code JAVA_SHORT}  → {@code "jint"} — same widening rule
 *   <li>{@code JAVA_LONG}   → {@code "jlong"}
 *   <li>{@code JAVA_DOUBLE} → {@code "jdouble"}
 *   <li>void return         → {@code "void"}
 * </ul>
 *
 * <h3>GraalVM scalarisation note</h3>
 * GraalVM can expand a {@code MemorySegment} context <em>field</em> into two
 * {@code void*} leaf params (base + offset), turning a 4-param descriptor into
 * a 5-param leaf type.  This test only checks the "standard" 4-param form;
 * the 5-param expanded form must be added manually (see the comment block above
 * the expanded entries in the JSON file).
 *
 * <h3>Workflow</h3>
 * <ol>
 *   <li>Add a new {@code linker.downcallHandle()} call to
 *       {@link FFMMuntNativeBridge}.
 *   <li>Add the same {@link FunctionDescriptor} to
 *       {@link FFMMuntNativeBridge#allDowncallDescriptors()}.
 *   <li>Run {@code ./gradlew test} — <em>this test will fail</em> with the
 *       exact JSON snippet to add.
 *   <li>Add the snippet to {@code reachability-metadata.json}.
 *   <li>If needed, also add the GraalVM-expanded 5-param form.
 *   <li>Run {@code ./gradlew nativeCompile} — the binary will start cleanly.
 * </ol>
 */
class NativeMetadataConsistencyTest {

    private static final Path METADATA_FILE = Path.of(
        "src/main/resources/META-INF/native-image/com.midiraja/midiraja/reachability-metadata.json"
    );

    @Test
    void testAllFFMDescriptorsRegisteredInMetadata() throws IOException {
        String json = Files.readString(METADATA_FILE);
        Set<String> registeredKeys = parseDowncallKeys(json);

        List<String> missing = new ArrayList<>();
        for (FunctionDescriptor fd : FFMMuntNativeBridge.allDowncallDescriptors()) {
            String key = toMetadataKey(fd);
            if (!registeredKeys.contains(key)) {
                missing.add(
                    "  parameterTypes: " + paramTypesJson(fd) + ", returnType: \"" + returnType(fd) + "\""
                    + "\n    (from FunctionDescriptor: " + fd + ")"
                );
            }
        }

        if (!missing.isEmpty()) {
            fail("""
                The following FFM downcall descriptors are used in FFMMuntNativeBridge \
                but are NOT registered in reachability-metadata.json.
                GraalVM native image will throw MissingForeignRegistrationError at runtime.

                Add each missing entry to the 'foreign.downcalls' array in:
                  %s

                Missing entries:
                %s

                Also check whether GraalVM needs a 5-param 'expanded' form — see class-level
                Javadoc in NativeMetadataConsistencyTest for details.
                """.formatted(METADATA_FILE, String.join("\n", missing)));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Converts a {@link FunctionDescriptor} to the lookup key used for
     * matching against metadata entries.
     *
     * <p>Format: {@code "void*,jint,void*,void*->jint"}
     */
    static String toMetadataKey(FunctionDescriptor fd) {
        String params = fd.argumentLayouts().stream()
            .map(NativeMetadataConsistencyTest::layoutToMetadataType)
            .collect(Collectors.joining(","));
        return params + "->" + returnType(fd);
    }

    private static String returnType(FunctionDescriptor fd) {
        return fd.returnLayout()
            .map(NativeMetadataConsistencyTest::layoutToMetadataType)
            .orElse("void");
    }

    /**
     * Maps a {@link MemoryLayout} to its GraalVM metadata type string.
     *
     * <p>Sub-int value types (byte, short, boolean, char) are widened to
     * {@code "jint"} because C ABI default argument promotion places them in
     * an int-sized register. GraalVM's leaf-type representation reflects this.
     */
    static String layoutToMetadataType(MemoryLayout layout) {
        if (layout instanceof AddressLayout) return "void*";
        Class<?> carrier = ((ValueLayout) layout).carrier();
        if (carrier == byte.class
                || carrier == boolean.class
                || carrier == short.class
                || carrier == char.class
                || carrier == int.class) return "jint";
        if (carrier == long.class)   return "jlong";
        if (carrier == float.class)  return "jfloat";
        if (carrier == double.class) return "jdouble";
        throw new IllegalArgumentException("Unknown ValueLayout carrier: " + carrier);
    }

    /** Formats the parameter types as a JSON array string (for the failure message). */
    private static String paramTypesJson(FunctionDescriptor fd) {
        String inner = fd.argumentLayouts().stream()
            .map(l -> "\"" + layoutToMetadataType(l) + "\"")
            .collect(Collectors.joining(", "));
        return "[" + inner + "]";
    }

    /**
     * Parses the {@code foreign.downcalls} array from the metadata JSON and
     * returns a set of lookup keys in the format {@code "t1,t2->ret"}.
     *
     * <p>Uses a two-phase regex approach: first locates the {@code downcalls}
     * section, then matches each {@code {"parameterTypes":[...],"returnType":"..."}}
     * entry regardless of field ordering or whitespace.
     */
    private static Set<String> parseDowncallKeys(String json) {
        Set<String> keys = new HashSet<>();

        int downcallsPos = json.indexOf("\"downcalls\"");
        if (downcallsPos < 0) return keys;
        String section = json.substring(downcallsPos);

        // Matches a downcall entry in either field order, across newlines.
        // Group 1/2: paramTypes array content, returnType value (order A)
        // Group 3/4: returnType value, paramTypes array content (order B)
        Pattern entryPattern = Pattern.compile(
            "\\{[^{}]*"
            + "\"parameterTypes\"\\s*:\\s*\\[([^\\]]*)\\][^{}]*"
            + "\"returnType\"\\s*:\\s*\"([^\"]+)\"[^{}]*\\}"
            + "|"
            + "\\{[^{}]*"
            + "\"returnType\"\\s*:\\s*\"([^\"]+)\"[^{}]*"
            + "\"parameterTypes\"\\s*:\\s*\\[([^\\]]*)\\][^{}]*\\}",
            Pattern.DOTALL
        );
        // Matches individual quoted string values inside an array
        Pattern stringPattern = Pattern.compile("\"([^\"]+)\"");

        Matcher m = entryPattern.matcher(section);
        while (m.find()) {
            String rawParams;
            String returnType;
            if (m.group(1) != null) {
                rawParams  = m.group(1);
                returnType = m.group(2);
            } else {
                returnType = m.group(3);
                rawParams  = m.group(4);
            }

            List<String> params = new ArrayList<>();
            Matcher pm = stringPattern.matcher(rawParams);
            while (pm.find()) params.add(pm.group(1));

            keys.add(String.join(",", params) + "->" + returnType);
        }
        return keys;
    }
}
