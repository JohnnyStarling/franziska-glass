#!/usr/bin/env python3
"""Reconstruct pinned public source in a NEW destination; never build/install/activate.

Python 3.9+, Git, and (only for --native) patch are required. Reads the adjacent
source-components.json. Network operations are public read-only HTTPS fetches.
"""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def relative(value):
    p = PurePosixPath(value)
    require(not p.is_absolute() and ".." not in p.parts and bool(p.parts),
            "Manifest path is not relative")
    return Path(*p.parts)


def run(argv, cwd=None):
    # No inherited Git credential/config helpers, local hooks, or env discovery.
    env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
    env.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull,
               GIT_TERMINAL_PROMPT="0", GIT_LFS_SKIP_SMUDGE="1")
    result = subprocess.run(argv, cwd=cwd, env=env, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=600)
    if result.returncode:
        # Do not echo arbitrary helper/env/config output into release receipts.
        raise RuntimeError("Command failed: " + Path(argv[0]).name +
                           " " + argv[1] + " (exit " + str(result.returncode) + ")")
    return result.stdout


def git(path, *args):
    return run(["git", "-c", "credential.helper=", "-c", "core.hooksPath=" + os.devnull,
                "-c", "core.autocrlf=false", "-C", str(path), *args])


def public_commit(url, commit):
    """Prove local-cache base object identity through the unauthenticated API."""
    match = re.fullmatch(r"https://github\.com/([^/]+)/([^/]+)\.git", url)
    if match is None:
        require(re.fullmatch(r"https://[a-z.-]+\.googlesource\.com/[^@?#]+", url),
                "Object cache requires a supported public commit proof")
        api = url.rstrip("/") + "/+/" + commit + "?format=JSON"
        request = urllib.request.Request(api, headers={"User-Agent": "source-only-reconstruction"})
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read().decode()
        info = json.loads(raw.split("\n", 1)[1] if raw.startswith(")]}") else raw)
        require(info["commit"] == commit, "Public commit proof differs from pin")
        return {"commit": info["commit"], "tree": info["tree"], "public_api": api}
    api = "https://api.github.com/repos/" + match[1] + "/" + match[2] + "/git/commits/" + commit
    request = urllib.request.Request(api, headers={"User-Agent": "source-only-reconstruction"})
    with urllib.request.urlopen(request, timeout=60) as response:
        info = json.load(response)
    require(info["sha"] == commit, "Public commit proof differs from pin")
    return {"commit": info["sha"], "tree": info["tree"]["sha"], "public_api": api}


def checkout(url, commit, destination, cache=None, expected_tree=None):
    require(re.fullmatch(r"[0-9a-f]{40}", commit), "Commit must be exact SHA1")
    require(url.startswith("https://") and "@" not in url, "Public HTTPS repository required")
    destination.mkdir(parents=True, exist_ok=False)
    git(destination, "init", "--quiet", "--template=")
    git(destination, "remote", "add", "origin", url)
    proof = None
    if cache:
        # Import ONLY the exact public base, never private HEAD, refs, remotes or config.
        proof = public_commit(url, commit)
        git(destination, "fetch", "--quiet", "--no-tags", "--depth=1",
            str(cache.resolve()), commit)
    else:
        git(destination, "fetch", "--quiet", "--no-tags", "--depth=1", "origin", commit)
    actual = git(destination, "rev-parse", "FETCH_HEAD").decode().strip()
    require(actual == commit, "Fetched commit differs from pin")
    tree = git(destination, "rev-parse", "FETCH_HEAD^{tree}").decode().strip()
    if expected_tree:
        require(tree == expected_tree, "Base tree differs from release manifest")
    if proof:
        require(tree == proof["tree"], "Cached tree differs from public commit")
    # Do not materialize even upstream test signing keys.
    info = destination / ".git/info"
    info.mkdir(exist_ok=True)
    (info / "sparse-checkout").write_text("/*\n!*.keystore\n!*.jks\n!*.p12\n!*.pfx\n")
    git(destination, "config", "core.sparseCheckout", "true")
    git(destination, "checkout", "--quiet", "--detach", "FETCH_HEAD")
    return {"commit": actual, "tree": tree, "acquisition": "public-object-cache" if cache else "public-https",
            "public_proof": proof}


