import re
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile
from email.parser import Parser
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[1]
POM = PROJECT.parents[1] / "pom.xml"
VERSION = re.search(r'^version = "([^"]+)"', (PROJECT / "pyproject.toml").read_text(), re.MULTILINE)
assert VERSION is not None
ROOT = f"tollgate_client-{VERSION.group(1)}"


class PackageTest(unittest.TestCase):
    folder: tempfile.TemporaryDirectory[str]

    @classmethod
    def setUpClass(cls) -> None:
        cls.folder = tempfile.TemporaryDirectory()
        subprocess.run(
            [sys.executable, "-m", "build", "--no-isolation", "--outdir", cls.folder.name],
            cwd=PROJECT,
            check=True,
            capture_output=True,
        )

    @classmethod
    def tearDownClass(cls) -> None:
        cls.folder.cleanup()

    def test_the_wheel_holds_only_the_package_and_its_metadata(self) -> None:
        wheel = Path(self.folder.name) / f"{ROOT}-py3-none-any.whl"
        with zipfile.ZipFile(wheel) as archive:
            names = sorted(archive.namelist())
        self.assertEqual(
            names,
            [
                "tollgate/__init__.py",
                "tollgate/py.typed",
                f"{ROOT}.dist-info/METADATA",
                f"{ROOT}.dist-info/RECORD",
                f"{ROOT}.dist-info/WHEEL",
                f"{ROOT}.dist-info/licenses/LICENSE",
            ],
        )

    def test_the_sdist_holds_only_the_source_and_the_project_files(self) -> None:
        with tarfile.open(Path(self.folder.name) / f"{ROOT}.tar.gz") as archive:
            names = sorted(archive.getnames())
        self.assertEqual(
            names,
            [
                f"{ROOT}/.gitignore",
                f"{ROOT}/LICENSE",
                f"{ROOT}/PKG-INFO",
                f"{ROOT}/README.md",
                f"{ROOT}/pyproject.toml",
                f"{ROOT}/src/tollgate/__init__.py",
                f"{ROOT}/src/tollgate/py.typed",
            ],
        )

    def test_the_wheel_requires_nothing_outside_the_two_extras(self) -> None:
        wheel = Path(self.folder.name) / f"{ROOT}-py3-none-any.whl"
        with zipfile.ZipFile(wheel) as archive:
            text = archive.read(f"{ROOT}.dist-info/METADATA").decode()
        required = Parser().parsestr(text).get_all("Requires-Dist") or []
        self.assertEqual(len(required), 2)
        for line in required:
            self.assertRegex(line, r"extra == '(openai|anthropic)'")

    def test_the_version_is_the_one_in_the_root_pom(self) -> None:
        pom = re.search(r"<version>([^<]+?)(-SNAPSHOT)?</version>", POM.read_text())
        project = re.search(
            r'^version = "([^"]+)"', (PROJECT / "pyproject.toml").read_text(), re.MULTILINE
        )
        assert pom is not None and project is not None
        self.assertEqual(project.group(1), pom.group(1))
