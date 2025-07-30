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
import javax.sound.midi.MetaMessage;
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
            List<MidiPort> ports = midiProvider.getOutputPorts();
            if (ports.isEmpty()) {
                System.err.println("Error: No MIDI output devices found.");
                return 1;
            }
            System.out.println("Available MIDI Output Devices:");
            ports.forEach(System.out::println);
            
            java.util.Scanner scanner = new java.util.Scanner(System.in);
            while (portSpec == null || portSpec.trim().isEmpty()) {
                System.out.print("Select a port by number or name (or type 'q' to quit): ");
                if (scanner.hasNextLine()) {
                    portSpec = scanner.nextLine().trim();
                    if ("q".equalsIgnoreCase(portSpec)) {
                        System.out.println("Exiting.");
                        return 1;
                    }
                } else {
                    // EOF 도달 시 (예: 파이프라이닝 환경)
                    System.err.println("\nError: Could not read standard input.");
                    return 1;
                }
            }
        }

        int portIndex;
        try {
            portIndex = findPortIndex(portSpec, midiProvider.getOutputPorts());
        } catch (Exception e) {
            System.err.println(e.getMessage());
            return 1;
        }

        playMidiWithProvider(midiFile, portIndex, midiProvider);
        return 0;
    }

    // 테스트용 플래그
    boolean isTestMode = false;

    int findPortIndex(String portSpec, List<MidiPort> ports) throws Exception {
        if (portSpec == null || ports.isEmpty()) {
            throw new Exception("No matching MIDI port found.");
        }

        // 1. Try exact integer match
        try {
            int index = Integer.parseInt(portSpec);
            for (MidiPort port : ports) {
                if (port.index() == index) {
                    return index;
                }
            }
        } catch (NumberFormatException ignored) {}

        // 2. Try partial case-insensitive match
        List<MidiPort> matches = new ArrayList<>();
        String lowerSpec = portSpec.toLowerCase();
        
        for (MidiPort port : ports) {
            if (port.name() != null && port.name().toLowerCase().contains(lowerSpec)) {
                matches.add(port);
            }
        }

        if (matches.size() == 1) {
            return matches.get(0).index();
        } else if (matches.size() > 1) {
            StringBuilder error = new StringBuilder("Ambiguous port name. Multiple matches found:\n");
            for (MidiPort port : matches) {
                error.append(String.format("  [%d] %s\n", port.index(), port.name()));
            }
            throw new Exception(error.toString());
        }

        throw new Exception("No matching MIDI port found for: " + portSpec);
    }

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

        // 정상 종료 플래그 (배열을 사용하여 익명 클래스 내에서 접근)
        final boolean[] isFinished = {false};

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!isFinished[0]) {
                System.out.println("\n[Midiraja] Stopping playback and clearing MIDI notes...");
                provider.panic();
                provider.closePort();
            }
        }));

        System.out.println("Started playing: " + file.getName() + " to " + targetPort.name());

        // 2. MIDI 파일 분석
        Sequence sequence = MidiSystem.getSequence(file);
        
        // 메타데이터(제목, 저작권 등) 추출 및 출력
        extractAndPrintMetadata(sequence);

        int resolution = sequence.getResolution();
        long totalTicks = sequence.getTickLength();
        float tempoBPM = 120.0f;

        List<MidiEvent> allEvents = new ArrayList<>();
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) allEvents.add(track.get(i));
        }
        Collections.sort(allEvents, Comparator.comparingLong(MidiEvent::getTick));

        // 3. UI 렌더링용 공유 변수
        final double[] channelLevels = new double[16];
        final long[] currentTickRef = {0};
        final float[] currentBpmRef = {120.0f};
        final boolean[] isPlaying = {true};

        // UI 렌더링 데몬 스레드 시작
        Thread uiThread = new Thread(() -> {
            String[] blocks = {" ", " ", "▂", "▃", "▄", "▅", "▆", "▇", "█"};
            while (isPlaying[0]) {
                StringBuilder sb = new StringBuilder("\rVol:[");
                for (int i = 0; i < 16; i++) {
                    int levelIndex = (int) Math.round(channelLevels[i] * 8);
                    if (levelIndex < 0) levelIndex = 0;
                    if (levelIndex > 8) levelIndex = 8;
                    sb.append(blocks[levelIndex]);
                    
                    // 자연스러운 감쇄 (Decay): 약 33ms마다 0.15 감소
                    channelLevels[i] -= 0.15;
                    if (channelLevels[i] < 0) channelLevels[i] = 0;
                }
                sb.append("] ");
                
                double pct = totalTicks > 0 ? (double) currentTickRef[0] / totalTicks : 0;
                sb.append(String.format("%3d%% (BPM: %5.1f)   ", (int) (pct * 100), currentBpmRef[0]));
                
                System.out.print(sb.toString());
                
                try {
                    Thread.sleep(33); // ~30 FPS
                } catch (InterruptedException ignored) {}
            }
        });
        uiThread.setDaemon(true);
        uiThread.start();
        
        // 4. 재생 루프 및 상태 갱신
        long lastTick = 0;
        for (MidiEvent event : allEvents) {
            MidiMessage msg = event.getMessage();
            byte[] raw = msg.getMessage();
            
            // 템포 변경 처리
            if (raw.length >= 6 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0x51) {
                int microsecPerQuarterNote = ((raw[3] & 0xFF) << 16) | ((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF);
                tempoBPM = 60000000.0f / microsecPerQuarterNote;
                currentBpmRef[0] = tempoBPM;
                continue;
            }

            // 일반 메시지 전송 (Meta 및 SysEx 제외한 짧은 메시지들)
            int status = raw[0] & 0xFF;
            if (status < 0xF0) {
                int cmd = status & 0xF0;
                int channel = status & 0x0F;

                // CC 7 (Main Volume) 가로채기 및 스케일링
                if (cmd == 0xB0 && raw.length >= 3 && raw[1] == 7) {
                    if (volume != null) {
                        int originalVol = raw[2] & 0xFF;
                        int scaledVol = (int) (originalVol * (volume / 100.0));
                        raw[2] = (byte) Math.max(0, Math.min(127, scaledVol));
                    }
                }
                
                // Transpose 처리 (Note On: 0x90, Note Off: 0x80)
                if (transpose != null && channel != 9 && raw.length >= 2) {
                    if (cmd == 0x90 || cmd == 0x80) {
                        int note = raw[1] & 0xFF;
                        int transposedNote = Math.max(0, Math.min(127, note + transpose));
                        raw[1] = (byte) transposedNote;
                    }
                }

                // SC-55 레벨 미터 업데이트 (Note On with velocity > 0)
                if (cmd == 0x90 && raw.length >= 3) {
                    int noteVelocity = raw[2] & 0xFF;
                    if (noteVelocity > 0) {
                        // 동시에 여러 노트가 눌릴 수 있으므로, 기존 값보다 클 때만 갱신
                        channelLevels[channel] = Math.max(channelLevels[channel], noteVelocity / 127.0);
                    }
                }

                long tickDiff = event.getTick() - lastTick;
                if (tickDiff > 0 && !isTestMode) {
                    long sleepMs = (long) (tickDiff * (60000.0 / (tempoBPM * resolution)));
                    if (sleepMs > 0) Thread.sleep(sleepMs);
                }

                provider.sendMessage(raw);
                lastTick = event.getTick();
                                currentTickRef[0] = lastTick;
                            }
                        }
                
                        isFinished[0] = true;
                        isPlaying[0] = false;
                        try { uiThread.join(200); } catch (InterruptedException ignored) {}
                        System.out.println("\nPlayback finished.");
                        provider.panic();
                        provider.closePort();
                    }
                
                    private void extractAndPrintMetadata(Sequence sequence) {
                        String title = null;
                        String copyright = null;
                        List<String> texts = new ArrayList<>();
                
                        for (Track track : sequence.getTracks()) {
                            for (int i = 0; i < track.size(); i++) {
                                MidiMessage msg = track.get(i).getMessage();
                                if (msg instanceof MetaMessage) {
                                    MetaMessage meta = (MetaMessage) msg;
                                    int type = meta.getType();
                                    byte[] data = meta.getData();
                                    
                                    if (data != null && data.length > 0) {
                                        // MIDI Text events are often ASCII or CP1252/SJIS depending on origin. 
                                        // Using ISO-8859-1 or standard String constructor usually works for generic English text.
                                        String text = new String(data).trim();
                                        // Ignore garbage or empty strings
                                        if (text.isEmpty() || text.matches("^[\\s\\p{C}]+$")) continue;
                
                                        if (type == 0x03 && title == null) {
                                            title = text;
                                        } else if (type == 0x02 && copyright == null) {
                                            copyright = text;
                                        } else if (type == 0x01 && texts.size() < 3) {
                                            // Collect up to 3 info texts to avoid flooding the screen
                                            if (!texts.contains(text)) {
                                                texts.add(text);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                
                        if (title != null) {
                            System.out.println("  Title: " + title);
                        }
                        if (copyright != null) {
                            System.out.println("  Copyright: " + copyright);
                        }
                        for (String info : texts) {
                            System.out.println("  Info: " + info);
                        }
                    }
                }
