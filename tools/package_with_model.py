#!/usr/bin/env python3
"""Build ONE installable IG Guard APK that carries the local risk model, on the owner's own PC.

Run this on the machine that holds the model. Nothing is uploaded and nothing is downloaded. The output contains the model
(trained partly on real conversations): never commit it, upload it, attach it to a release or share it. See
docs/local-model-package.md for the steps.

What it does, in order:
  1. reads the pinned model name, size and SHA-256 from RiskModel.kt (the one source of truth) and refuses a model that differs;
  2. copies the release APK entry by entry (same compression method and data), drops the old v1 signature files and adds the
     model as an uncompressed (STORED) entry assets/risk/<pinned file name>, streaming in 1 MiB chunks;
  3. runs zipalign -p -f 4, then apksigner sign (v2 + v3) and apksigner verify --print-certs;
  4. prints the output path, size, SHA-256 and the signing certificate, and writes <out>.sha256.

Standard library only (Python 3.12). Passwords come from environment variables or a hidden prompt, never from the command line.
"""
from __future__ import annotations

import argparse
import getpass
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional, Sequence

REPO_ROOT = Path(__file__).resolve().parents[1]
SPEC_FILE = REPO_ROOT / "RiskModel.kt"
WORKFLOW_FILE = REPO_ROOT / ".github" / "workflows" / "build-apk.yml"
CHUNK = 1 << 20
ASSET_DIR = "assets/risk"
# Old v1 signature files. v2/v3 signatures live in the APK Signing Block, which is not a zip entry and is rebuilt by apksigner.
SIGNATURE_ENTRY = re.compile(r"^META-INF/[^/]+\.(SF|RSA|DSA|EC)$|^META-INF/MANIFEST\.MF$", re.IGNORECASE)
# Older apksigner prints "Signer #1 certificate ..."; newer build-tools print "V3.0 Signer: certificate ..." (seen on the GitHub runner)
# or name the signer by its SDK range.
CERT_LINE = re.compile(r"^(?:Signer (?:#1|\([^)]*\))|V\d+(?:\.\d+)? Signer:) certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$", re.MULTILINE)
DEFAULT_KS_PASS_ENV = "IG_GUARD_KEYSTORE_PASSWORD"
DEFAULT_KEY_PASS_ENV = "IG_GUARD_KEY_PASSWORD"
# The child names the secrets are passed under to the signing tool; the command line only ever holds "env:<name>".
_TOOL_KS_ENV = "IG_PACKAGE_TOOL_KS_PASS"
_TOOL_KEY_ENV = "IG_PACKAGE_TOOL_KEY_PASS"


class PackagingError(Exception):
    """A problem the owner can fix. The message never contains a secret."""


@dataclass(frozen=True)
class ModelSpec:
    file_name: str
    size: int
    sha256: str


@dataclass(frozen=True)
class Tools:
    zipalign: str
    apksigner: str


@dataclass(frozen=True)
class SigningOptions:
    keystore: str
    alias: str
    keystore_password: str
    key_password: str


Runner = Callable[[Sequence[str], Optional[dict]], "subprocess.CompletedProcess[str]"]


# Names of environment variables that hold the keystore passwords; main() adds any custom names. They are removed from the
# environment of every subprocess: the signing tool gets the passwords only under the private names in sign().
SECRET_ENV_NAMES = {DEFAULT_KS_PASS_ENV, DEFAULT_KEY_PASS_ENV}


def clean_env(extra: Optional[dict] = None) -> dict:
    env = {k: v for k, v in os.environ.items() if k not in SECRET_ENV_NAMES}
    if extra:
        env.update(extra)
    return env


def default_runner(cmd: Sequence[str], env: Optional[dict] = None) -> "subprocess.CompletedProcess[str]":
    return subprocess.run(list(cmd), capture_output=True, text=True, env=clean_env(env), check=False)


def say(message: str = "") -> None:
    print(message, flush=True)


