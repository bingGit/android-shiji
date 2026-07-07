# VoiceFlow V3 Prototype Implementation Map

日期：2026-07-05

## Baseline

- Pencil source: `docs/prototype/voiceflow-v1.pen`
- Implementation target: Android Compose
- Design frame: `390 x 720`
- Runtime scale: all V3 prototype coordinates are stored against the `390` width frame and converted by `scale = screenWidth / 390`
- Page background: `#F8F5EF`
- Horizontal content inset: `22`
- Main content width: `346`
- Bottom navigation: `x=24, y=646, w=342, h=58`, bottom margin `16`
- Bottom navigation item: `w=107.33, h=46`, inner padding `6`, gap `4`, selected fill `#E8F0E8CC`

## Shared Tokens

| Purpose | Value |
| --- | --- |
| Background | `#F8F5EF` |
| Primary text | `#23302A` |
| Secondary text | `#68756E` |
| Muted text | `#77837C` / `#8A9790` |
| Green | `#376854` |
| Green soft | `#E8F0E8CC` / `#EAF1EA` |
| Sand chip | `#F3F1EA` |
| Line | `#E8E2D7` |
| Warm danger | `#B85B48` |
| Warm soft | `#F8EDE8` |

## Page Header

All V3 pages use the same header geometry:

| Element | x | y | w | h | Type |
| --- | ---: | ---: | ---: | ---: | --- |
| Eyebrow | 22 | 78 | 346 | 16 | 12sp / semibold / muted |
| Title | 22 | 104 | 346 | 26 | 22sp / bold / primary |
| Description | 22 | 134 | 316 | 19 | 13sp / regular / muted |

Implementation note:

- Compose implementation uses `PrototypeMetrics` to convert every Pencil `x/y/w/h` and typography size from the 390-wide prototype frame into runtime `dp/sp`.
- This avoids the earlier drift where the UI was capped at a fixed 390dp canvas and could visually separate from wider emulator screenshots.
- Dynamic content remains dynamic inside the same scaled slots; the slot coordinates should continue to come from the Pencil prototype.

## Record Page

Reference nodes:

- `X0Qf2e`: V3-0 initial idle state, waits for the first recording action
- `XRnq7`: V3 recording initial state
- `Y0Tkcd`: V3 text-rich state

V3-0 initial idle state:

| Element | x | y | w | h | Implementation |
| --- | ---: | ---: | ---: | ---: | --- |
| Header eyebrow | 22 | 78 | 346 | 16 | `记录灵感`; product UI text, not a design annotation |
| Header title | 22 | 104 | 346 | 26 | `准备记录` |
| Header description | 22 | 134 | 316 | 19 | `按下按钮，说出刚冒出来的想法。` |
| Initial copy area | 36 | 176 | 318 | 116 | Waiting status, user-facing prompt, CTA highlight |
| Idle record stage | 0 | 396 | 390 | 224 | Green microphone button, no red recording state, no waveform |
| Bottom nav | 24 | 646 | 342 | 58 | Fixed bottom with Record selected |

Prototype rule:

- Screen frame content must be production UI only. State names such as `V3-0 初始：等待记录` stay outside the phone frame as annotations.
- The idle record stage uses concentric circles centered on the microphone button. Do not draw the outer sound field as a non-square oval; the open sound field comes from opacity and radius, not horizontal stretching.

Recording and completed states:

| Element | x | y | w | h | Implementation |
| --- | ---: | ---: | ---: | ---: | --- |
| Transcript area | 36 | 166 | 318 | 130-220 | Dynamic text, status pill, char count |
| Record stage | 0 | 372/398 | 390 | 232 | Dynamic circular record button and waveform |
| Bottom nav | 24 | 646 | 342 | 58 | Fixed bottom with real tab state |

Dynamic mapping:

- Idle: use `X0Qf2e`; green microphone ready state, no recording waveform.
- Recording: transcript text grows in the transcript area; current status uses green state dot.
- Completed: text remains in the transcript area and a saved note snippet is shown before the stage when space allows.

## Card List Page

Reference node: `JvcZJ`.

| Element | x | y | w | h | Implementation |
| --- | ---: | ---: | ---: | ---: | --- |
| Background glow | 52 | 150 | 286 | 320 | Low opacity radial-ish canvas/ellipse |
| Filters | 22 | 174 | variable | 26 | All / pending / polished chips |
| List row 1 | 22 | 218 | 346 | 92 | Time, title, summary, status dot, more/copy/delete |
| List row 2 | 22 | 322 | 346 | 92 | Same |
| List row 3 | 22 | 426 | 346 | 92 | Same |
| Add button | 302 | 560 | 52 | 52 | Jump to record tab |
| Bottom nav | 24 | 646 | 342 | 58 | Fixed bottom |

Empty state exception:

- When there are no cards, replace row area with one rounded empty state block at `x=22, y=174, w=346`.

## Card Detail Page

Reference node: `OlWdv`.

| Element | x | y | w | h | Implementation |
| --- | ---: | ---: | ---: | ---: | --- |
| Original body title | 22 | 174 | 316 | 16 | "原文" / status |
| Original body | 22 | 202 | 346 | 69 | Editable text field without heavy card chrome |
| Divider | 22 | 328 | 346 | 1 | Line |
| Primary actions | 22/92/164/236 | 352 | variable | 32 | Edit / polish / summarize / delete |
| Result title | 22 | 414 | 316 | 16 | Current result title |
| Result marker | 22 | 446 | 3 | 84 | Vertical marker |
| Result body | 36 | 442 | 326 | 63 | Editable result body |
| Result actions | 36/106 | 544 | variable | 32 | Copy / replace original |
| Bottom nav | 24 | 646 | 342 | 58 | Fixed bottom |

## Settings Page

Reference node: `HEYTz`.

| Element | x | y | w | h | Implementation |
| --- | ---: | ---: | ---: | ---: | --- |
| Auth summary | 22 | 174 | 346 | 70 | Status, provider, model; implementation gives +6 height to prevent configured subtitle clipping |
| Setting row 1 | 22 | 266 | 346 | 56 | Realtime provider/model |
| Setting row 2 | 22 | 334 | 346 | 56 | Post-process provider/model |
| Setting row 3 | 22 | 402 | 346 | 56 | Base URL / relay station |
| Setting row 4 | 22 | 470 | 346 | 56 | Prompt / hotwords |
| Setting row 5 | 22 | 538 | 346 | 56 | Storage / privacy |
| Bottom nav | 24 | 646 | 342 | 58 | Fixed bottom |

## Acceptance Gate

- A screenshot should visibly match the V3 390x720 rhythm before judging business behavior.
- Header positions, bottom nav size, row heights, chip heights, and record stage placement are not optional styling.
- Dynamic content can truncate or scroll inside its assigned area, but must not push bottom navigation or main anchors.
