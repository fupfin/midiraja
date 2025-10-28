# MT-32 (Munt) Integration Architecture in Midiraja

Technical reference for the Munt MT-32 emulator integration. Covers component structure, threading model, timing design, MT-32 hardware constraints, and FFM API usage.

---

## 1. Component Architecture

The integration consists of three layers:

### 1.1 C Native Layer

**`libmt32emu`** — Munt의 C++ 코어. MT-32 LA 합성 엔진. 컴파일 결과물은 `libmt32emu.dylib/so/dll`이며, `c_interface.cpp`로 노출된 플랫 C-API를 통해서만 접근한다.

**`midiraja_audio.c`** — miniaudio 기반 오디오 드라이버 래퍼. 내부에 이벤트-드리븐 링 버퍼를 두어 Java 렌더 스레드와 OS 오디오 콜백 사이의 PCM 데이터를 중개한다. 링 버퍼가 가득 차면 Java 스레드를 네이티브 레벨에서 블로킹하고, OS 콜백이 샘플을 소비할 때 깨운다(`ma_event_wait` / `ma_event_signal`).

### 1.2 FFM Bridge Layer (`FFMMuntNativeBridge.java`)

Java 25 `java.lang.foreign` API를 사용해 `libmt32emu` C-API를 직접 바인딩한다. 핵심 책임:

- `Arena.ofShared()`로 PCM 렌더링 버퍼를 off-heap 할당 (GC 중단 방지)
- 플레이백 스레드에서 MIDI 이벤트를 타임스탬프와 함께 Munt 내부 큐에 직접 삽입 (`mt32emu_play_msg_at`, `mt32emu_play_sysex_at`)
- 렌더 스레드에서 PCM을 생성하고 타이밍 기준값 갱신

### 1.3 Provider Layer (`MuntSynthProvider.java`)

`SoftSynthProvider` 인터페이스를 구현해 `PlaybackEngine`이 Munt를 다른 신디사이저와 동일하게 다룰 수 있게 한다. 렌더 스레드 생명주기를 관리한다.

---

## 2. 오디오 렌더 스레드 페이싱

렌더 스레드는 Munt로부터 PCM을 생성한 뒤 `audio.push(pcmBuffer)`를 호출한다. 이 호출은 링 버퍼에 공간이 생길 때까지 네이티브 레벨에서 블로킹된다. OS 오디오 드라이버가 샘플을 소비하면 `data_callback`이 이벤트를 시그널해 스레드가 즉시 재개된다.

이 구조의 핵심 특성:
- **렌더 스레드는 최대 ~16ms 동안 블로킹될 수 있다** (512 frames @ 32kHz = 16ms)
- Java `Thread.sleep()`은 사용하지 않는다 — OS 스케줄러/GC로 인한 오버슬립이 버퍼 언더런을 유발하기 때문
- 링 버퍼 용량: 4096 frames × 2 channels = 8192 samples (stereo)

---

## 3. MIDI 이벤트 타이밍 (두 스레드 간 동기화)

### 3.1 Munt의 이벤트 처리 방식

`mt32emu_render_bit16s`는 내부적으로 `doRenderStreams()`를 호출한다. 이 함수는 렌더 청크를 이벤트 타임스탬프 위치에서 분할한다:

```
samplesToNextEvent = event.timestamp - getRenderedSampleCount()
```

- `samplesToNextEvent > 0`: 이벤트 위치까지 무음을 렌더링한 뒤 이벤트 처리 → 노트가 정확한 샘플 위치에서 시작
- `samplesToNextEvent <= 0`: 이벤트를 즉시 처리하되 최소 1 sample(31µs) 간격만 렌더링 → MT-32 LA 합성의 attack envelope가 전개될 시간이 없어 사실상 무음

따라서 **MIDI 이벤트는 반드시 미래 타임스탬프를 가져야** 정상적으로 발음된다.

### 3.2 wall-clock 기반 타임스탬프 계산

