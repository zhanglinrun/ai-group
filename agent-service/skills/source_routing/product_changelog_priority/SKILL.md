---
name: product-changelog-priority
description: Route product_changelog dimension to official changelog and release notes pages.
version: 1.0.0
tags:
  - source-routing
  - changelog
  - product-updates
  - releases
applies_to: source_routing
dependencies: []
---

## Routing Payload

```yaml
source_type: product_changelog
priority_delta: 3
dimension_keywords:
  - changelog
  - releases
  - updates
  - version
  - release_notes
  - product_changelog
  - product_updates
```

## Extraction Guidance

When researching `product_changelog`:
1. Fetch `{official_url}/changelog` or `{official_url}/releases` first (key page bucket priority).
   Fall back to GitHub releases page if it is an open-source product.
2. Extract: version numbers with dates, feature names per release, breaking changes,
   deprecated features, and cadence (monthly / quarterly / irregular).
3. Count major releases in the last 12 months as a proxy for engineering velocity.
4. Note any AI/ML feature additions — these are high-signal for competitive positioning shifts.
5. Evidence MUST link to the changelog page or GitHub release tag, not a blog post summary.

## Why

Product changelog is the most reliable source for tracking incremental capability gaps.
Competitors who release frequently signal high engineering velocity; those with sparse changelogs
may be stagnating or in a major platform rewrite. This source type is systematically underweighted
because LLMs default to search results over structured changelog pages.
