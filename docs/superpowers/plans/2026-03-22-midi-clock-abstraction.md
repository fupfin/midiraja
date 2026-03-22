# MidiClock Abstraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract `System.nanoTime()`, `Thread.sleep()`, and `Thread.onSpinWait()` from `PlaybackEngine.playLoop()` behind a `MidiClock` interface so that timing-dependent logic can be tested without real wall-clock delays.

**Architecture:** A new `MidiClock` interface in the `engine` package provides `nanoTime()`, `sleepMillis()`, and `onSpinWait()`. `PlaybackEngine` gets a `private final MidiClock clock` field injected via a new 8-parameter constructor; the existing 7-parameter constructor delegates with `MidiClock.SYSTEM`. Tests inject a `FakeClock` whose `sleepMillis()` advances a virtual clock instead of sleeping.

**Tech Stack:** Java 25, JUnit 5, javax.sound.midi

---

## Files

| File | Change |
|------|--------|
| `src/main/java/com/fupfin/midiraja/engine/MidiClock.java` | New — interface + `SYSTEM` constant |
| `src/main/java/com/fupfin/midiraja/engine/PlaybackEngine.java` | Add `clock` field, 8-param constructor, replace 8 timing call sites |
| `src/test/java/com/fupfin/midiraja/engine/PlaybackEngineTest.java` | Add `FakeClock` + 3 behavior tests |

---

### Task 1: Create `MidiClock` interface

**Files:**
- Create: `src/main/java/com/fupfin/midiraja/engine/MidiClock.java`

There is no pre-existing behaviour to break here, so no failing test is needed before creating
the interface. Write the interface, then verify it compiles cleanly.

- [ ] **Step 1: Create `MidiClock.java`**

```java
/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.engine;

/**
 * Abstraction over wall-clock timing primitives used by {@link PlaybackEngine}.
 * Replace with a test double to drive timing-sensitive logic without real sleeps.
 */
interface MidiClock {

    /** Returns the current value of the JVM's high-resolution timer, in nanoseconds. */
    long nanoTime();

    /**
     * Causes the calling thread to sleep for at least {@code ms} milliseconds.
     *
     * @throws InterruptedException if the thread is interrupted while sleeping
     */
    void sleepMillis(long ms) throws InterruptedException;

    /** Hints to the JVM that the calling thread is in a spin-wait loop. */
    void onSpinWait();

    /** Production implementation backed by {@link System#nanoTime()} and {@link Thread#sleep}. */
    MidiClock SYSTEM = new MidiClock() {
        @Override public long nanoTime() { return System.nanoTime(); }
        @Override public void sleepMillis(long ms) throws InterruptedException { Thread.sleep(ms); }
        @Override public void onSpinWait() { Thread.onSpinWait(); }
    };
}
```

- [ ] **Step 2: Build to verify compilation**

```bash
./gradlew classes
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/fupfin/midiraja/engine/MidiClock.java
git commit -m "feat: add MidiClock interface for timing abstraction"
```

---

### Task 2: Wire `MidiClock` into `PlaybackEngine`

**Files:**
- Modify: `src/main/java/com/fupfin/midiraja/engine/PlaybackEngine.java`
- Modify: `src/test/java/com/fupfin/midiraja/engine/PlaybackEngineTest.java`

The strategy is TDD: write a test that injects a clock first (it will fail to compile), then
implement the field and constructor to make it compile and pass.

- [ ] **Step 1: Write a failing test that uses constructor injection**

Add this to `PlaybackEngineTest` (after the existing `MockMidiProvider` class, before `@BeforeEach`):

```java
/** Advances virtual nanos on sleepMillis; never actually sleeps. */
static class FakeClock implements MidiClock {
    private long nanos = 0;

    @Override public long nanoTime() { return nanos; }

    @Override public void sleepMillis(long ms) {
        nanos += ms * 1_000_000L;
    }

    @Override public void onSpinWait() {
        nanos += 1;
    }
}
```

Then add this test method:

```java
@Test void clock_injection_constructor_compiles() throws Exception {
    var clock = new FakeClock();
    var engine = new PlaybackEngine(mockSequence, mockProvider, ctx(),
            100, 1.0, Optional.empty(), Optional.empty(), clock);
    assertNotNull(engine);
}
```

- [ ] **Step 2: Run test to verify it fails (compile error)**

```bash
./gradlew test --tests "com.fupfin.midiraja.engine.PlaybackEngineTest.clock_injection_constructor_compiles"
```