# ---------------------------------------------------------------- the pinned model
def load_spec(path: Path = SPEC_FILE) -> ModelSpec:
    """Parse FILE_NAME, FILE_BYTES and FILE_SHA256 from RiskModel.kt so there is a single source of truth."""
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as e:
        raise PackagingError(f"Cannot read {path}: {e.strerror or e}") from None

    def one(pattern: str, what: str) -> str:
        m = re.search(pattern, text)
        if not m:
            raise PackagingError(f"Could not find {what} in {path.name}. Has the file format changed?")
        return m.group(1)

    name = one(r'const\s+val\s+FILE_NAME\s*=\s*"([^"]+)"', "FILE_NAME")
    size = int(one(r"const\s+val\s+FILE_BYTES\s*=\s*([0-9_]+)L", "FILE_BYTES").replace("_", ""))
    sha = one(r'const\s+val\s+FILE_SHA256\s*=\s*"([0-9a-fA-F]{64})"', "FILE_SHA256").lower()
    if "/" in name or "\\" in name or name in ("", ".", ".."):
        raise PackagingError("The pinned model file name is not a plain file name.")
    return ModelSpec(name, size, sha)


def asset_entry_name(spec: ModelSpec) -> str:
    return f"{ASSET_DIR}/{spec.file_name}"


def sha256_file(path: Path, progress: Optional[Callable[[int], None]] = None) -> str:
    digest = hashlib.sha256()
    done = 0
    with open(path, "rb") as f:
        while True:
            block = f.read(CHUNK)
            if not block:
                break
            digest.update(block)
            done += len(block)
            if progress:
                progress(done)
    return digest.hexdigest()


def _percent_printer(label: str, total: int) -> Callable[[int], None]:
    state = {"last": -1}

    def show(done: int) -> None:
        step = int(done * 10 / total) if total else 10
        if step != state["last"]:
            state["last"] = step
            say(f"  {label}: {min(step * 10, 100)}%")
    return show


def verify_model(model: Path, spec: ModelSpec, quiet: bool = False) -> None:
    """Refuse a model whose size or SHA-256 differs from the pinned values."""
    if not model.is_file():
        raise PackagingError(f"Model file not found: {model}")
    size = model.stat().st_size
    if size != spec.size:
        raise PackagingError(f"The model is {size} bytes but the app pins {spec.size} bytes. This is not the expected model; nothing was written.")
    sha = sha256_file(model, None if quiet else _percent_printer("hashing the model", size))
    if sha != spec.sha256:
        raise PackagingError("The model's SHA-256 does not match the value pinned in RiskModel.kt. This is not the expected model; nothing was written.")
    if model.name != spec.file_name and not quiet:
        say(f"Note: the file is called {model.name}; it will be stored as {spec.file_name}, the name the app looks for.")


# ---------------------------------------------------------------- the zip
def rewrite_apk(src_apk: Path, model: Path, dst_zip: Path, spec: ModelSpec) -> dict:
    """Copy [src_apk] to [dst_zip] and add the model, uncompressed. Returns what happened (counts and dropped names).

    Each kept entry keeps its name, compression method, timestamp and attributes, and its content is copied in chunks.
    The model is hashed again while it is copied, so a file that changes after the check is caught.
    """
    entry_name = asset_entry_name(spec)
    kept, dropped = 0, []
    try:
        zin = zipfile.ZipFile(src_apk)
    except (zipfile.BadZipFile, OSError) as e:
        raise PackagingError(f"{src_apk} is not a readable APK (zip) file: {e}") from None
    with zin, zipfile.ZipFile(dst_zip, "w", allowZip64=True) as zout:
        for info in zin.infolist():
            if SIGNATURE_ENTRY.match(info.filename):
                dropped.append(info.filename)
                continue
            if info.filename == entry_name:
                raise PackagingError("The input APK already contains the model. Use the APK from the GitHub build, not a packaged one.")
            new = zipfile.ZipInfo(info.filename, info.date_time)
            new.compress_type = info.compress_type
            new.external_attr = info.external_attr
            new.create_system = info.create_system
            new.comment = info.comment
            new.file_size = info.file_size
            if info.is_dir():
                zout.writestr(new, b"")
            else:
                with zin.open(info) as r, zout.open(new, "w") as w:
                    shutil.copyfileobj(r, w, CHUNK)
            kept += 1
        item = zipfile.ZipInfo(entry_name, (2008, 1, 1, 0, 0, 0))
        item.compress_type = zipfile.ZIP_STORED
        item.external_attr = 0o644 << 16
        item.create_system = 3
        item.file_size = spec.size
        digest, copied = hashlib.sha256(), 0
        with open(model, "rb") as r, zout.open(item, "w") as w:
            while True:
                block = r.read(CHUNK)
                if not block:
                    break
                digest.update(block)
                copied += len(block)
                w.write(block)
        if copied != spec.size or digest.hexdigest() != spec.sha256:
            raise PackagingError("The model changed while it was being copied. Nothing was written.")
    return {"kept": kept, "dropped": dropped, "entry": entry_name}