플레이백 스레드는 Munt에 이벤트를 삽입할 때 다음 공식으로 타임스탬프를 계산한다:

```
timestamp = lastRenderedSampleCount + (nanoTime() - lastRenderCompletedNanos) × 32000 / 1_000_000_000
```

렌더 스레드가 `audio.push()`에서 블로킹된 동안에도 벽시계는 계속 흐른다. 서로 다른 시각에 삽입된 이벤트들은 자연스럽게 단조 증가하는 미래 타임스탬프를 갖게 된다. 렌더 스레드가 재개되면 Munt가 각 이벤트를 해당 샘플 위치에 정확히 배치한다.

렌더 스레드는 매 `renderAudio()` 완료 후 두 기준값을 갱신한다:

```java
private volatile int  lastRenderedSampleCount;   // mt32emu_get_internal_rendered_sample_count()
private volatile long lastRenderCompletedNanos;   // System.nanoTime()
```

### 3.3 스레드 안전성

`mt32emu_play_msg_at` / `mt32emu_play_sysex_at`는 Munt 내부 `MidiEventQueue`에 락을 걸고 삽입하므로 플레이백/렌더 스레드 동시 호출이 안전하다. Java 레벨의 별도 락은 필요하지 않다.

반면 `mt32emu_play_msg_now` / `mt32emu_play_sysex_now`는 스레드 비안전 — 렌더 스레드 내에서만 호출해야 하며, 호출 즉시 현재 렌더 위치의 타임스탬프로 삽입된다.

### 3.4 타이밍 기준값 초기화 시점

`lastRenderCompletedNanos`를 **언제** 초기화하느냐가 곡 시작 직후의 무음 여부를 결정한다.

**잘못된 초기화 시점 — 객체 생성자:**

```java
// ❌ 생성자 시점. ROM 로딩·synth 초기화·오디오 기기 설정 등이 아직 남아 있다.
private volatile long lastRenderCompletedNanos = System.nanoTime();
```

생성 이후 ROM 로딩, `openSynth()`, 오디오 기기 초기화 등 수백 ms ~ 수 초의 준비 과정이 이어진다. 첫 MIDI 이벤트가 도달하면 `computeTimestamp()`는 이 경과 시간 전체를 미래 샘플 오프셋으로 변환한다. 렌더 스레드는 해당 샘플 위치까지 묵음을 생성한 뒤에야 첫 노트를 발음한다.

**올바른 초기화 시점 — 렌더 스레드 진입 직전:**

렌더 클럭이 실제로 시작되는 시점(첫 `renderAudio()` 직전)에 기준값을 리셋해야 한다. 두 곳에서 중복 초기화하면 더 안전하다:

1. `openSynth()` 완료 직후 — ROM 로딩·합성 엔진 초기화 시간을 제거한다.
2. 렌더 스레드의 루프 진입 직전 — 오디오 기기 초기화·스레드 스케줄링 지연까지 흡수한다.

```java
// openSynth() 마지막 줄
lastRenderedSampleCount = 0;
lastRenderCompletedNanos = System.nanoTime();

// render thread, 루프 진입 직전
bridge.resetRenderTiming(); // 같은 동작, 스레드 내에서 한 번 더
while (running) { ... }
```

추가 방어책으로 `elapsedSamples`에 상한을 두면, 초기화가 늦어지더라도 이벤트가 임의의 먼 미래로 배정되는 최악의 경우를 막을 수 있다:

```java
// 링 버퍼 용량을 상한으로 사용 (4096 frames = 128ms @ 32kHz)
elapsedSamples = Math.min(elapsedSamples, RING_BUFFER_CAPACITY_FRAMES);
```

---

## 4. MT-32 하드웨어 특성

### 4.1 MIDI 채널 매핑

MT-32의 기본 채널 배정:

```cpp
chanAssign[i] = i + 1;  // Part 0 → MIDI channel 1 (0-indexed)
chantable[0]  = 0xFF;   // MIDI channel 0 → 미배정 (이벤트 무시)
```

