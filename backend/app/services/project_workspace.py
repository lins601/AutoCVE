"""Read-only project workspace resolution shared by HTTP APIs and MCP tools."""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any

from sqlalchemy import select

from app.core.config import settings
from app.db.session import AsyncSessionLocal
from app.models.project import Project

_IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")


class ProjectWorkspaceError(Exception):
    """Base error with a stable cross-service error code."""

    def __init__(
        self,
        code: str,
        message: str,
        *,
        retryable: bool = False,
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.retryable = retryable
        self.details = details


@dataclass(frozen=True)
class ResolvedProjectWorkspace:
    project_id: str
    project_name: str
    source_type: str
    root: Path


def validate_identifier(value: str, field_name: str) -> str:
    normalized = str(value or "").strip()
    if not _IDENTIFIER_PATTERN.fullmatch(normalized):
        raise ProjectWorkspaceError(
            "INVALID_ARGUMENT",
            f"{field_name} must be a non-empty identifier of at most 128 characters",
            details={"field": field_name},
        )
    return normalized


def ensure_project_relative_path(relative_path: str, *, allow_root: bool = False) -> str:
    """Normalize a POSIX-style project path and reject absolute/traversal inputs."""
    raw_path = str(relative_path or "").strip().replace("\\", "/")
    if raw_path == "." and allow_root:
        return "."

    candidate = PurePosixPath(raw_path)
    if not raw_path or candidate.is_absolute() or ".." in candidate.parts:
        raise ProjectWorkspaceError(
            "PERMISSION_DENIED",
            "path must stay inside the project root",
            details={"path": raw_path},
        )

    normalized = candidate.as_posix().lstrip("/")
    if not normalized or normalized == ".":
        if allow_root:
            return "."
        raise ProjectWorkspaceError(
            "INVALID_ARGUMENT",
            "path is required",
            details={"path": raw_path},
        )
    return normalized


def ensure_path_within_root(
    project_root: Path,
    relative_path: str,
    *,
    allow_root: bool = False,
    must_exist: bool = True,
) -> Path:
    """Resolve a path and reject symlink or lexical escapes from the project root."""
    root = project_root.resolve(strict=True)
    normalized = ensure_project_relative_path(relative_path, allow_root=allow_root)
    try:
        candidate = root if normalized == "." else (root / normalized).resolve(strict=must_exist)
    except (FileNotFoundError, NotADirectoryError) as exc:
        raise ProjectWorkspaceError(
            "INVALID_ARGUMENT",
            "project-relative path does not exist",
            details={"path": normalized},
        ) from exc
    try:
        candidate.relative_to(root)
    except ValueError as exc:
        raise ProjectWorkspaceError(
            "PERMISSION_DENIED",
            "resolved path escapes the project root",
            details={"path": normalized},
        ) from exc
    return candidate


def _select_effective_root(workspace_root: Path) -> Path:
    visible_items = [item for item in workspace_root.iterdir() if not item.name.startswith("__")]
    visible_directories = [
        item for item in visible_items if item.is_dir() and not item.name.startswith(".")
    ]
    visible_files = [
        item for item in visible_items if item.is_file() and not item.name.startswith(".")
    ]
    if len(visible_directories) == 1 and not visible_files:
        return visible_directories[0].resolve(strict=True)
    return workspace_root.resolve(strict=True)


def resolve_existing_project_root(project: Project, managed_projects_root: Path) -> Path:
    """Resolve an already-materialized project without cloning, extracting, or writing."""
    project_id = validate_identifier(str(project.id), "project_id")
    managed_root = managed_projects_root.resolve(strict=True)

    candidates: list[Path] = []
    stored_local_path = str(getattr(project, "local_path", "") or "").strip()
    if stored_local_path:
        candidates.append(Path(stored_local_path))
    candidates.append(managed_root / ".auditai_workspaces" / "projects" / project_id)

    for candidate in candidates:
        try:
            resolved = candidate.resolve(strict=True)
            resolved.relative_to(managed_root)
        except (FileNotFoundError, NotADirectoryError, ValueError):
            continue
        if resolved.is_dir():
            return _select_effective_root(resolved)

    raise ProjectWorkspaceError(
        "PROJECT_NOT_FOUND",
        "project exists but has no materialized workspace inside the managed projects root",
        details={"project_id": project_id},
    )


class DatabaseProjectWorkspaceResolver:
    """Resolve an active project from PostgreSQL to its read-only workspace."""

    def __init__(self, managed_projects_root: str | Path | None = None) -> None:
        self._managed_projects_root = Path(managed_projects_root or settings.MANAGED_PROJECTS_ROOT)

    async def resolve(self, project_id: str) -> ResolvedProjectWorkspace:
        normalized_id = validate_identifier(project_id, "project_id")
        async with AsyncSessionLocal() as session:
            result = await session.execute(select(Project).where(Project.id == normalized_id))
            project = result.scalar_one_or_none()

        if project is None or not bool(project.is_active):
            raise ProjectWorkspaceError(
                "PROJECT_NOT_FOUND",
                "project was not found or is inactive",
                details={"project_id": normalized_id},
            )

        try:
            root = resolve_existing_project_root(project, self._managed_projects_root)
        except FileNotFoundError as exc:
            raise ProjectWorkspaceError(
                "DEPENDENCY_UNAVAILABLE",
                "managed projects root is unavailable",
                retryable=True,
            ) from exc

        return ResolvedProjectWorkspace(
            project_id=normalized_id,
            project_name=str(project.name),
            source_type=str(project.source_type or "unknown"),
            root=root,
        )
