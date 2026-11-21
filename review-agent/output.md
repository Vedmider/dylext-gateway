# Output

Emit one JSON object. No prose inside the JSON. A short human-readable summary
may precede it in chat.

## Full shape

```json
{
  "profile": "standard-backend",
  "sections_evaluated": ["GEN-S1", "GEN-S2", "GEN-S3", "GEN-S4", "ARC-S2", "ARC-S4"],
  "findings": [ /* see below */ ],
  "suppressed": [ /* see below */ ],
  "verdict": "REQUEST_CHANGES",
  "summary": "One blocker: domain entity imports a transport DTO. One major: test coverage gap on the new branch of processOrder().",
  "counts": { "blocker": 1, "major": 1, "minor": 0, "nit": 0 }
}