Expected: compilation failure — no 8-parameter constructor exists yet.

- [ ] **Step 3: Add `clock` field and constructors to `PlaybackEngine`**

After the existing field declarations (around line 78, after `private Optional<String> initialResetType`),
add:

```java
private final MidiClock clock;
```

Replace the existing 7-parameter constructor with a delegating form, and add the new 8-parameter constructor:

```java
public PlaybackEngine(Sequence sequence, MidiOutProvider provider, PlaylistContext context,
        int initialVolumePercent, double initialSpeed, Optional<String> startTimeStr,
        Optional<Integer> initialTranspose)
{
    this(sequence, provider, context, initialVolumePercent, initialSpeed,
            startTimeStr, initialTranspose, MidiClock.SYSTEM);
}

public PlaybackEngine(Sequence sequence, MidiOutProvider provider, PlaylistContext context,
        int initialVolumePercent, double initialSpeed, Optional<String> startTimeStr,
        Optional<Integer> initialTranspose, MidiClock clock)
{
    this.clock = clock;
    this.sequence = sequence;
    this.provider = provider;
    // ... rest of existing 7-param body unchanged ...
}
```

Important: Move the entire body of the old constructor into the new 8-param constructor, and add
`this.clock = clock;` as the first line. The old constructor becomes a one-liner that delegates.

- [ ] **Step 4: Replace timing call sites**

There are **8 replacement blocks** (13 individual occurrences) to update. Search for them with:

```bash
grep -n "System\.nanoTime\|Thread\.sleep\|Thread\.onSpinWait\|System\.currentTimeMillis" \
  src/main/java/com/fupfin/midiraja/engine/PlaybackEngine.java
```

**In `sendInitialReset()` (line ~313):**
```java
// before
Thread.sleep(RESET_SETTLE_MS);
// after
clock.sleepMillis(RESET_SETTLE_MS);
```

**In `playLoop()` — startup delay section (lines ~327-336):**

The startup delay uses `System.currentTimeMillis()`. Convert to `nanoTime()` for consistency:

```java
// before
long startupWaitEnd = System.currentTimeMillis() + STARTUP_DELAY_MS;
while (System.currentTimeMillis() < startupWaitEnd)
{
    if (!isPlaying.get()) { return; }
    Thread.sleep(STARTUP_POLL_MS);
}

// after
long startupWaitEndNanos = clock.nanoTime() + STARTUP_DELAY_MS * 1_000_000L;
while (clock.nanoTime() < startupWaitEndNanos)
{
    if (!isPlaying.get()) { return; }
    clock.sleepMillis(STARTUP_POLL_MS);
}
```

**In `playLoop()` — `startTimeNanos` initialisation (line ~356):**
```java
// before
long startTimeNanos = System.nanoTime();
// after
long startTimeNanos = clock.nanoTime();
```

**In `playLoop()` — end-of-events hold loop (line ~435):**
```java
// before
Thread.sleep(PLAYBACK_POLL_MS);
// after
clock.sleepMillis(PLAYBACK_POLL_MS);
```

**In `playLoop()` — pause hold loop (line ~454):**
```java
// before
Thread.sleep(PLAYBACK_POLL_MS);
// after
clock.sleepMillis(PLAYBACK_POLL_MS);
```

Note: the same block contains `startTimeNanos += 50_000_000` (line ~459). This is a fixed 50ms
offset that matches the `PLAYBACK_POLL_MS` sleep; it keeps `startTimeNanos` in sync with the
FakeClock so the engine does not "catch up" with missed events when unpaused. No change needed —
it remains a literal constant.

**In `playLoop()` — timing reset after seek (line ~409):**
```java
// before
startTimeNanos = System.nanoTime() - elapsedNanos;
// after
startTimeNanos = clock.nanoTime() - elapsedNanos;
```

**In `playLoop()` — high-resolution event delay loop (lines ~474-491):**
```java
// before
long currentNanos = System.nanoTime();
while (currentNanos < targetNanos)
{
    ...
    Thread.sleep(min(remainingMs - 1, PLAYBACK_POLL_MS));
    ...
    Thread.onSpinWait();
    ...
    currentNanos = System.nanoTime();
}

// after
long currentNanos = clock.nanoTime();
while (currentNanos < targetNanos)
{
    ...
    clock.sleepMillis(min(remainingMs - 1, PLAYBACK_POLL_MS));
    ...
    clock.onSpinWait();
    ...
    currentNanos = clock.nanoTime();
}
```

