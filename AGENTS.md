# AGENTS.md

Guidance for AI agents (and humans) working in this project.
This file mainly focused on the main project.
For subprojects, refer to the documents under subprojects' folder.

## Project

XMPP based Chat Bot with OMEMO support.

The main project (daapu) is a Kotlin project, focused on ChatBot logic.

The subproject `xmpp-bridge` is a Python adapter that bridges XMPP and NATS.

## Verification commands

For main project, use Gradle to run unit tests.

For subprojects, refer to the instructions under subprojects' folder.

## Code quality and style rules

These sections describe the rules/items to watch out when writing or reviewing code.
**These rules apply to the whole repo (main project + all subprojects).**
When writing or reviewing code, looking for bugs with the following perspectives:

+ Bug detection and correctness: Logic errors, off-by-one mistakes, race conditions, unhandled edge cases, incorrect assumptions, regressions.
+ Test coverage and test quality: Coverage gaps, weak assertions, tautological tests, missing scenarios. Are key code paths tested? Do tests actually validate correct behavior? Are unit tests well-structured with meaningful assertions?
+ Performance and security: Inefficiencies, resource leaks, injection risks, insecure defaults, exposed secrets, missing 
+ Code quality and style: follow existing pattern (project conventions), no dark magic, no hacky solution/workaround, no complex logic without comments. Maintainability is the first priority.
