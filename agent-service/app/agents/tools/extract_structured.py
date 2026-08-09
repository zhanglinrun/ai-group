from __future__ import annotations

from service.collector.base import BaseChannel, CollectorObservation, SourceType, ToolObservationResult
from service.collector.errors import ChannelError
from schemas.agent_outputs import ExtractStructuredOutput
from schemas.contracts import validate_source_type
from service.llm import EXTRACT_STRUCTURED_SYSTEM_PROMPT, build_extract_structured_repair_user_prompt
from service.llm.harness import complete_structured

from core.config import settings
from agents.tools.parse_page import infer_source_type

# Feed the extractor a full long-form article and let the LLM summarize, instead
# of a hand-picked few-KB head clip that silently drops most of the page. Derived
# from the model input budget (config SSOT); ~half the budget leaves room for the
# system prompt and output.
EXTRACT_PROMPT_CHAR_LIMIT = settings.llm_prompt_char_budget // 2
# When structured extraction fails entirely we still keep a substantive body
# rather than a mid-sentence cut; downstream previews bound what each agent sees.
EXTRACT_FALLBACK_QUOTE_CHAR_LIMIT = 6000


class ExtractStructuredChannel(BaseChannel):
    name = "extract_structured"

    async def invoke(self, **kwargs: object) -> CollectorObservation:
        text = kwargs.get("text")
        source_url = kwargs.get("source_url")
        source_title = kwargs.get("source_title")
        source_type_raw = kwargs.get("source_type")
        if not isinstance(text, str) or not text.strip():
            raise ChannelError("extract_structured requires non-empty text.")
        if source_url is not None and not isinstance(source_url, str):
            raise ChannelError("extract_structured source_url must be string when provided.")
        if source_title is not None and not isinstance(source_title, str):
            raise ChannelError("extract_structured source_title must be string when provided.")

        source_type: SourceType
        if isinstance(source_type_raw, str):
            try:
                source_type = validate_source_type(source_type_raw)
            except ValueError:
                source_type = "article"
        else:
            source_type = infer_source_type(
                source_url=source_url,
                official_hosts=None,
            )

        prompt_text = text[:EXTRACT_PROMPT_CHAR_LIMIT]
        fallback_prompt = f"Return minimal JSON quote for text:\n{prompt_text[:2000]}"
        harness_result = await complete_structured(
            model_slot="research",
            system_prompt=EXTRACT_STRUCTURED_SYSTEM_PROMPT,
            user_prompt=f"Extract from text:\n{prompt_text}",
            output_model=ExtractStructuredOutput,
            parser=ExtractStructuredOutput.parse_llm_content,
            fallback_system_prompt=EXTRACT_STRUCTURED_SYSTEM_PROMPT,
            fallback_user_prompt=fallback_prompt,
            repair_user_prompt_builder=lambda errors: build_extract_structured_repair_user_prompt(
                validation_errors=errors,
                text_preview=prompt_text,
            ),
            log_event="extract.harness.finish",
        )
        llm_response = harness_result.llm_response
        if harness_result.value is not None:
            quote = harness_result.value.quote
            normalized_title = harness_result.value.source_title or (
                source_title if isinstance(source_title, str) else "structured_extract"
            )
        else:
            quote = text[:EXTRACT_FALLBACK_QUOTE_CHAR_LIMIT]
            normalized_title = (
                source_title if isinstance(source_title, str) else "structured_extract"
            )
        snippet = self._build_snippet(
            raw_text=quote,
            source_type=source_type,
            source_url=source_url if isinstance(source_url, str) else None,
            source_title=normalized_title,
            metadata={
                "source": "extract_structured",
                "llm_error": llm_response.error or "",
            },
        )
        return CollectorObservation(
            channel=self.name,
            args={
                "source_url": source_url,
                "source_title": source_title,
                "source_type": source_type_raw,
            },
            result=ToolObservationResult(
                snippets=[snippet],
                metadata={"source_type": source_type},
            ),
        )
