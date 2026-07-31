import asyncio
from pathlib import Path

from reactor_tool.tool.report import html_report, markdown_report, render_report_spec, render_report_spec_pdf


def test_report_renderer_does_not_need_a_model_configuration():
    chunks = asyncio.run(_collect(markdown_report("render supplied content", original_query="only use facts")))
    assert "render supplied content" in "".join(chunks)
    assert "不补写未知事实" in "".join(chunks)


def test_html_renderer_is_self_contained():
    chunks = asyncio.run(_collect(html_report("render", original_query="closed world")))
    content = "".join(chunks)
    assert "<html" in content
    assert "<script src=" not in content


def test_report_spec_renderer_keeps_only_spec_claims_and_hash_bound_citations():
    spec = _spec()
    markdown = render_report_spec(spec, "markdown")
    html = render_report_spec(spec, "html")

    assert "Verified claim" in markdown
    assert "[S1]" in markdown
    assert "sha256-evidence" in markdown
    assert "invented" not in markdown.lower()
    assert "href=\"https://source.example/report\"" in html
    assert "<script" not in html


def test_report_spec_pdf_renderer_writes_real_pdf(tmp_path: Path):
    output = tmp_path / "report.pdf"

    render_report_spec_pdf(_spec(), str(output))

    assert output.read_bytes().startswith(b"%PDF-")


def _spec():
    return {
        "title": "ReportSpec test",
        "executiveSummary": "Verified summary",
        "methodology": "Fetched and extracted evidence only.",
        "sections": [{"id": "findings", "heading": "Findings", "claimIds": ["claim-1"]}],
        "claims": [{"id": "claim-1", "statement": "Verified claim", "evidenceIds": ["evidence-1"], "uncertainty": "NONE"}],
        "citations": [{"evidenceId": "evidence-1", "claimId": "claim-1", "sourceUrl": "https://source.example/report",
                       "contentHash": "sha256-evidence", "exactQuote": "Verified quote", "startOffset": 0,
                       "endOffset": 14, "fetchedAtEpochMillis": 1234, "offlineFixture": False}],
        "conflicts": [],
        "limitations": ["Only current-run evidence is rendered."],
        "generatedAt": "2026-07-31T00:00:00Z",
        "rendererVersion": "researchpilot-deterministic-v1",
    }


async def _collect(generator):
    return [item async for item in generator]