GM(General MIDI)의 채널 1(1-indexed)은 MT-32의 채널 0(0-indexed)에 해당하는데, 이 채널은 기본적으로 미배정 상태다. 일반 MIDI 파일을 그대로 재생하면 첫 번째 트랙이 무음이 되는 원인이다.

Part 1-8을 MIDI 채널 1-8(1-indexed)에 매핑하려면 `openSynth()` 직후 SysEx로 재매핑해야 한다:

```
// Part 1을 MIDI Channel 1(0x00)에 매핑: Address 10 00 00
F0 41 10 16 12  10 00 00  00  [Checksum] F7
```

### 4.2 SysEx 체크섬

MT-32는 Roland SysEx 체크섬을 엄격히 검증한다. 체크섬이 맞지 않으면 메시지를 조용히 무시한다. Roland 체크섬: `(128 - (address_bytes + data_bytes의 합 % 128)) % 128`.

리셋 SysEx의 올바른 형식 (11 bytes):
```
F0 41 10 16 12  7F 00 00  00  01  F7
               [addr 3B] [data][chk]
```

### 4.3 마스터 볼륨 오버라이드

Munt의 `Extensions` 구조체는 zero-initialize된다. `masterVolumeOverride`의 기본값 `0`은 `Synth::open()` 내부 조건 `if (masterVolumeOverride < 100)`을 만족시켜 마스터 볼륨을 0으로 덮어쓴다 — 완전한 무음.

`mt32emu_open_synth` 호출 전에 반드시 오버라이드를 비활성화해야 한다:

```java
mt32emu_set_master_volume_override.invokeExact(context, (byte) 0xFF); // 0xFF > 100 → 오버라이드 비활성
mt32emu_set_stereo_output_samplerate.invokeExact(context, 32000.0);
int rc = (int) mt32emu_open_synth.invokeExact(context);
```

### 4.4 폴리포니와 Partial Generator 관리

MT-32는 32개의 partial generator를 가진다. 8개 파트 × 파트당 최대 4 partial이다. 각 partial은 `INACTIVE → ATTACK → SUSTAIN → RELEASE` 상태를 순서대로 거친다.

**CC 120 (All Sound Off)의 한계**: MT-32는 CC 120 표준보다 오래된 하드웨어다. Munt에서 CC 120을 처리하면 partial이 즉시 INACTIVE로 전환되는 것이 아니라 **RELEASE 상태로 진입**해 LA 합성 엔벨로프 decay 과정을 그대로 거친다. RELEASE 상태의 partial은 여전히 generator를 점유하며, 새 노트가 그 generator를 필요로 하면 `onNoteOnIgnored` 콜백이 발생하거나 voice stealing이 시도된다. 이 decay가 완료되기까지 **최대 ~2초**가 걸릴 수 있다.

```
panic() 호출 후 partial 상태:
  ch 0-15, note 0-127 → RELEASE (generator 점유 중)
  5 render cycles(80ms) 후에도 mt32emu_has_active_partials() = true
```

**Render thread가 멈추면 Munt의 내부 시간도 멈춘다**: `renderPaused = true`로 render thread를 정지시키면 `renderAudio()` 호출이 중단되고, Munt의 내부 샘플 카운터가 전진하지 않는다. RELEASE 상태의 partial은 `renderAudio()` 호출 횟수에 따라 decay하므로, render thread를 멈춘 동안에는 reverb tail이 전혀 사라지지 않는다. render thread를 재개해도 해당 reverb tail을 실시간으로 전부 렌더링해야 decay가 끝난다.

**Fast-drain 패턴**: `prepareForNewTrack()` 안에서 render thread가 정지된 상태로 `renderAudio()`를 직접 호출해 reverb tail을 오디오 출력 없이 소진한다. `hasActivePartials()`가 false를 반환할 때까지 반복한다.

