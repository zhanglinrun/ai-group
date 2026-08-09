---
name: user-feedback-public-review-priority
description: Prefer public review sources for user feedback dimensions.
version: 1.0.0
tags:
  - source-routing
  - user-feedback
  - review
applies_to: source_routing
dependencies: []
---

## Routing Payload

```yaml
source_type: public_review
priority_delta: 2
dimension_keywords:
  - user_feedback
  - review
  - sentiment
  - persona
```

## Why

User feedback dimensions should prioritize third-party review evidence to avoid one-sided vendor narratives.
