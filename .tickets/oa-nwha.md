---
id: oa-nwha
status: open
deps: []
links: [oa-qy0f, oa-wmvc, oa-wxf2, oa-casy, oa-3yk2]
created: 2026-07-05T18:03:15Z
type: epic
priority: 1
assignee: Jasmin Le Roux
---
# Resolve audit-wide source-of-truth and lifecycle regressions

Problem:
The four red-test complaint regressions exposed broader classes of source-of-truth, layer-boundary, lifecycle, fallback, and test-contract problems across the app. Audit agents found additional instances beyond the original permission title, undo/redo dispatch, model defaults, and chat scroll restoration tickets.

Evidence:
Audits found further instances in built-in command semantics, hardcoded display strings outside resource/UI boundaries, lifecycle-blind UI state, Android-guessed defaults, stale runtime after settings writes, workspace/global fallback leakage, and tests preserving implementation details.

UX Constraint:
P4OC must keep workspace/session/user state predictable on phones. Fixes should avoid adding persistent chrome unless justified, should keep core editing/chat workflows in-app, and should make failure states human-readable instead of surfacing protocol or JSON details.

Expected Behavior:
Android should treat upstream/server protocol, resource-backed UI formatting, workspace/session-scoped state, and observable settings/config as authoritative. Local fallbacks must be explicit, tested, and user-visible when they represent degraded behavior.

Acceptance Criteria:
- Child tickets cover each audited problem family with concrete files and evidence.
- Existing original complaint tickets remain linked rather than duplicated.
- Each child ticket defines user-facing expected behavior and verification notes.
- Follow-up work removes or rewrites tests that encode incorrect implementation contracts.

Verification:
Use targeted unit/androidTest/Compose tests per child ticket. Run compile/detekt only after implementation work, not as part of ticket creation.

