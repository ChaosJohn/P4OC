---
id: oa-r0sq
status: open
deps: []
links: []
created: 2026-05-10T16:27:28Z
type: task
priority: 2
assignee: Jasmin Le Roux
---
# Investigate slash input interaction model

Problem:
Slash autocomplete and slash command execution need a deliberate interaction model. The current implementation is functional enough for testing, but recent iteration exposed ambiguous UX/architecture choices around popup anchoring, command insertion vs execution, loading states, and command palette overlap.

Evidence:
- Inline layout pushed agent/model controls upward, which is not acceptable for chat chrome density.
- Input-local overlay could cover the text field or become constrained/tiny depending on parent measurement.
- A top-level Popup with a position provider works better for z-order and layout isolation, but should be validated across IME, rotation, small screens, and attachment rows.
- Selecting a slash suggestion currently inserts /command plus a trailing space for optional args; sending /command routes to executeCommand rather than normal chat.
- Built-in commands are merged with API commands because listCommands does not return OpenCode built-ins.

UX Constraint:
The popup must be compact, one-line per row, transient, and must not consume persistent chat space. It must not cover the typed command/cursor and must not push agent/model controls or other chat chrome. The user should be able to scroll the full matching command list with no artificial item cap.

Expected Behavior:
Typing / opens a compact command menu anchored above the chat input. The popup overlays at top z-level, remains linked to the input position, handles IME/window insets correctly, and shows all matches in a bounded scrollable list. Selecting a command should either insert /command with cursor at the end for arguments or execute immediately, based on a clearly chosen rule per command type.

Acceptance Criteria:
- Decide and document whether slash suggestions are insertion-only, immediate execution, or command-type dependent.
- Decide how built-in commands, MCP commands, custom commands, skills, and subtasks should be labeled and executed.
- Validate popup positioning with IME open, attachments present, portrait/landscape, and small phone widths.
- Verify the popup never pushes agent/model controls and never covers the typed command/cursor.
- Verify full result scrolling without synthetic caps.
- Identify whether command palette and slash autocomplete should share filtering/source-label helpers.
- Capture failure states for command loading with human-readable errors and no raw protocol payloads.

Verification:
Manual emulator/device testing should include typing /, filtering to an empty state, scrolling a long list, selecting a command with args, executing /compact, and retrying command loading failure.