```java
// ring buffer에 push하지 않고 CPU 속도로 reverb tail 소진
short[] drainBuf = new short[1024];
int maxChunks = (MUNT_SAMPLE_RATE / 512) * 2; // 최대 2초 분량 = 125 청크
for (int i = 0; i < maxChunks && bridge.hasActivePartials(); i++) {
    bridge.renderAudio(drainBuf, 512);
}
```

CPU 렌더링 속도는 실시간의 10-20배이므로 2초치 reverb tail을 ~100-200ms 내에 소진한다.

**진단용 폴링 API** (Munt C API / `FFMMuntNativeBridge` 제공):

| 함수 | 반환값 | 용도 |
|------|--------|------|
| `mt32emu_has_active_partials(ctx)` | bool | 어느 partial이라도 non-INACTIVE이면 true. RELEASE 상태도 포함. |
| `mt32emu_is_active(ctx)` | bool | 큐에 대기 중인 이벤트 또는 reverb 포함 모든 활동 |
| `mt32emu_get_part_states(ctx)` | int bitmask | bit 0 = Part 1 활성, bit 7 = Part 8, bit 8 = Rhythm. ATTACK/SUSTAIN만 반영하며 RELEASE는 포함하지 않는다. |
| `mt32emu_get_playing_notes(ctx, partNum, keys*, vels*)` | count | 지정 파트의 현재 playing notes (RELEASE 포함). |

> **주의**: `getPlayingNotes()` 반환값에는 RELEASE 상태의 노트도 포함된다. 반환 count가 0이 아니더라도 해당 노트가 실제 오디오를 생성 중이라는 보장은 없다. 새 노트 가청 여부 확인에는 `get_part_states()`의 bit 확인이 더 신뢰성이 높다.

### 4.5 MIDI 인터페이스 지연 모드

실제 MT-32 하드웨어는 직렬 케이블로 MIDI 바이트를 수신하는 데 전송 지연이 존재한다. Munt는 `midiDelayMode`가 `MIDIDelayMode_IMMEDIATE`가 아닐 경우 이 지연을 시뮬레이션해 `addMIDIInterfaceDelay()`로 타임스탬프를 조정한다.

`mt32emu_play_msg_at`을 사용할 경우, wall-clock 기반으로 이미 미래 타임스탬프를 계산하므로 이 추가 지연이 적용되더라도 이벤트 순서와 간격은 유지된다. 별도 대응 불필요.

---

## 5. Munt MIDI 이벤트 큐

### 5.1 기본 큐 크기와 panic() 충돌

Munt의 내부 `MidiEventQueue`는 기본 크기가 `MT32EMU_DEFAULT_MIDI_EVENT_QUEUE_SIZE = 1024`이다.

`panic()`은 모든 채널에 대해 `(4 CC + 128 note-off) × 16 = 2112 메시지`를 보낸다. 1024를 초과하면 `mt32emu_play_msg_at`이 `MT32EMU_RC_QUEUE_FULL (-6)`을 반환하고 해당 메시지를 **조용히 버린다**. 채널 8-15의 note-off들이 Munt에 도달하지 못하고, 해당 채널의 partial은 RELEASE 상태로 계속 generator를 점유한다.

**Fix**: `createSynth()` 직후, `openSynth()` 호출 전에 큐 크기를 늘린다. 크기는 반드시 2의 거듭제곱이어야 한다.

```java
// createSynth() 안, create_context 직후
int ignored = (int) mt32emu_set_midi_event_queue_size.invokeExact(context, 4096);
```

FFM 바인딩:
```java
FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
```

### 5.2 리턴 코드 무시의 위험

`mt32emu_play_msg_at` 등의 반환 코드를 `int ignored2 = ...`로 무시하면 큐 overflow를 발견할 수 없다. 디버깅 시에는 `MT32EMU_RC_QUEUE_FULL (-6)` 반환을 카운팅하거나 로깅해야 한다.

---

## 6. FFM API 사용 시 주의사항

### 6.1 `invokeExact`의 엄격한 타입 매칭

