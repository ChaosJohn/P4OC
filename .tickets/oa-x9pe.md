---
id: oa-x9pe
status: open
deps: []
links: [oa-9ev3, oa-n1fs, oa-4olr]
created: 2026-05-09T15:46:58Z
type: feature
priority: 2
assignee: Jasmin Le Roux
---
# Add terminal copy and paste support

Terminal should support mobile-friendly clipboard workflows. Today the terminal view accepts keyboard input but does not expose obvious copy/paste affordances.\n\nExpected UX:\n- Users can paste clipboard text into the terminal without relying on OS keyboard tricks.\n- Users can select/copy terminal output.\n- Copy/paste controls should be touch-friendly and consistent with the terminal extra-keys/action UI.\n\nSuggested phased approach:\n1. Add a Paste action/extra key wired to Android ClipboardManager.\n2. Add long-press/select mode with Copy / Paste / Select All toolbar.\n\nAcceptance criteria:\n- Paste inserts clipboard text into the active terminal session.\n- Copy can copy selected terminal output to the clipboard.\n- Long-press/touch behavior does not break terminal focus or soft keyboard behavior.\n- Empty clipboard and non-text clipboard states are handled gracefully.\n- Functional controls have content descriptions/test tags.


## Notes

**2026-05-09T15:52:24Z**

Standardization note: deliver the full terminal clipboard workflow, not a partial spike. Include both paste and copy/select support unless implementation proves blocked by terminal-view limitations. UI chrome must be justified by agent-space constraints: prefer existing extra-keys/action surfaces and transient selection toolbar over persistent controls. Acceptance should include: paste text clipboard into active terminal; select/copy terminal output; select-all if terminal view supports it; handle empty/non-text clipboard; preserve terminal focus/IME behavior; no persistent chrome that reduces agent/terminal viewport without a strong reason; content descriptions/test tags for actions.
