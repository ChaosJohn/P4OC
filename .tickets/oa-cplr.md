---
id: oa-cplr
status: open
deps: []
links: [oa-77dh]
created: 2026-07-06T19:22:45Z
type: bug
priority: 1
assignee: Jasmin Le Roux
---
# Persist unsent chat drafts and attachments per tab

Problem:
The local busy-message queue has been removed in favor of upstream-submitted follow-ups, but unsent chat composer state remains local UI/ViewModel state. Typed-but-not-sent draft text and selected attachments may still be lost or incorrectly reused across tab/session recreation, process return, or file picker return.

Evidence:
The busy follow-up refactor deletes ChatUiState.queuedMessages and sends submitted prompts upstream immediately, so queued prompt loss is no longer a local persistence problem. Remaining user-authored state before Send still lives in ChatUiState.inputText and FilePickerManager attached-file state.

UX Constraint:
The chat composer is core workspace state. Draft text or selected attachments must not leak across sessions/workspaces/tabs, and missing attachment references must produce readable recovery UI rather than silent loss or raw errors.

Expected Behavior:
Unsent draft text and selected attachments restore for the same tab/session/workspace when safe. Switching to a different session/workspace/tab does not inherit the prior draft or attachments. Missing or inaccessible attachment references show a human-readable unavailable/removable state.

Acceptance Criteria:
- Identify the current source of truth for unsent draft text, selected attachments, and file picker return state after the busy-follow-up queue refactor.
- Persist or save draft/attachment state keyed by existing tab/session/workspace identity without introducing global/default workspace fallbacks.
- Ensure switching sessions/workspaces/tabs does not leak draft text or attachments.
- Handle missing/inaccessible restored attachments with clear UI and a remove path.
- Add focused behavior/ViewModel tests for same-key restoration, cross-key isolation, and missing attachment recovery where seams exist.

Verification:
Run targeted ChatViewModel/ChatInputBar/FilePicker tests. Smoke test typing a draft with an attachment, switching away/back, and returning from file picker.

