# Android VoiceFlow Prototype

## Source of truth

`voiceflow-v1.pen` is the editable Pencil design source file for the current interaction prototype.

Use Pencil MCP tools to inspect or edit `.pen` files. Do not read `.pen` files as plain text because the format is encrypted and tool-specific.

## Review exports

PNG, PDF, or HTML exports are optional review artifacts. They are useful for checkpoint comparison, but they are not the design source.

Recommended workflow:

1. Edit and review `voiceflow-v1.pen` in Pencil.
2. Use Pencil MCP `snapshot_layout`, `get_screenshot`, and `get_variables` during implementation.
3. Export PNG or PDF only when a review checkpoint is needed.
