---
name: pricing-must-have-tier
description: Ensure pricing sections include at least one concrete tier or plan detail.
version: 1.0.0
tags:
  - qa
  - pricing
  - baseline
applies_to: qa_rule
dependencies: []
---

## Rule DSL

```yaml
id: pricing_must_have_tier
when:
  section_id_in: ["pricing"]
require:
  evidence_refs_count_gte: 1
  section_content_min_chars: 80
severity: blocking
reject_to: writer
message: "Pricing section should include concrete tier details or plan-level evidence."
```

## Why

Pricing comparisons are low value when only qualitative statements are present.
