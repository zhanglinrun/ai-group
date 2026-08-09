from __future__ import annotations


class LLMError(RuntimeError):
    """Base class for LLM service failures."""


class LLMRequestError(LLMError):
    """Raised when provider request fails."""

    def __init__(
        self,
        message: str,
        *,
        retryable: bool = True,
        http_status: int | None = None,
        retry_after_seconds: float | None = None,
        error_class: str | None = None,
    ) -> None:
        super().__init__(message)
        self.retryable = retryable
        self.http_status = http_status
        self.retry_after_seconds = retry_after_seconds
        self.error_class = error_class


class LLMResponseFormatError(LLMError):
    """Raised when provider response is not valid JSON object content."""
