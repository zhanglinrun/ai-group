import asyncio

from reactor_tool.tool.deepsearch import DeepSearch


class Doc:
    def __init__(self, title, link, content):
        self.title = title
        self.link = link
        self.content = content
        self.data = {"search_engine": "test"}


def test_deep_search_returns_sorted_evidence_candidates_without_answer_generation():
    search = DeepSearch(engines=["ddg"])

    async def fake_search(**kwargs):
        return [Doc("B", "https://b.example", "second"), Doc("A", "https://a.example", "first")]

    search._search_single_query = fake_search
    candidates = asyncio.run(search.collect_evidence(" durable tools ", "req-1", 10))

    assert [item["url"] for item in candidates] == ["https://a.example", "https://b.example"]
    assert all(item["sourceHash"].startswith("sha256:") for item in candidates)
    assert all("fetchedAt" in item for item in candidates)
