---
id: oa-3l1w
status: open
deps: []
links: [oa-wmvc, oa-12ui]
created: 2026-07-05T18:06:47Z
type: bug
priority: 2
assignee: Jasmin Le Roux
parent: oa-nwha
---
# Resource file language fallback labels

Problem:
File language fallback labels such as plain text are hardcoded instead of resource-backed presentation text.

Evidence:
Display-boundary audit identified SoraLanguageRegistry.kt language fallback labels, including plain text, as user-facing copy outside a resource boundary.

UX Constraint:
File language labels should be concise and localizable, and should not confuse protocol/file-extension identifiers with display names.

Expected Behavior:
Language detection returns stable technical language ids/kinds. UI presentation maps them to resource-backed names, with unknown/plain-text fallbacks handled consistently.

Acceptance Criteria:
- Separate language id/kind from display label.
- Move plain-text and unknown-language display labels to resources or a UI formatter.
- Preserve extension/language identifiers for syntax logic only, not as localized labels.
- Add tests for known, plain-text, and unknown fallback display behavior.

Verification:
Run targeted file/language registry tests and compile after implementation.

