# Accessibility audit rubric

Record each issue with an **evidence path and line**, impact, severity, reproduction, improvement and verification. Mark unverifiable items as *Needs device test*, not *Passed*.

| Priority | Interpretation | Example |
| --- | --- | --- |
| P0 | Immediate serious harm, exposure or loss of user control | Mic continues capturing after leaving without disclosure |
| P1 | Core communication inaccessible to affected users | Essential icon-only action has no accessible name, gesture-only capture |
| P2 | Material degradation or recurring confusion | Rapid partial text generates excessive screen-reader announcements |
| P3 | Polish or improvement | Redundant decorative semantic nodes |

## Audit evidence
- **Static code:** manifest; Compose semantics and input modifiers; recognition listener lifecycle; resource cleanup; persistence.
- **Unit/integration:** pure state transitions, permission denial, recognition error, partial/final transitions, mode changes.
- **Instrumented:** accessibility semantics assertions and UI navigation on Android.
- **Human/device:** TalkBack, external keyboard/switch, large-font settings, varying densities, landscape/portrait, multiple real devices for Nearby, reduced motion/flash.
- **Accessibility standards:** map to current applicable WCAG/Android guidance as relevant, but never claim formal compliance without full audit.

## Output requirements
Use observed/inferred/unverified labels; no fabricated results or invented line numbers. Clearly separate accessibility problems from broader refactoring and product wishes. Recommend tests for each P0/P1 finding.
