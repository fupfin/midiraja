package com.fupfin.midiraja.cli;

import com.fupfin.midiraja.MidirajaCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.io.*;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ResumeCommandTest {

    @Test
    void resumeWithEmptyHistory_printsNoHistory(@TempDir Path tmpDir) {
        System.setProperty("midiraja.history.path", tmpDir.resolve("history.json").toString());
        try {
            var out = new ByteArrayOutputStream();
            var err = new ByteArrayOutputStream();
            var cmd = new CommandLine(new MidirajaCommand())
                    .setOut(new PrintWriter(out))
                    .setErr(new PrintWriter(err));
            int exitCode = cmd.execute("resume", "--non-interactive");
            var output = err.toString() + out.toString();
            assertTrue(output.contains("No session history"),
                    "Expected 'No session history' message, got: " + output);
        } finally {
            System.clearProperty("midiraja.history.path");
        }
    }
}
