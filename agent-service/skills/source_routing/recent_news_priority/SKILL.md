---
name: recent-news-priority
description: Route recent_news dimension to press and news sources.
version: 1.0.0
tags:
  - source-routing
  - news
  - funding
  - events
applies_to: source_routing
dependencies: []
---

## Routing Payload

```yaml
source_type: official_site
priority_delta: 1
dimension_keywords:
  - news
  - funding
  - announcements
  - press
  - acquisition
  - partnership
  - launch
  - recent_news
```

## Extraction Guidance

When researching `recent_news`:
1. Search for `{competitor} funding OR acquisition OR partnership OR product launch site:techcrunch.com OR site:venturebeat.com OR "{competitor}" news {year}`.
2. Limit to events within the last 90 days unless user specifies otherwise.
3. Extract: event type (funding/acquisition/launch/partnership), date, dollar amount if applicable,
   strategic implication (who acquired, what product launched, which market entered).
4. Cite TechCrunch / VentureBeat / official press releases.
5. Do NOT cite social media posts or unverified rumors.

## Why

Funding rounds, acquisitions, and strategic partnerships are material events that shift competitive
dynamics within weeks. Standard feature/pricing research misses these short-horizon signals.