`MethodHandle.invokeExact()`는 호출 지점의 타입이 핸들의 타입과 정확히 일치해야 한다. `int`를 반환하는 핸들을 리턴값 없이 호출하면 네이티브 함수 호출 전에 `WrongMethodTypeException`이 발생한다. `catch (Throwable)` 패턴 안에서는 예외가 조용히 삼켜지므로 함수가 실제로 호출되지 않았음을 알 수 없다.

```java
// 올바른 사용 — 반드시 리턴값을 변수에 대입
int rc = (int) mt32emu_play_msg_at.invokeExact(context, msg, timestamp);

// 잘못된 사용 — WrongMethodTypeException이 묻혀 native 호출 자체가 일어나지 않음
mt32emu_play_msg_at.invokeExact(context, msg, timestamp);
```

### 6.2 SysEx 네이티브 메모리 수명

`mt32emu_play_sysex_at`는 호출 반환 전에 데이터를 내부 큐로 복사한다. 따라서 네이티브 메모리는 호출 완료 후 즉시 해제해도 안전하다. 공유 `Arena`에 할당하면 호출마다 메모리가 누적된다.

```java
// 올바른 사용 — 호출 직후 해제되는 confined arena 사용
try (Arena tempArena = Arena.ofConfined()) {
    MemorySegment seg = tempArena.allocateFrom(ValueLayout.JAVA_BYTE, sysexData);
    int rc = (int) mt32emu_play_sysex_at.invokeExact(context, seg, sysexData.length, timestamp);
} catch (Throwable ignored) {}
```

---

## 7. 곡 전환(Song Transition) 관리

플레이리스트에서 곡이 바뀔 때 발생하는 침묵의 원인과 해결 순서.

### 7.1 곡 전환 시 무음의 원인 분류

| 원인 | 규모 | 해결책 |
|------|------|--------|
| Partial generator가 RELEASE 상태로 점유 → 새 노트 drop | ~1.5s | Fast-drain (§4.4) |
| Munt MIDI 큐 overflow → panic() note-off 미전달 | 즉각적·누적 | 큐 크기 4096으로 증가 (§5.1) |
| Ring buffer가 이미 침묵 프레임으로 채워진 채 시작 | ~128ms | renderPaused 전략 |
| 곡 로딩 후 render thread가 침묵 프레임을 먼저 push | ~32ms | onPlaybackStarted()에서 resetRenderTiming |
| MIDI 파일 자체의 초기 무음 (SysEx 초기화 등) | 파일 의존 | 변경 불가 |

### 7.2 권장 전환 시퀀스

```
[PlaybackEngine.start()]
  → provider.prepareForNewTrack()
       1. renderPaused = true (렌더 스레드 정지)
       2. Thread.sleep(20ms) — 현재 renderAudio() 완료 보장
       3. audio.flush() — ring buffer 비우기
       4. fast-drain loop — bridge.renderAudio() 반복, 오디오 push 없이
          while (bridge.hasActivePartials() && i < 125) { bridge.renderAudio(buf, 512) }
       5. renderPaused = true 유지

  → playLoop()
       → provider.onPlaybackStarted()
            - bridge.resetRenderTiming()  ← 타이밍 기준값 갱신
            - renderPaused = false         ← 렌더 스레드 재개
       → 첫 MIDI 이벤트 dispatch
```

`renderPaused = true`를 유지한 채 fast-drain을 실행하는 이유: render thread가 드레인 중인 버퍼를 동시에 push하지 못하도록 한다. `onPlaybackStarted()` 시점까지 ring buffer가 비어 있어야 새 곡의 첫 오디오가 버퍼 앞자리를 차지한다.

### 7.3 mt32emu_qt와의 구조적 차이

mt32emu_qt는 **pull 기반** 오디오 모델이다. OS AudioQueue 콜백이 직접 Munt의 `render()`를 호출하므로 Java 측 ring buffer가 없다. 곡 전환 시 별도의 flush·drain이 필요 없고, reverb tail은 자동으로 실시간 재생되며 decay한 뒤 자연스럽게 침묵으로 이어진다.

