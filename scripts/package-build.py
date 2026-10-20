# turns a publish tree into a SharpEmu build: a directory and a zip, with an identity.
#
#   py scripts/package-build.py                              whatever branch the fork has checked out
#   py scripts/package-build.py --branch android             the timestamp stamps itself
#   py scripts/package-build.py --no-publish                 repackage what is already published
#   py scripts/package-build.py --from-archive <path|url> --id android
#
# **it does not touch a device.** producing a build and putting one on a phone are two jobs, which is
# what lets a build packaged last week -- or one somebody else packaged -- be staged without
# republishing anything.
#
# **`--from-archive` needs no fork checkout, no .NET SDK and no git.** that is the path a third party
# takes and the one any automated job would take. what it cannot do is record a commit, so the
# build's `commit` is empty and its `source` names the archive instead -- and with no fork there is
# no remote to take an author from, so `--author` is how one is set there.
#
# **everything else defaults from the fork**: the id and the `source` from the branch, the author
# from the owner of that branch's `origin` remote, and the display name from the table below where
# there is one for it.
#
# **a build is a directory, not a file.** the publish output is the payload *and* its plugins, which
# the payload resolves relative to its own executable -- both for its managed plugins and for the
# media libraries behind video. packaging the payload alone is a build with no audio and no video.
#
# **identity lives in the metadata and never in a filename.** the folder a build lands in on a device
# is derived from it, so two builds of the same source coexist and nothing has to guess which is
# which.
#
# **the zip is the distribution format and the directory is what runs.** both are produced here, so
# the format is exercised by the thing that produces it.

import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from sharpdroid import builds, paths
from sharpdroid import toolchain as tc
from sharpdroid.shell import (Refusal, capture, ensure, fresh, main, produced, run, say, size, step,
                            tree_size, warn, wipe, write_text)
from sharpdroid.vocabulary import Parser

# the launcher-to-payload interface generation. the build format document is where this is defined;
# the number is here because packaging is what stamps it.
HOST_CONTRACT = 3

# what the first-party ids *are*, so that naming a branch produces a build that behaves as
# advertised rather than a build plus a knob the caller had to remember. this table stands in for a
# release pipeline; a third-party build passes the same things on the command line.
#
# **one of these is a maintained branch and the rest are topic branches.** the first is the only one
# that absorbs upstream; anything with a type prefix is archived at the commit it was cut from and is
# merged nowhere. that is the fork's own naming convention rather than a quirk of this script, and it
# is what keeps absorbing an upstream release a thing you do once.
#
# **a topic branch says which base it sits on, and the android one says so explicitly.**
# `<type>/android/<topic>` carries the platform support underneath the change, so it builds an APK
# and is what a measurement runs; `<type>/<topic>` is upstream plus the change and nothing else,
# which is what a pull request is cut from and what cannot build an APK at all. only the first kind
# is ever packaged, which is why every id here has the segment.
KNOWN = {
    "android": {
        "name": "Android platform support",
        "env": [],
        "notes": "SharpEmu expanded by Android platform support.",
    },
    "perf/android/flip-snapshot-pool": {
        "name": "Flip snapshot pool",
        "env": [],
        "notes": "android plus a pool for the per-frame guest flip snapshot. a topic branch, open "
                 "upstream -- import it to try the change before it lands.",
    },
    "perf/android/host-cached-memory": {
        "name": "Host-cached memory",
        "env": [],
        "notes": "android plus a host-cached memory preference for CPU-written allocations on "
                 "integrated GPUs. it is what a third-party driver needs and does little for the "
                 "platform's own.",
    },
    "perf/android/render-pass-batching": {
        "name": "Render pass batching",
        "env": ["SHARPEMU_BATCH_RENDER_PASSES=1"],
        "notes": "android plus render pass batching. a parked topic branch that joins nothing: a "
                 "per-draw global-memory barrier refuses every join, so the change is measured and "
                 "merged nowhere.",
    },
}