**In `playLoop()` — end-of-track delay (line ~520):**
```java
// before
Thread.sleep(END_OF_TRACK_MS);
// after
clock.sleepMillis(END_OF_TRACK_MS);
```

- [ ] **Step 5: Run all tests**

```bash
./gradlew test
```

Expected: all tests pass, including `clock_injection_constructor_compiles`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/fupfin/midiraja/engine/PlaybackEngine.java \
        src/test/java/com/fupfin/midiraja/engine/PlaybackEngineTest.java
git commit -m "feat: inject MidiClock into PlaybackEngine; replace timing calls"
```

---

### Task 3: Behavior tests using `FakeClock`

**Files:**
- Modify: `src/test/java/com/fupfin/midiraja/engine/PlaybackEngineTest.java`

These tests verify that timing-sensitive paths in `playLoop()` behave correctly without any
real wall-clock delay. `FakeClock.sleepMillis()` advances `nanos`, so `clock.nanoTime()`
returns a monotonically increasing value as the playback loop "sleeps".

The trick for testing `playLoop()` via `engine.start()` is that `isPlaying` is set to `true`
inside `start()` before `playLoop()` is called, and the input thread drives key events. For
these tests, inject a `QUIT` key immediately so `isPlaying` becomes `false` as soon as the
startup delay finishes.

- [ ] **Step 1: Write failing test — FakeClock sanity**

This test verifies `FakeClock` itself before using it in engine tests.

```java
@Test void fakeClock_nanoTime_advancesWithSleep() throws InterruptedException {
    var clock = new FakeClock();
    long t0 = clock.nanoTime();
    clock.sleepMillis(100);
    long t1 = clock.nanoTime();
    clock.onSpinWait();
    long t2 = clock.nanoTime();

    assertEquals(100_000_000L, t1 - t0, "sleepMillis(100) should advance by 100ms in nanos");
    assertTrue(t2 > t1, "onSpinWait() should advance nanos by at least 1");
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.fupfin.midiraja.engine.PlaybackEngineTest.fakeClock_nanoTime_advancesWithSleep"
```

Expected: FAIL — `FakeClock` not yet defined. (If Task 2 completed first, this will already pass.)

- [ ] **Step 3: Write test — end-of-track delay is observed**

`Sequence.createTrack()` automatically inserts an End-of-Track meta event at tick 0. With one
event at tick=0 (same as `lastTick=0`), the event-timing wait is skipped and the main
`while` loop exits after processing that single event. No QUIT key is needed — the loop
terminates naturally, then `playLoop()` hits the END_OF_TRACK_MS sleep before returning.

```java
/**
 * After the last event, playLoop() sleeps END_OF_TRACK_MS (20 ms) before returning.
 * FakeClock confirms the delay is observed without real wall-clock time passing.
 *
 * Code path: empty track → sortedEvents has one End-of-Track meta at tick 0 →
 * outer while loop exits after processing it (eventIndex == sortedEvents.size()) →
 * falls through to the END_OF_TRACK_MS sleep.
 */
@Test void endOfTrack_delay_isObserved() throws Exception {
    var clock = new FakeClock();
    // createTrack() inserts a single End-of-Track meta at tick 0.
    var singleEventSeq = new Sequence(Sequence.PPQ, 480);
    singleEventSeq.createTrack();

    var engine = new PlaybackEngine(singleEventSeq, mockProvider, ctx(),
            100, 1.0, Optional.empty(), Optional.empty(), clock);

    ScopedValue.where(TerminalIO.CONTEXT, mockIO)
            .call(() -> engine.start(new DumbUI()));

    // Startup delay: 50 × sleepMillis(10) = 500ms fake.
    // End-of-track delay: sleepMillis(20) = 20ms fake.
    // Total minimum = 520ms = 520,000,000 ns.
    assertTrue(clock.nanoTime() >= 520_000_000L,
            "Expected startup + end-of-track delay >= 520ms, got " + clock.nanoTime() + " ns");
}
```

- [ ] **Step 4: Run this test**

```bash
./gradlew test --tests "com.fupfin.midiraja.engine.PlaybackEngineTest.endOfTrack_delay_isObserved"
```

Expected: PASS (FakeClock accumulates both startup delay and end-of-track delay).

- [ ] **Step 5: Run all tests**

```bash
./gradlew test
```

Expected: all tests pass with no regressions.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/fupfin/midiraja/engine/PlaybackEngineTest.java
git commit -m "test: FakeClock behavior tests for PlaybackEngine timing paths"
```