def check_patch(path, descriptor):
    patch = ROOT / relative(descriptor["path"])
    require(digest(patch) == descriptor["sha256"], "Patch SHA256 mismatch: " + descriptor["path"])
    git(path, "apply", "--check", "--binary", str(patch))
    git(path, "apply", "--binary", str(patch))


def verify_files(path, files):
    for item in files:
        p = path / relative(item["path"])
        require(p.is_file() and not p.is_symlink(), "Missing regular reconstructed file: " + item["path"])
        require(digest(p) == item["sha256"], "Reconstructed file mismatch: " + item["path"])
        if "mode" in item:
            require((p.stat().st_mode & 0o111) == (int(item["mode"], 8) & 0o111),
                    "Reconstructed executable mode mismatch: " + item["path"])
    return hashlib.sha256("".join(
        f["path"] + "\0" + f["sha256"] + "\n" for f in files).encode()).hexdigest()


def fetch_verified(url, expected, path, cache=None):
    if cache and (cache / expected).is_file():
        shutil.copyfile(cache / expected, path)
    else:
        with urllib.request.urlopen(url, timeout=120) as src, path.open("wb") as out:
            shutil.copyfileobj(src, out)
    require(digest(path) == expected, "Frozen source download checksum mismatch; pins NOT rewritten")


def native_sources(destination, manifest, caches, download_cache):
    core = destination / "navigation/osmglass/core-legacy"
    n = manifest["native_sources"]
    proto = core / "externals/protobuf/upstream.patched"
    proto.mkdir()
    with tempfile.TemporaryDirectory(prefix="protobuf-source-") as td:
        archive = Path(td) / "protobuf.tar.bz2"
        fetch_verified(n["protobuf"]["url"], n["protobuf"]["sha256"], archive, download_cache)
        with tarfile.open(archive, "r:bz2") as tar:
            for member in tar:
                parts = PurePosixPath(member.name).parts
                if len(parts) < 2:
                    continue
                rel = relative(str(PurePosixPath(*parts[1:])))
                target = proto / rel
                require(not member.issym() and not member.islnk() and
                        (member.isdir() or member.isfile()), "Unexpected source archive entry")
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with tar.extractfile(member) as src, target.open("wb") as out:
                        shutil.copyfileobj(src, out)
                    target.chmod(member.mode & 0o777)
    s = n["skia"]
    skia = core / "externals/skia/upstream.patched"
    proofs = [{"repository": s["repository"], **checkout(s["repository"], s["commit"], skia, caches.get("skia"))}]
    for dep in s["dependencies"]:
        proofs.append({"repository": dep["url"], **checkout(dep["url"], dep["commit"], skia / relative(dep["path"]),
                 caches.get("skia-" + Path(dep["path"]).name))})
    for lib in ["protobuf", "skia"]:
        target = core / "externals" / lib / "upstream.patched"
        for patch in sorted((core / "externals" / lib / "patches").glob("*.patch")):
            argv = ["patch", "--batch", "--forward", "-p1"]
            if lib == "skia":
                argv.append("-l")
            run(argv + ["-i", str(patch)], cwd=target)
    native = json.loads((ROOT / relative(n["manifest"])).read_text())
    files = [{"path": k, "sha256": v} for k, v in sorted(native["files"].items())]
    verify_files(core, files)
    return {"status": "source-reconstructed", "checked_files": len(files),
            "repositories": proofs,
            "build_verified": False, "scope": "legacy JNI sources only; NOT frozen OpenGL/Qt AAR sources"}


# These exclusions apply to source-only publication snapshots, not upstream rights.
# Runtime map downloads and binary dependencies must not sneak in via base trees.
BINARY = {".apk", ".aab", ".aar", ".jar", ".class", ".so", ".dylib", ".dll",
          ".a", ".o", ".obj", ".exe", ".dex", ".keystore", ".jks", ".p12", ".pfx",
          ".obf", ".ocbf", ".db", ".sqlite", ".zip", ".7z", ".bz2", ".qz",
          ".ttf", ".otf", ".woff", ".woff2", ".pyc", ".gz", ".bin", ".pbf", ".wasm", ".pak", ".qm"}
MEDIA = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".ogg", ".wav", ".mp3", ".mp4", ".pdf"}


