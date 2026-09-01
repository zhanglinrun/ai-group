from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class SourceRoutingPolicy:
    source_type: str
    priority_delta: int
    dimension_keywords: tuple[str, ...]


# These policies are intentionally versioned in code so research behavior stays
# deterministic and independent of review or runtime state.
_POLICIES: tuple[SourceRoutingPolicy, ...] = (
    SourceRoutingPolicy(
        source_type="docs",
        priority_delta=2,
        dimension_keywords=("feature", "integration", "capability", "api", "tech"),
    ),
    SourceRoutingPolicy(
        source_type="official_site",
        priority_delta=2,
        dimension_keywords=("hiring", "jobs", "recruitment", "talent", "career", "job_postings", "hiring_signals"),
    ),
    SourceRoutingPolicy(
        source_type="pricing_page",
        priority_delta=3,
        dimension_keywords=("pricing", "billing", "plan", "package"),
    ),
    SourceRoutingPolicy(
        source_type="product_changelog",
        priority_delta=3,
        dimension_keywords=("changelog", "releases", "updates", "version", "release_notes", "product_changelog", "product_updates"),
    ),
    SourceRoutingPolicy(
        source_type="official_site",
        priority_delta=1,
        dimension_keywords=("news", "funding", "announcements", "press", "acquisition", "partnership", "launch", "recent_news"),
    ),
    SourceRoutingPolicy(
        source_type="public_review",
        priority_delta=2,
        dimension_keywords=("user_feedback", "review", "sentiment", "persona"),
    ),
)


def source_routing_policies() -> tuple[SourceRoutingPolicy, ...]:
    return _POLICIES