# ---------------------------------------------------------------- where the output may go
def git_root(path: Path) -> Optional[Path]:
    """The work tree that contains [path] (an ancestor holding .git), or None."""
    for parent in [path.resolve(), *path.resolve().parents]:
        if (parent / ".git").exists():
            return parent
    return None


def git_ignored(path: Path, root: Path) -> bool:
    """True when git says [path] is ignored (a tracked file is never ignored). Refuses when git cannot answer."""
    try:
        result = subprocess.run(["git", "-C", str(root), "check-ignore", "-q", "--", str(path.resolve())], capture_output=True, text=True, check=False, env=clean_env())
    except OSError:
        raise PackagingError("The output folder is inside a git work tree and git is not available to check .gitignore. Choose an output outside the repository.") from None
    if result.returncode == 0:
        return True
    if result.returncode == 1:
        return False
    raise PackagingError("git could not check whether the output path is ignored. Choose an output outside the repository.")


def ensure_output_allowed(out: Path) -> None:
    """The output holds the model: it must not sit where `git add` could pick it up."""
    anchor = out.parent if out.parent.exists() else next((p for p in out.resolve().parents if p.exists()), out.resolve().parent)
    root = git_root(anchor)
    if root is None:
        return
    for candidate in (out, out.with_name(out.name + ".sha256")):
        if not git_ignored(candidate, root):
            raise PackagingError(
                f"{candidate} is inside the git work tree {root} and is not git-ignored. The package contains the model and must never be committed. "
                "Use a path under /dist/ (ignored) or outside the repository.")


# ---------------------------------------------------------------- cloud-sync folders
SYNC_ENV_VARS = ("OneDrive", "OneDriveConsumer", "OneDriveCommercial")
SYNC_COMPONENTS = ("onedrive", "dropbox", "google drive", "googledrive", "iclouddrive", "icloud drive", "com~apple~clouddocs", "mobile documents")


def synced_folder_reason(path: Path, environ=os.environ) -> Optional[str]:
    """Why [path] looks like it is inside a cloud-synced folder (OneDrive, Dropbox, Google Drive, iCloud), or None."""
    resolved = Path(os.path.normcase(str(path.resolve())))
    for var in SYNC_ENV_VARS:
        root = environ.get(var)
        if root:
            base = Path(os.path.normcase(str(Path(root).resolve())))
            if resolved == base or base in resolved.parents:
                return f"it is under the folder named by %{var}%"
    for part in resolved.parts:
        for name in SYNC_COMPONENTS:
            if name in part.lower():
                return f'its path contains "{part}"'
    return None


def refuse_synced(paths: dict, environ=os.environ) -> None:
    """The model and the packaged APK must not be copied to a cloud by a sync client."""
    for label, p in paths.items():
        why = synced_folder_reason(p, environ)
        if why:
            raise PackagingError(f"The {label} ({p}) looks like a cloud-synced folder: {why}. A sync client would upload the model. "
                                 "Use a local folder that is not synced, or pass --allow-synced-folder if you are sure it is not.")


