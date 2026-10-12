"""Deterministic, read-only repository profiling and code search."""

from __future__ import annotations

import fnmatch
import os
import re
from collections import Counter
from collections.abc import Iterable
from pathlib import Path, PurePosixPath
from typing import Any

from app.services.project_workspace import (
    ProjectWorkspaceError,
    ensure_path_within_root,
    ensure_project_relative_path,
)
from app.services.rag.splitter import CodeSplitter, TreeSitterParser

DEFAULT_EXCLUDE_PATTERNS = (
    ".git/**",
    ".idea/**",
    ".venv/**",
    "__pycache__/**",
    "build/**",
    "coverage/**",
    "dist/**",
    "node_modules/**",
    "out/**",
    "target/**",
    "vendor/**",
)
MAX_DISCOVERED_FILES = 25_000
MAX_SEARCH_FILE_BYTES = 512 * 1024
MAX_SEARCH_FILES = 5_000
MAX_SEARCH_TOTAL_BYTES = 32 * 1024 * 1024
MAX_PROFILE_FILE_BYTES = 64 * 1024

LANGUAGE_BY_SUFFIX = {
    **TreeSitterParser.LANGUAGE_MAP,
    ".bash": "bash",
    ".css": "css",
    ".graphql": "graphql",
    ".htm": "html",
    ".html": "html",
    ".json": "json",
    ".md": "markdown",
    ".mjs": "javascript",
    ".ps1": "powershell",
    ".sh": "bash",
    ".sql": "sql",
    ".vue": "vue",
    ".xml": "xml",
    ".yaml": "yaml",
    ".yml": "yaml",
}

TEXT_FILE_NAMES = {
    "Dockerfile",
    "Gemfile",
    "Jenkinsfile",
    "Makefile",
    "Procfile",
    "go.mod",
    "pom.xml",
    "requirements.txt",
}

ENTRY_POINT_NAMES = {
    "Application.java",
    "Program.cs",
    "app.py",
    "index.js",
    "index.ts",
    "main.go",
    "main.java",
    "main.kt",
    "main.py",
    "server.js",
    "server.ts",
}

SENSITIVE_PATH_TERMS = {
    "admin",
    "auth",
    "config",
    "credential",
    "login",
    "permission",
    "secret",
    "security",
    "token",
}

QUERY_EXPANSIONS = {
    "鉴权": {"auth", "authorize", "authorization", "authenticated", "permission", "role"},
    "授权": {"authorize", "authorization", "permission", "role", "access"},
    "越权": {"idor", "permission", "ownership", "owner", "admin", "role"},
    "注入": {"execute", "exec", "query", "sql", "command", "eval", "input"},
    "secret": {"secret", "password", "token", "api_key", "credential"},
    "authorization": {"auth", "authorize", "permission", "role", "admin"},
    "authentication": {"auth", "login", "token", "session", "principal"},
    "sql injection": {"sql", "query", "execute", "raw", "input"},
    "command injection": {"command", "exec", "system", "subprocess", "shell", "input"},
    "path traversal": {"path", "filename", "open", "read", "resolve", "normalize"},
}


def _path_matches(relative_path: str, patterns: Iterable[str]) -> bool:
    path = PurePosixPath(relative_path)
    for pattern in patterns:
        normalized_pattern = pattern.replace("\\", "/").lstrip("./")
        if not normalized_pattern:
            continue
        if path.match(normalized_pattern) or fnmatch.fnmatch(relative_path, normalized_pattern):
            return True
        prefix = normalized_pattern.removesuffix("/**")
        if prefix != normalized_pattern and (
            relative_path == prefix or relative_path.startswith(prefix + "/")
        ):
            return True
    return False


def _validate_exclude_patterns(patterns: Iterable[str]) -> list[str]:
    validated: list[str] = []
    for pattern in patterns:
        normalized = str(pattern or "").strip().replace("\\", "/")
        candidate = PurePosixPath(normalized)
        if not normalized or candidate.is_absolute() or ".." in candidate.parts:
            raise ProjectWorkspaceError(
                "PERMISSION_DENIED",
                "exclude patterns must stay relative to the project root",
                details={"pattern": normalized},
            )
        validated.append(normalized)
    return validated


def _looks_like_text(path: Path) -> bool:
    return path.name in TEXT_FILE_NAMES or path.suffix.lower() in LANGUAGE_BY_SUFFIX


