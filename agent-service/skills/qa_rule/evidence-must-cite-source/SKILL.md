---
name: evidence-must-cite-source
description: Require every key claim to include at least one evidence id citation.
version: 1.0.0
tags:
  - qa
  - citation
  - baseline
applies_to: qa_rule
dependencies: []
---

## Rule DSL

```yaml
id: evidence_must_cite_source
when:
  section_id_in: ["feature", "pricing", "user_feedback"]
require:
  evidence_refs_count_gte: 1
severity: blocking
reject_to: writer
message: "Each core section must reference at least one evidence id."
```

## Why

This rule prevents fluent-but-unsupported summaries from passing QA.