# ---------------------------------------------------------------- Android tools
def _executable(folder: Path, name: str) -> Optional[Path]:
    for candidate in (name, name + ".exe", name + ".bat", name + ".cmd"):
        p = folder / candidate
        if p.is_file():
            return p
    return None


def _version_key(p: Path):
    return tuple(int(x) if x.isdigit() else 0 for x in re.split(r"[.\-]", p.name))


def find_tools(build_tools: Optional[str], environ=os.environ) -> Tools:
    folders: list[Path] = []
    if build_tools:
        folders.append(Path(build_tools))
    else:
        for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
            root = environ.get(var)
            if root and (Path(root) / "build-tools").is_dir():
                folders += sorted((d for d in (Path(root) / "build-tools").iterdir() if d.is_dir()), key=_version_key, reverse=True)
    for folder in folders:
        za, sg = _executable(folder, "zipalign"), _executable(folder, "apksigner")
        if za and sg:
            return Tools(str(za), str(sg))
    if build_tools:
        raise PackagingError(f"zipalign and apksigner were not both found in {build_tools}.")
    raise PackagingError("Could not find Android build-tools. Install them (see docs/local-model-package.md) and pass --build-tools <folder>, "
                         "or set ANDROID_HOME / ANDROID_SDK_ROOT.")


def parse_cert_digest(text: str) -> Optional[str]:
    m = CERT_LINE.search(text)
    return m.group(1).lower() if m else None


def pinned_certificate(path: Path = WORKFLOW_FILE) -> Optional[str]:
    """The certificate digest the GitHub workflow expects of a signed release, if the workflow file is here."""
    try:
        m = re.search(r'test\s+"\$certificate"\s*=\s*"([0-9a-fA-F]{64})"', path.read_text(encoding="utf-8"))
    except OSError:
        return None
    return m.group(1).lower() if m else None


def input_certificate(tools: Tools, apk: Path, runner: Runner) -> Optional[str]:
    r = runner([tools.apksigner, "verify", "--print-certs", str(apk)], None)
    return parse_cert_digest(r.stdout) if r.returncode == 0 else None


def _fail(step: str, r: "subprocess.CompletedProcess[str]") -> PackagingError:
    detail = "\n".join(x for x in ((r.stdout or "").strip(), (r.stderr or "").strip()) if x)
    return PackagingError(f"{step} failed (exit code {r.returncode}).\n{detail}".rstrip())


def zipalign(tools: Tools, src: Path, dst: Path, runner: Runner) -> None:
    """-p page-aligns the uncompressed .so files (the release build stores them uncompressed); 4 aligns everything else."""
    r = runner([tools.zipalign, "-p", "-f", "4", str(src), str(dst)], None)
    if r.returncode != 0:
        raise _fail("zipalign", r)
    r = runner([tools.zipalign, "-c", "-p", "4", str(dst)], None)
    if r.returncode != 0:
        raise _fail("zipalign check", r)


def sign(tools: Tools, opts: SigningOptions, src: Path, dst: Path, runner: Runner, extra: Sequence[str] = ()) -> None:
    cmd = [tools.apksigner, "sign", "--ks", opts.keystore, "--ks-key-alias", opts.alias,
           "--ks-pass", f"env:{_TOOL_KS_ENV}", "--key-pass", f"env:{_TOOL_KEY_ENV}",
           "--v1-signing-enabled", "false", "--v2-signing-enabled", "true", "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
           *extra, "--out", str(dst), str(src)]
    r = runner(cmd, {_TOOL_KS_ENV: opts.keystore_password, _TOOL_KEY_ENV: opts.key_password})
    if r.returncode != 0:
        raise _fail("apksigner sign", r)


def verify_signed(tools: Tools, apk: Path, runner: Runner) -> tuple[str, str]:
    """Returns (certificate SHA-256, the tool's verbose report)."""
    r = runner([tools.apksigner, "verify", "--verbose", "--print-certs", str(apk)], None)
    if r.returncode != 0:
        raise _fail("apksigner verify", r)
    digest = parse_cert_digest(r.stdout)
    if not digest:
        raise PackagingError("apksigner verified the APK but printed no certificate digest. It printed:\n" + ((r.stdout or "") + (r.stderr or ""))[:800])
    for scheme in ("v2", "v3"):
        if not re.search(rf"Verified using {scheme} scheme[^\n]*: true", r.stdout):
            raise PackagingError(f"The signed APK is not verified with the {scheme} scheme.")
    return digest, r.stdout


