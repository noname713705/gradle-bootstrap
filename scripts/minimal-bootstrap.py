#!/usr/bin/env python3
"""Minimal bootstrap of a Gradle version: compile its sources without its own build, into a raw distribution
that is only good enough to run the real build of the same version (see README.adoc, bootstrap by minimal build).

No Gradle is used. Steps (each one leaves its result in work/minimal/gradle-<v>/, a step is skipped when its
result is already there; --clean starts over):
  1. src/      export of the tag (git archive), not a checkout
  2. tools/    ivy and groovy-all (the Groovy version of the target) from Maven Central
  3. deps/     the external dependencies of every module: gradle/dependencies.gradle evaluated with Groovy (stubbed
               Gradle API), the module build files scanned for their main configurations, resolved with Ivy
               (one Ivy configuration per module), group substitutions (resolutionStrategy) applied afterwards
  4. classes/  one joint Java/Groovy compilation of the main sources of all modules (groovyc -j)
  5. dist/     classes split back into one jar per module (by source file), resources, generated resources
               (classpath manifests, build receipt, plugin lists, default imports / api mapping, relocated
               package lists, embedded wrapper jar), lib/ (core runtime) and lib/plugins/, bin/gradle
Tests, docs, test-only and performance projects are left out. The Kotlin DSL (an external module) is left out:
the bootstrap only runs Groovy build scripts.

usage: minimal-bootstrap.py <tag> [--java JDK] [--clean]
  e.g. minimal-bootstrap.py v4.0.0   ->  target/gradle-4.0-minimal
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.join(ROOT, 'gradle')
CENTRAL = 'https://repo1.maven.org/maven2'
GRADLE_LIBS = 'https://repo.gradle.org/gradle/libs-releases'
IVY = ('org.apache.ivy', 'ivy', '2.5.2')
MAIN_CONFS = ('compile', 'runtime', 'compileOnly', 'api', 'implementation', 'runtimeOnly', 'publishCompile')
# projects that only hold tests, performance tests or the distribution build
LEFT_OUT = re.compile(r'^(internal-.*|integ-test|performance|smoke-test|soak|distributions|build-scan-performance)$')
# coordinates that are on no repository anymore under their original group (same version elsewhere)
MOVED = {'org.samba.jcifs:jcifs': 'jcifs:jcifs'}
# org/gradle classes and the JDK / Groovy / logging packages are not relocated (PackageListGenerator.DEFAULT_EXCLUDES)
RELOCATION_EXCLUDES = ['org/gradle', 'java', 'javax', 'groovy', 'groovyjarjarantlr', 'net/rubygrapefruit',
                       'org/codehaus/groovy', 'org/apache/tools/ant', 'org/apache/commons/logging', 'org/slf4j',
                       'org/apache/log4j', 'org/apache/xerces', 'org/w3c/dom', 'org/xml/sax']


def log(msg):
    print(f'[minimal] {msg}', flush=True)


def run(cmd, **kw):
    r = subprocess.run(cmd, **kw)
    if r.returncode != 0:
        sys.exit(f'failed ({r.returncode}): {" ".join(cmd[:6])} ...\n' + ((r.stderr or '')[-3000:] if kw.get('capture_output') else ''))
    return r


def kebab(name):
    return re.sub(r'(?<!^)(?=[A-Z])', '-', name).lower()


def download(group, artifact, version, dest_dir):
    path = os.path.join(dest_dir, f'{artifact}-{version}.jar')
    if not os.path.isfile(path):
        url = f'{CENTRAL}/{group.replace(".", "/")}/{artifact}/{version}/{artifact}-{version}.jar'
        log(f'download {url}')
        os.makedirs(dest_dir, exist_ok=True)
        with urllib.request.urlopen(url, timeout=120) as r, open(path + '.part', 'wb') as f:
            f.write(r.read())
        os.replace(path + '.part', path)
    return path


class Build:
    def __init__(self, tag, java):
        self.tag = tag
        self.java = java
        self.base_version = re.sub(r'^(v|REL_|REL-)', '', tag).removesuffix('.0') if tag.count('.') > 1 else re.sub(r'^(v|REL_|REL-)', '', tag)
        self.work = os.path.join(ROOT, 'work', 'minimal', f'gradle-{self.base_version}')
        self.src = os.path.join(self.work, 'src')
        self.timestamp = time.strftime('%Y%m%d%H%M%S', time.gmtime())
        self.version = f'{self.base_version}-{self.timestamp}+0000'

    # 1. sources
    def export(self):
        if os.path.isdir(self.src):
            return
        log(f'export {self.tag} to {self.src}')
        os.makedirs(self.src)
        archive = subprocess.run(['git', '-C', REPO, 'archive', self.tag], capture_output=True, check=True).stdout
        subprocess.run(['tar', 'x', '-C', self.src], input=archive, check=True)

    def read(self, rel):
        p = os.path.join(self.src, rel)
        return open(p, encoding='utf-8').read() if os.path.isfile(p) else ''

    # 2. tools
    def tools(self):
        d = os.path.join(self.work, 'tools')
        self.ivy_jar = download(*IVY, d)
        groovy = re.search(r'versions\.groovy\s*=\s*["\']([^"\']+)', self.read('gradle/dependencies.gradle')).group(1)
        self.groovy_jar = download('org.codehaus.groovy', 'groovy-all', groovy, d)
        log(f'tools: ivy {IVY[2]}, groovy-all {groovy}')

    def groovy(self, script, *args, heap='1g'):
        cp = os.pathsep.join([self.groovy_jar, os.path.join(self.java, 'lib', 'tools.jar')])
        return run([os.path.join(self.java, 'bin', 'java'), f'-Xmx{heap}', '-cp', cp, 'groovy.ui.GroovyMain', script, *args],
                   capture_output=True, text=True)

    # 3. modules and dependencies
    def modules(self):
        mods = {}
        for d in sorted(os.listdir(os.path.join(self.src, 'subprojects'))):
            if LEFT_OUT.match(d):
                continue
            build_file = os.path.join('subprojects', d, f'{d}.gradle')
            mods[d] = {'build': self.read(build_file), 'projects': [], 'libs': [], 'coords': []}
        for d, m in mods.items():
            # classpathManifest { additionalProjects = [...] }: projects only listed in the classpath manifest
            for refs in re.findall(r'additionalProjects\s*=\s*\[([^\]]*)\]', m['build']):
                m['projects'] += [kebab(p) for p in re.findall(r"project\(['\"]:(\w+)['\"]\)", refs) if kebab(p) in mods]
            for line in m['build'].split('\n'):
                mm = re.match(r'\s*(\w+)\s*\(?\s*(.*)$', line)
                if not mm or mm.group(1) not in MAIN_CONFS:
                    continue
                args = mm.group(2)
                m['libs'] += re.findall(r'libraries\.(\w+)', args)
                for p in re.findall(r'project\(\s*(?:path:\s*)?["\']:(\w+)["\']', args):
                    if kebab(p) in mods and kebab(p) != d and kebab(p) not in m['projects']:
                        m['projects'].append(kebab(p))
                m['coords'] += [c for c in re.findall(r'["\']([\w.\-]+:[\w.\-]+:[\w.\-]+(?:@jar)?)["\']', args)]
        self.mods = mods

    def dependencies(self):
        out = os.path.join(self.work, 'deps', 'libraries.json')
        if not os.path.isfile(out):
            os.makedirs(os.path.dirname(out), exist_ok=True)
            script = os.path.join(self.work, 'deps', 'eval-dependencies.groovy')
            with open(script, 'w') as f:
                f.write(EVAL_DEPENDENCIES)
            r = self.groovy(script, os.path.join(self.src, 'gradle', 'dependencies.gradle'))
            with open(out, 'w') as f:
                f.write(r.stdout)
        self.libraries = json.load(open(out))
        # resolutionStrategy.eachDependency: requested group -> libraries.x
        text = self.read('gradle/dependencies.gradle')
        self.substitutions = {g: self.libraries[l][0]['coord'] for g, l in
                              re.findall(r"requested\.group\s*==\s*'([^']+)'\s*\)\s*\{\s*details\.useTarget\(libraries\.(\w+)\)", text)}
        log(f'{len(self.libraries)} libraries, substitutions: {self.substitutions}')
        for entries in self.libraries.values():
            for e in entries:
                ga, _, v = e['coord'].rpartition(':')
                if ga in MOVED:
                    e['coord'] = f'{MOVED[ga]}:{v}'
        for d, m in self.mods.items():
            entries = []
            for lib in m['libs']:
                if lib not in self.libraries:
                    sys.exit(f'{d}: unknown libraries.{lib}')
                entries += self.libraries[lib]
            entries += [{'coord': c.removesuffix('@jar'), 'transitive': not c.endswith('@jar')} for c in m['coords']]
            m['deps'] = entries

    def resolve(self):
        deps_dir = os.path.join(self.work, 'deps', 'jars')
        if not os.path.isdir(deps_dir):
            by_coord = {}
            for d, m in self.mods.items():
                for e in m['deps']:
                    c = by_coord.setdefault(e['coord'], {'confs': [], 'transitive': False})
                    if d not in c['confs']:
                        c['confs'].append(d)
                    c['transitive'] |= e['transitive']
            confs = ''.join(f'<conf name="{d}"/>' for d in self.mods)
            lines = []
            for coord, c in sorted(by_coord.items()):
                g, a, v = coord.split(':')[:3]
                lines.append(f'<dependency org="{g}" name="{a}" rev="{v}" transitive="{str(c["transitive"]).lower()}" '
                             f'conf="{";".join(x + "->default" for x in c["confs"])}"/>')
            ivy_xml = os.path.join(self.work, 'deps', 'ivy.xml')
            with open(ivy_xml, 'w') as f:
                f.write(f'<ivy-module version="2.0"><info organisation="org.gradle" module="gradle-bootstrap"/>'
                        f'<configurations>{confs}</configurations><dependencies>\n' + '\n'.join(lines) + '\n</dependencies></ivy-module>\n')
            settings = os.path.join(self.work, 'deps', 'ivysettings.xml')
            with open(settings, 'w') as f:
                f.write(f'<ivysettings><settings defaultResolver="chain"/><resolvers><chain name="chain">'
                        f'<ibiblio name="central" m2compatible="true" root="{CENTRAL}/"/>'
                        f'<ibiblio name="gradle" m2compatible="true" root="{GRADLE_LIBS}/"/>'
                        f'</chain></resolvers></ivysettings>\n')
            log(f'resolve {len(by_coord)} dependencies for {len(self.mods)} modules with ivy')
            r = subprocess.run([os.path.join(self.java, 'bin', 'java'), '-jar', self.ivy_jar, '-settings', settings,
                                '-ivy', ivy_xml, '-cache', os.path.join(self.work, 'deps', 'ivy-cache'), '-confs', *self.mods,
                                '-retrieve', os.path.join(deps_dir, '[conf]', '[organisation]', '[artifact]-[revision](-[classifier]).[ext]'),
                                '-types', 'jar', 'bundle'], capture_output=True, text=True)
            with open(os.path.join(self.work, 'deps', 'ivy.log'), 'w') as f:
                f.write(r.stdout + r.stderr)
            if r.returncode != 0 or 'UNRESOLVED DEPENDENCIES' in r.stdout:
                sys.exit('ivy resolution failed, see ' + os.path.join(self.work, 'deps', 'ivy.log') + '\n' +
                         '\n'.join(l for l in r.stdout.split('\n') if '::' in l or 'not found' in l)[-3000:])
        # per module: jar files, with the group substitutions
        sub_files = {}
        for g, coord in self.substitutions.items():
            sg, sa, sv = coord.split(':')[:3]
            for root, _, files in os.walk(deps_dir):
                for fn in files:
                    if root.endswith(os.sep + sg) and fn == f'{sa}-{sv}.jar':
                        sub_files[g] = os.path.join(root, fn)
        for d, m in self.mods.items():
            jars = {}
            mdir = os.path.join(deps_dir, d)
            if os.path.isdir(mdir):
                for org in sorted(os.listdir(mdir)):
                    for fn in sorted(os.listdir(os.path.join(mdir, org))):
                        p = os.path.join(mdir, org, fn)
                        if org in self.substitutions and p != sub_files.get(org) and not fn.startswith(self.substitutions[org].split(':')[1] + '-'):
                            p = sub_files[org]
                        jars[os.path.basename(p)] = p
            m['jars'] = jars

    # 4. compilation
    def compile(self):
        out = os.path.join(self.work, 'classes')
        self.sources = {}
        for d in self.mods:
            for lang in ('java', 'groovy'):
                base = os.path.join(self.src, 'subprojects', d, 'src', 'main', lang)
                for root, _, files in os.walk(base):
                    for fn in files:
                        if fn.endswith(('.java', '.groovy')) and fn != 'package-info.java':
                            p = os.path.join(root, fn)
                            self.sources[p] = (d, os.path.relpath(p, base).rsplit('.', 1)[0])
        if os.path.isfile(os.path.join(out, '.done')):
            return
        shutil.rmtree(out, ignore_errors=True)
        os.makedirs(out)
        # modules in dependency order (base modules first): their jars come first on the single classpath, so a fat
        # jar of a high level module (e.g. sbt's incremental-compiler bundles jansi) does not shadow a base library
        order = []
        def visit(d, stack=()):
            if d in order or d in stack:
                return
            for p in self.mods[d]['projects']:
                visit(p, stack + (d,))
            order.append(d)
        for d in self.mods:
            visit(d)
        keep = set(newest({p for m in self.mods.values() for p in m['jars'].values()}))
        jars = []
        for d in order:
            jars += [p for p in self.mods[d]['jars'].values() if p in keep and p not in jars]
        cp = os.pathsep.join(jars + [os.path.join(self.java, 'lib', 'tools.jar')])
        listing = os.path.join(self.work, 'sources.txt')
        with open(listing, 'w') as f:
            f.write('\n'.join(sorted(self.sources)) + '\n')
        with open(os.path.join(self.work, 'classpath.txt'), 'w') as f:
            f.write(cp)
        script = os.path.join(self.work, 'compile.groovy')
        with open(script, 'w') as f:
            f.write(COMPILE)
        log(f'compile {len(self.sources)} sources ({len(jars)} jars on the classpath)')
        r = subprocess.run([os.path.join(self.java, 'bin', 'java'), '-Xmx4g', '-cp',
                            os.pathsep.join([self.groovy_jar, os.path.join(self.java, 'lib', 'tools.jar')]),
                            'groovy.ui.GroovyMain', script, listing, os.path.join(self.work, 'classpath.txt'), out],
                           capture_output=True, text=True)
        with open(os.path.join(self.work, 'compile.log'), 'w') as f:
            f.write(r.stdout + r.stderr)
        if r.returncode != 0:
            sys.exit('compilation failed, see ' + os.path.join(self.work, 'compile.log') + '\n' + (r.stdout + r.stderr)[-4000:])
        open(os.path.join(out, '.done'), 'w').close()

    # 5. distribution
    def split_classes(self):
        out = os.path.join(self.work, 'classes')
        owner = {rel: d for d, rel in self.sources.values()}
        by_pkg = {}
        for d, rel in self.sources.values():
            by_pkg.setdefault(os.path.dirname(rel), set()).add(d)
        classes = {d: [] for d in self.mods}
        orphans = []
        for root, _, files in os.walk(out):
            for fn in files:
                if not fn.endswith('.class'):
                    continue
                rel = os.path.relpath(os.path.join(root, fn), out)
                base = rel[:-6].split('$')[0]
                d = owner.get(base)
                if d is None:
                    mods = by_pkg.get(os.path.dirname(base), set())
                    d = next(iter(mods)) if len(mods) == 1 else self.find_declaring_module(base, mods)
                if d is None:
                    orphans.append(rel)
                else:
                    classes[d].append(rel)
        if orphans:
            sys.exit(f'classes without a module: {orphans[:10]}')
        return classes

    def find_declaring_module(self, base, mods):
        name = os.path.basename(base)
        for p, (d, rel) in self.sources.items():
            if d in mods and os.path.dirname(rel) == os.path.dirname(base):
                if re.search(rf'\b(class|interface|enum)\s+{re.escape(name)}\b', open(p, encoding='utf-8').read()):
                    return d
        return None

    def project_closure(self, roots):
        seen = []
        todo = list(roots)
        while todo:
            d = todo.pop(0)
            if d in seen or d not in self.mods:
                continue
            seen.append(d)
            todo += self.mods[d]['projects']
        return seen

    def distribution(self):
        dist = os.path.join(self.work, 'dist')
        shutil.rmtree(dist, ignore_errors=True)
        os.makedirs(os.path.join(dist, 'lib', 'plugins'))
        os.makedirs(os.path.join(dist, 'bin'))
        classes = self.split_classes()
        root_build = self.read('build.gradle')
        core_roots = [kebab(p) for p in re.findall(r"^\s*(?:coreRuntime|runtime)\s+project\(['\"]:(\w+)['\"]\)", root_build, re.M)]
        core = self.project_closure(core_roots)
        log(f'lib/: {len(core)} modules ({", ".join(core_roots)} and their project dependencies), lib/plugins/: {len(self.mods) - len(core)}')
        generated = {d: {} for d in self.mods}
        # classpath manifests
        for d, m in self.mods.items():
            generated[d][f'gradle-{d}-classpath.properties'] = (
                f'projects={",".join("gradle-" + p for p in m["projects"])}\nruntime={",".join(m["jars"])}\n')
        # build receipt
        commit = subprocess.run(['git', '-C', REPO, 'rev-list', '-n1', self.tag], capture_output=True, text=True).stdout.strip()
        generated['base-services']['org/gradle/build-receipt.properties'] = (
            f'baseVersion={self.base_version}\ncommitId={commit}\nbuildTimestamp={self.timestamp}+0000\n'
            f'versionNumber={self.version}\nisSnapshot=true\n')
        # plugin lists (core.gradle pluginsManifest / implementationPluginsManifest)
        for var, fn in (('pluginProjects', 'gradle-plugins.properties'), ('implementationPluginProjects', 'gradle-implementation-plugins.properties')):
            mm = re.search(rf'{var}\s*=\s*\[([^\]]*)\]', root_build)
            if mm:
                names = sorted('gradle-' + kebab(n) for n in re.findall(r"['\"](\w+)['\"]", mm.group(1)))
                generated['core'][fn] = f'plugins={",".join(names)}\n'
        # default imports and api mapping (docs project)
        imports, mapping = self.default_imports()
        generated.setdefault('docs', {})
        generated['docs']['default-imports.txt'] = imports
        generated['docs']['api-mapping.txt'] = mapping
        # relocated packages (runtime-api-info)
        all_jars = sorted({p for m in self.mods.values() for p in m['jars'].values()})
        core_jars = sorted({p for d in core for p in self.mods[d]['jars'].values()})
        if 'runtime-api-info' in self.mods:
            generated['runtime-api-info']['org/gradle/api/internal/runtimeshaded/api-relocated.txt'] = relocated_packages(all_jars)
            generated['runtime-api-info']['org/gradle/api/internal/runtimeshaded/test-kit-relocated.txt'] = relocated_packages(core_jars)
        # jars
        cls_dir = os.path.join(self.work, 'classes')
        jar_paths = {}
        for d in self.mods:
            target = os.path.join(dist, 'lib' if d in core else os.path.join('lib', 'plugins'), f'gradle-{d}-{self.version}.jar')
            jar_paths[d] = target
            attrs = {'Implementation-Title': 'Gradle', 'Implementation-Version': self.version}
            if d == 'launcher':
                cp_line = re.search(r"'Class-Path':\s*\"([^\"]+)\"", self.mods[d]['build'])
                refs = re.findall(r"project\(['\"]:(\w+)['\"]\)", cp_line.group(1)) if cp_line else ['core', 'baseServices']
                attrs['Class-Path'] = ' '.join(f'gradle-{kebab(r)}-{self.version}.jar' for r in refs)
                attrs['Main-Class'] = 'org.gradle.launcher.GradleMain'
            write_jar(target, attrs, [(os.path.join(cls_dir, c), c) for c in sorted(classes[d])],
                      self.resources(d), generated.get(d, {}))
        # gradle-wrapper.jar embedded in the project that copies :wrapper's executableJar (plugins)
        for d, m in self.mods.items():
            if re.search(r'project\(["\']:wrapper["\']\)\.executableJar', m['build']):
                wrapper_jar = os.path.join(self.work, 'gradle-wrapper.jar')
                parts = ['wrapper'] + self.mods['wrapper']['projects']
                write_jar(wrapper_jar, {}, [(os.path.join(cls_dir, c), c) for p in parts for c in sorted(classes[p])],
                          [r for p in parts for r in self.resources(p)], {})
                append_to_jar(jar_paths[d], 'gradle-wrapper.jar', wrapper_jar)
        # external jars: lib/ for the core runtime, lib/plugins/ for the rest
        core_names = set()
        for d in core:
            for fn, p in self.mods[d]['jars'].items():
                shutil.copy(p, os.path.join(dist, 'lib', fn))
                core_names.add(fn)
        for d, m in self.mods.items():
            for fn, p in m['jars'].items():
                if fn not in core_names:
                    shutil.copy(p, os.path.join(dist, 'lib', 'plugins', fn))
        with open(os.path.join(dist, 'bin', 'gradle'), 'w') as f:
            f.write(START_SCRIPT.replace('@LAUNCHER@', f'gradle-launcher-{self.version}.jar'))
        os.chmod(os.path.join(dist, 'bin', 'gradle'), 0o755)
        return dist

    def resources(self, d):
        base = os.path.join(self.src, 'subprojects', d, 'src', 'main', 'resources')
        res = []
        for root, _, files in os.walk(base):
            for fn in files:
                p = os.path.join(root, fn)
                res.append((p, os.path.relpath(p, base)))
        return res

    def default_imports(self):
        """docs.gradle: defaultImports (GenerateDefaultImportsTask) over the javadocAll sources of the public projects"""
        docs = self.read('subprojects/docs/docs.gradle')
        jd = re.search(r'task javadocAll[^{]*\{(.*?)\n\}', docs, re.S).group(1)
        includes = re.findall(r"^\s*include\s+'([^']+)'", jd, re.M)
        excludes = re.findall(r"^\s*exclude\s+'([^']+)'", jd, re.M)
        di = re.search(r'task defaultImports[^{]*\{(.*?)\n\}', docs, re.S).group(1)
        excluded = re.findall(r"excludePackage\s+'([^']+)'", di)
        extra = re.findall(r"extraPackage\s+'([^']+)'", di)
        packages, names = set(extra), {}
        for p, (d, rel) in self.sources.items():
            path = rel + '.' + p.rsplit('.', 1)[1]
            if not any(glob_match(i, path) for i in includes) or any(glob_match(e, path) for e in excludes):
                continue
            pkg = os.path.dirname(rel).replace('/', '.')
            if any(pkg == e[:-3] or pkg.startswith(e[:-2]) if e.endswith('.**') else pkg == e for e in excluded):
                continue
            packages.add(pkg)
            names.setdefault(os.path.basename(rel), []).append(pkg + '.' + os.path.basename(rel))
        imports = ''.join(f'import {p}.*\n' for p in sorted(packages))
        mapping = ''.join(f'{n}:' + ''.join(c + ';' for c in sorted(cs)) + '\n' for n, cs in sorted(names.items()))
        return imports, mapping


def version_key(v):
    return [(0, int(x), '') if x.isdigit() else (1, 0, x) for x in re.split(r'[.\-]', v)]


def newest(jars):
    """one jar per organisation/artifact (the newest version), like the conflict resolution of a single classpath"""
    best = {}
    for p in jars:
        org = os.path.basename(os.path.dirname(p))
        mm = re.match(r'^(.+?)-(\d.*)\.jar$', os.path.basename(p))
        key, v = ((org, mm.group(1)), mm.group(2)) if mm else ((org, os.path.basename(p)), '')
        if key not in best or version_key(v) > version_key(best[key][0]):
            best[key] = (v, p)
    return sorted(p for _, p in best.values())


def glob_match(pattern, path):
    """ant style include pattern (** any directories, * within one directory)"""
    rx = re.escape(pattern).replace(r'\*\*/', '(.*/)?').replace(r'\*\*', '.*').replace(r'\*', '[^/]*')
    return re.fullmatch(rx, path) is not None


def relocated_packages(jars):
    """PackageListGenerator + Trie.dump(false): the package directories of the classes, without the excluded
    prefixes, only the shortest words (a word that has another word as prefix is not listed), sorted"""
    words = set()
    for j in jars:
        with zipfile.ZipFile(j) as z:
            for n in z.namelist():
                if n.endswith('.class') and '/' in n:
                    pkg = n[:n.rindex('/')]
                    if not any(pkg.startswith(e) for e in RELOCATION_EXCLUDES):
                        words.add(pkg)
    out, last = [], None
    for w in sorted(words):
        if last is not None and w.startswith(last):
            continue
        out.append(w)
        last = w
    return ''.join(w + '\n' for w in out)


def write_jar(path, attrs, classes, resources, generated):
    manifest = 'Manifest-Version: 1.0\n' + ''.join(f'{k}: {v}\n' for k, v in attrs.items())
    seen = set()
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('META-INF/MANIFEST.MF', manifest.replace('\n', '\r\n'))
        seen.add('META-INF/MANIFEST.MF')
        for name, text in generated.items():
            z.writestr(name, text)
            seen.add(name)
        for src, name in list(classes) + list(resources):
            if name not in seen:
                z.write(src, name)
                seen.add(name)


def append_to_jar(jar, name, file):
    with zipfile.ZipFile(jar, 'a', zipfile.ZIP_DEFLATED) as z:
        z.write(file, name)


EVAL_DEPENDENCIES = r'''
// Evaluates gradle/dependencies.gradle with a stubbed Gradle API, prints the libraries as JSON:
// name -> [{coord, transitive}] (a client module is its own artifact plus the listed dependencies)
import groovy.json.JsonOutput

class ClientModule { String coord; List deps = []
    void dependency(Object d) { deps << d } }
class DependenciesStub {
    def module(String c) { new ClientModule(coord: c) }
    def module(String c, Closure cl) { def m = new ClientModule(coord: c); cl.delegate = m; cl.resolveStrategy = Closure.DELEGATE_FIRST; cl(); m }
    // create(coord) { exclude ... }: a plain transitive dependency, the excludes are not kept (simplification)
    def create(Object c) { c }
    def create(Object c, Closure cl) { c }
}
abstract class DependenciesScript extends Script {
    def dependencies = new DependenciesStub()
    void ext(Closure c) { c.delegate = this; c.resolveStrategy = Closure.DELEGATE_FIRST; c() }
    void allprojects(Closure c) {}
    void subprojects(Closure c) {}
    void configurations(Closure c) {}
}
def config = new org.codehaus.groovy.control.CompilerConfiguration(scriptBaseClass: DependenciesScript.name)
def binding = new Binding()
// another script name: a script named 'dependencies' makes dependencies.module() a static call on itself
new GroovyShell(this.class.classLoader, binding, config).evaluate(new File(args[0]).text, 'DependenciesGradle')
def flatten
flatten = { d ->
    if (d instanceof ClientModule) {
        return [[coord: d.coord.replaceAll(/@jar$/, ''), transitive: false]] + d.deps.collectMany { flatten(it) }
    }
    if (d instanceof Collection) { return d.collectMany { flatten(it) } }
    if (d instanceof Map) { d = "${d.group}:${d.name}:${d.version}" }
    def s = d.toString()
    return [[coord: s.replaceAll(/@jar$/, ''), transitive: !s.endsWith('@jar')]]
}
def libraries = binding.getVariable('libraries')
println JsonOutput.toJson(libraries.collectEntries { k, v -> [k, flatten(v)] })
'''

COMPILE = r'''
// Joint Java/Groovy compilation of all the main sources: args = source list file, classpath file, output dir
def sources = new File(args[0]).readLines().findAll { it }
def cp = new File(args[1]).text.trim()
def args2 = ['-j', '-Jsource=1.8', '-Jtarget=1.8', '-Jencoding=UTF-8', '--encoding', 'UTF-8',
             '-cp', cp, '-d', args[2]] + sources
org.codehaus.groovy.tools.FileSystemCompiler.commandLineCompile(args2 as String[], false)
'''

START_SCRIPT = r'''#!/usr/bin/env sh
# minimal start script of a bootstrap distribution (scripts/minimal-bootstrap.py)
APP_HOME=$(cd "$(dirname "$0")/.." && pwd -P)
JAVACMD=java
[ -n "$JAVA_HOME" ] && JAVACMD="$JAVA_HOME/bin/java"
exec "$JAVACMD" $JAVA_OPTS $GRADLE_OPTS -Dorg.gradle.appname=gradle -classpath "$APP_HOME/lib/@LAUNCHER@" org.gradle.launcher.GradleMain "$@"
'''


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('tag')
    p.add_argument('--java', default='/data/devel/jdk8u482-b08')
    p.add_argument('--clean', action='store_true')
    a = p.parse_args()
    b = Build(a.tag, a.java)
    if a.clean:
        shutil.rmtree(b.work, ignore_errors=True)
    b.export()
    b.tools()
    b.modules()
    b.dependencies()
    b.resolve()
    b.compile()
    dist = b.distribution()
    target = os.path.join(ROOT, 'target', f'gradle-{b.base_version}-minimal')
    shutil.rmtree(target, ignore_errors=True)
    shutil.copytree(dist, target, symlinks=True)
    log(f'distribution: {target} (version {b.version})')


if __name__ == '__main__':
    main()
