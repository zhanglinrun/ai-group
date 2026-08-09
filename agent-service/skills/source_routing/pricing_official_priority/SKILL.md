---
name: pricing-official-priority
description: Prefer official pricing pages for pricing-related dimensions.
version: 1.0.0
tags:
  - source-routing
  - pricing
  - buyer-critical
applies_to: source_routing
dependencies: []
---

## Routing Payload

```yaml
source_type: pricing_page
priority_delta: 3
dimension_keywords:
  - pricing
  - billing
  - plan
  - package
```

## Why

Pricing claims are high-risk in business reports and should prioritize first-party pricing pages.
