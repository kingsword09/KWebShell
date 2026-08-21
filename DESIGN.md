---
name: KWebShell Proof Desk
description: Evidence-led browser engineering presented as a precise prepress proofing surface.
colors:
  coated-paper: "#f5f3ec"
  paper-deep: "#e9e6da"
  paper-shadow: "#d8d4c7"
  graphite: "#242825"
  graphite-soft: "#5e635d"
  graphite-faint: "#8d9188"
  rule: "#c7c5ba"
  rule-dark: "#989c93"
  process-cyan: "#008eaa"
  process-magenta: "#ba356f"
  process-yellow: "#d5a900"
  registration-black: "#20231f"
  proof-green: "#2e765a"
typography:
  display:
    fontFamily: "Georgia, serif"
    fontSize: "36px"
    fontWeight: 500
    lineHeight: 0.98
    letterSpacing: "normal"
  title:
    fontFamily: "Georgia, serif"
    fontSize: "21px"
    fontWeight: 500
    lineHeight: 1.1
    letterSpacing: "normal"
  body:
    fontFamily: "Proof Sans, Avenir Next, Segoe UI, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.45
    letterSpacing: "normal"
  label:
    fontFamily: "SFMono-Regular, Consolas, monospace"
    fontSize: "10px"
    fontWeight: 500
    lineHeight: 1.45
    letterSpacing: "0.08em"
rounded:
  control: "3px"
  circular: "999px"
spacing:
  xs: "5px"
  sm: "8px"
  md: "14px"
  lg: "18px"
  xl: "24px"
components:
  button-primary:
    backgroundColor: "{colors.graphite}"
    textColor: "{colors.coated-paper}"
    typography: "{typography.body}"
    rounded: "{rounded.control}"
    padding: "9px 11px"
    height: "44px"
  navigation-active:
    backgroundColor: "{colors.coated-paper}"
    textColor: "{colors.graphite}"
    rounded: "0"
    padding: "7px 8px"
    height: "56px"
  proof-tag:
    backgroundColor: "transparent"
    textColor: "{colors.graphite-soft}"
    typography: "{typography.label}"
    rounded: "0"
    padding: "4px 6px"
---

# Design System: KWebShell Proof Desk

## Overview

**Creative North Star: "The Prepress Proofing Desk"**

KWebShell presents technical evidence like a physical press proof: coated
paper, graphite rules, CMYK registration marks, job-ticket navigation, and
measuring ledgers. The surface is calm and information-dense, with visual
character coming from production artifacts rather than generic dashboard
cards or decorative effects.

The interface must make correctness observable. Progress travels through the
registration ruler, measured values remain textual, and unavailable evidence
stays visibly unavailable. Expressive details support inspection; they never
turn benchmark fixtures into product or performance claims.

**Key Characteristics:**

- Coated-paper fields divided by precise graphite rules.
- Editorial serif headings paired with utilitarian sans and mono evidence.
- CMYK accents reserved for registration, progress, focus, and measurement.
- Flat, native-feeling controls with explicit state text.
- Desktop information density that collapses into a usable single-column proof.

## Colors

The palette combines warm proofing stock with restrained graphite and real
process-print accents.

### Primary

- **Graphite Ink:** the default text, control, and strongest-rule color.
- **Process Cyan:** focus, active progress, and the first registration channel.

### Secondary

- **Process Magenta:** the second registration and timeline channel.
- **Process Yellow:** the third registration and timeline channel.

### Neutral

- **Coated Paper:** the primary canvas and inverse text on dark controls.
- **Deep Stock:** rails, timelines, and persistent peripheral surfaces.
- **Soft Graphite:** secondary copy and metric labels.
- **Proof Rules:** structural separation without card chrome.
- **Registration Black:** code surfaces and the fourth process channel.

**The Process-Ink Rule.** CMYK color marks process, focus, or measured state;
it is never decorative fill for large surfaces.

**The Evidence-State Rule.** Green means a completed proof only. Missing data
uses text and neutral graphite instead of a reassuring success color.