def exclusion(cid, rel, fixtures):
    p = PurePosixPath(rel)
    low = rel.lower()
    name = p.name.lower()
    if any(part in p.parts for part in (".git", "__pycache__", ".beads")):
        return "metadata/cache"
    if low in fixtures:
        return None
    if any(part in ("keystores", "keystore") for part in p.parts) or p.suffix.lower() in {".keystore", ".jks", ".p12", ".pfx"}:
        return "signing-material"
    if cid == "openclaw":
        # Licensed upstream fonts are preferred application/resource inputs, not
        # prebuilt app/native binaries. Public source-test data is not owner state.
        if p.suffix.lower() in {".ttf", ".otf", ".woff", ".woff2"}:
            return None
        if low.startswith(("test/fixtures/", "src/daemon/fixtures/")) and p.suffix.lower() in {".sqlite", ".gz"}:
            return None
    if p.suffix.lower() in BINARY or low.endswith((".osm.gz", ".tar.gz")):
        return "prebuilt-or-dataset-not-source"
    if cid != "openclaw":
        if p.suffix.lower() in MEDIA:
            return "upstream-media-not-cleared-for-this-source-pack"
        # Entire vector artwork collections, including XML representations, are upstream.
        if any(part.startswith(("drawable", "mipmap")) for part in p.parts):
            return "upstream-artwork"
        if cid == "resources" and p.parts[0] in ("icons", "rendering_styles"):
            # Keep rendering rule code and legal texts, not icon/font collections.
            if p.parts[0] == "icons" or any(x in p.parts for x in ("style-icons", "fonts", "legacy_fonts")):
                if not name.startswith(("license", "copying", "notice")):
                    return "upstream-artwork-or-font"
    return None


