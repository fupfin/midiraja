# Implementation Plan: Event-Driven UI Architecture (IoC)

This plan outlines the refactoring to an event-driven Inversion of Control architecture, creating a formal `LayoutManager` and passive UI `Panel`s.

## Phase 1: Event System & Core Decoupling
- [ ] Task: Define the `PlaybackEventListener` interface in `com.midiraja.ui`.
    - [ ] Add methods: `onPlaybackStateChanged`, `onTick`, `onTempoChanged`, `onChannelActivity`.
- [ ] Task: Refactor `PlaybackEngine` to support event broadcasting.
    - [ ] Add `List<PlaybackEventListener> listeners`.
    - [ ] Create `addPlaybackEventListener(PlaybackEventListener listener)`.
    - [ ] Trigger `onChannelActivity` within the MIDI message sending logic (Note On events).
    - [ ] Trigger `onTick` within the main `start()` loop.
    - [ ] Trigger `onPlaybackStateChanged` within `adjustVolume`, `adjustSpeed`, etc.
- [ ] Task: Conductor - User Manual Verification 'Event System & Core Decoupling' (Protocol in workflow.md)

## Phase 2: Layout Manager Abstraction
- [ ] Task: Define `LayoutConstraints` record containing `allocatedWidth`, `allocatedHeight`, `showHeaders`.
- [ ] Task: Define `LayoutListener` interface with `onLayoutUpdated(LayoutConstraints bounds)`.
- [ ] Task: Create `DashboardLayoutManager` class.
    - [ ] Move the 5-priority height calculation logic from `DashboardUI` into this class.
    - [ ] Provide a `calculateLayout(int termWidth, int termHeight, int numPlaylistItems)` method that returns a map or object containing the `LayoutConstraints` for each panel (Metadata, Status, Channels, Controls, Playlist).
- [ ] Task: Conductor - User Manual Verification 'Layout Manager Abstraction' (Protocol in workflow.md)

## Phase 3: Passive Panel Implementations
- [ ] Task: Refactor the `Panel` interface.
    - [ ] Change `render(StringBuilder sb, int width, int height, boolean showHeaders, PlaybackEngine engine)` to `render(StringBuilder sb)`.
    - [ ] Extend `LayoutListener` and `PlaybackEventListener` interfaces.
- [ ] Task: Update all `Panel` implementations (`MetadataPanel`, `StatusPanel`, `ChannelActivityPanel`, `ControlsPanel`) to become passive state receivers.
    - [ ] Cache engine state internally (e.g., `currentMicroseconds`, `volumeScale`).
    - [ ] Cache `LayoutConstraints` internally.
    - [ ] Implement self-contained VU meter decay logic within `ChannelActivityPanel.render()`.
- [ ] Task: Conductor - User Manual Verification 'Passive Panel Implementations' (Protocol in workflow.md)

## Phase 4: UI Assembly & Testing
- [ ] Task: Refactor `DashboardUI.java` to wire up the new components.
    - [ ] Instantiate `DashboardLayoutManager`.
    - [ ] Register panels as listeners to the engine and the layout manager.
    - [ ] Simplify the 20fps `runRenderLoop` to solely poll terminal size, trigger layout recalculations if changed, and call `panel.render(sb)` for each panel.
- [ ] Task: Update `LineUI` and `DumbUI` to use the new event-driven `StatusPanel` correctly.
- [ ] Task: Run the test suite and ensure no regressions.
- [ ] Task: Conductor - User Manual Verification 'UI Assembly & Testing' (Protocol in workflow.md)