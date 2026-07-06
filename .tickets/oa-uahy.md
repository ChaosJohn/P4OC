---
id: oa-uahy
status: open
deps: []
links: []
created: 2026-07-06T19:23:41Z
type: bug
priority: 2
assignee: Jasmin Le Roux
---
# Server URL field can append pasted input into existing value

Problem:
On the Connect screen, the Server URL text field can keep an old cursor/value and append a newly typed/pasted URL into the middle of the existing URL, producing malformed connection targets.

Evidence:
During debug-app ADB testing after selecting/typing the same discovered server, the Server URL field showed a malformed value like: http://192.1http://192.168.24.25:409668.24.25... This happened while trying to replace the URL with http://192.168.24.25:4096.

UX Constraint:
Server connection setup must make wrong-target mistakes obvious and avoid corrupting the primary endpoint field. Users should be able to select a discovered server or replace a manual URL without needing to manually clear hidden prior text/cursor state.

Expected Behavior:
Selecting a discovered server or entering a new URL replaces the field contents cleanly, keeps the cursor at the end, and validates the final normalized URL before connect. A malformed URL should produce a readable validation error before attempting network fallback.

Acceptance Criteria:
- Selecting a discovered server replaces the Server URL field rather than appending into it.
- Manual paste/typing after a failed connection can cleanly replace the full prior URL.
- Connect validates the normalized URL and shows a readable malformed-URL error before network attempts.
- Regression coverage for replacing an existing URL value with a discovered/manual URL.

Verification:
On debug build, connect screen: fail one connection, select discovered server, then manually replace URL; confirm the field contains exactly one normalized URL and Connect uses that URL.