midiraja의 **push 기반** 모델(Java render thread → ring buffer → OS callback)에서는 render thread를 멈추면 Munt 시간도 멈추므로, reverb tail을 명시적으로 소진하는 fast-drain 단계가 필수다.

---

## 8. 오디오 재생 지연(Latency) 측정

VU 미터·자막 등 오디오 출력에 시각적 이벤트를 동기화하려면 전체 파이프라인 지연을 정확히 측정해야 한다.

```
total_latency_frames = ring_buffer_frames
                     + miniaudio_pipeline_frames
                     + hardware_frames          (macOS: CoreAudio 전용)
```

### 8.1 miniaudio 파이프라인 지연 — 샘플 레이트 환산 필수

`internalPeriodSizeInFrames`와 `internalPeriods`는 **기기의 내부 동작 레이트(`internalSampleRate`) 기준 프레임**이다. 요청한 sample rate와 다를 수 있으므로(예: 요청 32 kHz, 기기 동작 44100 Hz) 반드시 스케일링해야 한다.

```c
double internalRate = (double)ctx->device.playback.internalSampleRate;
double rawFrames    = (double)(ctx->device.playback.internalPeriodSizeInFrames
                               * ctx->device.playback.internalPeriods);
int scaledFrames    = (int)(rawFrames * ctx->sampleRate / internalRate);
```

환산 없이 사용하면 `internalSampleRate / sampleRate` 배(44100/32000 ≈ 1.38배)만큼 지연을 과대 추정한다.

### 8.2 macOS CoreAudio 하드웨어 지연 — 세 가지 속성 합산

CoreAudio 하드웨어 지연은 단일 속성이 아니라 세 값의 합이다:

| 속성 | 의미 |
|------|------|
| `kAudioDevicePropertyLatency` | 기기 자체 하드웨어 지연 |
| `kAudioDevicePropertySafetyOffset` | OS가 언더런 방지용으로 요구하는 추가 리드 타임 |
| `kAudioStreamPropertyLatency` | 출력 스트림 지연 (첫 번째 스트림 기준) |

세 값 모두 **`kAudioDevicePropertyNominalSampleRate` 기준 프레임**이므로 target rate로 환산해야 한다:

```c
UInt32 devLatency = hardwareLatency + safetyOffset + streamLatency;
Float64 hwRate = ...; // kAudioDevicePropertyNominalSampleRate
devLatency = (UInt32)((devLatency * ctx->sampleRate) / hwRate);
```

`kAudioDevicePropertySafetyOffset`을 빠뜨리면 Bluetooth 기기에서 특히 체감되는 OS 믹서 제출 버퍼 지연이 누락된다.

### 8.3 링 버퍼 지연 — 동적 점유량이 아닌 용량을 사용

링 버퍼의 현재 점유량(`getQueuedFrames()`)을 지연 추정에 사용하면 두 가지 문제가 생긴다:

1. **시작 시점 과소 추정**: 렌더 스레드가 버퍼를 채우기 전에 측정하면 0을 반환한다.
2. **불안정성**: 점유량은 렌더 스레드와 OS 콜백이 동시에 변경하므로 스냅샷 시점에 따라 값이 달라진다.

**링 버퍼 용량(고정값)을 사용**하면 안정적인 보수적 추정치를 얻는다. 정상 상태에서 렌더 스레드는 버퍼를 항상 가득 채운 상태를 유지하므로 용량이 실제 지연에 가장 근접한다:

```java
// ✓ 안정적 보수적 추정 (권장)
long totalFrames = RING_BUFFER_CAPACITY_FRAMES + audio.getDeviceLatencyFrames();

// ✗ 불안정한 동적 추정 (시작 시점에 과소 추정됨)
long totalFrames = audio.getQueuedFrames() + audio.getDeviceLatencyFrames();
```