## Typography

- **Display Font:** Georgia with a serif fallback
- **Body Font:** Proof Sans with Avenir Next, Segoe UI, and sans-serif fallbacks
- **Label/Mono Font:** SFMono-Regular with Consolas and monospace fallbacks

**Character:** Editorial headings make the workload read like a galley proof;
the sans face keeps operations legible, while mono labels distinguish measured
evidence, identifiers, timing, and machine state.

### Hierarchy

- **Display:** medium-weight, tightly led serif for the primary proof question.
- **Title:** compact serif for ledgers, source libraries, and route sheets.
- **Body:** neutral sans for explanations, streamed content, and controls.
- **Label:** small mono with restrained tracking for job data and measurements.

**The Three-Face Rule.** Serif is editorial, sans is operational, and mono is
evidence. Do not swap their responsibilities for decoration.

## Layout

The desktop shell uses a fixed job-ticket rail, a fluid central proof galley,
and a narrow inspection ledger beneath a shared registration header. A full-
width timing ruler anchors the bottom edge. The central sheet is bounded for
reading while virtualized data and code remain independently scrollable.

Below 1080px, the ledger becomes a horizontal evidence band beneath the main
proof. Below 720px, the shell becomes a single column: navigation turns into
four equal touch targets, secondary job metadata disappears, the galley stacks,
and the timeline retains explicit labels. Spacing follows a compact 5/8/14/18/24
pixel rhythm, with 44px or taller interactive controls.

## Elevation & Depth

The system is deliberately flat. Depth comes from stock tone, one-pixel rules,
inset regions, and dark code plates rather than drop shadows or glass effects.
The only glow-like treatment is the bounded running-status pulse, and reduced-
motion mode removes its animation.

**The Flat-Proof Rule.** A resting surface has no shadow. Use material color and
rules to express ownership; reserve motion for a state transition.

## Shapes

Most surfaces are rectangular, echoing paper, rulers, and job tickets. Primary
controls use only a tight 3px radius. Circular geometry is reserved for
registration marks, state lamps, timeline event dots, and focus-like indicators.
Borders are structural one-pixel rules, not ornamental card outlines.

## Components

### Buttons

- **Shape:** compact press control with a tight 3px radius and 44px minimum height.
- **Primary:** graphite fill, coated-paper text, bold operational label.
- **Hover / Focus:** a lighter graphite hover and a two-pixel cyan external outline.
- **Disabled:** reduced opacity while running; full opacity when the recorded proof is terminal.

### Chips

- **Style:** square proof labels with a one-pixel neutral rule and mono text.
- **State:** informational only; they do not imitate selectable pills.

### Cards / Containers

- **Corner Style:** square paper sheets and ledgers.
- **Background:** paper tones or the registration-black code plate.
- **Shadow Strategy:** none; use tone and rules.
- **Internal Padding:** compact 14–24px bands based on information density.

### Navigation

Job-ticket links combine an icon, route label, short evidence description, and
folio number. The active route is a bordered paper ticket; hover uses only a
subtle stock-lightening treatment. Mobile navigation keeps four equal, 58px
touch targets and removes secondary copy before reducing target size.

### Proof Timeline

The timeline is a measuring ruler, not a generic progress bar. Its CMYK fill
advances through a horizontal scale, while adjacent event labels keep each
stage understandable without color.

## Do's and Don'ts

### Do:

- **Do** keep raw measurements, unavailable states, and synthetic-fixture labels explicit.
- **Do** use paper tone, rules, and typography to establish hierarchy before adding color.
- **Do** preserve keyboard focus, 44px controls, semantic regions, and reduced-motion behavior.
- **Do** let streamed text and the registration ruler carry the principal motion.

### Don't:

- **Don't** replace the proofing language with generic AI chat cards or dashboard tiles.
- **Don't** use gradients, glassmorphism, ambient shadows, or large decorative color fields.
- **Don't** communicate pass, progress, or availability through color alone.
- **Don't** imply that the synthetic workload is LobeHub or third-party performance evidence.