def entry():
    parser = Parser(description="package a SharpEmu build from the fork or from an archive")
    parser.add_argument("--branch", metavar="NAME", default=None,
                        help="the fork branch this build is cut from, and also its id. defaults to "
                             "whatever is checked out; naming a different one is refused rather "
                             "than checked out.")
    parser.add_argument("--packaged-at", metavar="STAMP", type=int, default=0,
                        help="when this build was packaged, yyyyMMddHHmmss, and the key everything "
                             "is ordered by. it assigns itself; pass one only to reproduce a "
                             "folder name.")
    parser.add_argument("--no-publish", action="store_true",
                        help="package the publish tree that is already there rather than building "
                             "one.")
    parser.add_argument("--from-archive", metavar="PATH", default=None,
                        help="a published linux-x64 tree as an archive or a directory, by path or "
                             "URL, instead of a fork checkout.")
    parser.add_argument("--id", metavar="ID", default=None,
                        help="the build's id. defaults to the fork branch, which an archive has "
                             "not got, so it is required there.")
    parser.add_argument("--sharpemu-version", metavar="VERSION", default=None,
                        help="the SharpEmu version. read from the fork otherwise.")
    parser.add_argument("--name", metavar="NAME", default=None, help="the display name.")
    parser.add_argument("--notes", metavar="TEXT", default=None,
                        help="one line, printed at launch under the identity.")
    parser.add_argument("--author", metavar="WHO", default=None,
                        help="who produced this build -- not who wrote the emulator. defaults to "
                             "the owner of the fork's origin remote. never derived from the log, "
                             "which after an upstream merge would credit an upstream contributor "
                             "for a package they never made.")
    parser.add_argument("--guest-env", metavar="NAME=VALUE", nargs="+", default=None,
                        help="guest environment this build wants defaulted on. below "
                             "everything but the app's own defaults, which it replaces.")
    arguments = parser.parse_args()

    toolchain = tc.resolve()
    if arguments.from_archive:
        identity, publish = from_archive(toolchain, arguments)
    else:
        identity, publish = from_fork(toolchain, arguments)

    package(toolchain, arguments, identity, publish)


# --- where the payload comes from -------------------------------------------------------------------


def from_archive(toolchain, arguments):
    """a published tree, extracted and searched for the payload.

    a directory is accepted too and skips straight to the search: a work-in-progress publish tree is
    the same shape as an unpacked archive, and this is what gives one an identity without a fork
    checkout or a repackage.
    """
    if not arguments.id:
        raise Refusal("--from-archive needs --id: there is no branch to take the build's id from")
    branch = arguments.id
    version = arguments.sharpemu_version or _version_from_name(arguments.from_archive)

    work = fresh(paths.BUILD / "from-archive")
    source = arguments.from_archive

    if not re.match(r"^https?://", source) and Path(source).is_dir():
        extracted = Path(source).resolve()
        say("using the directory as it is: {}".format(extracted))
    else:
        archive = Path(source)
        if re.match(r"^https?://", source):
            archive = work / source.rstrip("/").rsplit("/", 1)[-1]
            step("downloading")
            say("  " + source)
            with urllib.request.urlopen(source) as response, open(str(archive), "wb") as handle:
                shutil.copyfileobj(response, handle)
            produced(archive, archive.name)
        if not archive.exists():
            raise Refusal("no archive at {}".format(archive))
        extracted = ensure(work / "extract")
        step("extracting")
        if archive.suffix.lower() == ".zip":
            with zipfile.ZipFile(str(archive)) as zipped:
                zipped.extractall(str(extracted))
        else:
            # nothing about unix permissions is preserved and none is needed: the payload is never
            # executed as a file, it is read into guest memory by the host layer's ELF loader.
            with tarfile.open(str(archive)) as tarred:
                tarred.extractall(str(extracted))

    # the payload can be at the root or one wrapper directory down, depending on who packed it.
    # found rather than assumed, and named when found so the log says which layout it was.
    found = [path.parent for path in extracted.rglob("SharpEmu")
             if path.is_file() and (path.parent / "plugins").is_dir()]
    if not found:
        raise Refusal(
            "no SharpEmu payload with a plugins/ beside it anywhere in {} -- a build is a "
            "directory, and that is half of it".format(source))
    if len(found) > 1:
        raise Refusal("found {} candidate payloads in {}; cannot tell which is meant".format(
            len(found), source))
    where = str(found[0])[len(str(extracted)):].strip("\\/") or "the archive root"
    say("payload found at {}".format(where))

    return {"branch": branch, "version": version, "commit": "", "source": source}, found[0]


def _version_from_name(source):
    """upstream names its release assets with the version in them, so it is usually right there.

    guessing is fine; guessing silently is not, so this says where the answer came from and refuses
    when the name does not carry one.
    """
    leaf = source.rstrip("/").rsplit("/", 1)[-1]
    match = re.search(r"sharpemu-(.+?)-(?:linux|osx|win)-", leaf)
    if not match:
        raise Refusal("cannot tell the SharpEmu version from '{}'. pass --sharpemu-version".format(
            leaf))
    say("sharpemu version {}, read off the archive name".format(match.group(1)))
    return match.group(1)


