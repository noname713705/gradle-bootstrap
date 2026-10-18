#!/usr/bin/env python3
"""Try to build a Gradle version (tag or commit) with an already built Gradle (the builder).

Each attempt gets its own git worktree, work/<branch>, on a new branch <branch> created from
the target. ./gradle (its checked out branch, its untracked files) is never touched.
Nothing is committed: the changes stay in the worktree for review.

The mechanical fixes from the earlier steps are applied, each only where the files need it:
  - build scripts with group-less dependencies (":junit:4.4"): groups, Maven Central resolver,
    ivy/svnkit versions (scripts/patch-deps.py)
  - Maven Central / repo.gradle.org over https; MAVEN_REPO_URL without trailing slash
    (the jar resolver adds one, Central answers // with 404)
  - tests disabled in the root build script (test { exclude '**' })
  - src/samples created when the build uses it (an empty directory in svn, not kept by git)
Build script translations to the builder's API are not automatic: they are reported as the
first error of the build, to be fixed by hand in the worktree, then run again with --keep.

The builder is started with scripts/gradle-isolated.sh when its bin/gradle uses
org.gradle.BootstrapMain (builder classes must not leak into the compile classpath),
otherwise with its bin/gradle.

Checks on the result: bin/gradle -v, then the sandbox project
(sandbox/hello before 1.0, sandbox/hello-1.0 from 1.0 on, sandbox/hello-5.0 from 5.0 on).

usage:
  try-build.py <builder dist> <target ref> <branch> [options]
  e.g. try-build.py target/gradle-0.5 REL-0.6 gradle-0.6
options:
  --task TASKS     gradle tasks (default: clean explodedDistBase)
  --dist DIR       built distribution, relative to the worktree (default: build/distributions/exploded)
  --java DIR       JAVA_HOME (default: zulu 7)
  --keep           reuse an existing worktree (after fixing it by hand) instead of creating it
  --install        copy the result to target/<branch> when all checks pass
  --maven-proxy    run scripts/maven-proxy.py (http://localhost:8765/...) during the build, for builders
                   that cannot reach Maven Central over https (Gradle 1.x on Java 7)
  --no-script-fixes  leave the build scripts untouched (for builds from alternate files
                   in the builder's DSL, e.g. build.gradle.0.5): only source/directory fixes
"""
import argparse
import glob
import os
import re
import shutil
import subprocess
import sys

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.join(PROJECT, 'gradle')
WORK = os.path.join(PROJECT, 'work')
LOGS = os.path.join(WORK, 'logs')
SCRIPTS = os.path.join(PROJECT, 'scripts')
DEFAULT_JAVA = '/data/devel/zulu7.56.0.11-ca-jdk7.0.352-linux_x64'


def run(cmd, cwd=None, env=None, log=None, check=True):
    """run a command; output to the log file (appended) or returned"""
    if log:
        with open(log, 'a') as f:
            f.write(f'\n$ {" ".join(cmd)}  (in {cwd})\n')
            f.flush()
            result = subprocess.run(cmd, cwd=cwd, env=env, stdout=f, stderr=subprocess.STDOUT)
    else:
        result = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True)
    if check and result.returncode != 0:
        out = '' if log else result.stdout + result.stderr
        sys.exit(f'failed: {" ".join(cmd)}\n{out}')
    return result


def note(msg):
    print(msg, flush=True)


def build_scripts(tree):
    """the root and buildSrc build scripts (0.x: gradlefile, then build.gradle)"""
    found = []
    for d in ['', 'buildSrc']:
        for name in ['build.gradle', 'gradlefile']:
            path = os.path.join(tree, d, name)
            if os.path.isfile(path):
                found.append(path)
                break
    return found


def repo_method(builder):
    """the builder's method to add Maven Central: addIBiblio up to 0.1, addMavenRepo from 0.1.4 on"""
    for jar in glob.glob(os.path.join(builder, 'lib', 'gradle-*.jar')):
        listing = run(['unzip', '-l', jar], check=False).stdout
        if 'org/gradle/api/DependencyManager.class' in listing:
            out = subprocess.run(['unzip', '-p', jar, 'org/gradle/api/DependencyManager.class'],
                                 capture_output=True).stdout
            if b'addMavenRepo' in out:
                return 'addMavenRepo'
            if b'addIBiblio' in out:
                return 'addIBiblio'
    return None


