from pathlib import Path


def test_python_data_plane_has_no_model_sdk_or_hidden_model_gateway():
    root = Path(__file__).resolve().parents[1]
    project = (root / "pyproject.toml").read_text(encoding="utf-8").lower()
    for forbidden in ("litellm", "openai>=", "smolagents"):
        assert forbidden not in project

    forbidden_imports = ("from litellm", "import litellm", "from smolagents", "import smolagents")
    for source in (root / "reactor_tool").rglob("*.py"):
        content = source.read_text(encoding="utf-8").lower()
        assert not any(token in content for token in forbidden_imports), source
