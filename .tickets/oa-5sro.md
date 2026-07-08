---
id: oa-5sro
status: open
deps: [oa-6swf, oa-yx4y, oa-07lr, oa-fac4, oa-nugm, oa-pjcl, oa-1xnu, oa-cj0w]
links: []
created: 2026-07-08T14:42:17Z
type: task
priority: 1
assignee: Jasmin Le Roux
parent: oa-xju6
---
# Approval gate: staged rollout and visual QA

Before shipping, perform staged verification and visual QA for the pinned Home + Start Work architecture.

## Acceptance Criteria

- Compile, detekt, and relevant unit/UI tests pass.
- Manual/visual QA covers: first connect, restore existing chat, Home server carousel, workspace drill-in, + from active chat, + from Home workspace detail, browse sessions, server auth failure, server removal with open tabs, app background/reconnect.
- Screenshots demonstrate Home = existing work and + = new work distinction.
- Approval recorded before release branch merge.


## Verification Notes

- 2026-07-08: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:compileDebugKotlin && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:detekt && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest` passed.
- 2026-07-08: Manual/visual QA is blocked because `adb devices` returned no connected emulator/device. Do not close until screenshots cover the required 11 scenarios.
- 2026-07-08: `emulator -list-avds` found `Pixel7`, but booting it failed because x86_64 emulation requires hardware acceleration and `/dev/kvm` is unavailable. `adb wait-for-device` was cancelled after the emulator failure. Visual QA remains blocked until a physical device or KVM-capable emulator is available.
- Planned screenshot walkthrough once device is available: first connect; restore existing chat; Home server carousel; workspace drill-in; + from active chat; + from Home workspace detail; browse sessions; server auth failure; server removal with open tabs; app background/reconnect; Home vs + distinction.
