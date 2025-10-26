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

### 4.4 MIDI 인터페이스 지연 모드

실제 MT-32 하드웨어는 직렬 케이블로 MIDI 바이트를 수신하는 데 전송 지연이 존재한다. Munt는 `midiDelayMode`가 `MIDIDelayMode_IMMEDIATE`가 아닐 경우 이 지연을 시뮬레이션해 `addMIDIInterfaceDelay()`로 타임스탬프를 조정한다.

`mt32emu_play_msg_at`을 사용할 경우, wall-clock 기반으로 이미 미래 타임스탬프를 계산하므로 이 추가 지연이 적용되더라도 이벤트 순서와 간격은 유지된다. 별도 대응 불필요.

---

## 5. FFM API 사용 시 주의사항

### 5.1 `invokeExact`의 엄격한 타입 매칭

`MethodHandle.invokeExact()`는 호출 지점의 타입이 핸들의 타입과 정확히 일치해야 한다. `int`를 반환하는 핸들을 리턴값 없이 호출하면 네이티브 함수 호출 전에 `WrongMethodTypeException`이 발생한다. `catch (Throwable)` 패턴 안에서는 예외가 조용히 삼켜지므로 함수가 실제로 호출되지 않았음을 알 수 없다.

```java
// 올바른 사용 — 반드시 리턴값을 변수에 대입
int rc = (int) mt32emu_play_msg_at.invokeExact(context, msg, timestamp);

// 잘못된 사용 — WrongMethodTypeException이 묻혀 native 호출 자체가 일어나지 않음
mt32emu_play_msg_at.invokeExact(context, msg, timestamp);
```

### 5.2 SysEx 네이티브 메모리 수명

`mt32emu_play_sysex_at`는 호출 반환 전에 데이터를 내부 큐로 복사한다. 따라서 네이티브 메모리는 호출 완료 후 즉시 해제해도 안전하다. 공유 `Arena`에 할당하면 호출마다 메모리가 누적된다.

```java
// 올바른 사용 — 호출 직후 해제되는 confined arena 사용
try (Arena tempArena = Arena.ofConfined()) {
    MemorySegment seg = tempArena.allocateFrom(ValueLayout.JAVA_BYTE, sysexData);
    int rc = (int) mt32emu_play_sysex_at.invokeExact(context, seg, sysexData.length, timestamp);
} catch (Throwable ignored) {}
```
