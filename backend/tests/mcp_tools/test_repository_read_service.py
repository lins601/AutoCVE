from __future__ import annotations

from pathlib import Path

import pytest

from app.services.project_workspace import ProjectWorkspaceError
from app.services.repository_read_service import RepositoryReadService


@pytest.fixture
def sample_repository(tmp_path: Path) -> Path:
    repository = tmp_path / "sample"
    source = repository / "src" / "main" / "java" / "example"
    source.mkdir(parents=True)
    (repository / "pom.xml").write_text(
        "<project><artifactId>demo</artifactId><parent>spring-boot</parent></project>",
        encoding="utf-8",
    )
    (source / "DemoApplication.java").write_text(
        """package example;
public class DemoApplication {
    public static void main(String[] args) {}
}
""",
        encoding="utf-8",
    )
    (source / "AdminController.java").write_text(
        """package example;
public class AdminController {
    public String getAdminData(String userInput) {
        return database.executeQuery(\"SELECT * FROM users WHERE id = \" + userInput);
    }
}
""",
        encoding="utf-8",
    )
    (repository / "node_modules" / "ignored.js").parent.mkdir()
    (repository / "node_modules" / "ignored.js").write_text("eval(input)", encoding="utf-8")
    return repository


def test_profile_repository_returns_structured_profile_without_writes(
    sample_repository: Path,
) -> None:
    service = RepositoryReadService()
    before = {
        path.relative_to(sample_repository).as_posix(): path.read_bytes()
        for path in sample_repository.rglob("*")
        if path.is_file()
    }

    profile, warnings = service.profile_repository(sample_repository)

    after = {
        path.relative_to(sample_repository).as_posix(): path.read_bytes()
        for path in sample_repository.rglob("*")
        if path.is_file()
    }
    assert warnings == []
    assert profile["file_count"] == 3
    assert {item["name"] for item in profile["languages"]} == {"java", "xml"}
    assert "Spring Boot" in profile["frameworks"]
    assert "osv" in profile["recommended_scanners"]
    assert profile["index_status"]["search_mode"] in {"keyword_ast", "keyword_regex"}
    assert before == after


def test_search_code_returns_bounded_snippet_and_symbol(sample_repository: Path) -> None:
    service = RepositoryReadService()

    result, warnings = service.search_code(
        sample_repository,
        query="SQL injection execute user input",
        top_k=5,
        path_filter=["src"],
        language_filter=["java"],
    )

    assert warnings == []
    assert result["search_mode"] in {"keyword_ast", "keyword_regex"}
    assert result["result_count"] >= 1
    match = result["results"][0]
    assert match["file_path"].endswith("AdminController.java")
    assert "executeQuery" in match["code"]
    assert match["line_start"] >= 1
    assert 0 < match["score"] <= 1
    assert match["symbol"]["name"] in {"AdminController", "getAdminData"}


@pytest.mark.parametrize("unsafe_path", ["../outside", "/etc", "src/../../outside"])
def test_path_traversal_is_rejected(sample_repository: Path, unsafe_path: str) -> None:
    service = RepositoryReadService()

    with pytest.raises(ProjectWorkspaceError) as error:
        service.profile_repository(sample_repository, target_paths=[unsafe_path])

    assert error.value.code == "PERMISSION_DENIED"


def test_symlink_escape_is_rejected(sample_repository: Path, tmp_path: Path) -> None:
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "secret.py").write_text("PASSWORD = 'do-not-read'", encoding="utf-8")
    link = sample_repository / "escape"
    try:
        link.symlink_to(outside, target_is_directory=True)
    except OSError:
        pytest.skip("symlinks are unavailable on this platform")

    service = RepositoryReadService()
    with pytest.raises(ProjectWorkspaceError) as error:
        service.search_code(sample_repository, query="PASSWORD", path_filter=["escape"])

    assert error.value.code == "PERMISSION_DENIED"


def test_missing_target_path_returns_invalid_argument(sample_repository: Path) -> None:
    service = RepositoryReadService()

    with pytest.raises(ProjectWorkspaceError) as error:
        service.profile_repository(sample_repository, target_paths=["missing/path"])

    assert error.value.code == "INVALID_ARGUMENT"
