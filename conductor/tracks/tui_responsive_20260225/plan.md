# Implementation Plan: TUI Responsive Layout & Dynamic Resizing

This plan outlines the steps to make the `midra` Full-Screen TUI responsive to terminal size changes, enforcing minimum dimensions and dynamically scaling UI components.

## Phase 1: Terminal Abstraction & Dimension Querying
- [x] Task: Update `TerminalIO` Interface
    - [ ] Add `int getWidth()` and `int getHeight()` methods.
- [x] Task: Implement JLine Terminal querying
    - [ ] Update `JLineTerminalIO` to return actual `terminal.getWidth()` and `terminal.getHeight()`.
- [x] Task: Update `MockTerminalIO` (Test Double)
    - [ ] Implement dummy methods for `getWidth` and `getHeight` returning fixed sizes (e.g., 80x24).
- [x] Task: Write basic unit tests to ensure `JLineTerminalIO` correctly reports dimensions.
- [x] Task: Conductor - User Manual Verification 'Terminal Abstraction & Dimension Querying' (Protocol in workflow.md)

## Phase 2: PlaybackEngine TUI Layout Refactoring
- [x] Task: Define Minimum TUI Dimensions
    - [ ] Set minimum required width (e.g., 80) and height (e.g., 24).
    - [ ] Implement a fallback render view if `term.getWidth() < MIN_WIDTH || term.getHeight() < MIN_HEIGHT`.
- [x] Task: Implement Dynamic Horizontal Scaling
    - [ ] Refactor horizontal line drawing (`====`, `----`) to use `term.getWidth()`.
    - [ ] Refactor the progress bar `[===========>-]` calculation to scale linearly with `term.getWidth()`.
    - [ ] Implement a dynamic string truncator to ensure long titles/filenames fit within `term.getWidth()` bounds without causing line wrap.
- [x] Task: Implement Dynamic Vertical Scaling
    - [ ] Adjust the number of displayed `[PLAYLIST]` items based on `term.getHeight()`.
    - [ ] If height is extremely constrained but > MIN_HEIGHT, consider collapsing non-essential panels (e.g., hiding playlist).
- [x] Task: Refine `uiLoop` performance
    - [ ] Cache terminal dimensions at the start of the loop iteration.
    - [ ] Ensure clearing (`\033[J`, `\033[H`) properly resets the layout if the terminal shrinks.
- [x] Task: Add integration/unit tests for the layout engine logic.
- [x] Task: Conductor - User Manual Verification 'PlaybackEngine TUI Layout Refactoring' (Protocol in workflow.md)
\n## Phase: Review Fixes\n- [x] Task: Apply review suggestions [5ced387]\n