def from_fork(toolchain, arguments):
    """the fork checkout, published now or already published."""
    fork = toolchain.fork
    branch = resolve_branch(fork, arguments.branch)

    # **the commit is asked for before anything else**, because the warning below is the first thing
    # this step has to say and it is the same answer: a build published from a tree with edits in it
    # records them in its commit, and one query decides both so they cannot disagree.
    #
    # **the marker travels with the build and the warning does not.** a warning is read once, on this
    # machine, by whoever typed the command -- while the metadata is staged to devices, bundled into
    # APKs, named in every launch log, drawn on two screens and compared against this checkout by the
    # next run, and a build published from a tree with edits in it is not the commit it would
    # otherwise claim.
    commit = builds.checkout_commit(fork)
    if not commit:
        raise Refusal("{} would not say what commit it is at, so a build published from it could "
                      "not record where it came from".format(fork))
    if commit.endswith(builds.DIRTY):
        # the path is named because two checkouts of this fork exist on a development machine by
        # design, and which one this is matters.
        warn("{} has a dirty working tree, so this build is not a clean checkout of {}".format(
            fork, branch))

    # the version the fork declares, never one written down here.
    props = (fork / "Directory.Build.props").read_text(encoding="utf-8")
    version = re.search(r"<SharpEmuVersion>([^<]+)</SharpEmuVersion>", props)
    if not version:
        raise Refusal("no <SharpEmuVersion> in {}/Directory.Build.props".format(fork))
    version = version.group(1)

    publish = fork / "artifacts" / "publish" / "SharpEmu.CLI" / "Release" / "net10.0" / "linux-x64"

    # **what the publish tree was last built from, recorded beside it.** without this, repackaging
    # stamps the identity of whatever branch is checked out onto whatever payload happens to be
    # there -- a plausible artefact attributed to the wrong source, which is this project's most
    # expensive failure shape and is silent by construction, because both halves are individually
    # valid: the payload is real and so is the identity. the commit is part of it, because a branch
    # name alone would not notice a rebuild after a commit on the same branch.
    stamp = publish / ".packaged-from"
    identity = "{} {}".format(branch, commit)

    if not arguments.no_publish:
        step("publishing {}".format(branch))
        # the publish tree is not cleaned between branches, so a file a branch stopped producing
        # would survive into the next package. cheap to remove and expensive to debug.
        wipe(publish)
        environment = dict(os.environ)
        environment["DOTNET_ROOT"] = str(toolchain.dotnet_root)
        environment["PATH"] = str(toolchain.dotnet_root) + os.pathsep + environment.get("PATH", "")
        run([toolchain.dotnet, "publish",
             str(fork / "src" / "SharpEmu.CLI" / "SharpEmu.CLI.csproj"),
             "-c", "Release", "-r", "linux-x64"], env=environment)
        write_text(stamp, identity + "\n")

    if not (publish / "SharpEmu").exists():
        raise Refusal("no payload at {} -- publish it, or drop --no-publish".format(publish))

    if arguments.no_publish:
        # refuse rather than warn. a warning on a package that then succeeds is a build somebody
        # keeps, and its metadata is the only place the mistake would ever show.
        was = stamp.read_text(encoding="utf-8").strip() if stamp.exists() else ""
        if not was:
            raise Refusal(
                "the publish tree at {} has no record of what it was built from, so --no-publish "
                "cannot confirm it is {}. drop --no-publish to publish it again".format(
                    publish, identity))
        if was != identity:
            raise Refusal(
                "the publish tree at {} was built from '{}' and this would label it '{}'. drop "
                "--no-publish to publish the checked-out branch, or check out the branch it came "
                "from".format(publish, was, identity))

    owner, project = fork_origin(fork)
    return {"branch": branch, "version": version, "commit": commit,
            "author": owner,
            "source": tree_url(project, branch) if project else "unknown"}, publish