def apply_fixes(tree, builder, script_fixes=True):
    """the mechanical fixes, each only where the files need it; returns what was done"""
    done = []
    method = repo_method(builder)
    for script in (build_scripts(tree) if script_fixes else []):
        text = open(script).read()
        rel = os.path.relpath(script, tree)
        # group-less dependencies (0.x notation) -> groups + Maven Central resolver of the builder
        if re.search(r'''["']:[A-Za-z0-9._-]+:''', text) and 'dependencies {' in text:
            if not method:
                done.append(f'{rel}: group-less dependencies, but no Maven resolver method found in the builder')
            else:
                r = run([sys.executable, os.path.join(SCRIPTS, 'patch-deps.py'), script, method], check=False)
                done.append(f'{rel}: dependency groups + {method}()' if r.returncode == 0
                            else f'{rel}: patch-deps.py failed: {(r.stdout + r.stderr).strip()}')
            text = open(script).read()
        # svnkit 1.1.6 (com.svnkit) is not on Maven Central: org.tmatesoft.svnkit 1.3.5
        new = re.sub(r'com\.svnkit:(svnkit(?:-javahl)?):1\.1\.6', r'org.tmatesoft.svnkit:\1:1.3.5', text)
        if new != text:
            done.append(f'{rel}: svnkit 1.3.5 from Maven Central')
            text = new
        # https for the repositories in the build scripts
        new = re.sub(r'http://(repo1\.maven\.org|repo\.gradle\.org)', r'https://\1', text)
        if new != text:
            done.append(f'{rel}: https repositories')
            text = new
        # tests disabled (root script only)
        if os.path.dirname(script) == tree and re.search(r'^test \{', text, re.M) and "exclude '**'" not in text:
            text = re.sub(r'^test \{', "test {\n    // tests disabled for the bootstrap (see README)\n    exclude '**'",
                          text, count=1, flags=re.M)
            done.append(f'{rel}: tests disabled')
        open(script, 'w').write(text)
    # empty directories of the svn era (git does not keep them), used by the dist tasks
    root_script = next((open(x).read() for x in build_scripts(tree) if os.path.dirname(x) == tree), '')
    samples = os.path.join(tree, 'src', 'samples')
    if re.search(r"srcRoot, 'samples'|'src/samples'", root_script) and not os.path.isdir(samples):
        os.makedirs(samples)
        done.append('src/samples: created (empty directory, not kept by git)')
    # Maven Central URL in the sources: https, no trailing slash
    # (MAVEN_REPO_URL in DependencyManager.java up to 0.5, MAVEN_CENTRAL_URL in ResolverContainer.java from 0.6)
    # (sources in src/ up to 0.7, in subprojects/*/src/ from 0.8 on)
    sources = glob.glob(os.path.join(tree, 'src', '**', '*.java'), recursive=True) \
        + glob.glob(os.path.join(tree, 'subprojects', '*', 'src', '**', '*.java'), recursive=True)
    for java in sources:
        text = open(java, errors='replace').read()
        if 'http://repo1.maven.org' not in text:
            continue
        new = re.sub(r'(_URL = ")http://repo1\.maven\.org/maven2/?"', r'\1https://repo1.maven.org/maven2"', text)
        if new != text:
            open(java, 'w').write(new)
            done.append(f'{os.path.relpath(java, tree)}: Maven URL https, no trailing slash')
    return done


def launcher(builder):
    """how to start the builder: isolated when it uses BootstrapMain"""
    start = open(os.path.join(builder, 'bin', 'gradle')).read()
    if 'org.gradle.BootstrapMain' in start:
        return [os.path.join(SCRIPTS, 'gradle-isolated.sh'), builder]
    return [os.path.join(builder, 'bin', 'gradle')]


def first_errors(log):
    lines = [l.rstrip() for l in open(log, errors='replace') if 'build-resolver' not in l]
    keep = [l for l in lines if re.search(r'Cause:|Exception:|error:|line\(s\)|line: \d|module not found|BUILD (FAILED|SUCCESSFUL)', l)]
    return keep[:8]


