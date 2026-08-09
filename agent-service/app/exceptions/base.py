from __future__ import annotations

from dataclasses import dataclass


@dataclass
class APIException(Exception):
    status_code: int
    error_code: str
    message: str

    def to_dict(self) -> dict[str, str]:
        return {
            "error_code": self.error_code,
            "message": self.message,
        }
