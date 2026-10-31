# Illuminant — HLD

## Purpose

Decide the colour of the light. Everything else in the app is settled maths; this is a guess,
and the quality of the guess is the quality of the product.

## Owns

- Which pixels may inform the estimate (D5).
- How much each region weighs (D5).
- The estimators: gray-world (reported, never voting), shades-of-gray, gray-edge, skin anchor
  (D6).
- Combining them, and the confidence of the result (D7).
- Turning a neutral tap into an illuminant (D8).

## Key decisions

- **Garments out.** A pixel any model calls worn never votes. Neither do clipped or
  near-black pixels, whose colour is unknown (D5).
- **Regions capped.** Background, hair and skin each carry their share of the usable pixels,
  capped at half; so one huge wall cannot be the whole answer when there is anything else
  (D5).
- **Several estimators, and their disagreement is the confidence.** No single
  neutral-average assumption is trusted alone (D6, D7).
- **No usable scene, no correction.** Below a minimum number of usable pixels nothing is
  estimated and the photo is left as the phone made it (D7).
- **One light per photo** (D9).

## The failure this is designed around

A large saturated garment. With the garment removed, what remains — background, skin,
hair — is what the estimate reads. When what remains is itself one saturated wall, the
estimators disagree (gray-edge looks at edges, not means), the confidence drops, and the
neutral tap is offered. When *nothing* remains, nothing is guessed.

## Failure modes

| Case | Outcome |
|---|---|
| Fewer usable pixels than the minimum | No estimate, identity correction, `low`, *not enough of the scene* |
| No texture outside the garments, and no skin | Gray-edge and the skin anchor do not answer; one estimator alone is never `HIGH` |
| No face or skin in frame | No skin anchor; the other two decide |
| Neutral tap on a blown-out or black spot | Refused, with which |

## Non-goals

A learned estimator (D6), per-region light (D9), a per-device bias applied to estimates (D19).
