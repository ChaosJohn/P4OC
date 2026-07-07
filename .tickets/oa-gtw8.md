---
id: oa-gtw8
status: open
deps: []
links: [oa-v3js, oa-t3tb, oa-12ui]
created: 2026-07-05T18:06:47Z
type: bug
priority: 1
assignee: Jasmin Le Roux
parent: oa-nwha
---
# Preserve in-app file editor buffers across lifecycle

Problem:
Unsaved in-app file edit buffers are lifecycle-critical and may be lost when the file viewer/editor is disposed, recreated, or switched across tabs.

Evidence:
Lifecycle audit identified FileViewerScreen.kt unsaved edit buffer state as restoration-critical. Project instructions forbid relying on external editors for core editing workflows, so in-app editor state must be protected.

UX Constraint:
Losing unsaved edits is a severe user-data-loss bug. File editing must remain tabbed inside P4OC by default and must respect workspace/file identity.

Expected Behavior:
Unsaved edits are scoped by workspace, tab, and file path. Switching tabs, configuration changes, or process recreation should preserve the draft where feasible. Conflicts or unavailable files must be shown with clear recovery options, not silent overwrite/discard.

Acceptance Criteria:
- Identify current file editor buffer source of truth and lifecycle boundaries.
- Persist unsaved edit buffers per workspace/tab/file path or explicitly store recoverable drafts.
- Detect file-on-disk changes/conflicts before saving a restored draft.
- Add behavior tests for typing edits, switching away/back, and preserving the buffer.
- Add conflict/failure tests for deleted or externally modified files if supported by repository seams.

Verification:
Run targeted file viewer/editor tests and smoke test editing a file, switching tabs, rotating/recreating, and returning.

