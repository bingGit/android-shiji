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

## Canvas organization

Every new prototype area must be clearly named and visually grouped.

Rules:

1. Add a title for every new area, such as `V2 interaction prototype`, `Visual direction exploration`, or `Implementation reference`.
2. Add a short note under the title explaining the area's purpose.
3. Use a light boundary or background region to show which screens belong together.
4. Do not overwrite previous areas. Keep V1, V2, visual explorations, and implementation references separate for comparison.
5. Name areas so they can be referenced in discussion, for example: "apply visual direction B to V1".