# ---------------------------------------------------------------- the whole job
def run_packaging(apk: Path, model: Path, out: Path, spec: ModelSpec, tools: Tools, opts: SigningOptions,
                  runner: Runner = default_runner, workflow: Path = WORKFLOW_FILE, force: bool = False,
                  sign_extra: Sequence[str] = (), check_space: bool = True, allow_synced_folder: bool = False) -> dict:
    if not apk.is_file():
        raise PackagingError(f"APK not found: {apk}")
    out = out.resolve()
    if not allow_synced_folder:
        refuse_synced({"output": out, "model": model})
    # Python writes zip64 records (which Android may reject) once an entry or the archive passes 2 GiB.
    if apk.stat().st_size + spec.size + (1 << 20) > zipfile.ZIP64_LIMIT:
        raise PackagingError("The APK plus the model would pass about 2 GiB, where the zip format needs zip64 records that Android may reject. Nothing was written.")
    if out.exists() and not force:
        raise PackagingError(f"{out} already exists. Choose another name or pass --force.")
    if out.suffix.lower() != ".apk":
        raise PackagingError("The output name must end in .apk")
    out.parent.mkdir(parents=True, exist_ok=True)
    ensure_output_allowed(out)
    verify_model(model, spec)
    if check_space:
        need = spec.size + 2 * apk.stat().st_size + 2 * spec.size + (256 << 20)
        free = shutil.disk_usage(out.parent).free
        if free < need:
            raise PackagingError(f"Not enough free space in {out.parent}: about {need / 2**30:.1f} GB is needed while building, {free / 2**30:.1f} GB is free.")
    old_cert = input_certificate(tools, apk, runner)
    work = Path(tempfile.mkdtemp(prefix=".package-", dir=out.parent))
    try:
        say("Copying the APK and adding the model (this takes a few minutes)...")
        stats = rewrite_apk(apk, model, work / "unaligned.apk", spec)
        say(f"  kept {stats['kept']} entries, dropped {len(stats['dropped'])} old signature file(s), added {stats['entry']} (stored, not compressed)")
        say("Aligning (zipalign)...")
        zipalign(tools, work / "unaligned.apk", work / "aligned.apk", runner)
        (work / "unaligned.apk").unlink()
        say("Signing (apksigner, v2 + v3)...")
        sign(tools, opts, work / "aligned.apk", work / "signed.apk", runner, sign_extra)
        (work / "aligned.apk").unlink()
        new_cert, _report = verify_signed(tools, work / "signed.apk", runner)
        os.replace(work / "signed.apk", out)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    digest = sha256_file(out)
    out.with_name(out.name + ".sha256").write_text(f"{digest}  {out.name}\n", encoding="ascii")
    pinned = pinned_certificate(workflow)
    warnings = []
    for label, other in (("the APK you gave me", old_cert), ("the certificate the GitHub workflow expects (.github/workflows/build-apk.yml)", pinned)):
        if other and other != new_cert:
            warnings.append(label)
    return {"out": out, "size": out.stat().st_size, "sha256": digest, "new_cert": new_cert, "old_cert": old_cert, "pinned_cert": pinned, "warnings": warnings}