def snapshot(component, destination, archives):
    cid = component["id"]
    work = destination / relative(component["destination"])
    # git ls-files limits base export; add ONLY explicit approved additions.
    tracked = git(work, "ls-files", "-z").decode().split("\0")
    paths = set(filter(None, tracked)) | {f["path"] for f in component["files"]}
    fixtures = {f["path"].lower() for f in component["files"] if f["kind"] == "routing-test-fixture"}
    if cid == "core-legacy":
        n = json.loads((ROOT / "patches/manifests/native-source-files.json").read_text())
        paths |= {k for k in n["files"] if (work / k).is_file()}
    entries, omitted = [], []
    out = archives / (cid + "-source.tar.gz")
    # Deterministic metadata; never git archive HEAD (which would omit applied changes).
    with out.open("wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0) as gz:
        with tarfile.open(fileobj=gz, mode="w|") as tar:
            for rel in sorted(paths):
                p = work / relative(rel)
                reason = exclusion(cid, rel, fixtures)
                if reason:
                    omitted.append({"path": rel, "reason": reason})
                    continue
                if not p.exists() or p.is_dir():
                    continue  # separately pinned gitlinks and deliberately absent keys
                if not p.is_symlink():
                    with p.open("rb") as stream:
                        prefix = stream.read(8192)
                    executable_magic = prefix.startswith((b"\x7fELF", b"MZ", b"\xfe\xed\xfa\xce", b"\xfe\xed\xfa\xcf", b"\xce\xfa\xed\xfe", b"\xcf\xfa\xed\xfe", b"\xca\xfe\xba\xbe", b"\x00asm"))
                    if executable_magic or (cid != "openclaw" and rel.lower() not in fixtures and b"\0" in prefix):
                        omitted.append({"path": rel, "reason": "binary-payload-not-source"})
                        continue
                # Preserve safe source symlinks without following them.
                info = tarfile.TarInfo(cid + "/" + rel)
                info.uid = info.gid = info.mtime = 0
                info.uname = info.gname = ""
                info.mode = 0o755 if p.stat().st_mode & 0o111 else 0o644
                if p.is_symlink():
                    link = os.readlink(p)
                    require(not os.path.isabs(link) and
                            os.path.commonpath([str(work.resolve()), str(p.resolve())]) == str(work.resolve()),
                            "Unsafe source symlink")
                    info.type = tarfile.SYMTYPE
                    info.linkname = link
                    tar.addfile(info)
                    entries.append({"path": rel, "symlink": link})
                else:
                    info.size = p.stat().st_size
                    with p.open("rb") as stream:
                        tar.addfile(info, stream)
                    entries.append({"path": rel, "sha256": digest(p), "bytes": info.size, "mode": oct(info.mode)})
    file_manifest = {"component": cid, "files": entries, "omitted": omitted,
                     "source_only": True, "build_verified": False}
    manifest_path = archives / (cid + "-files.json")
    manifest_path.write_text(json.dumps(file_manifest, indent=2, sort_keys=True) + "\n")
    return {"archive": out.name, "sha256": digest(out), "bytes": out.stat().st_size,
            "file_manifest": manifest_path.name, "file_manifest_sha256": digest(manifest_path),
            "files": len(entries), "omitted": len(omitted)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path, help="must not already exist")
    parser.add_argument("--component", action="append", choices=["openclaw", "osmglass", "osmand", "core-legacy", "resources"])
    parser.add_argument("--native", action="store_true", help="also reconstruct exact legacy-native prepared sources")
    parser.add_argument("--object-cache", action="append", default=[], metavar="ID=PATH",
                        help="optional local object source; exact public GitHub commit is verified online")
    parser.add_argument("--download-cache", type=Path, help="source archives named by SHA256; never build binaries")
    parser.add_argument("--snapshots", action="store_true", help="write curated working-tree source archives, no .git/keys/prebuilts/navigation artwork")
    parser.add_argument("--verify-build-inputs", type=Path, help="verify six separately obtained binary/data inputs; does not fetch them")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "source-components.json").read_text())
    caches = {}
    for item in args.object_cache:
        key, value = item.split("=", 1)
        require(key not in caches, "Duplicate object cache")
        caches[key] = Path(value).expanduser()
    selected = [c for c in manifest["components"] if not args.component or c["id"] in args.component]
    # Validate every chosen patch before destination creation or network activity.
    for component in selected:
        require(digest(ROOT / relative(component["patch"]["path"])) == component["patch"]["sha256"],
                "Patch SHA256 mismatch: " + component["id"])
    if args.native:
        require(any(c["id"] == "core-legacy" for c in selected), "--native needs core-legacy")
        require(digest(ROOT / relative(manifest["native_sources"]["manifest"])) ==
                manifest["native_sources"]["manifest_sha256"], "Native source manifest checksum mismatch")
    dest = args.destination.expanduser().absolute()
    require(not dest.exists(), "Destination exists; choose an explicit fresh destination")
    dest.mkdir(parents=True, exist_ok=False)
    receipt = {"format_version": 1, "status": "incomplete", "build_verified": False,
               "components": {}, "native": "not requested", "snapshots": {},
               "limitations": manifest["limitations"]}
    try:
        for component in selected:
            cid = component["id"]
            print("Reconstructing " + cid, flush=True)
            work = dest / relative(component["destination"])
            # Nested components live in ignored/unregistered sibling repositories.
            if work.exists() and work.is_dir() and not any(work.iterdir()):
                work.rmdir()
            proof = checkout(component["repository"], component["base_commit"], work,
                             caches.get(cid), component["base_tree"])
            check_patch(work, component["patch"])
            aggregate = verify_files(work, component["files"])
            require(aggregate == component["changed_files_sha256"], "Changed-file tree digest mismatch")
            proof.update(status="source-reconstructed", checked_files=len(component["files"]),
                         changed_files_sha256=aggregate, patch_sha256=component["patch"]["sha256"])
            # Separate public packaging adaptation after exact release-code verification.
            for overlay in component.get("packaging_patches", []):
                check_patch(work, overlay)
                verify_files(work, overlay["files"])
            receipt["components"][cid] = proof
        if args.native:
            print("Reconstructing legacy-native prepared source", flush=True)
            receipt["native"] = native_sources(dest, manifest, caches, args.download_cache)
        if args.verify_build_inputs:
            for name, pin in manifest["phone_downloads"].items():
                file = args.verify_build_inputs / relative(name)
                require(file.is_file() and digest(file) == pin["sha256"], "Pinned build input mismatch: " + name)
            receipt["verified_external_build_inputs"] = 6
        if args.snapshots:
            archives = dest / "source-archives"
            archives.mkdir()
            for component in selected:
                receipt["snapshots"][component["id"]] = snapshot(component, dest, archives)
        receipt["status"] = "source-reconstructed"
    finally:
        (dest / "RECONSTRUCTION.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
    print("Source reconstruction verified. No build/install/activation performed.")
    print("Receipt: " + str(dest / "RECONSTRUCTION.json"))


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as exc:
        print("Source reconstruction failed: " + str(exc), file=sys.stderr)
        sys.exit(1)
