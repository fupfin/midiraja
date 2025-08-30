# Event-Driven UI Architecture (IoC) Specification

## Overview
Currently, the UI `Panel`s are active and tightly coupled to the `PlaybackEngine`. Every 50ms, the `DashboardUI` polls the engine's state, calculates a complex priority-based layout, and commands each panel to render by passing the entire engine instance. 

This track refactors the UI into an **Event-Driven Inversion of Control (IoC) architecture**.
*   **Decoupling State:** Panels will no longer fetch state from the engine. Instead, they will be passive listeners that update internal state variables only when notified by the engine or layout manager.
*   **Layout Encapsulation:** The massive layout algorithm in `DashboardUI` will be extracted into a dedicated `LayoutManager`.
*   **Autonomous Rendering:** Panels will maintain their own decay logic (e.g., VU meters) internally, independent of the engine's tick, while the `DashboardUI` simply calls `panel.render(sb)` at a fixed frame rate.

## Architectural Changes

### 1. The `PlaybackEventListener`
The `PlaybackEngine` will become an observable subject. It will broadcast events to registered listeners:
*   `onTick(long currentMicroseconds)`: Fired periodically to update progress.
*   `onChannelActivity(int channel, int velocity)`: Fired immediately when a Note On message is sent to a specific channel.
*   `onPlaybackStateChanged(PlaybackState state)`: Fired on volume/speed/transpose/pause changes.

### 2. The `LayoutManager` & `LayoutListener`
*   A new `DashboardLayoutManager` class will poll the terminal size (or use a JLine size listener if available).
*   When the size changes, it will calculate the allocated `width`, `height`, and `showHeaders` flags for each panel using the existing 5-priority algorithm.
*   It will dispatch a `onLayoutUpdated(LayoutConstraints bounds)` event to each panel.

### 3. Passive `Panel` Implementations
*   The `render(StringBuilder sb)` method will no longer take an engine, width, height, or showHeaders parameters.
*   Instead, panels will implement `LayoutListener` and `PlaybackEventListener` to cache their assigned bounds and the latest engine state internally.
*   The `ChannelActivityPanel` will manage its own `channelLevels` array, incrementing them upon `onChannelActivity` events and decaying them by `0.05` upon every `render()` call.