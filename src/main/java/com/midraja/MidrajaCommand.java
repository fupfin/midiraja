package com.midraja;

import com.midraja.midi.MidiOutProvider;
import com.midraja.midi.MidiPort;
import com.midraja.midi.MidiProviderFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Track;
import java.io.File;
import java.util.concurrent.Callable;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Comparator;

@Command(name = "midraja", mixinStandardHelpOptions = true, version = "1.0",
        description = "Plays a MIDI file to an external or virtual MIDI device across platforms.")
public class MidrajaCommand implements Callable<Integer> {

    @Option(names = {"-l", "--list"}, description = "List available MIDI output devices")
    private boolean listDevices;

    @Option(names = {"-p", "--port"}, description = "Index of the MIDI output port")
    private Integer portIndex;

    @Parameters(index = "0", description = "The MIDI file to play", arity = "0..1")
    private File midiFile;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new MidrajaCommand()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        MidiOutProvider midiProvider = MidiProviderFactory.createProvider();

        if (listDevices) {
            List<MidiPort> ports = midiProvider.getOutputPorts();
            System.out.println("Available MIDI Output Devices:");
            if (ports.isEmpty()) {
                System.out.println("No MIDI output devices found.");
            } else {
                ports.forEach(System.out::println);
            }
            return 0;
        }

        if (midiFile == null || !midiFile.exists()) {
            System.err.println("Error: Missing or invalid MIDI file.");
            new CommandLine(this).usage(System.err);
            return 1;
        }

        if (portIndex == null) {
            System.err.println("Error: Please specify a MIDI port index with --port.");
            return 1;
        }

        playMidiWithProvider(midiFile, portIndex, midiProvider);
        return 0;
    }

    private void playMidiWithProvider(File file, int targetPortIndex, MidiOutProvider provider) throws Exception {
        List<MidiPort> ports = provider.getOutputPorts();
        
        if (targetPortIndex < 0 || targetPortIndex >= ports.size()) {
            System.err.println("Error: Invalid port index. Available ports: 0 to " + (ports.size() - 1));
            return;
        }

        MidiPort targetPort = ports.get(targetPortIndex);
        
        // 1. 포트 열기 및 셧다운 훅 등록
        provider.openPort(targetPortIndex);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[Midraja] Stopping playback and clearing MIDI notes...");
            provider.panic();
            provider.closePort();
        }));

        System.out.println("Started playing: " + file.getName() + " to " + targetPort.name());

        // 2. MIDI 파일 분석
        Sequence sequence = MidiSystem.getSequence(file);
        int resolution = sequence.getResolution();
        long totalTicks = sequence.getTickLength();
        float tempoBPM = 120.0f;

        List<MidiEvent> allEvents = new ArrayList<>();
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) allEvents.add(track.get(i));
        }
        Collections.sort(allEvents, Comparator.comparingLong(MidiEvent::getTick));

        // 3. 재생 루프 및 진행률 표시
        long lastTick = 0;
        for (MidiEvent event : allEvents) {
            MidiMessage msg = event.getMessage();
            byte[] raw = msg.getMessage();
            
            // 템포 변경 처리
            if (raw.length >= 6 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0x51) {
                int microsecPerQuarterNote = ((raw[3] & 0xFF) << 16) | ((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF);
                tempoBPM = 60000000.0f / microsecPerQuarterNote;
                continue;
            }

            // 일반 메시지 전송 (Meta 및 SysEx 제외한 짧은 메시지들)
            int status = raw[0] & 0xFF;
            if (status < 0xF0) {
                long tickDiff = event.getTick() - lastTick;
                if (tickDiff > 0) {
                    long sleepMs = (long) (tickDiff * (60000.0 / (tempoBPM * resolution)));
                    if (sleepMs > 0) Thread.sleep(sleepMs);
                }

                provider.sendMessage(raw);
                lastTick = event.getTick();

                // 100틱마다 진행률 표시 (화면 깜빡임 방지)
                if (lastTick % 100 == 0) {
                    printProgressBar(lastTick, totalTicks, tempoBPM);
                }
            }
        }

        System.out.println("\nPlayback finished.");
        provider.panic();
        provider.closePort();
    }

    private void printProgressBar(long current, long total, float bpm) {
        int barLength = 40;
        double progress = (double) current / total;
        int completed = (int) (progress * barLength);
        
        StringBuilder bar = new StringBuilder("\r[");
        for (int i = 0; i < barLength; i++) {
            bar.append(i < completed ? "=" : (i == completed ? ">" : " "));
        }
        bar.append(String.format("] %d%% (BPM: %.1f)", (int) (progress * 100), bpm));
        System.out.print(bar.toString());
    }
}
