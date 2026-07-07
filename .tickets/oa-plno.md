---
id: oa-plno
status: open
deps: []
links: [oa-ua2q, oa-12ui]
created: 2026-07-05T18:06:47Z
type: bug
priority: 2
assignee: Jasmin Le Roux
parent: oa-nwha
---
# Restore session list search and tree expansion state

Problem:
Session list search text and tree expansion state may be lost or leak because they are lifecycle-blind or not clearly scoped.

Evidence:
Lifecycle audit identified SessionListScreen.kt and SessionListViewModel.kt search and tree expansion state as restoration-critical for navigating sessions.

UX Constraint:
Session navigation must remain compact but predictable. Losing search/expansion while switching tabs can make it hard to resume work and can increase wrong-session selection risk.

Expected Behavior:
Session list search and tree expansion restore for the same workspace/server context and do not leak across different workspaces/servers. Clear actions should explicitly reset state.

Acceptance Criteria:
- Scope search query and tree expansion by workspace/server/tab context.
- Preserve state across tab switch and configuration recreation.
- Avoid leaking one workspace/server's expanded tree into another.
- Add tests for same-context restoration and different-context isolation.
- Define behavior when restored search no longer matches any sessions.

Verification:
Run targeted SessionListViewModel/Screen tests and smoke test switching between two workspace session lists.

