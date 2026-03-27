# VGM → MIDI 변환 아키텍처 설계

## 개요

VGM(Video Game Music) 파일을 javax.sound.midi `Sequence`로 변환하여 기존 플레이백 파이프라인에서 재생한다.
변환은 `PlaylistPlayer.play()`의 `MidiUtils.loadSequence()` 호출 직전에 삽입되며, VGM 파일 감지 시 변환기를 거쳐 `Sequence`를 생성한다.

---

## 클래스 목록과 책임

### `VgmParser` — `com.fupfin.midiraja.vgm`

VGM 파일을 읽어 칩별 이벤트 스트림으로 파싱한다.

**책임:**
- VGM 헤더 파싱 (magic `Vgm `, version, clock, GD3 offset 등)
- 데이터 오프셋부터 커맨드 바이트 순차 해석
- 대기(wait) 커맨드를 샘플 카운트로 누산
- 파싱 결과를 `VgmEvent` 레코드 리스트로 반환

```java
record VgmEvent(long sampleOffset, int chip, byte[] rawData) {}

class VgmParser {
    VgmParseResult parse(File file) throws IOException;
}

record VgmParseResult(
    int vgmVersion,
    long sn76489Clock,   // Hz, 0이면 칩 없음
    long ym2612Clock,    // Hz, 0이면 칩 없음
    List<VgmEvent> events,
    @Nullable String gd3Title
) {}
```

---

### `VgmToMidiConverter` — `com.fupfin.midiraja.vgm`

`VgmParseResult`를 `javax.sound.midi.Sequence`로 변환하는 최상위 오케스트레이터.

**책임:**
- PPQ=4410, tempo=100000µs 설정 → 44100 ticks/sec = VGM 44100 samples/sec와 1:1 매핑
- `Sn76489MidiConverter`와 `Ym2612MidiConverter`에 이벤트를 라우팅
- GD3 제목을 Track 0에 MetaMessage(type=0x03)로 삽입
- 최종 `Sequence` 반환

```java
class VgmToMidiConverter {
    Sequence convert(VgmParseResult parsed);
}
```

---

### `Sn76489MidiConverter` — `com.fupfin.midiraja.vgm`

SN76489 PSG 칩 이벤트를 MIDI 이벤트로 변환한다.

**책임:**
- Latch/Data 바이트 디코딩 → 채널별 tone/noise/volume 레지스터 상태 유지
- Tone 주파수 → MIDI 노트 번호 변환
- 볼륨(0-15) → MIDI 벨로시티(0-127) 변환
- NoteOn/NoteOff 이벤트 생성 (MIDI 채널 0-3)

---

### `Ym2612MidiConverter` — `com.fupfin.midiraja.vgm`

YM2612 FM 칩 이벤트를 MIDI 이벤트로 변환한다.

**책임:**
- 포트0/포트1 레지스터 쓰기 디코딩 (0xA0-0xA6, 0x28)
- F-Number + Block → 주파수 → MIDI 노트 번호 변환
- Key-on(0x28) 이벤트를 NoteOn으로, Key-off를 NoteOff로 변환
- MIDI 채널 4-9 사용 (채널 9=드럼: DAC 채널 또는 예비)

---

### `VgmCommand` — `com.fupfin.midiraja.cli`

picocli 서브커맨드. 기존 `OplCommand`, `FluidCommand`와 동일한 패턴.

**책임:**
- `--vgm` 옵션으로 파일/디렉터리/M3U 받기
- `JavaSynthCommand`와 동일한 MIDI 출력 프로바이더 사용
- `PlaybackRunner.run()`에 위임

```java
@Command(name = "vgm", mixinStandardHelpOptions = true,
         description = "VGM file playback (SN76489/YM2612 → MIDI conversion).")
class VgmCommand implements Callable<Integer> { ... }
```

---

## MIDI 채널 배치

| MIDI 채널 | 용도 |
|-----------|------|
| 0 | SN76489 Tone 0 (사각파 ch1) |
| 1 | SN76489 Tone 1 (사각파 ch2) |
| 2 | SN76489 Tone 2 (사각파 ch3) |
| 3 | SN76489 Noise (노이즈 ch4) |
| 4 | YM2612 FM ch1 |
| 5 | YM2612 FM ch2 |
| 6 | YM2612 FM ch3 |
| 7 | YM2612 FM ch4 |
| 8 | YM2612 FM ch5 |
| 9 | YM2612 FM ch6 / DAC (드럼 채널) |

채널 9는 GM 드럼 채널이므로 YM2612 ch6의 DAC(PCM 드럼) 출력에 활용한다.

---

## 타이밍 변환

### 기본 원칙

VGM 타임베이스는 44100 samples/sec로 고정되어 있다.

```
PPQ    = 4410
tempo  = 100000 µs/beat  (= 600 BPM)
→ ticks/sec = PPQ × (1,000,000 / tempo)
            = 4410 × 10
            = 44100 ticks/sec
```

따라서 **VGM sample offset = MIDI tick** 으로 1:1 매핑된다.

### Sequence 생성 코드 패턴

```java
var seq = new Sequence(Sequence.PPQ, 4410);
// Track 0: tempo 설정
var tempoTrack = seq.createTrack();
byte[] tempoBytes = { 0x01, (byte)0x86, (byte)0xA0 }; // 100000 µs = 0x0186A0
tempoTrack.add(new MidiEvent(new MetaMessage(0x51, tempoBytes, 3), 0));
```

---

## 주파수 → MIDI 음정 변환 공식

공통 변환식:

```
note = round(12 × log2(f / 440.0) + 69)
note = clamp(note, 0, 127)
```

### SN76489 주파수 계산

