"""Logging helpers that deliberately omit exception messages and source payloads."""

from __future__ import annotations

import logging


def log_unexpected_failure(
    logger: logging.Logger,
    event: str,
    error: BaseException,
) -> None:
    """Log only bounded metadata; exception text may contain API keys or source."""
    logger.error(
        "%s error_type=%s",
        event,
        type(error).__name__[:128],
    )