def main():
    p = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    p.add_argument('builder')
    p.add_argument('target')
    p.add_argument('branch')
    p.add_argument('--task', default='clean explodedDistBase')
    p.add_argument('--dist', default='build/distributions/exploded')
    p.add_argument('--java', default=DEFAULT_JAVA)
    p.add_argument('--keep', action='store_true')
    p.add_argument('--install', action='store_true')
    p.add_argument('--maven-proxy', action='store_true')
    p.add_argument('--no-script-fixes', action='store_true',
                   help='leave the build scripts untouched (builds from alternate files in the builder DSL)')
    a = p.parse_args()

    builder = os.path.abspath(a.builder)
    if not os.path.isfile(os.path.join(builder, 'bin', 'gradle')):
        sys.exit(f'not a gradle distribution: {builder}')
    tree = os.path.join(WORK, a.branch)
    os.makedirs(LOGS, exist_ok=True)
    log = os.path.join(LOGS, f'{a.branch}.log')
    env = dict(os.environ, JAVA_HOME=a.java, PATH=f'{a.java}/bin:' + os.environ.get('PATH', ''))

    if a.keep:
        if not os.path.isdir(tree):
            sys.exit(f'--keep: no worktree {tree}')
        note(f'reusing worktree {tree}')
    else:
        if os.path.exists(tree):
            sys.exit(f'{tree} exists: use --keep, or remove it (git -C gradle worktree remove {tree})')
        if run(['git', 'rev-parse', '--verify', '-q', f'refs/heads/{a.branch}'], cwd=REPO, check=False).returncode == 0:
            sys.exit(f'branch {a.branch} exists already in ./gradle')
        run(['git', 'worktree', 'add', '-q', '-b', a.branch, tree, a.target], cwd=REPO)
        note(f'worktree {tree} on new branch {a.branch} from {a.target}')
        for d in apply_fixes(tree, builder, not a.no_script_fixes):
            note(f'  fix: {d}')

    proxy = None
    if a.maven_proxy:
        proxy = subprocess.Popen([sys.executable, os.path.join(SCRIPTS, 'maven-proxy.py')])
        note('maven proxy running on http://localhost:8765 (scripts/maven-proxy.py)')
    open(log, 'w').close()  # one log per run
    note(f'building with {os.path.basename(builder)}: {a.task}  (log: {os.path.relpath(log, PROJECT)})')
    try:
        result = run(launcher(builder) + a.task.split(), cwd=tree, env=env, log=log, check=False)
    finally:
        if proxy:
            proxy.terminate()
    for line in first_errors(log):
        note(f'  | {line[:220]}')
    built = os.path.join(tree, a.dist)
    # old Gradle versions exit with 0 even on BUILD FAILED: the output decides
    succeeded = 'BUILD SUCCESSFUL' in open(log, errors='replace').read()
    if result.returncode != 0 or not succeeded or not os.path.isdir(os.path.join(built, 'lib')):
        note('RESULT: build failed (fix in the worktree, then run again with --keep)')
        sys.exit(1)

    # checks on the result
    version = run(launcher(built) + ['-v'], env=env, check=False)
    vline = next((l for l in version.stdout.splitlines() if l.startswith('Gradle ')), None)
    note(f'  -v: {vline or "FAILED"}')
    if not vline:
        note('RESULT: built, but the result does not start')
        sys.exit(1)
    major_minor = tuple(int(x) for x in re.findall(r'\d+', a.branch)[:2]) or (0, 0)
    sandbox = os.path.join(PROJECT, 'sandbox', 'hello-5.0' if major_minor >= (5, 0) else 'hello-1.0' if major_minor >= (1, 0) else 'hello')
    # the jar task: libs up to 0.7, jar from 0.8 on
    tasks = ['clean', 'hello', 'jar'] if major_minor >= (0, 8) else ['clean', 'hello', 'libs']
    shutil.rmtree(os.path.join(sandbox, 'build'), ignore_errors=True)
    sb = run(launcher(built) + ['-q'] + tasks, cwd=sandbox, env=env, check=False)
    jars = glob.glob(os.path.join(sandbox, 'build', '**', '*.jar'), recursive=True)
    hello = run([f'{a.java}/bin/java', '-jar', jars[0]], env=env, check=False).stdout.strip() if jars else ''
    note(f'  sandbox {os.path.basename(sandbox)}: {hello or "FAILED"}')
    if sb.returncode != 0 or not hello:
        note('RESULT: built, but the sandbox build fails')
        sys.exit(1)

    if a.install:
        dest = os.path.join(PROJECT, 'target', a.branch)
        shutil.rmtree(dest, ignore_errors=True)
        shutil.copytree(built, dest, symlinks=True)
        note(f'  installed: {os.path.relpath(dest, PROJECT)}')
    note('RESULT: OK (nothing committed: review and commit in the worktree)')


if __name__ == '__main__':
    main()
