---
id: oa-12ui
status: open
deps: [oa-wmvc, oa-3yk2, oa-qy0f, oa-wxf2, oa-ivwp, oa-vf6h, oa-prjv, oa-3l1w, oa-0mel, oa-9ev3, oa-n1fs, oa-gtw8, oa-tzta, oa-plno, oa-ua2q, oa-e6g3, oa-77dh]
links: [oa-prjv, oa-tzta, oa-n1fs, oa-qy0f, oa-e6g3, oa-9ev3, oa-wmvc, oa-gtw8, oa-plno, oa-wxf2, oa-vf6h, oa-3l1w, oa-0mel, oa-ivwp, oa-3yk2, oa-ua2q, oa-77dh]
created: 2026-07-05T18:04:23Z
type: task
priority: 1
assignee: Jasmin Le Roux
parent: oa-nwha
---
# Rewrite tests that preserve stale implementation contracts

Problem:
Audit found tests that lock in current implementation details or incorrect fallback behavior instead of intended user/upstream contracts. These tests can make future correct fixes look like regressions.

Evidence:
Examples include EventMapperTest.kt and older ToolStateExtTest.kt asserting English permission title behavior, ChatViewModelTest.kt previously missing command palette dispatch paths, ModelAgentManagerTest.kt and ModelReasoningEffortsTest.kt encoding fragile model/reasoning defaults, TabChatRouteCodecTest.kt and TabManagerPersistenceTest.kt preserving older route/tab shapes, ChatScrollRestorationTest.kt inspecting ChatScreen.kt source strings, and ChatScreenScrollRestorationTest.kt existing but blocked by androidTest harness issues.

UX Constraint:
Tests should protect user-visible behavior and architectural contracts, not implementation accidents. Red tests should fail for the intended reason and should not require future agents to rediscover why a bad fallback was once expected.

Expected Behavior:
Tests assert protocol/domain/UI boundaries, typed versus palette command behavior, explicit default precedence, workspace scoping, and lifecycle restoration behavior. Source-inspection tests are temporary only and replaced by behavior tests where possible.

Acceptance Criteria:
- Inventory stale-contract tests from audit and tag each as keep, rewrite, or delete.
- Replace English/domain permission assertions with raw data plus resource/UI formatter assertions.
- Ensure command tests cover typed and palette dispatch paths for local/session/server commands.
- Rewrite model/reasoning tests to assert server/config/user-default precedence without first-item fallback assumptions.
- Replace ChatScrollRestorationTest source guard with androidTest behavior coverage after the coroutine ServiceLoader blocker is fixed.
- Update route/tab persistence tests to assert current workspace/session identity requirements, not legacy compatibility guessing.

Verification:
Run targeted tests for each rewritten area and confirm failures, if any, point to production contract gaps rather than stale test expectations.