def fork_origin(fork):
    """who the fork belongs to and where it lives, out of its `origin` remote, or two empty strings.

    **the remote is the only place a clone knows whose fork it is.** `git config user.name` is who
    is sitting here, which is not the same claim -- a checkout of somebody else's fork would credit
    the wrong person for their build -- and the log is worse still, since after an upstream merge the
    last commit is an upstream contributor's.

    both URL shapes git writes are read, `https://host/owner/project` and `git@host:owner/project`,
    because which one a clone has is a preference nobody sets deliberately.
    """
    url = subprocess.run(["git", "-C", str(fork), "remote", "get-url", "origin"],
                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                         encoding="utf-8", errors="replace")
    if url.returncode != 0:
        return "", ""
    found = re.match(r"^(?:https?://|ssh://git@|git@)([^/:]+)[/:]([^/]+)/(.+?)(?:\.git)?/?$",
                     (url.stdout or "").strip())
    if not found:
        return "", ""
    host, owner, project = found.group(1), found.group(2), found.group(3)
    return owner, "https://{}/{}/{}".format(host, owner, project)


def tree_url(project, branch):
    """the branch's own page, which is where a person sent to a build's source should land.

    the project alone answers "whose emulator is this" and not "which of their branches", and the
    branches are the whole point here: one of them is the maintained tier and the rest are archived
    at a commit and merged nowhere.

    `/tree/<branch>` is GitHub's path and other forges spell it differently. that costs nothing to be
    wrong about: this string is provenance a person reads, nothing resolves it, and the project it
    is built from is right whatever the branch path turns out to be.
    """
    return "{}/tree/{}".format(project, branch)


def resolve_branch(fork, wanted):
    """which branch this build is, and a refusal rather than a checkout when they disagree.

    the device gets whichever branch this is, so it is the one thing worth refusing over: a build
    labelled as one branch and cut from another is a plausible artefact attributed to the wrong
    source, which nothing downstream can detect.
    """
    checked_out = capture(["git", "-C", str(fork), "rev-parse", "--abbrev-ref", "HEAD"]).strip()
    if checked_out != "HEAD":
        if wanted and wanted != checked_out:
            raise Refusal("asked for '{}' and '{}' is checked out. run: git -C \"{}\" checkout {}"
                          .format(wanted, checked_out, fork, wanted))
        return wanted or checked_out

    # **a detached HEAD is the ordinary state, not a broken one.** updating a submodule leaves it
    # detached at the recorded commit, so this is what everyone building from a clone gets -- and
    # asking for the abbreviated ref answers the literal word HEAD, which would otherwise become
    # this build's id.
    #
    # the branch is recovered from the remote refs that contain the commit. **the preference is
    # explicit**, because a commit is commonly on several branches at once: every topic branch is
    # cut from the trunk, so the trunk's own tip is contained by all of them, and taking whichever
    # was listed first would be a coin toss deciding what a build calls itself.
    listed = capture(["git", "-C", str(fork), "branch", "-r", "--contains", "HEAD",
                      "--format=%(refname:short)"], check=False)
    on = [line.strip()[len("origin/"):] if line.strip().startswith("origin/") else line.strip()
          for line in listed.splitlines() if line.strip() and line.strip() != "HEAD"]
    on = [name for name in on if name and name != "HEAD"]

    if wanted:
        # naming one is allowed here, unlike the attached case, because there is no branch to switch
        # to. **but containment is not enough to accept a topic branch**: containment means "is an
        # ancestor of", and since every topic branch is cut from the trunk, every trunk commit is an
        # ancestor of all of them. a bare containment test would accept a topic branch's name at a
        # commit carrying none of that branch's changes.
        #
        # so the trunk and a topic branch are judged differently, and the fork's own naming
        # convention is what separates them: the trunk is what every branch descends from, so being
        # on its history *is* being it, while a topic branch is defined by the commits it adds on
        # top and only its tip carries them.
        if wanted not in on:
            raise Refusal("asked for '{}', but the commit checked out in {} is not on it. it is "
                          "on: {}".format(wanted, fork, ", ".join(on) or "no branch at origin"))
        if wanted != "android":
            tip = capture(["git", "-C", str(fork), "rev-parse",
                           "refs/remotes/origin/" + wanted]).strip()
            head = capture(["git", "-C", str(fork), "rev-parse", "HEAD"]).strip()
            if tip != head:
                raise Refusal(
                    "asked for '{}', and {} is at {} which is an ancestor of it rather than its "
                    "tip {}. a build named after a topic branch that does not carry that branch's "
                    "commits is attributed to the wrong source. check the tip out, or omit "
                    "--branch and let it be inferred".format(wanted, fork, head[:7], tip[:7]))
        return wanted

    if "android" in on:
        return "android"
    if not on:
        raise Refusal(
            "{} is on a detached HEAD whose commit is on no branch at origin. either origin has "
            "not been fetched, or the recorded pointer names a commit that was rewritten "
            "away".format(fork))
    if len(on) == 1:
        return on[0]
    raise Refusal("{} is on a detached HEAD and its commit is on {} branches ({}). pass --branch "
                  "to say which one this build is".format(fork, len(on), ", ".join(on)))


