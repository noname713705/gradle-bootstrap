#!/usr/bin/env python3
"""Table of the Gradle minor releases (1.0 .. newest tag in ./gradle): bundled Groovy version, build script
language of Gradle's own build, JDK required to build (from CONTRIBUTING.md / README.md).
Read-only: git show / git ls-tree on the tags of ./gradle.

usage: versions-table.py [first major]   (default 1)
"""
import re
import subprocess
import sys

REPO = __file__.rsplit('/scripts/', 1)[0] + '/gradle'


def git(*args):
    return subprocess.run(['git', '-C', REPO] + list(args), capture_output=True, text=True, errors='replace').stdout


def show(tag, path):
    return git('show', f'{tag}:{path}')


def release_tags(first_major):
    tags = set(git('tag').split())
    result = []
    for major in range(first_major, 20):
        for minor in range(0, 30):
            for t in (f'v{major}.{minor}.0', f'REL_{major}.{minor}', f'v{major}.{minor}'):
                if t in tags:
                    result.append((f'{major}.{minor}', t))
                    break
    return result


def groovy_version(tag, files):
    # 8.x+: build-logic/dependency-modules/.../ExternalModulesExtension.kt
    for f in files:
        if f.endswith('ExternalModulesExtension.kt'):
            txt = show(tag, f)
            m = re.search(r'val groovyVersion = "([^"]+)"', txt)
            if m:
                return m.group(1)
            m = re.search(r'bundleGroovyMajor\s*=\s*[^\n]*?(\d)', txt)
            versions = re.findall(r'(\d)\s*->\s*"([^"]+)"', txt)
            if versions:
                major = None
                props = show(tag, 'gradle.properties')
                pm = re.search(r'bundleGroovy\w*\s*=\s*(\d)', props)
                if pm:
                    major = pm.group(1)
                if major:
                    return dict(versions).get(major, '/'.join(v for _, v in versions))
                return '/'.join(v for _, v in versions)
    # 3.x-4.x: gradle/dependencies.gradle versions.groovy = "x"
    txt = show(tag, 'gradle/dependencies.gradle')
    m = re.search(r'versions\.groovy\s*=\s*["\']([^"\']+)', txt)
    if m:
        return m.group(1)
    # 5.x-7.x: groovyVersion = 'x' somewhere in the build files
    for f in files:
        if re.search(r'(\.gradle|\.gradle\.kts|\.kt)$', f) and ('buildSrc' in f or f.startswith('gradle/') or '/' not in f or 'build-logic' in f):
            m = re.search(r'groovyVersion\s*=\s*["\']([0-9][^"\']+)', show(tag, f))
            if m:
                return m.group(1)
    # 1.x-2.x: groovy-all:x in build.gradle
    m = re.search(r'groovy-all:([0-9][^"\'@ ]+)', show(tag, 'build.gradle'))
    return m.group(1) if m else '?'


def script_language(files):
    kts = sum(1 for f in files if f.endswith('.gradle.kts'))
    groovy = sum(1 for f in files if f.endswith('.gradle') and '/src/' not in f and 'samples' not in f)
    if kts and kts >= groovy:
        return 'kotlin'
    return 'kotlin+groovy' if kts else 'groovy'


def build_jdk(tag):
    for doc in ('CONTRIBUTING.md', 'README.md'):
        txt = show(tag, doc)
        for line in txt.split('\n'):
            if re.search(r'\b(JDK|Java)\b', line) and re.search(r'\b(build|install|require|need)', line, re.I) and re.search(r'\d', line):
                m = re.search(r'(?:JDK|Java)[ -]*(?:version )?(\d+(?:\.\d+)?)', line)
                if m:
                    return m.group(1)
    return ''


# documented minimum Java version to run Gradle (gradle.org compatibility notes)
RUN_JAVA = [((1, 0), '5'), ((2, 0), '6'), ((3, 0), '7'), ((5, 0), '8'), ((9, 0), '17')]


def run_java(version):
    v = tuple(int(x) for x in version.split('.'))
    result = ''
    for since, java in RUN_JAVA:
        if v >= since:
            result = java
    return result


def main():
    first = int(sys.argv[1]) if len(sys.argv) > 1 else 1
    print(f"{'version':8} {'tag':10} {'groovy':10} {'scripts':14} {'run java':9} build jdk")
    prev = None
    for version, tag in release_tags(first):
        files = [f for f in git('ls-tree', '-r', '--name-only', tag).split('\n')
                 if '/src/test' not in f and '/src/integTest' not in f]
        row = (groovy_version(tag, files), script_language(files), run_java(version), build_jdk(tag))
        mark = '' if prev is None or row == prev else '  <- change'
        print(f"{version:8} {tag:10} {row[0]:10} {row[1]:14} {row[2]:9} {row[3]}{mark}")
        prev = row


if __name__ == '__main__':
    main()
