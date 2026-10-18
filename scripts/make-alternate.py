#!/usr/bin/env python3
"""Create the alternate build files of a bootstrap step, in a worktree (see README.adoc, method).

For building Gradle <target> with the builder <builder version>, next to the original files:
- settings.gradle.<builder>: the projects of settings.gradle without the excluded ones, every project build
  file named <name>.gradle.<builder>, the root build file build.gradle.<builder>
- buildSrc/settings.gradle: the builder builds buildSrc before any settings script and only from the buildSrc
  directory; this file selects buildSrc/build.gradle.<builder> only when the running Gradle is the builder
- copies <file>.<builder> of the root, buildSrc and kept project build files and of gradle/*.gradle, with the
  apply from: references pointing to the copies
The copies are then translated by hand to the builder's DSL. Existing copies are not overwritten.

usage: make-alternate.py <worktree> <builder version> <target name> [excluded project...]
  e.g. make-alternate.py work/gradle-2.0 1.0 v2.0.0 docs integTest internalTesting
"""
import glob
import os
import re
import sys


def main():
    tree, builder, target, excluded = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
    os.chdir(tree)
    s = open('settings.gradle').read()
    for p in excluded:
        if f"include '{p}'\n" not in s:
            sys.exit(f"include '{p}' not found in settings.gradle")
        s = s.replace(f"include '{p}'\n", f"// include '{p}': docs/tests only, not built for the bootstrap\n")
    if 'project.buildFileName = "${fileBaseName}.gradle"' in s:
        s = s.replace('project.buildFileName = "${fileBaseName}.gradle"', f'project.buildFileName = "${{fileBaseName}}.gradle.{builder}"')
    else:
        print('warning: no project.buildFileName in settings.gradle, set the build file names by hand')
    s = s.replace("rootProject.name = 'gradle'\n", f"rootProject.name = 'gradle'\nrootProject.buildFileName = 'build.gradle.{builder}'\n")
    write(f'settings.gradle.{builder}',
          f"// Settings for building Gradle ({target} sources) with Gradle {builder}, the builder of this step.\n"
          f"// Same projects as settings.gradle (without docs and test-only projects), every build file is the\n"
          f"// <name>.{builder} one, in the DSL of Gradle {builder}.\n" + s +
          f"\n// buildSrc: gradle-{builder} builds it before this file is read, buildSrc/settings.gradle selects buildSrc/build.gradle.{builder}\n")
    if os.path.isdir('buildSrc'):
        write('buildSrc/settings.gradle', f"""// Bootstrap only (branch of {target}): built by gradle-{builder} from build files in the {builder} DSL
// (see settings.gradle.{builder}). gradle-{builder} builds buildSrc before any settings script and only from this
// directory: this file points it to build.gradle.{builder}. Any other Gradle (the bootstrapped version itself,
// later versions) keeps the default build.gradle.
if (gradle.gradleVersion.startsWith('{builder}')) {{
    rootProject.buildFileName = 'build.gradle.{builder}'
}}
""")
    kept = {re.sub(r'(?<!^)(?=[A-Z])', '-', n).lower() for n in re.findall(r"^include '([^']+)'", s, re.M)}
    files = ['build.gradle'] + (['buildSrc/build.gradle'] if os.path.isfile('buildSrc/build.gradle') else []) + \
        sorted(glob.glob('gradle/*.gradle')) + sorted(f for f in glob.glob('subprojects/*/*.gradle') if f.split('/')[1] in kept)
    for f in files:
        t = re.sub(r'(gradle/[A-Za-z0-9-]+)\.gradle(["\'])', rf'\1.gradle.{builder}\2', open(f).read())
        write(f + f'.{builder}', f"// {f} of {target} in the DSL of Gradle {builder} (the builder of this step), see settings.gradle.{builder}\n" + t)
    print(f'{len(files)} build files, {len(kept)} projects')


def write(path, text):
    if os.path.exists(path):
        print(f'kept existing {path}')
        return
    with open(path, 'w') as f:
        f.write(text)


if __name__ == '__main__':
    main()
