#!/usr/bin/env python3
"""Make an early (0.x) Gradle build file resolve from Maven Central.

- add the group to dependencies written without one (":junit:4.4" -> "junit:junit:4.4")
- replace the ivy betas / release candidates (not on Maven Central) with 2.0.0
- add the Maven Central resolver next to the flat dir resolver 'lib'

usage: patch-deps.py <build file> [repo method]
  repo method: the builder's method to add Maven Central, default addIBiblio
               (gradle 0.1), addMavenRepo from gradle 0.1.4 on
"""
import re
import sys

GROUPS = {
    'ant': 'org.apache.ant', 'ant-commons-net': 'org.apache.ant', 'ant-junit': 'org.apache.ant',
    'ant-launcher': 'org.apache.ant', 'ant-nodeps': 'org.apache.ant', 'ant-trax': 'org.apache.ant',
    'backport-util-concurrent': 'backport-util-concurrent',
    'cglib-nodep': 'cglib',
    'commons-cli': 'commons-cli', 'commons-codec': 'commons-codec', 'commons-httpclient': 'commons-httpclient',
    'commons-io': 'commons-io', 'commons-lang': 'commons-lang', 'commons-logging': 'commons-logging',
    'commons-net': 'commons-net', 'oro': 'oro',
    'dom4j': 'dom4j',
    'groovy-all': 'org.codehaus.groovy',
    'hamcrest-core': 'org.hamcrest', 'hamcrest-library': 'org.hamcrest',
    'ivy': 'org.apache.ivy',
    'jaxen': 'jaxen',
    'jmock': 'org.jmock', 'jmock-junit4': 'org.jmock', 'jmock-legacy': 'org.jmock',
    'jcl-over-slf4j': 'org.slf4j',
    'jetty': 'org.mortbay.jetty', 'jetty-naming': 'org.mortbay.jetty', 'jetty-plus': 'org.mortbay.jetty',
    'jetty-util': 'org.mortbay.jetty',
    'jopt-simple': 'net.sf.jopt-simple',
    'junit': 'junit',
    'logback-classic': 'ch.qos.logback', 'logback-core': 'ch.qos.logback', 'slf4j-api': 'org.slf4j',
    'bndlib': 'biz.aQute',
    'maven-ant-tasks': 'org.apache.maven',
    'objenesis': 'org.objenesis',
    'servlet-api': 'javax.servlet',
    'svnkit': 'org.tmatesoft.svnkit', 'svnkit-javahl': 'org.tmatesoft.svnkit',
    'retrotranslator-runtime': 'net.sf.retrotranslator', 'retrotranslator-transformer': 'net.sf.retrotranslator',
    'webdavlib': 'slide',
    'xmlunit': 'xmlunit',
}
# only in the flat dir 'lib' (not on Maven Central), left without group
# (svnkit is on Maven Central: org.tmatesoft.svnkit, 1.3.5 replaces 1.1.6)
FLAT_DIR_ONLY = ('launch4j-',)

path = sys.argv[1]
repo_method = sys.argv[2] if len(sys.argv) > 2 else 'addIBiblio'
s = open(path).read()
start = s.index('dependencies {')
end = s.index('\n}', start)
block = s[start:end]


def add_group(m):
    quote, name = m.group(1), m.group(2)
    if name.startswith(FLAT_DIR_ONLY):
        return m.group(0)
    if name not in GROUPS:
        sys.exit(f'unknown group for dependency {name}, add it to GROUPS')
    return quote + GROUPS[name] + ':' + name + ':'


block = re.sub(r'(["\']):([A-Za-z0-9._-]+):', add_group, block)
block = re.sub(r'ivy:2\.0\.0\.(beta2_20080305165542|cr1_20080911151837)', 'ivy:2.0.0', block)
block = block.replace('svnkit:1.1.6', 'svnkit:1.3.5').replace('svnkit-javahl:1.1.6', 'svnkit-javahl:1.3.5')
if repo_method + '()' not in block:
    block += '\n    ' + repo_method + '()'
open(path, 'w').write(s[:start] + block + s[end:])
