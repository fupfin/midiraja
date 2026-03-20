# TUI Engineering

**Status:** Implementation Complete

Midiraja는 터미널 크기, 상호작용 여부, 출력 리디렉션 유무를 실시간으로 감지하여 전체 화면 대시보드, 단일 줄 상태 표시줄, 정적 로그 중 가장 적합한 UI를 자동 선택합니다. 이 문서는 세 UI 모드의 렌더링 아키텍처, 키 입력 처리, ANSI 이스케이프 시퀀스 관리, 터미널 수명 주기 정책(특히 Ctrl+C 동작), 그리고 이를 뒷받침하는 동시성 모델을 설명합니다.

---

## 목차

1. [UI 모드 개요](#1-ui-모드-개요)
2. [터미널 I/O 추상화](#2-터미널-io-추상화)
3. [ANSI 이스케이프 시퀀스와 Theme](#3-ansi-이스케이프-시퀀스와-theme)
4. [DashboardUI — 전체 화면 TUI](#4-dashboardui--전체-화면-tui)
5. [LineUI — 단일 줄 상태 표시줄](#5-lineui--단일-줄-상태-표시줄)
6. [DumbUI — 정적 출력](#6-dumbui--정적-출력)
7. [입력 처리](#7-입력-처리)
8. [TerminalSelector — 대화형 선택 메뉴](#8-terminalselector--대화형-선택-메뉴)
9. [CLI 설정 레이어](#9-cli-설정-레이어)
10. [PlaybackRunner — 재생 오케스트레이션](#10-playbackrunner--재생-오케스트레이션)
11. [PlaybackEngine 동시성 모델](#11-playbackengine-동시성-모델)
12. [터미널 수명 주기와 Ctrl+C](#12-터미널-수명-주기와-ctrlc)

---

## 1. UI 모드 개요

| 모드 | 클래스 | 조건 | Alt 화면 | 커서 숨김 |
|------|--------|------|----------|----------|
| `--full` (`-3`) | `DashboardUI` | 상호작용 터미널, 높이 ≥ 10 | ✓ | ✓ |
| `--mini` (`-2`) | `LineUI` | 상호작용 터미널, 높이 < 10 | ✗ | ✓ |
| `--classic` (`-1`) | `DumbUI` | 파이프 출력, 비상호작용 | ✗ | ✗ |

명시적 플래그가 없으면 `PlaybackRunner.buildUI()`가 자동 선택합니다:

```java
PlaybackUI buildUI(UiModeOptions uiOpts, boolean isInteractive,
                   int activeIOHeight, boolean[] useAltScreenOut) {
    if (uiOpts.classicMode)          return new DumbUI();
    if (uiOpts.miniMode)             return new LineUI();
    if (uiOpts.fullMode)             { useAltScreenOut[0] = true; return new DashboardUI(); }
    if (!isInteractive)              return new DumbUI();
    if (activeIOHeight < 10)         return new LineUI();
    useAltScreenOut[0] = true;
    return new DashboardUI();
}
```

---

## 2. 터미널 I/O 추상화

### TerminalIO 인터페이스

`io/TerminalIO.java`는 모든 터미널 접근의 진입점입니다. DIP(Dependency Inversion Principle)를 적용해 실제 JLine 터미널과 테스트용 Mock을 동일하게 취급합니다.

```java
public interface TerminalIO {
    // ScopedValue로 컨텍스트 전파 — 파라미터 드릴링 없이
    // 스택 어디서든 TerminalIO.CONTEXT.get()으로 접근
    ScopedValue<TerminalIO> CONTEXT = ScopedValue.newInstance();

    enum TerminalKey {
        NONE, SEEK_FORWARD, SEEK_BACKWARD, VOLUME_UP, VOLUME_DOWN,
        SPEED_UP, SPEED_DOWN, TRANSPOSE_UP, TRANSPOSE_DOWN,
        NEXT_TRACK, PREV_TRACK, QUIT, PAUSE,
        BOOKMARK, RESUME_SESSION, TOGGLE_LOOP, TOGGLE_SHUFFLE
    }

    boolean isInteractive();  // dumb 터미널이면 false (파이프, CI 등)
    void init() throws IOException;
    void close() throws IOException;
    TerminalKey readKey() throws IOException;  // Non-blocking (10ms peek)
    void print(String str);
    void println(String str);
    int getWidth();   // 기본값 80 (터미널 미연결 시)
    int getHeight();  // 기본값 24
}
```

**ScopedValue 패턴**: `PlaybackRunner`에서 `ScopedValue.where(TerminalIO.CONTEXT, activeIO).call(...)` 로 바인딩하면, 하위 스택 (`PlaybackEngine → PlaybackUI → Panel`)이 `TerminalIO.CONTEXT.get()`으로 직접 접근 가능합니다.

### JLineTerminalIO 구현

`JLineTerminalIO`는 JLine 3.x (`jline-terminal-ffm` 백엔드 — JNI 없이 FFM API 사용)를 기반으로 합니다.

**초기화 순서:**

```java
public void init() throws IOException {
    terminal = TerminalBuilder.builder().system(true).build();
    terminal.enterRawMode();          // ICANON, ECHO, IEXTEN 비활성화

    Attributes attr = terminal.getAttributes();
    attr.setLocalFlag(Attributes.LocalFlag.ECHO, false);

    // ★ 핵심 설정 — 아래 §12 참조
    // ISIG를 비활성화해 Ctrl+C가 SIGINT 신호 대신 \x03 문자로 전달되게 함
    attr.setLocalFlag(Attributes.LocalFlag.ISIG, false);
    terminal.setAttributes(attr);

    keyMap = buildKeyMap(terminal);
    bindingReader = new BindingReader(terminal.reader());
}
```

**키 매핑 (`buildKeyMap`):**

```
방향키 (터미널 capability + ANSI/SS3 폴백):
  ▲  → PREV_TRACK     ▼  → NEXT_TRACK
  ◀  → SEEK_BACKWARD  ▶  → SEEK_FORWARD

문자 바인딩:
  Space       → PAUSE
  q/Q, ESC    → QUIT
  \x03 (Ctrl+C) → QUIT  ← ISIG 비활성화 덕분에 동작
  n/N         → NEXT_TRACK     p/P → PREV_TRACK
  +/=/u/U     → VOLUME_UP      -/_/d/D → VOLUME_DOWN
  f/F         → SEEK_FORWARD   b/B → SEEK_BACKWARD
  '           → TRANSPOSE_UP   /   → TRANSPOSE_DOWN
  ./> → SPEED_UP              ,/< → SPEED_DOWN
  *           → BOOKMARK
  r/R         → RESUME_SESSION
  l/L         → TOGGLE_LOOP    s/S → TOGGLE_SHUFFLE
```

**ESC 모호성 해소**: `km.setAmbiguousTimeout(100)` — ESC 단독 입력과 방향키(`\033[A` 등) ESC 시퀀스를 구분하기 위해 100ms 대기.

**Non-blocking 키 읽기:**

```java
public TerminalKey readKey() throws IOException {
    if (bindingReader == null || keyMap == null) return TerminalKey.NONE;
    // 10ms peek — 입력 없으면 즉시 NONE 반환해 렌더 루프 차단 안 함
    if (terminal.reader().peek(10) == NonBlockingReader.READ_EXPIRED)
        return TerminalKey.NONE;
    TerminalKey key = bindingReader.readBinding(keyMap, null, false);
    return key != null ? key : TerminalKey.NONE;
}
```

**close() 후처리:**

```java
public void close() throws IOException {
    if (terminal != null) {
        try { terminal.close(); } catch (IOException _) {}
        // JLine이 close() 후 커서를 숨긴 채로 두는 경우 대비
        try {
            System.out.print("\033[?25h\033[?7h");
            System.out.flush();
        } catch (Exception _) {}
    }
}
```

---

## 3. ANSI 이스케이프 시퀀스와 Theme

`ui/Theme.java`에 모든 터미널 이스케이프 시퀀스 상수가 집중화되어 있습니다.

| 상수 | 시퀀스 | 용도 |
|------|--------|------|
| `TERM_HIDE_CURSOR` | `\033[?25l` | 렌더 루프 시작 |
| `TERM_SHOW_CURSOR` | `\033[?25h` | 렌더 루프 종료 및 복원 |
| `TERM_ALT_SCREEN_ENABLE` | `\033[?1049h` | DashboardUI 진입 |
| `TERM_ALT_SCREEN_DISABLE` | `\033[?1049l` | DashboardUI 종료 |
| `TERM_AUTOWRAP_OFF` | `\033[?7l` | DECAWM 비활성화 (줄 바꿈 방지) |
| `TERM_AUTOWRAP_ON` | `\033[?7h` | DECAWM 복원 |
| `TERM_CURSOR_HOME` | `\033[H` | 커서를 0,0으로 (프레임 시작) |
| `TERM_CURSOR_UP` | `\033[A` | LineUI 정리 시 윗줄로 이동 |
| `TERM_CLEAR_TO_EOL` | `\033[K` | 줄 끝까지 지우기 |
| `TERM_CLEAR_TO_END` | `\033[J` | 화면 끝까지 지우기 |
| `TERM_MOUSE_DISABLE` | `\033[?1000l` … `\033[?1015l` | 6개 마우스 추적 모드 해제 |
| `COLOR_HIGHLIGHT` | `\033[38;5;215m` | Roland SC-55 앰버 (주요 강조) |
| `COLOR_DIM_FG` | `\033[2m` | 비활성 상태 (루프/셔플 아이콘) |
| `COLOR_YELLOW` | `\033[1;33m` | PAUSED 표시 |
| `FORMAT_INVERT` | `\033[7m` | 상단 배너 반전 |

**마우스 추적 해제**: `TERM_MOUSE_DISABLE`은 종료 시 항상 전송됩니다. 마우스 추적이 활성화된 채로 프로그램이 종료되면, 이후 스크롤 이벤트가 쉘에서 커서 키 입력으로 해석될 수 있습니다.

**ANSI-aware 절단**: 패널의 `truncate()` 는 가시 길이 계산 시 ANSI 코드를 제거합니다.

```java
default String truncate(String text, int maxLength) {
    String stripped = text.replaceAll("\\033\\[[;\\d]*m", "");
    if (stripped.length() <= maxLength) return text;
    return stripped.substring(0, max(0, maxLength - 3)) + "...";
}
```

---

## 4. DashboardUI — 전체 화면 TUI

### 레이아웃 구조

```
┌─ 배너 [MIDIraja vX.Y — tagline]  [★ Bookmarked] ─────────┐  1줄
├─ ≡≡[ NOW PLAYING ]≡≡ ─────────────────────────────────── ┤  2-6줄
│  타이틀, 시간/진행바, 메타데이터, 일시정지 표시              │
├─ ≡≡[ MIDI CHANNELS ]≡≡ ─── ≡≡[ PLAYLIST ↺⇆ ]≡≡ ──────── ┤  가변
│  16채널 VU 미터 (1/2/4열)   현재 트랙 강조, 번호 목록       │
├─ ≡≡[ CONTROLS ]≡≡ ─────────────────────────────────────── ┤  1-3줄
│  키 바인딩 참조                                            │
└─ ══════════════════════════════════════════════════════ ┘  1줄
```

**레이아웃 결정 (`DashboardLayoutManager`)**:
- 터미널 높이 ≥ 요구량 + 다중 파일이면 CHANNELS/PLAYLIST 좌우 2열
- 그 외 스택형 (CHANNELS 위, PLAYLIST 아래)
- 여유 공간 배분: NOW PLAYING → CONTROLS → CHANNELS/PLAYLIST 순

**렌더 루프:**

```java
public void runRenderLoop(PlaybackEngine engine) {
    var term = TerminalIO.CONTEXT.get();
    term.print(Theme.TERM_HIDE_CURSOR + Theme.TERM_AUTOWRAP_OFF);

    while (engine.isPlaying()) {
        // 터미널 크기 변경 감지 → 레이아웃 재계산
        int w = term.getWidth(), h = term.getHeight();
        if (w != lastWidth || h != lastHeight) recalculateLayout(w, h);

        ScreenBuffer buffer = new ScreenBuffer(4096);
        buffer.append(Theme.TERM_CURSOR_HOME);  // 커서 홈으로

        // 배너 (반전, 북마크 태그 포함)
        renderBanner(buffer);
        // 각 패널 렌더링
        titledNowPlayingPanel.render(buffer);
        renderChannelsAndPlaylist(buffer);
        controlsPanel.render(buffer);
        renderBottomBorder(buffer);

        // 줄 단위로 EOL 클리어 삽입 후 단일 print()로 플러시
        String frame = buffer.toString().replace("\n", "\033[K\n");
        term.print(frame + Theme.TERM_CLEAR_TO_END);

        Thread.sleep(50);  // ~20 FPS
    }

    term.print(Theme.TERM_SHOW_CURSOR + Theme.TERM_AUTOWRAP_ON);
}
```

**단일 print() 호출**: 프레임 전체를 `ScreenBuffer`에 모아 한 번에 출력합니다. 문자 단위 I/O 오버헤드와 부분 렌더링 깜빡임을 방지합니다.

### 패널 구현

**NowPlayingPanel**:
- 2~6줄 높이 (레이아웃 제약에 따라)
- 타이틀 줄에 `★ Bookmarked` 표시 (항상 표시)
- 진행바: `████████░░ 01:23 / 03:45`
- 메타데이터: 포트, BPM, 속도, 전조, 볼륨

**ChannelActivityPanel**:
- 16채널 VU 미터, 프레임당 5% 감쇠
- 공간에 따라 1열 / 2열 / 4열 전환
  - 넓음: `CH 01 (Piano    ): [████░░]`
  - 좁음: `C01:[███··]`

**PlaylistPanel**:
- 현재 트랙 앰버 강조, 상하 창 슬라이딩
- 비동기 MIDI 타이틀 캐싱 (VirtualThread — 시작 차단 방지)
- 우측 태그: `↺` (루프), `⇆` (셔플) 활성/비활성 색상

**ControlsPanel**:
- 높이 ≥ 3줄: `[Spc]Pause  [▲▼]Track  [◀▶]Seek ...` (2줄)
- 2줄: 압축 1줄
- 1줄: 초압축 (`[Spc]Pause [Q]Quit ...`)

### ScreenBuffer

```java
public class ScreenBuffer {
    private final StringBuilder sb;  // 4096자 사전 할당

    public ScreenBuffer append(String text) { sb.append(text); return this; }
    public ScreenBuffer format(String fmt, Object... args) { ... }
    public ScreenBuffer repeat(char c, int count) { ... }
    public String[] toLines() { return sb.toString().split("\n", -1); }
}
```

렌더 루프에서 프레임당 객체 할당을 최소화합니다.

---

## 5. LineUI — 단일 줄 상태 표시줄

Alt 화면 없이 동일 줄을 덮어쓰는 방식입니다.

```
MIDIraja v1.0 — ...
Playing [1/3]: my_song.mid  [Port: Hardware MIDI]
  Title: My Song
  Controls: [Spc]Pause [▲ ▼]Track [◀ ▶]Seek ...

Vol:[▂▃▄▅▆▇█░░░░░░░░] 01:23/03:45 | Spd: 1.0x(BPM: 120.0) | Tr: +0 | Vol: 100% ↺⇆
```

**렌더 루프:**

```java
public void runRenderLoop(PlaybackEngine engine) {
    var term = TerminalIO.CONTEXT.get();
    if (term.isInteractive())
        term.print(Theme.TERM_HIDE_CURSOR + "\033[?7l");  // 커서 숨김, 줄바꿈 방지

    // 정적 헤더 줄 출력 (트랙명, 제목, 컨트롤 안내)
    int staticLines = printStaticHeader(term, context);

    String[] blocks = {" ", " ", "▂", "▃", "▄", "▅", "▆", "▇", "█"};

    while (engine.isPlaying()) {
        engine.decayChannelLevels(0.15);   // 프레임당 15% 감쇠

        ScreenBuffer buffer = new ScreenBuffer();
        buffer.append("\r");               // 줄 시작으로
        renderVuBar(buffer, engine, blocks);
        renderStatusLine(buffer, engine);
        renderLoopShuffleIcons(buffer, engine);

        // 터미널 너비에 맞게 절단 후 EOL 클리어
        String line = truncateAnsi(buffer.toString(), max(10, term.getWidth() - 2));
        term.print(line + Theme.TERM_CLEAR_TO_EOL);

        Thread.sleep(33);  // ~30 FPS
    }

    // 종료 시 헤더 줄 지우기 (커서 위로 이동 후 화면 클리어)
    if (term.isInteractive()) {
        term.print("\r");
        for (int i = 0; i < staticLines; i++) term.print(Theme.TERM_CURSOR_UP);
        term.print(Theme.TERM_CLEAR_TO_END);
        term.print(Theme.TERM_SHOW_CURSOR + "\033[?7h");
    } else {
        term.println("");
    }
}
```

---

## 6. DumbUI — 정적 출력

파이프, CI, 로깅 용도의 ANSI 없는 단순 출력입니다. 키 입력 처리가 없고 `runRenderLoop`은 `engine.isPlaying()`이 false가 될 때까지 폴링만 합니다.

---

## 7. 입력 처리

### InputLoopRunner

```java
public static void run(PlaybackEngine engine,
        BiConsumer<PlaybackEngine, TerminalKey> keyHandler) {
    var term = TerminalIO.CONTEXT.get();
    try {
        while (engine.isPlaying()) {
            var key = term.readKey();  // Non-blocking (10ms peek)
            keyHandler.accept(engine, key);
        }
    } catch (IOException _) {
        engine.requestStop(PlaybackEngine.PlaybackStatus.QUIT_ALL);
    }
}
```

### InputHandler

```java
// handleCommonInput — DashboardUI + LineUI 공통
case VOLUME_UP       -> engine.adjustVolume(0.05);     // +5%
case VOLUME_DOWN     -> engine.adjustVolume(-0.05);    // -5%
case NEXT_TRACK      -> engine.requestStop(NEXT);
case PREV_TRACK      -> engine.requestStop(PREVIOUS);
case TRANSPOSE_UP    -> engine.adjustTranspose(1);     // +1 반음
case TRANSPOSE_DOWN  -> engine.adjustTranspose(-1);
case SPEED_UP        -> engine.adjustSpeed(0.1);
case SPEED_DOWN      -> engine.adjustSpeed(-0.1);
case SEEK_FORWARD    -> engine.seekRelative(10_000_000);   // +10초
case SEEK_BACKWARD   -> engine.seekRelative(-10_000_000);  // -10초
case PAUSE           -> engine.togglePause();
case BOOKMARK        -> engine.fireBookmark();
case TOGGLE_LOOP     -> engine.toggleLoop();
case TOGGLE_SHUFFLE  -> engine.toggleShuffle();
case QUIT            -> engine.requestStop(QUIT_ALL);
case RESUME_SESSION  -> engine.requestStop(RESUME_SESSION);
```

`handleMiniInput` (LineUI): `TOGGLE_LOOP`와 `TOGGLE_SHUFFLE`을 no-op 처리합니다.

---

## 8. TerminalSelector — 대화형 선택 메뉴

포트 선택, 이력 선택 등에 사용하는 범용 대화형 UI입니다.

```java
Integer result = TerminalSelector.select(items, config,
        uiOpts.fullMode, uiOpts.miniMode, uiOpts.classicMode, err);
```

**모드 선택 로직:**

```java
SelectionMode resolveMode(boolean isInteractive, int termHeight,
        boolean preferFull, boolean preferMini, boolean preferClassic) {
    if (!isInteractive || preferClassic) return CLASSIC;
    if (preferMini) return MINI;
    return (preferFull || termHeight >= 10) ? FULL : MINI;
}
```

| 모드 | 설명 |
|------|------|
| `FULL` | Alt 화면, 데코레이티드 박스, 방향키 탐색 |
| `MINI` | 인라인 렌더링, 방향키 탐색 |
| `CLASSIC` | stderr에 번호 프롬프트, 파이프 친화적 |

**확장 선택 결과** (`SelectResult<T>`):
- `Chosen<T>` — 항목 선택
- `Delete<T>` — D키 → 삭제 확인
- `Cancelled` — 종료

---

## 9. CLI 설정 레이어

### UiModeOptions Mixin

```java
public class UiModeOptions {
    @Option(names = {"-1", "--classic"}) public boolean classicMode;
    @Option(names = {"-2", "--mini"})    public boolean miniMode;
    @Option(names = {"-3", "--full"})    public boolean fullMode;
}
```

### CommonOptions Mixin

주요 필드:

```java
@Option(names = {"-v", "--volume"})   public int volume = 100;     // 0-150
@Option(names = {"-x", "--speed"})    public double speed = 1.0;   // 0.5-2.0
@Option(names = {"-S", "--start"})    public Optional<String> startTime;
@Option(names = {"-s", "--shuffle"})  public boolean shuffle;
@Option(names = {"-r", "--loop"})     public boolean loop;
@Option(names = {"-t", "--transpose"}) public Optional<Integer> transpose;
```

### MidirajaCommand 전역 플래그

```java
public class MidirajaCommand implements Callable<Integer> {
    public static volatile boolean SHUTTING_DOWN = false;
    public static volatile boolean ALT_SCREEN_ACTIVE = false;
}
```

`SHUTTING_DOWN`은 셧다운 훅 실행 중 PlaybackEngine이 불필요한 출력을 억제하는 데 사용됩니다.

---

## 10. PlaybackRunner — 재생 오케스트레이션

`PlaybackRunner.run()`이 전체 재생 수명 주기를 관리합니다.

**수명 주기 단계:**

1. 파일 유효성 검사
2. 플레이리스트 파싱 (M3U, 디렉터리)
3. 세션 이력 자동 저장
4. 포트 선택 (대화형 또는 명시적)
5. 프로바이더 초기화 (사운드뱅크 로드 또는 포트 열기)
6. `JLineTerminalIO.init()` — 원시 모드, ISIG 비활성화
7. **셧다운 훅** 등록 (외부 시그널 대비 안전망)
8. Alt 화면 진입 (DashboardUI인 경우만)
9. `playPlaylistLoop()` — 재생 루프
10. `finally` 블록 — 터미널 복원

**Alt 화면 진입/복원:**

```java
// 진입 (DashboardUI인 경우)
if (useAltScreen && isInteractive && !suppressAltScreenRestore) {
    out.print("\033[?1049h\033[?25l");  // Alt 화면 + 커서 숨김
    out.flush();
    MidirajaCommand.ALT_SCREEN_ACTIVE = true;
    // Alt 화면 중 stderr를 버퍼로 리디렉션 (화면 오염 방지)
    errBuffer = new ByteArrayOutputStream();
    System.setErr(new PrintStream(errBuffer, true));
}

// 복원 (finally 블록)
finally {
    System.setErr(savedErr);
    MidirajaCommand.ALT_SCREEN_ACTIVE = false;
    activeIO.close();
    if (isInteractive) {
        String safeRestore =
            (useAltScreen && !suppressAltScreenRestore ? Theme.TERM_ALT_SCREEN_DISABLE : "")
            + Theme.TERM_MOUSE_DISABLE + Theme.COLOR_RESET
            + "\033[?7h" + Theme.TERM_SHOW_CURSOR + "\r\033[K\n";
        out.print(safeRestore);
        out.flush();
    }
    if (errBuffer != null && errBuffer.size() > 0)
        savedErr.print(errBuffer.toString());
}
```

---

## 11. PlaybackEngine 동시성 모델

재생 중 세 가지 작업이 동시에 실행됩니다.

```java
public PlaybackStatus start(PlaybackUI ui) throws Exception {
    isPlaying.set(true);
    try (var scope = StructuredTaskScope.open()) {
        scope.fork(() -> { ui.runRenderLoop(this); return true; });  // 렌더 스레드
        scope.fork(() -> { ui.runInputLoop(this);  return true; });  // 입력 스레드
        try {
            playLoop();          // 메인 스레드: MIDI 이벤트 타이밍
        } finally {
            isPlaying.set(false);
            if (playbackActuallyStarted.get()) provider.panic();
        }
        scope.join();            // 렌더/입력 스레드 완료 대기
    }
    return endStatus.get();
}
```

| 스레드 | 역할 | 종료 조건 |
|--------|------|----------|
| 메인 (playLoop) | MIDI 이벤트 타이밍, 디스패치 | `isPlaying = false` 또는 곡 끝 |
| 렌더 (virtual) | `while (engine.isPlaying())` — 50ms | `isPlaying = false` |
| 입력 (virtual) | `while (engine.isPlaying())` — 10ms peek | `isPlaying = false` 또는 IOException |

**원자적 상태 변수:**

```java
final AtomicBoolean isPlaying;        // 재생 중
final AtomicBoolean isPaused;         // 일시정지
final AtomicLong currentMicroseconds; // 현재 재생 위치
final AtomicLong seekTarget;          // 요청된 탐색 위치
final AtomicReference<Float> currentBpm;
final AtomicReference<Double> currentSpeed;
```

**이벤트 알림**: `ScheduledExecutorService`가 오디오 레이턴시만큼 지연 후 리스너에게 채널 활동(`onChannelActivity`)을 전달합니다. `CopyOnWriteArrayList`로 스레드 안전 이터레이션을 보장합니다.

**playLoop 타이밍 세부:**

- **50ms 수면 상한**: seek/stop 응답성 ≤ 50ms 보장
- **바쁜 대기 (spin-wait)**: 1ms 미만 구간에서 `Thread.onSpinWait()` 사용 — 샘플 정확도 확보
- **일시정지 시간 보정**: `isPaused` 동안 `startTimeNanos` 를 전진시켜 재개 후 따라잡기 방지

---

## 12. 터미널 수명 주기와 Ctrl+C

### 관리하는 터미널 상태

| 상태 | 진입 | 복원 | 위치 |
|------|------|------|------|
| Alt 화면 | `\033[?1049h` | `\033[?1049l` | `PlaybackRunner.run()` |
| 커서 숨김 | `\033[?25l` | `\033[?25h` | Alt 화면 진입, `LineUI.runRenderLoop()` |
| 자동 줄바꿈 비활성 | `\033[?7l` | `\033[?7h` | `LineUI.runRenderLoop()`, `DashboardUI` |
| 원시 키보드 모드 | `tcsetattr` (enterRawMode) | `terminal.close()` | `JLineTerminalIO.init()` |
| 마우스 추적 | — | `\033[?1000l` … `\033[?1015l` | 복원 시 항상 전송 |

### 정상 종료 경로 (Q, ESC, 곡 끝)

```
Q 키 입력
  └── TerminalKey.QUIT
        └── engine.requestStop(QUIT_ALL) → isPlaying = false
              └── 렌더 루프 종료 (다음 프레임)
                    └── StructuredTaskScope.join() 반환
                          └── engine.start() 반환
                                └── PlaybackRunner.run() finally 블록
                                      └── out.print("\033[?1049l…\033[?25h")  ✓
```

### Ctrl+C 경로 — 과거 실패와 해결

**셧다운 훅 방식의 구조적 문제:**

`System.exit()` 는 JVM 명세에 따라 애플리케이션 스레드를 종료하지 않습니다. 셧다운 훅 스레드가 `\033[?1049l`을 쓰는 동안 DashboardUI 렌더 루프가 계속 실행되어 복원 시퀀스를 덮어씁니다. GraalVM native image의 `--install-exit-handlers` 플래그로 설치된 네이티브 SIGINT 핸들러도 `System.exit()`을 호출하므로 동일한 경쟁 조건이 발생합니다.

**해결책: ISIG 비활성화 + `\x03` → QUIT 바인딩**

ISIG를 비활성화하면 Ctrl+C가 SIGINT 신호 대신 `\x03`(ETX) 문자로 전달됩니다. JLine이 이것을 `TerminalKey.QUIT`으로 매핑하여 Q키와 동일한 정상 종료 경로를 거칩니다:

```
Ctrl+C (ISIG 비활성화)
  └── 터미널이 \x03(ETX) 전달 — SIGINT 없음
        └── JLine: "\003" → TerminalKey.QUIT
              └── (Q 키와 동일한 경로)  ✓
```

**ISIG 비활성화의 부수 효과:**

| 동작 | 결과 |
|------|------|
| Ctrl+C (터미널) | `\x03` → QUIT — 정상 동작 |
| Ctrl+Z (일시정지) | SIGTSTP 없음 — 일시정지 불가 (허용 가능한 트레이드오프) |
| Ctrl+\\ (SIGQUIT) | 무시됨 |
| `kill -INT <pid>` | SIGINT 여전히 전달됨 (프로세스 레벨 신호는 ISIG와 무관) |

재생 중에는 사용자가 Q로 종료할 수 있으므로 Ctrl+Z 일시정지 기능의 부재는 수용 가능합니다.

**셧다운 훅 — 외부 신호 안전망:**

`kill -INT <pid>`, `kill -TERM <pid>`, 시스템 종료 등 터미널 외부에서 전달되는 신호에 대비해 셧다운 훅은 유지됩니다. 렌더 루프와의 경쟁 조건이 있어 항상 보장되지는 않지만, 완전히 복원되지 않는 것보다 낫습니다.

```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    MidirajaCommand.SHUTTING_DOWN = true;
    // Alt 화면 진입 여부와 무관하게 항상 포함 — 무해한 no-op
    String safeRestore = Theme.TERM_ALT_SCREEN_DISABLE
        + Theme.TERM_MOUSE_DISABLE + Theme.COLOR_RESET
        + "\033[?7h" + Theme.TERM_SHOW_CURSOR + "\r\033[K\n";

    if (isInteractive) {
        // 1차: Alt 화면 진입과 동일한 경로 (System.out)
        try { System.out.print(safeRestore); System.out.flush(); } catch (Exception _) {}
        // 2차: 쉘 리디렉션을 우회하는 /dev/tty 직접 쓰기
        try (var tty = new FileOutputStream("/dev/tty")) {
            tty.write(safeRestore.getBytes(UTF_8)); tty.flush();
        } catch (Exception _) {}
    }
    activeIO.close();
    // MIDI 포트 정리 (음 지속 방지)
    if (portClosed.compareAndSet(false, true)) {
        provider.panic();
        Thread.sleep(200);
        provider.closePort();
    }
}));
```

### 종료 경로 요약

| 경로 | 방식 | 렌더 루프 정지 | 복원 위치 | 신뢰도 |
|------|------|---------------|----------|--------|
| Q / ESC | 정상 QUIT 키 | `isPlaying=false` → 다음 프레임 | `finally` 블록 | ✓ 확실 |
| Ctrl+C (ISIG 비활성) | `\x03` → QUIT 키 | 동일 | `finally` 블록 | ✓ 확실 |
| `kill -INT <pid>` | SIGINT → 셧다운 훅 | 중단 안 됨 (경쟁 조건) | 셧다운 훅 | ⚠ 최선 |
| `kill -9 <pid>` | SIGKILL | 즉시 종료 | 없음 | ✗ 불가 |

### 회귀 방지

**테스트**: `JLineTerminalIOTest.ctrlC_isBoundToQuit()` — dumb 터미널로 `"\003"` → `QUIT` 바인딩을 CI에서 검증합니다. 이 바인딩이 제거되면 테스트가 즉시 실패합니다.

**규칙**:
- `JLineTerminalIO.init()`에서 `ISIG` 를 다시 활성화하지 마세요 — 셧다운 훅 경쟁 조건이 재발합니다
- `"\003"` → QUIT 바인딩을 제거하지 마세요
- 터미널 복원 코드를 `finally` 블록에서 셧다운 훅 전용으로 이동하지 마세요

자세한 근거는 `CLAUDE.md`의 "Terminal lifecycle" 섹션을 참조하세요.
