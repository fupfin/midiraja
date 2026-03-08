# AGENTS.md

This file provides critical guidance and architectural context to AI agents when working with the Midiraja codebase.

## Behavioral guidelines

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