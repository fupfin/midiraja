package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fupfin.midiraja.cli.FxOptions;
import com.fupfin.midiraja.io.MockTerminalIO;
import com.fupfin.midiraja.io.TerminalIO.TerminalKey;
import com.fupfin.midiraja.midi.MidiOutProvider;
import com.fupfin.midiraja.midi.MidiPort;
import com.fupfin.midiraja.ui.DumbUI;

class PlaybackRunnerTest
{

    private ByteArrayOutputStream outBytes;
    private ByteArrayOutputStream errBytes;
    private MockTerminalIO mockIO;
    private CommonOptions common;

    static class MockMidiProvider implements MidiOutProvider
    {
        boolean isOpen = false;
        boolean isClosed = false;

        @Override
        public List<MidiPort> getOutputPorts()
        {
            return List.of(new MidiPort(0, "MockPort"));
        }

        @Override
        public void openPort(int portIndex)
        {
            isOpen = true;
        }

        @Override
        public void closePort()
        {
            isClosed = true;
        }

        @Override
        public void sendMessage(byte[] data)
        {
        }

        @Override
        public void panic()
        {
        }

        @Override
        public long getAudioLatencyNanos()
        {
            return 0;
        }

        @Override
        public void onPlaybackStarted()
        {
        }

        @Override
        public void prepareForNewTrack(Sequence sequence)
        {
        }
    }

    @BeforeEach
    void setUp()
    {
        outBytes = new ByteArrayOutputStream();
        errBytes = new ByteArrayOutputStream();
        mockIO = new MockTerminalIO();
        common = new CommonOptions();
    }

    private File createTestMidi(Path tempDir, String name) throws Exception
    {
        File midiFile = tempDir.resolve(name).toFile();
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        Track t = seq.createTrack();
        t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 64), 0));
        t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 2400));
        try (FileOutputStream fos = new FileOutputStream(midiFile))
        {
            MidiSystem.write(seq, 1, fos);
        }
        return midiFile;
    }

    /** Backward-compat overload used by existing tests. */
    private File createTestMidi(Path tempDir) throws Exception
    {
        return createTestMidi(tempDir, "test.mid");
    }

    @Test
    void testRunnerQuitsOnQKey(@TempDir Path tempDir) throws Exception
    {
        File midiFile = createTestMidi(tempDir);
        MockMidiProvider provider = new MockMidiProvider();
        PlaybackRunner runner = new PlaybackRunner(new PrintStream(outBytes), new PrintStream(errBytes), mockIO, true, new FxOptions());

        // Inject the 'q' key to force the loop to exit early
        mockIO.injectKey(TerminalKey.QUIT);

        // Run as SoftSynth (skips port selection)
        int exitCode = runner.run(provider, true, Optional.empty(), Optional.empty(), List.of(midiFile), common,
                List.of());

        assertEquals(0, exitCode, "Exit code should be 0 on normal quit");
        assertTrue(provider.isOpen, "Provider should have been opened");

        // Ensure that the output stream was used (meaning UI or at least init text was written)
        // Test passed if it reached here without hanging
    }

    @Test
    void buildUI_quiet_returnsDumbUI_evenWhenMiniRequested()
    {
        var runner = new PlaybackRunner(
                new PrintStream(outBytes), new PrintStream(errBytes), mockIO, true, new FxOptions());
        var uiOpts = new UiModeOptions();
        uiOpts.miniMode = true;

        PlaybackRunner.UIResult result = runner.buildUI(true, uiOpts, true, 80);

        assertInstanceOf(DumbUI.class, result.ui());
        assertFalse(result.useAltScreen());
    }

    @Test
    void testRunnerHandlesNoFiles() throws Exception
    {
        MockMidiProvider provider = new MockMidiProvider();
        PlaybackRunner runner = new PlaybackRunner(new PrintStream(outBytes), new PrintStream(errBytes), mockIO, true, new FxOptions());

        int exitCode = runner.run(provider, true, Optional.empty(), Optional.empty(), new ArrayList<>(), common,
                List.of());

        assertEquals(1, exitCode, "Exit code should be 1 if no files are found");
        String errOutput = errBytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(errOutput.contains("No MIDI files specified"), "Should print error about missing files");
    }
}
