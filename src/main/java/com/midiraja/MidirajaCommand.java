package com.midiraja;

import com.midiraja.midi.MidiOutProvider;
import com.midiraja.midi.MidiPort;
import com.midiraja.midi.MidiProviderFactory;
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

@Command(name = "midra", mixinStandardHelpOptions = true, version = "1.0",
        description = "Plays a MIDI file to an external or virtual MIDI device across platforms.")
public class MidirajaCommand implements Callable<Integer> {

    @Option(names = {"-l", "--list"}, description = "List available MIDI output devices")
    private boolean listDevices;

    @Option(names = {"-p", "--port"}, description = "Index or partial name of the MIDI output port")
    private String portSpec;

    @Option(names = {"-v", "--volume"}, description = "Global playback volume percentage (0-100)", defaultValue = "100", showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
    private Integer volume;

    @Option(names = {"-t", "--transpose"}, description = "Transpose notes by semitones (+/-)", defaultValue = "0", showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
    private Integer transpose;

    @Parameters(index = "0", description = "The MIDI file to play", arity = "0..1")
    private File midiFile;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new MidirajaCommand()).execute(args);
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

        if (volume != null && (volume < 0 || volume > 100)) {
            System.err.println("Error: Volume percentage must be between 0 and 100.");
            return 1;
        }

        if (midiFile == null || !midiFile.exists()) {
            System.err.println("Error: Missing or invalid MIDI file.");
            new CommandLine(this).usage(System.err);
            return 1;
        }

        if (portSpec == null) {
            System.err.println("Error: Please specify a MIDI port with --port.");
            return 1;
        }

        // 임시로 숫자 파싱 (다음 Task에서 Smart Matching으로 교체 예정)
        int portIndex;
        try {
            portIndex = Integer.parseInt(portSpec);
        } catch (NumberFormatException e) {
            System.err.println("Error: Port must be an integer index for now.");
            return 1;
        }

        playMidiWithProvider(midiFile, portIndex, midiProvider);
        return 0;
    }

    // 테스트용 플래그
    boolean isTestMode = false;

    void playMidiWithProvider(File file, int targetPortIndex, MidiOutProvider provider) throws Exception {
        List<MidiPort> ports = provider.getOutputPorts();
        
        if (targetPortIndex < 0 || targetPortIndex >= ports.size()) {
            System.err.println("Error: Invalid port index. Available ports: 0 to " + (ports.size() - 1));
            return;
        }

        MidiPort targetPort = ports.get(targetPortIndex);
        
        // 1. 포트 열기 및 셧다운 훅 등록
        provider.openPort(targetPortIndex);

        if (volume != null) {
            // 백분율(0-100)을 MIDI 볼륨(0-127)으로 변환
            int initialMidiVol = (int) (127 * (volume / 100.0));
            provider.setVolume(initialMidiVol);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[Midiraja] Stopping playback and clearing MIDI notes...");
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
                // CC 7 (Main Volume) 가로채기 및 스케일링
                if ((status & 0xF0) == 0xB0 && raw.length >= 3 && raw[1] == 7) {
                    if (volume != null) {
                        int originalVol = raw[2] & 0xFF;
                        int scaledVol = (int) (originalVol * (volume / 100.0));
                        raw[2] = (byte) Math.max(0, Math.min(127, scaledVol));
                    }
                }
                
                // Transpose 처리 (Note On: 0x90, Note Off: 0x80)
                int channel = status & 0x0F;
                if (transpose != null && channel != 9 && raw.length >= 2) {
                    int cmd = status & 0xF0;
                    if (cmd == 0x90 || cmd == 0x80) {
                        int note = raw[1] & 0xFF;
                        int transposedNote = Math.max(0, Math.min(127, note + transpose));
                        raw[1] = (byte) transposedNote;
                    }
                }

                long tickDiff = event.getTick() - lastTick;
                if (tickDiff > 0 && !isTestMode) {
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