# --- packaging ----------------------------------------------------------------------------------------


def package(toolchain, arguments, identity, publish):
    branch = identity["branch"]
    known = KNOWN.get(branch, {})
    name = arguments.name or known.get("name") or branch
    notes = arguments.notes if arguments.notes is not None else known.get("notes", "")
    # **the fork's own remote is the default and the table above may still override it**, though
    # nothing in it does: naming an author there would state on every machine what only the machine
    # packaging can know. empty is a supported answer and means the app's own screens say it once.
    author = arguments.author if arguments.author is not None else (
        known.get("author") or identity.get("author", ""))
    guest_env = arguments.guest_env if arguments.guest_env is not None else known.get("env", [])

    packaged_at = arguments.packaged_at or int(datetime.now().strftime("%Y%m%d%H%M%S"))
    folder = builds.slug("{}-{}-{}".format(branch, identity["version"], packaged_at))

    step("packaging {} -- {} {} {}".format(name, branch, identity["version"], packaged_at))
    say("  into {}".format(folder))

    staging = fresh(paths.BUILD_BUILDS / folder)
    # the whole publish directory rather than the payload: the plugins are most of it, and the
    # payload cannot find its audio or its video without them.
    shutil.copytree(str(publish), str(staging), dirs_exist_ok=True)
    # except the marker, which is a note this script left itself about the *publish* tree and is not
    # part of any build. a third party would have to wonder what it was, and the app's asset packer
    # drops names beginning with a dot -- so a bundled build listing one would describe a tree the
    # APK does not contain.
    if (staging / ".packaged-from").exists():
        (staging / ".packaged-from").unlink()
    if not (staging / "plugins").is_dir():
        raise Refusal("the publish output has no plugins/ -- a build is a directory, and that is "
                      "half of it")

    meta = {
        "id": branch,
        "name": name,
        "sharpemuVersion": identity["version"],
        "packagedAt": packaged_at,
        "hostContract": HOST_CONTRACT,
        "payload": "SharpEmu",
        "env": read_environment(guest_env),
        "notes": notes,
        "author": author,
        # neither of these is part of the format the app reads, and both are recorded deliberately:
        # a build that behaves differently from another has to be traceable to where it came from
        # without a change log. the commit is **empty rather than absent** when there was no
        # checkout to ask, and the source says what it was instead -- a build whose provenance is
        # unknown should say so rather than leave a reader to notice a missing field.
        "commit": identity["commit"],
        "source": identity["source"],
    }
    # no byte order mark. the readers on the other side cope, and every other tool that has to read
    # this one day may not.
    write_text(staging / "meta.json", json.dumps(meta, indent=4))

    packaged = builds.Build(staging).check()

    # the metadata at the zip *root* rather than inside a wrapper folder -- the single thing most
    # likely to differ between two hand-made packages.
    archive = paths.BUILD_BUILDS / (folder + ".zip")
    if archive.exists():
        archive.unlink()
    step("zipping")
    with zipfile.ZipFile(str(archive), "w", zipfile.ZIP_DEFLATED) as zipped:
        for path in sorted(staging.rglob("*")):
            if path.is_file():
                zipped.write(str(path), path.relative_to(staging).as_posix())
    produced(archive, "the zip", quiet=True)

    say("")
    say("  payload    {}".format(size(packaged.payload_size())))
    say("  directory  {}".format(size(tree_size(staging))))
    say("  zip        {}  {}".format(size(archive.stat().st_size), paths.relative(archive)))
    say("  commit     {}".format(meta["commit"] or "none, packaged from an archive"))
    if meta["env"]:
        say("  env        {}".format(" ".join("{}={}".format(k, v)
                                              for k, v in meta["env"].items())))
    say("")
    say("put it on a device with:")
    say("  py scripts/stage.py --sharpemu {}".format(paths.relative(staging)))


def read_environment(assignments):
    values = {}
    for assignment in assignments or []:
        if not assignment:
            continue
        if "=" not in assignment or assignment.startswith("="):
            raise Refusal("--guest-env wants NAME=VALUE, got '{}'".format(assignment))
        name, value = assignment.split("=", 1)
        values[name] = value
    return values


if __name__ == "__main__":
    main(entry)
