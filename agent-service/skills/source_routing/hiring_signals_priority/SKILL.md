---
name: hiring-signals-priority
description: Route hiring_signals dimension to job boards and career pages.
version: 1.0.0
tags:
  - source-routing
  - hiring
  - strategic-signal
applies_to: source_routing
dependencies: []
---

## Routing Payload

```yaml
source_type: official_site
priority_delta: 2
dimension_keywords:
  - hiring
  - jobs
  - recruitment
  - talent
  - career
  - job_postings
  - hiring_signals
```

## Extraction Guidance

When researching `hiring_signals`:
1. Search for `{competitor} site:linkedin.com/jobs OR careers.{domain}` to find recent openings.
2. Extract: total open positions, department breakdown (Engineering / Sales / Marketing / AI-ML),
   key role titles, and any sudden surge (>20% YoY change = strategic inflection signal).
3. Large AI/ML engineering hiring = product pivot toward AI. Large GTM hiring = growth phase.
4. Cite the job listing URL or LinkedIn page as evidence.

## Why

Job postings are a leading indicator of strategic direction. High engineering headcount growth
precedes product expansion; high GTM growth precedes market push. This signal is underused in
standard competitive analysis but reliable for 3-6 month forecasts.
