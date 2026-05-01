# AGENTS.md

This file provides critical guidance and architectural context to AI agents when working with the Midiraja codebase.

## Behavioral guidelines

* Do not add `Co-Authored-By` lines to git commit messages.

* Proceed with all work based on evidence and explicit agreement with the user.
* Verify actual results for every task, and determine success and completion based on that verification.
* Break work down into the smallest practical units and proceed step by step.
* Perform only the work that has been explicitly agreed upon with the user.
* For every task, plan the goal, execution procedure, and completion criteria in advance, then proceed step by step.

## Coding guide
* Code is a form of documentation. It should be readable and allow people to understand the intent.
* Use the simplest approach possible. Prefer functions over OOP, and actively use lambdas, switch expressions, records, method references, and static imports.
* Avoid code duplication. Eliminate it using method extraction, higher-order functions, the Strategy pattern, the Template Method pattern, and similar techniques.
* Write as much test code as possible, and whenever feasible, write tests before implementation.
* Refactor for testability by applying principles such as DIP and SRP.
* Prefer immutability. Use values rather than state.
* By default, do not allow null for method parameters and return values. Locally, null may be allowed and used internally.
* Keep git messages brief, summarizing only changes, omitting extra details, under 5 lines.

For other information about this product, refer to @READEME.md, and for work instructions, refer to @CONTRIBUTING.md.

## Terminal lifecycle — DO NOT CHANGE without understanding this

**Why ISIG is disabled in `JLineTerminalIO.init()`:**

When ISIG is enabled (the default), pressing Ctrl+C generates SIGINT, which causes
`System.exit()`. During `System.exit()`, JVM application threads (the render loop, input loop)
continue running while shutdown hooks execute concurrently. The running render loop writes
terminal escape sequences that overwrite any restore sequences the shutdown hook sends —
leaving the cursor invisible and the alt screen active after the process exits.

The fix: disable ISIG so Ctrl+C is delivered as the character `\x03` (ETX). JLine reads it
and maps it to `TerminalKey.QUIT` (via `km.bind(TerminalKey.QUIT, "\003")`). This routes
through the normal QUIT key path, which stops the render loop cleanly before the terminal
is restored by the `finally` block in `PlaybackRunner.run()`.

**Do not:**
- Re-enable ISIG (`Attributes.LocalFlag.ISIG`) in `JLineTerminalIO.init()`
- Remove the `\003` → QUIT binding in `buildKeyMap()`
- Move terminal restore logic from the `finally` block in `PlaybackRunner.run()` into
  a shutdown hook — shutdown hooks run while app threads are still active

**Test:** `JLineTerminalIOTest.ctrlC_isBoundToQuit()` will fail if the key binding is removed.

## graphify

This project has a graphify knowledge graph at graphify-out/.

Rules:
- Before answering architecture or codebase questions, read graphify-out/GRAPH_REPORT.md for god nodes and community structure
- If graphify-out/wiki/index.md exists, navigate it instead of reading raw files
- For cross-module "how does X relate to Y" questions, prefer `graphify query "<question>"`, `graphify path "<A>" "<B>"`, or `graphify explain "<concept>"` over grep — these traverse the graph's EXTRACTED + INFERRED edges instead of scanning files
- After modifying code files in this session, run `graphify update .` to keep the graph current (AST-only, no API cost)