```
f = clock / (32 × N)
```
- `clock`: SN76489 클럭 (일반적으로 3579545 Hz — NTSC)
- `N`: 10비트 tone 레지스터 값 (0은 DC, 무시)

```java
static int sn76489Note(long clock, int N) {
    if (N <= 0) return -1;
    double f = clock / (32.0 * N);
    return clampNote((int) Math.round(12 * Math.log(f / 440.0) / Math.log(2) + 69));
}
```

### YM2612 주파수 계산

```
f = FNum × clock / (144 × 2^(21 - block))
```
- `FNum`: 11비트 F-Number
- `block`: 3비트 옥타브 블록 (0-7)
- `clock`: YM2612 클럭 (일반적으로 7670453 Hz — Sega Genesis)

```java
static int ym2612Note(long clock, int fnum, int block) {
    double f = fnum * clock / (144.0 * (1L << (21 - block)));
    return clampNote((int) Math.round(12 * Math.log(f / 440.0) / Math.log(2) + 69));
}
```

---

## SN76489 레지스터 구조

### Latch Byte (bit7=1)

```
bit7: 1 (latch indicator)
bit6: channel (0-2 = tone ch1-3, 3 = noise)
bit5: type (0 = tone/noise register, 1 = volume register)
bit4-0: data (lower 4 bits of tone, or noise control, or volume)
```

### Data Byte (bit7=0)

```
bit7: 0 (data indicator)
bit5-0: upper 6 bits of tone register
```

Tone 레지스터는 10비트: `{data[5:0], latch[3:0]}`.

### 볼륨 → 벨로시티

SN76489 볼륨은 0(최대)~15(무음). 선형 역변환:

```java
int velocity = (int) Math.round((15 - volume) / 15.0 * 127);
```

볼륨 15(무음)이면 NoteOff로 처리한다.

---

## YM2612 Key-on / F-Number 레지스터

### Key-on 레지스터 (0x28)

```
Address: 0x28
Data:
  bit7-4: operator key-on flags (OP1-OP4)
  bit3:   예약
  bit2-0: channel select (0-2 = ch1-3, 4-6 = ch4-6, port0/port1)
```

- `data & 0xF0 != 0` → NoteOn
- `data & 0xF0 == 0` → NoteOff

### F-Number 레지스터 (0xA0-0xA6)

```
Address 0xA4+ch (port0), 0xAC+ch (port1): high byte
  bit2-0: Block (3비트)
  bit5-3: FNum high (3비트)
Address 0xA0+ch (port0), 0xA8+ch (port1): low byte
  bit7-0: FNum low (8비트)
```

FNum 전체 = `{high[2:0], low[7:0]}` = 11비트.
레지스터 쓰기 순서: 반드시 high(0xA4) 먼저, 그다음 low(0xA0).

---

## PlaylistPlayer 수정 포인트

### 현재 코드 (line 92)

```java
var sequence = MidiUtils.loadSequence(file);
```

### 수정 후

```java
var sequence = VgmFileDetector.isVgmFile(file)
    ? new VgmToMidiConverter().convert(new VgmParser().parse(file))
    : MidiUtils.loadSequence(file);
```

`VgmFileDetector.isVgmFile()`은 파일 확장자(`.vgm`, `.vgz`) 또는 매직바이트(`Vgm `, 0x56 0x67 0x6D 0x20)로 판별한다.

---

## VGM 커맨드 바이트 목록

| 커맨드 | 길이 | 설명 |
|--------|------|------|
| `0x50` | 1바이트 데이터 | SN76489 레지스터 쓰기 |
| `0x52` | 2바이트 (addr, data) | YM2612 포트0 레지스터 쓰기 |
| `0x53` | 2바이트 (addr, data) | YM2612 포트1 레지스터 쓰기 |
| `0x61` | 2바이트 (lo, hi) | 대기 N 샘플 (N = `lo | hi<<8`) |
| `0x62` | 없음 | 대기 735 샘플 (NTSC 1/60초) |
| `0x63` | 없음 | 대기 882 샘플 (PAL 1/50초) |
| `0x66` | 없음 | 데이터 스트림 종료 (EOF) |
| `0x70`-`0x7F` | 없음 | 대기 `(cmd & 0x0F) + 1` 샘플 |

---

## 파일 구조 요약

```
src/main/java/com/fupfin/midiraja/
├── vgm/
│   ├── VgmParser.java            -- 헤더+커맨드 파싱
│   ├── VgmParseResult.java       -- 파싱 결과 레코드
│   ├── VgmEvent.java             -- 칩 이벤트 레코드
│   ├── VgmToMidiConverter.java   -- Sequence 생성 오케스트레이터
│   ├── Sn76489MidiConverter.java -- PSG → MIDI
│   ├── Ym2612MidiConverter.java  -- FM → MIDI
│   └── VgmFileDetector.java      -- 파일 감지 유틸
└── cli/
    └── VgmCommand.java           -- picocli 서브커맨드
```

---

## 설계 결정 사항

1. **변환 시점**: 재생 직전(PlaylistPlayer), 스트리밍 아님. VGM 파일은 수백 KB~수 MB로 메모리 변환이 적합하다.
2. **FM 음정 근사**: YM2612의 FM 합성은 배음이 복잡하므로 기본 주파수(Operator 1 또는 carrier F-Number)만으로 MIDI 음정을 근사한다. 정확도보다 재생 가능성을 우선한다.
3. **노이즈 처리**: SN76489 노이즈 채널은 MIDI 채널 3에 고정 노트(GM percussion 유사)로 매핑한다. 주기 노이즈(periodic noise)는 근사 주파수를 사용한다.
4. **압축 파일**: `.vgz`는 gzip 압축 VGM. `GZIPInputStream`으로 투명하게 처리한다.
