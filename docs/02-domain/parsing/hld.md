# Parsing — HLD

## Purpose

Decide, for every pixel of the working image, what it is: scene the estimate may read,
skin, a garment to report, or something to ignore. Parsing does not decide any colour. It
decides which pixels are allowed to inform the estimate, and it gives each garment a region.

## Owns

- The label set the engine uses, and each label's role (§1 of the LLD).
- Mapping each model's raw classes onto it, and fusing the two (D13).
- Tier 3: the dragged box and the centre of the frame, refined by GrabCut (D20, D24).
- SegFormer's input tensor and the upsampling of its output — pure functions, so they are
  tested on the JVM. Running the models is `models/` in the app.

## Key decisions

- **Either model can veto a pixel.** Worn according to either one means out of the estimate
  (D13). Losing a little scene costs less than letting a garment vote.
- **SegFormer names garments; MediaPipe does not.** With MediaPipe alone, everything worn is
  one garment, *clothing*.
- **Accessories are excluded and unreported** (D13, PRD §4).
- **Tiers are recorded** on every capture, so a wrong colour can be traced to a missing model.

## Tiers

| Tier | When | Garments reported |
|---|---|---|
| `both` | Both models ran | SegFormer's garments |
| `clothes-only` | Only SegFormer ran | SegFormer's garments |
| `people-only` | Only MediaPipe ran | One *clothing* region |
| `box` | The person dragged a box | One *selected area*: GrabCut's cut-out of the box (D24) |
| `centre` | Neither model ran and no box yet | One *selected area*: GrabCut's cut-out of the centre of the frame |

## Failure modes

A model that throws is logged and treated as absent for that photo; the tier records it.
Neither present → `centre`, and the result offers the box.

## Non-goals

Training, per-instance garments (two tops are one region), GrabCut on a model's output (it
refines boxes only).