def _read_text(path: Path, max_bytes: int) -> str | None:
    try:
        if path.stat().st_size > max_bytes:
            return None
        payload = path.read_bytes()
    except (OSError, PermissionError):
        return None
    if b"\x00" in payload[:4096]:
        return None
    return payload.decode("utf-8", errors="ignore")


class RepositoryReadService:
    """Analyze materialized repositories without network access or file writes."""

    def __init__(self) -> None:
        # The native parser is created inside each operation so its lifecycle
        # stays on the same worker thread used by the MCP application layer.
        pass

    @staticmethod
    def _offline_analysis_enabled() -> bool:
        return os.environ.get("AUTOCVE_OFFLINE_ANALYSIS", "").lower() in {
            "1",
            "true",
            "yes",
        }

    def _iter_files(
        self,
        project_root: Path,
        *,
        target_paths: list[str] | None = None,
        exclude_patterns: list[str] | None = None,
    ) -> tuple[list[Path], list[str]]:
        root = project_root.resolve(strict=True)
        requested_targets = target_paths or ["."]
        patterns = list(DEFAULT_EXCLUDE_PATTERNS)
        patterns.extend(_validate_exclude_patterns(exclude_patterns or []))

        files: list[Path] = []
        seen: set[Path] = set()
        warnings: list[str] = []

        for target_path in requested_targets:
            target = ensure_path_within_root(root, target_path, allow_root=True)
            if target.is_file():
                resolved_file = target.resolve(strict=True)
                if resolved_file not in seen and _looks_like_text(resolved_file):
                    files.append(resolved_file)
                    seen.add(resolved_file)
                continue
            if not target.is_dir():
                raise ProjectWorkspaceError(
                    "INVALID_ARGUMENT",
                    "target path must be a file or directory",
                    details={"path": target_path},
                )

            for current_root, directory_names, file_names in os.walk(target, followlinks=False):
                current = Path(current_root)
                safe_directories: list[str] = []
                for directory_name in directory_names:
                    directory = current / directory_name
                    relative_directory = directory.relative_to(root).as_posix()
                    if directory.is_symlink() or _path_matches(relative_directory, patterns):
                        continue
                    safe_directories.append(directory_name)
                directory_names[:] = safe_directories

                for file_name in file_names:
                    candidate = current / file_name
                    relative_file = candidate.relative_to(root).as_posix()
                    if _path_matches(relative_file, patterns) or not _looks_like_text(candidate):
                        continue
                    try:
                        resolved_file = candidate.resolve(strict=True)
                        resolved_file.relative_to(root)
                    except (FileNotFoundError, ValueError):
                        continue
                    if not resolved_file.is_file() or resolved_file in seen:
                        continue
                    files.append(resolved_file)
                    seen.add(resolved_file)
                    if len(files) >= MAX_DISCOVERED_FILES:
                        warnings.append(
                            f"file discovery stopped at the safety limit of {MAX_DISCOVERED_FILES} files"
                        )
                        return sorted(files), warnings

        return sorted(files), warnings

    def profile_repository(
        self,
        project_root: Path,
        *,
        target_paths: list[str] | None = None,
        exclude_patterns: list[str] | None = None,
    ) -> tuple[dict[str, Any], list[str]]:
        root = project_root.resolve(strict=True)
        files, warnings = self._iter_files(
            root,
            target_paths=target_paths,
            exclude_patterns=exclude_patterns,
        )

        language_counts: Counter[str] = Counter()
        entry_points: list[str] = []
        sensitive_paths: list[str] = []
        manifest_names: set[str] = set()
        manifest_contents: dict[str, str] = {}

        for path in files:
            relative_path = path.relative_to(root).as_posix()
            language = LANGUAGE_BY_SUFFIX.get(path.suffix.lower())
            if language:
                language_counts[language] += 1
            if path.name in ENTRY_POINT_NAMES or path.name.endswith("Application.java"):
                entry_points.append(relative_path)
            if any(term in relative_path.lower().split("/") for term in SENSITIVE_PATH_TERMS):
                sensitive_paths.append(relative_path)

            manifest_names.add(path.name)
            if path.name in {
                "build.gradle",
                "build.gradle.kts",
                "composer.json",
                "go.mod",
                "package.json",
                "pom.xml",
                "pyproject.toml",
                "requirements.txt",
            }:
                content = _read_text(path, MAX_PROFILE_FILE_BYTES)
                if content is not None:
                    manifest_contents[path.name] = content.lower()

        frameworks = self._detect_frameworks(manifest_names, manifest_contents)
        dependency_manifests = sorted(
            name
            for name in manifest_names
            if name
            in {
                "Cargo.lock",
                "Cargo.toml",
                "Gemfile.lock",
                "build.gradle",
                "build.gradle.kts",
                "composer.json",
                "composer.lock",
                "go.mod",
                "go.sum",
                "package-lock.json",
                "package.json",
                "pnpm-lock.yaml",
                "pom.xml",
                "pyproject.toml",
                "requirements.txt",
                "uv.lock",
                "yarn.lock",
            }
        )
        recommended_scanners = ["semgrep", "gitleaks"]
        if dependency_manifests:
            recommended_scanners.append("osv")

        data = {
            "languages": [
                {"name": language, "file_count": count}
                for language, count in language_counts.most_common()
            ],
            "frameworks": frameworks,
            "file_count": len(files),
            "entry_points": sorted(entry_points)[:100],
            "sensitive_paths": sorted(sensitive_paths)[:200],
            "dependency_manifests": dependency_manifests,
            "recommended_scanners": recommended_scanners,
            "index_status": {
                "state": "not_indexed",
                "search_mode": (
                    "keyword_regex" if self._offline_analysis_enabled() else "keyword_ast"
                ),
                "semantic_search_available": False,
            },
        }
        return data, warnings

    @staticmethod
    def _detect_frameworks(
        manifest_names: set[str], manifest_contents: dict[str, str]
    ) -> list[str]:
        detected: set[str] = set()
        package_json = manifest_contents.get("package.json", "")
        pom_xml = manifest_contents.get("pom.xml", "")
        gradle = manifest_contents.get("build.gradle", "") + manifest_contents.get(
            "build.gradle.kts", ""
        )
        python_manifests = manifest_contents.get("pyproject.toml", "") + manifest_contents.get(
            "requirements.txt", ""
        )

        framework_markers = {
            "Spring Boot": "spring-boot",
            "Django": "django",
            "FastAPI": "fastapi",
            "Flask": "flask",
            "Express": "express",
            "Next.js": "next",
            "React": "react",
            "Vue": "vue",
        }
        combined = "\n".join((package_json, pom_xml, gradle, python_manifests))
        for framework, marker in framework_markers.items():
            if marker in combined:
                detected.add(framework)
        if "go.mod" in manifest_names:
            detected.add("Go Modules")
        if "composer.json" in manifest_names:
            detected.add("Composer/PHP")
        return sorted(detected)

    def search_code(
        self,
        project_root: Path,
        *,
        query: str,
        top_k: int = 10,
        path_filter: list[str] | None = None,
        language_filter: list[str] | None = None,
    ) -> tuple[dict[str, Any], list[str]]:
        normalized_query = str(query or "").strip()
        if not normalized_query or len(normalized_query) > 2000:
            raise ProjectWorkspaceError(
                "INVALID_ARGUMENT",
                "query must contain between 1 and 2000 characters",
                details={"field": "query"},
            )
        if top_k < 1 or top_k > 20:
            raise ProjectWorkspaceError(
                "INVALID_ARGUMENT",
                "top_k must be between 1 and 20",
                details={"field": "top_k"},
            )

        root = project_root.resolve(strict=True)
        requested_paths = path_filter or ["."]
        for requested_path in requested_paths:
            ensure_project_relative_path(requested_path, allow_root=True)
        files, warnings = self._iter_files(root, target_paths=requested_paths)

        languages = {
            str(item).strip().lower() for item in (language_filter or []) if str(item).strip()
        }
        terms = self._query_terms(normalized_query)
        results: list[dict[str, Any]] = []
        candidate_lines: list[tuple[float, Path, int, list[str]]] = []
        file_contexts: dict[Path, tuple[str, str, list[str], str]] = {}
        searched_bytes = 0

        for file_index, path in enumerate(files):
            if file_index >= MAX_SEARCH_FILES:
                warnings.append(
                    f"code search stopped at the safety limit of {MAX_SEARCH_FILES} files"
                )
                break
            try:
                file_size = path.stat().st_size
            except OSError:
                continue
            if searched_bytes + file_size > MAX_SEARCH_TOTAL_BYTES:
                warnings.append(
                    "code search stopped at the safety limit of "
                    f"{MAX_SEARCH_TOTAL_BYTES} source bytes"
                )
                break
            searched_bytes += file_size
            relative_path = path.relative_to(root).as_posix()
            language = LANGUAGE_BY_SUFFIX.get(path.suffix.lower(), "text")
            if languages and language.lower() not in languages:
                continue
            content = _read_text(path, MAX_SEARCH_FILE_BYTES)
            if content is None:
                continue
            lines = content.splitlines()
            matched_lines = self._rank_matching_lines(lines, relative_path, normalized_query, terms)
            if not matched_lines:
                continue
            for line_number, score, matched_terms in matched_lines:
                candidate_lines.append((score, path, line_number, matched_terms))
            file_contexts[path] = (relative_path, language, lines, content)

        # Rank cheap line matches first, then run Tree-sitter only for the
        # highest-value files. This keeps a 20-second tool timeout realistic.
        candidate_lines.sort(key=lambda item: (-item[0], str(item[1]), item[2]))
        search_mode = "keyword_regex" if self._offline_analysis_enabled() else "keyword_ast"
        splitter = CodeSplitter(use_tree_sitter=not self._offline_analysis_enabled())
        chunk_cache: dict[Path, list[Any]] = {}
        accepted_ranges: dict[Path, list[tuple[int, int]]] = {}
        candidate_pool_size = max(40, top_k * 8)
        for score, path, line_number, matched_terms in candidate_lines[:candidate_pool_size]:
            relative_path, language, lines, content = file_contexts[path]
            snippet_start = max(1, line_number - 4)
            snippet_end = min(len(lines), line_number + 4)
            path_ranges = accepted_ranges.setdefault(path, [])
            if any(start <= line_number <= end for start, end in path_ranges):
                continue
            path_ranges.append((snippet_start, snippet_end))

            if path not in chunk_cache:
                chunk_cache[path] = splitter.split_file(content, relative_path, language)
            symbol = next(
                (
                    chunk
                    for chunk in chunk_cache[path]
                    if chunk.line_start <= line_number <= chunk.line_end
                ),
                None,
            )
            results.append(
                {
                    "file_path": relative_path,
                    "line_start": snippet_start,
                    "line_end": snippet_end,
                    "code": "\n".join(lines[snippet_start - 1 : snippet_end]),
                    "score": round(score, 4),
                    "language": language,
                    "symbol": {
                        "name": symbol.name if symbol else None,
                        "parent_name": symbol.parent_name if symbol else None,
                        "kind": symbol.chunk_type.value if symbol else "unknown",
                        "signature": symbol.signature if symbol else None,
                    },
                    "security_indicators": symbol.security_indicators if symbol else [],
                    "matched_terms": matched_terms,
                }
            )
            if len(results) >= top_k:
                break

        data = {
            "query": normalized_query,
            "search_mode": search_mode,
            "results": results,
            "result_count": len(results),
            "truncated": len(candidate_lines) > len(results),
        }
        return data, warnings

    @staticmethod
    def _query_terms(query: str) -> set[str]:
        lowered = query.lower()
        terms = {
            token
            for token in re.findall(r"[a-zA-Z_][a-zA-Z0-9_.-]{1,}|[\u4e00-\u9fff]{2,}", lowered)
            if token not in {"code", "find", "search", "查找", "搜索", "代码", "相关"}
        }
        for marker, expanded_terms in QUERY_EXPANSIONS.items():
            if marker in lowered:
                terms.update(expanded_terms)
        return terms or {lowered}

    @staticmethod
    def _rank_matching_lines(
        lines: list[str],
        relative_path: str,
        query: str,
        terms: set[str],
    ) -> list[tuple[int, float, list[str]]]:
        lowered_query = query.lower()
        ranked: list[tuple[int, float, list[str]]] = []
        for index, line in enumerate(lines, start=1):
            lowered_line = line.lower()
            matches = sorted(term for term in terms if term in lowered_line)
            if not matches:
                continue
            coverage = len(matches) / max(1, len(terms))
            exact_bonus = 0.35 if lowered_query in lowered_line else 0.0
            path_bonus = 0.1 if any(term in relative_path.lower() for term in terms) else 0.0
            score = min(1.0, 0.35 + coverage * 0.55 + exact_bonus + path_bonus)
            ranked.append((index, score, matches))
        ranked.sort(key=lambda item: (-item[1], item[0]))
        return ranked[:8]
