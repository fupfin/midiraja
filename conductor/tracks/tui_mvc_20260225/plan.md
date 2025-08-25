# Implementation Plan: TUI MVC & Responsive Component Architecture

This plan executes the refactoring of `midra`'s TUI from procedural code into a responsive Model-View-Controller (MVC) architecture with independent, layout-managed Panels.

## Phase 1: Engine and UI Decoupling (MVC Foundation)
- [ ] Task: Create `src/main/java/com/midiraja/ui/` package.
- [ ] Task: Define the `PlaybackUI` interface with `runRenderLoop(PlaybackEngine engine)` and `runInputLoop(PlaybackEngine engine)`.
- [ ] Task: Refactor `PlaybackEngine.java`:
    - [ ] Remove `uiLoop()` and `inputLoop()` methods completely.
    - [ ] Create public getter methods for all necessary state variables (e.g., `getCurrentMicroseconds()`, `getChannelLevels()`, `getCurrentBpm()`, etc.).
    - [ ] Create public action methods for UI control (e.g., `adjustVolume(double delta)`, `adjustSpeed(double delta)`, `seekRelative(long micros)`, `requestStop(PlaybackStatus status)`).
    - [ ] Modify `start()` to accept a `PlaybackUI` instance and execute its render and input loops within the `StructuredTaskScope`.
- [ ] Task: Update `MidirajaCommand.java`:
    - [ ] Add the `--ui <mode>` command-line option (`auto`, `tui`, `line`, `dumb`).
    - [ ] Instantiate the selected UI implementation (`DumbUI`, `LineUI`, or `DashboardUI`) and pass it to `engine.start(ui)`.
- [ ] Task: Conductor - User Manual Verification 'Engine and UI Decoupling (MVC Foundation)' (Protocol in workflow.md)

## Phase 2: Component Architecture & Responsive Panels
- [ ] Task: Define a `Panel` interface in the `ui` package:
    - [ ] Method `int calculateHeight(int availableHeight)`: Determines the desired height based on current constraints.
    - [ ] Method `void render(StringBuilder sb, int allocatedWidth, int allocatedHeight, PlaybackEngine engine)`: Renders the component's content into the provided string builder.
- [ ] Task: Implement `MetadataPanel`:
    - [ ] Render 1 to N lines of text depending on allocated height.
- [ ] Task: Implement `StatusPanel` (Responsive):
    - [ ] Implement 5-line "Max" layout (Current design).
    - [ ] Implement 2-3 line "Medium" layout (Consolidated info).
    - [ ] Implement 1-line "Min" layout (Classic CLI style).
- [ ] Task: Implement `ChannelActivityPanel` (Responsive):
    - [ ] Implement "Primary" 16-line vertical layout.
    - [ ] Implement "Fallback" 3-4 line horizontal grid layout for small terminals.
- [ ] Task: Implement `ControlsPanel` (Responsive):
    - [ ] Implement 3-line "Primary" layout.
    - [ ] Implement 1-line "Min" condensed layout.
- [ ] Task: Conductor - User Manual Verification 'Component Architecture & Responsive Panels' (Protocol in workflow.md)

## Phase 3: Dynamic Layout Manager Integration
- [ ] Task: Refactor `DashboardUI.java` to use the new Panels.
- [ ] Task: Implement the "Layout Negotiation Priority Algorithm" in `DashboardUI`:
    - [ ] Step 1: Calculate heights assuming 16-line vertical channels.
    - [ ] Step 2: If remaining space is insufficient for Status/Controls (e.g., < 1 line), recalculate assuming horizontal 4-line channels.
    - [ ] Step 3: Clamp all panel heights to absolute minimums (1 line) if space is still critically constrained.
- [ ] Task: Ensure the `uiLoop` in `DashboardUI` renders the layout smoothly at 20 FPS without flickering (using `\033[K` and `\033[J`).
- [ ] Task: Ensure `LineUI.java` renders only a simple 1-line status.
- [ ] Task: Ensure `DumbUI.java` renders only a static startup message and sleeps.
- [ ] Task: Add integration tests for the layout calculation and rendering engine.
- [ ] Task: Conductor - User Manual Verification 'Dynamic Layout Manager Integration' (Protocol in workflow.md)