def report(result: dict) -> None:
    say()
    say(f"Output:      {result['out']}")
    say(f"Size:        {result['size']:,} bytes")
    say(f"SHA-256:     {result['sha256']}")
    say(f"Checksum file: {result['out']}.sha256")
    say(f"Signing certificate SHA-256: {result['new_cert']}")
    if result["old_cert"]:
        say(f"Input APK certificate SHA-256: {result['old_cert']}")
    else:
        say("Input APK was not signed (or its signature could not be verified).")
    if result["pinned_cert"]:
        say(f"Certificate pinned in the GitHub workflow: {result['pinned_cert']}")
    if result["warnings"]:
        say()
        say("WARNING: the new signing certificate is DIFFERENT from " + " and from ".join(result["warnings"]) + ".")
        say("         Android will refuse to update an installed IG Guard that was signed with another certificate.")
        say("         To install this APK the old app must be uninstalled first, and the app's data on that phone is lost.")
        say("         If you meant to keep the existing installation, sign with the same keystore as the GitHub release.")
    say()
    say("This package contains the model. Never upload, commit, attach or share it.")


def read_password(env_name: str, prompt: str, fallback: Optional[str] = None) -> str:
    value = os.environ.get(env_name)
    if value:
        return value
    if not sys.stdin or not sys.stdin.isatty():
        if fallback is not None:
            return fallback
        raise PackagingError(f"No password: set the environment variable {env_name} or run this in a terminal to be asked.")
    typed = getpass.getpass(prompt)
    return typed if typed else (fallback if fallback is not None else "")


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Package the IG Guard release APK together with the local risk model (run on your own PC).")
    p.add_argument("--apk", required=True, help="the release APK from the GitHub build (signed or unsigned)")
    p.add_argument("--model", required=True, help="the model .gguf file")
    p.add_argument("--out", required=True, help="where to write the packaged APK, e.g. dist/ig-guard-with-model.apk (must end in .apk)")
    p.add_argument("--keystore", required=True, help="the signing keystore (.jks) outside the repository")
    p.add_argument("--ks-alias", default="igguard", help="key alias in the keystore (default: igguard)")
    p.add_argument("--ks-pass-env", default=DEFAULT_KS_PASS_ENV, help=f"environment variable holding the keystore password (default {DEFAULT_KS_PASS_ENV}); asked for when unset")
    p.add_argument("--key-pass-env", default=DEFAULT_KEY_PASS_ENV, help=f"environment variable holding the key password (default {DEFAULT_KEY_PASS_ENV}); the keystore password is used when unset or empty")
    p.add_argument("--build-tools", help="Android SDK build-tools folder with zipalign and apksigner (found from ANDROID_HOME / ANDROID_SDK_ROOT when omitted)")
    p.add_argument("--force", action="store_true", help="overwrite an existing output file")
    p.add_argument("--allow-synced-folder", action="store_true", help="allow the model or the output to be in a folder that looks cloud-synced (OneDrive, Dropbox, Google Drive, iCloud); not recommended")
    return p


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        spec = load_spec()
        tools = find_tools(args.build_tools)
        SECRET_ENV_NAMES.update({args.ks_pass_env, args.key_pass_env})
        out_path = Path(args.out).resolve()
        before = out_path.stat().st_mtime_ns if out_path.exists() else None
        keystore = Path(args.keystore)
        if not keystore.is_file():
            raise PackagingError(f"Keystore not found: {keystore}")
        ks_pass = read_password(args.ks_pass_env, "Keystore password: ")
        key_pass = read_password(args.key_pass_env, "Key password (Enter = same as the keystore password): ", fallback=ks_pass) or ks_pass
        opts = SigningOptions(str(keystore), args.ks_alias, ks_pass, key_pass)
        result = run_packaging(Path(args.apk), Path(args.model), Path(args.out), spec, tools, opts, force=args.force, allow_synced_folder=args.allow_synced_folder)
    except PackagingError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2
    except OSError as e:
        where = f" ({e.filename})" if getattr(e, "filename", None) else ""
        print(f"ERROR: {e.strerror or e}{where}. Nothing more was done.", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        after = out_path.stat().st_mtime_ns if "out_path" in locals() and out_path.exists() else None
        if "out_path" in locals() and after is not None and after != before:
            print(f"Interrupted after {out_path} was written. It may be complete but was not checked: delete it and run again.", file=sys.stderr)
        else:
            print("Cancelled. No output was written.", file=sys.stderr)
        return 130
    report(result)
    return 0


if __name__ == "__main__":
    sys.exit(main())
