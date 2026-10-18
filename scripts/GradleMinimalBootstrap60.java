/*
 * Minimal bootstrap of Gradle 6.0 (copy of GradleMinimalBootstrap50.java, adapted to 6.0): compile its sources without its own build, into a raw distribution that
 * is only good enough to run the real build of the same version (Java version of scripts/minimal-bootstrap.py,
 * see TODO.adoc, scripts/minimal-bootstrap.py).
 *
 * No Gradle is used. Steps (each one leaves its result in <work>/gradle-<v>/, a step is skipped when its result is
 * already there; --clean starts over):
 *   1. src/      export of the tag (git archive), not a checkout
 *   2. tools/    ivy and groovy-all (the Groovy version of the target) from Maven Central
 *   3. deps/     the external dependencies of every module: gradle/dependencies.gradle evaluated with Groovy
 *                (stubbed Gradle API), the module build files scanned for their main configurations, resolved with
 *                Ivy (one Ivy configuration per module), group substitutions (resolutionStrategy) applied afterwards
 *   4. classes/  one joint Java/Groovy compilation of the main sources of all modules (groovyc -j)
 *   5. dist/     classes split back into one jar per module (by source file), resources, generated resources
 *                (classpath manifests, build receipt, plugin lists, default imports / api mapping, relocated package
 *                lists, embedded wrapper jar), lib/ (core runtime) and lib/plugins/, bin/gradle
 * Tests, docs, test-only and performance projects are left out.
 *
 * Changes for 5.0 (from GradleMinimalBootstrap40.java):
 * - libraries are maps [coordinates:, version:] (no client modules), module build files are .gradle.kts or .gradle
 *   (api(project(":x")), implementation(library("x"))); the library versions act as a platform: Ivy overrides
 * - Groovy: dependencies.gradle is evaluated with a fixed groovy-all (EVAL_GROOVY), the sources are compiled with the
 *   target's own Groovy (libraries.groovy, resolved like the other libraries)
 * - plugin lists and the public API includes come from buildSrc (Kotlin), read with regular expressions
 * - the Kotlin DSL is an external module (root externalModules): resolved, put in lib/, patched with a classpath
 *   manifest (as patchExternalModules), the CharsetProvider service entry removed from kotlin-compiler-embeddable
 * - component metadata rules of buildSrc (DependenciesMetadataRulesPlugin): DependencyRemovalByGroupRule /
 *   DependencyRemovalByNameRule: for a library("x") of the distribution, a global Ivy exclude (the library is often
 *   only reached transitively, e.g. maven-compat below maven-core: Gradle applies the rule wherever the module is);
 *   for a literal coordinate (test libraries: spock, cglib, littleproxy) an exclude nested in the dependency on it
 *   (their rules remove guava, ant... which the distribution needs elsewhere),
 *   ExcludeDependencies makes the library non transitive; gradle/dependency-management/capabilities.json: every
 *   providedBy module is excluded (Gradle replaces it with the selected module, which the distribution has: e.g.
 *   google-collections -> guava, commons-logging -> jcl-over-slf4j); MavenDependencyCleaningRule (keep only some
 *   dependencies of maven-core) is not reproduced: the bootstrap ships a few more maven jars than the original
 * Changes for 6.0 (from GradleMinimalBootstrap50.java):
 * - the Kotlin DSL is part of the sources: Kotlin modules (only Kotlin sources, no Java/Groovy code depends on them)
 *   compiled with kotlinc (kotlin-compiler-embeddable of the target's kotlinVersion, run in a child JVM, options of
 *   buildSrc configureKotlinCompilerForGradleBuild), one module at a time in dependency order, after the joint
 *   Java/Groovy compilation of the other modules
 * - futureKotlin("x") dependencies, isTransitive = false blocks, base.archivesBaseName (kotlin-compiler-embeddable)
 * - kotlin-compiler-embeddable: the jar of the Kotlin compiler without the excluded entries of its build file
 * - kotlin-dsl generated sources (buildSrc GenerateKotlinDependencyExtensions templates) and resources
 *   (gradle-kotlin-dsl-versions.properties, gradle-kotlin-dsl-embedded-kotlin.properties)
 * - an empty <module>-parameter-names.properties in every module jar; docs sources not compiled
 * - new generated resources: api-metadata (gradle-api-declaration.properties, an empty parameter names index),
 *   docs release-features.txt
 *
 * Java 8 source, only the JDK. Run from the project directory (it holds gradle/ and target/):
 *   JDK 8:   mkdir -p work/minimal-tools && javac -d work/minimal-tools scripts/GradleMinimalBootstrap60.java
 *            java -cp work/minimal-tools GradleMinimalBootstrap60 v4.0.0
 *   JDK 11+: java scripts/GradleMinimalBootstrap60.java v4.0.0
 * options: --java <JDK used to compile Gradle, default /data/devel/jdk8u482-b08>, --work <dir, default work/minimal>,
 *          --target <dir, default target/gradle-<v>-minimal>, --clean
 */

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class GradleMinimalBootstrap60 {

    static final String CENTRAL = "https://repo1.maven.org/maven2";
    static final String GRADLE_LIBS = "https://repo.gradle.org/gradle/libs-releases";
    static final String[] IVY = {"org.apache.ivy", "ivy", "2.5.2"};
    static final List<String> MAIN_CONFS = Arrays.asList("compile", "runtime", "compileOnly", "api", "implementation", "runtimeOnly", "publishCompile");
    // projects that only hold tests, performance tests or the distribution build
    static final Pattern LEFT_OUT = Pattern.compile("^(internal-.*|integ-test|performance|smoke-test|soak|distributions|distributions-dependencies|build-scan-performance|architecture-test|instant-execution-report|kotlin-dsl-integ-tests|kotlin-dsl-plugins|kotlin-dsl-test-fixtures|samples)$");
    // projects in the distribution whose sources are not compiled (docs: documentation tooling, only generated resources)
    static final Set<String> NO_SOURCES = Collections.singleton("docs");
    static final String KOTLIN_VERSION = "@KOTLIN@";  // placeholder in coordinates, replaced by dependencies.gradle kotlinVersion
    static final String KOTLIN_COMPILER = "kotlin-compiler";  // Ivy configuration of the Kotlin compiler (a tool)
    // Groovy used to evaluate gradle/dependencies.gradle (any Groovy 2.4 runs it); the sources are compiled with the
    // target's own Groovy
    static final String[] EVAL_GROOVY = {"org.codehaus.groovy", "groovy-all", "2.4.15"};
    static final String EXTERNAL_MODULES = "external-modules";
    static final String COMPILE_ONLY = "--compile-only";  // Ivy configuration of the compileOnly dependencies of a module
    // coordinates that are on no repository anymore under their original group (same version elsewhere)
    static final Map<String, String> MOVED = Collections.singletonMap("org.samba.jcifs:jcifs", "jcifs:jcifs");
    // versions on no repository anymore: the next version available (README.adoc: slightly newer allowed), as Ivy
    // overrides (they also apply to transitive dependencies)
    static final Map<String, String> UPGRADED = Collections.emptyMap();  // (5.0: kotlinx-metadata-jvm 0.0.4 -> 0.0.5)
    // org/gradle classes and the JDK / Groovy / logging packages are not relocated (PackageListGenerator.DEFAULT_EXCLUDES)
    static final List<String> RELOCATION_EXCLUDES = Arrays.asList("org/gradle", "java", "javax", "groovy", "groovyjarjarantlr",
        "net/rubygrapefruit", "org/codehaus/groovy", "org/apache/tools/ant", "org/apache/commons/logging", "org/slf4j",
        "org/apache/log4j", "org/apache/xerces", "org/w3c/dom", "org/xml/sax");

    // ---- state
    final Path root = Paths.get("").toAbsolutePath();
    final Path repo = root.resolve("gradle");
    final String tag;
    final Path java;
    final String baseVersion;
    final Path work;
    final Path src;
    final String timestamp;
    final String version;
    Path ivyJar;
    Path groovyJar;     // evaluates dependencies.gradle
    Path compilerJar;   // the target's Groovy (libraries.groovy), compiles the sources

    static class Dep {
        String coord;
        boolean transitive;
        Dep(String coord, boolean transitive) { this.coord = coord; this.transitive = transitive; }
    }

    static class Module {
        String build;
        List<String> projects = new ArrayList<>();
        List<String> libs = new ArrayList<>();
        List<String> coords = new ArrayList<>();
        List<String> optional = new ArrayList<>();  // classpathManifest optionalProjects
        String archive;     // jar / module name: gradle-<dir>, or base.archivesBaseName
        boolean kotlin;     // Kotlin sources only (compiled with kotlinc)
        // compileOnly dependencies: on the compile classpath only, not in the classpath manifest nor the distribution
        List<String> compileOnlyLibs = new ArrayList<>();
        List<String> compileOnlyCoords = new ArrayList<>();
        List<Dep> compileOnlyDeps = new ArrayList<>();
        List<Path> compileOnlyJars = new ArrayList<>();
        List<Dep> deps = new ArrayList<>();
        Map<String, Path> jars = new LinkedHashMap<>();  // file name -> file
    }

    static class Source {
        final String module;
        final String rel;  // path in the source root, without extension
        Source(String module, String rel) { this.module = module; this.rel = rel; }
    }

    final Map<String, Module> mods = new TreeMap<>();
    String kotlinVersion;
    final List<Path> kotlinCompilerJars = new ArrayList<>();
    final Map<String, String> embeddedKotlin = new TreeMap<>();  // group:artifact -> version (stdlib-jdk8 + reflect closure)
    Map<String, List<Dep>> libraries;
    final Map<String, String> substitutions = new LinkedHashMap<>();
    // "group:name" of a library -> {"org" or "module", value}: component metadata removal rules
    final Map<String, List<String[]>> ivyExcludes = new HashMap<>();
    final List<String[]> globalExcludes = new ArrayList<>();
    final Set<String> nonTransitive = new HashSet<>();     // "group:name": ExcludeDependencies rule
    final Map<Path, Source> sources = new TreeMap<>();
    final Map<String, Path> externalJars = new TreeMap<>();  // the external modules (Kotlin DSL) and their dependencies
    // external module (artifact) -> the jar names of its own transitive dependencies (its classpath manifest)
    final Map<String, Set<String>> externalClosures = new TreeMap<>();

    GradleMinimalBootstrap60(String tag, Path java, Path workRoot) {
        this.tag = tag;
        this.java = java;
        String v = tag.replaceFirst("^(v|REL_|REL-)", "");
        this.baseVersion = count(tag, '.') > 1 && v.endsWith(".0") ? v.substring(0, v.length() - 2) : v;
        this.work = workRoot.resolve("gradle-" + baseVersion);
        this.src = work.resolve("src");
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        this.timestamp = f.format(new Date());
        this.version = baseVersion + "-" + timestamp + "+0000";
    }

    // ---- 1. sources
    void export() throws Exception {
        if (Files.isDirectory(src)) {
            return;
        }
        log("export " + tag + " to " + src);
        Files.createDirectories(src);
        Path tar = work.resolve("src.tar");
        exec(null, "git", "-C", repo.toString(), "archive", "-o", tar.toString(), tag);
        exec(null, "tar", "xf", tar.toString(), "-C", src.toString());
        Files.delete(tar);
    }

    String read(String rel) throws IOException {
        Path p = src.resolve(rel);
        return Files.isRegularFile(p) ? new String(Files.readAllBytes(p), StandardCharsets.UTF_8) : "";
    }

    // ---- 2. tools
    void tools() throws Exception {
        Path d = work.resolve("tools");
        ivyJar = download(IVY[0], IVY[1], IVY[2], d);
        groovyJar = download(EVAL_GROOVY[0], EVAL_GROOVY[1], EVAL_GROOVY[2], d);
        log("tools: ivy " + IVY[2] + ", groovy-all " + EVAL_GROOVY[2] + " (evaluation)");
    }

    Path download(String group, String artifact, String version, Path dir) throws Exception {
        Path path = dir.resolve(artifact + "-" + version + ".jar");
        if (!Files.isRegularFile(path)) {
            String url = CENTRAL + "/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".jar";
            log("download " + url);
            Files.createDirectories(dir);
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(60000);
            c.setReadTimeout(120000);
            Path part = dir.resolve(path.getFileName() + ".part");
            try (InputStream in = c.getInputStream()) {
                Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(part, path, StandardCopyOption.REPLACE_EXISTING);
        }
        return path;
    }

    String javaCmd() {
        return java.resolve("bin").resolve("java").toString();
    }

    String toolsJar() {
        return java.resolve("lib").resolve("tools.jar").toString();
    }

    // ---- 3. modules and dependencies
    void modules() throws IOException {
        try (Stream<Path> dirs = Files.list(src.resolve("subprojects"))) {
            for (Path p : dirs.sorted().collect(Collectors.toList())) {
                String d = p.getFileName().toString();
                if (LEFT_OUT.matcher(d).matches()) {
                    continue;
                }
                Module m = new Module();
                String kts = read("subprojects/" + d + "/" + d + ".gradle.kts");
                m.build = kts.isEmpty() ? read("subprojects/" + d + "/" + d + ".gradle") : kts;
                Matcher an = Pattern.compile("base\\.archivesBaseName\\s*=\\s*\"([^\"]+)\"").matcher(m.build);
                m.archive = an.find() ? an.group(1).replace("$kotlinVersion", KOTLIN_VERSION) : "gradle-" + d;
                Path main = p.resolve("src").resolve("main");
                m.kotlin = !walk(main.resolve("kotlin")).isEmpty() && walk(main.resolve("java")).isEmpty() && walk(main.resolve("groovy")).isEmpty();
                mods.put(d, m);
            }
        }
        Pattern confLine = Pattern.compile("^\\s*(\\w+)\\s*\\(?\\s*(.*)$");
        for (Map.Entry<String, Module> e : mods.entrySet()) {
            String d = e.getKey();
            Module m = e.getValue();
            // classpathManifest { additionalProjects = [...] }: projects only listed in the classpath manifest
            for (String refs : findAll("additionalProjects\\s*=\\s*(?:listOf\\(|\\[)([^\\]\\n]*)", m.build)) {
                for (String p : findAll("project\\(['\"]:(\\w+)['\"]\\)", refs)) {
                    if (mods.containsKey(kebab(p))) {
                        m.projects.add(kebab(p));
                    }
                }
            }
            for (String ref : findAll("additionalProjects\\.add\\(\"(:\\w+)\"\\)", m.build)) {  // 6.0 form
                if (mods.containsKey(kebab(ref.substring(1))) && !m.projects.contains(kebab(ref.substring(1)))) {
                    m.projects.add(kebab(ref.substring(1)));
                }
            }
            for (String refs : findAll("optionalProjects\\s*=\\s*(?:listOf\\(|\\[)([^\\]\\n]*)", m.build)) {
                m.optional.addAll(findAll("['\"]([\\w-]+)['\"]", refs));
            }
            for (String ref : findAll("optionalProjects\\.add\\(\"([^\"]+)\"\\)", m.build)) {
                m.optional.add(ref.startsWith(":") ? "gradle-" + kebab(ref.substring(1)) : ref);  // archive names, see distribution()
            }
            String[] lines = m.build.split("\n");
            for (int li = 0; li < lines.length; li++) {
                String line = lines[li];
                Matcher mm = confLine.matcher(line);
                if (!mm.matches() || !MAIN_CONFS.contains(mm.group(1))) {
                    continue;
                }
                String args = mm.group(2);
                boolean compileOnly = mm.group(1).equals("compileOnly");
                List<String> libs = compileOnly ? m.compileOnlyLibs : m.libs;
                libs.addAll(findAll("libraries\\.(\\w+)", args));
                libs.addAll(findAll("\\blibrary\\(\\s*[\"'](\\w+)[\"']\\s*\\)", args));
                for (String p : findAll("project\\(\\s*(?:path:\\s*)?[\"']:(\\w+)[\"']", args)) {
                    String k = kebab(p);
                    if (mods.containsKey(k) && !k.equals(d) && !m.projects.contains(k)) {
                        m.projects.add(k);
                    }
                }
                // dependency { isTransitive = false } on the next lines: artifact only (@jar)
                boolean jarOnly = false;
                if (line.trim().endsWith("{")) {
                    for (int k = li + 1; k < lines.length && !lines[k].trim().startsWith("}"); k++) {
                        jarOnly |= lines[k].contains("isTransitive = false");
                    }
                }
                List<String> found = findAll("[\"']([\\w.\\-]+:[\\w.\\-]+:[\\w.\\-]+(?:@jar)?)[\"']", args);
                for (String k : findAll("\\bfutureKotlin\\(\\s*\"([\\w-]+)\"\\s*\\)", args)) {
                    found.add("org.jetbrains.kotlin:kotlin-" + k + ":" + KOTLIN_VERSION);
                }
                for (String c : found) {
                    (compileOnly ? m.compileOnlyCoords : m.coords).add(jarOnly && !c.endsWith("@jar") ? c + "@jar" : c);
                }
            }
        }
    }

    void dependencies() throws Exception {
        Path out = work.resolve("deps").resolve("libraries.tsv");
        if (!Files.isRegularFile(out)) {
            Files.createDirectories(out.getParent());
            Path script = work.resolve("deps").resolve("eval-dependencies.groovy");
            write(script, EVAL_DEPENDENCIES);
            try {
                exec(out, javaCmd(), "-Xmx1g", "-cp", groovyJar + File.pathSeparator + toolsJar(), "groovy.ui.GroovyMain",
                    script.toString(), src.resolve("gradle").resolve("dependencies.gradle").toString());
            } catch (IllegalStateException e) {
                Files.deleteIfExists(out);  // a failed evaluation must not be reused by the next run
                throw e;
            }
        }
        libraries = new LinkedHashMap<>();
        for (String line : Files.readAllLines(out, StandardCharsets.UTF_8)) {
            String[] f = line.split("\t");
            if (f.length != 3) {
                continue;
            }
            String coord = f[1];
            int colon = coord.lastIndexOf(':');
            String ga = coord.substring(0, colon);
            if (MOVED.containsKey(ga)) {
                coord = MOVED.get(ga) + coord.substring(colon);
            }
            libraries.computeIfAbsent(f[0], k -> new ArrayList<>()).add(new Dep(coord, Boolean.parseBoolean(f[2])));
        }
        // resolutionStrategy.eachDependency: requested group -> libraries.x
        Matcher m = Pattern.compile("requested\\.group\\s*==\\s*'([^']+)'\\s*\\)\\s*\\{\\s*details\\.useTarget\\(libraries\\.(\\w+)\\)")
            .matcher(read("gradle/dependencies.gradle"));
        while (m.find()) {
            substitutions.put(m.group(1), libraries.get(m.group(2)).get(0).coord);
        }
        // component metadata rules (buildSrc DependenciesMetadataRulesPlugin), see the header
        String rules = buildSrcKotlin();
        Matcher rule = Pattern.compile("withLibraryDependencies\\(\\s*(?:library\\(\"(\\w+)\"\\)|\"([^\"]+)\")\\s*,\\s*DependencyRemovalBy(Group|Name)Rule::class\\s*,\\s*setOf\\(([^)]*)\\)\\)").matcher(rules);
        while (rule.find()) {
            for (String v : findAll("\"([^\"]+)\"", rule.group(4))) {
                String[] x = {rule.group(3).equals("Group") ? "org" : "module", v};
                if (rule.group(1) != null) {
                    globalExcludes.add(x);
                } else {
                    ivyExcludes.computeIfAbsent(rule.group(2), k -> new ArrayList<>()).add(x);
                }
            }
        }
        // capabilities.json (lenient JSON with // comments): the providedBy modules are replaced by the selected one
        for (String providers : findAll("\"providedBy\"\\s*:\\s*\\[([^\\]]*)\\]", read("gradle/dependency-management/capabilities.json"))) {
            for (String ga : findAll("\"([^\":]+:[^\"]+)\"", providers)) {
                String[] x = ga.split(":");
                globalExcludes.add(new String[]{"org", x[0], "module", x[1]});
            }
        }
        nonTransitive.addAll(findAll("withModule\\(\\s*\"([^\"]+)\"\\s*,\\s*ExcludeDependencies::class", rules));
        // kotlinVersion (ext of dependencies.gradle) replaces the placeholder of futureKotlin() and archive names
        kotlinVersion = find("kotlinVersion\\s*=\\s*['\"]([^'\"]+)['\"]", read("gradle/dependencies.gradle"), 0);
        for (Module mod : mods.values()) {
            mod.archive = mod.archive.replace(KOTLIN_VERSION, kotlinVersion);
            mod.coords.replaceAll(c -> c.replace(KOTLIN_VERSION, kotlinVersion));
            mod.compileOnlyCoords.replaceAll(c -> c.replace(KOTLIN_VERSION, kotlinVersion));
        }
        log(libraries.size() + " libraries, kotlin " + kotlinVersion + ", substitutions: " + substitutions + ", excludes: "
            + globalExcludes.stream().map(x -> x[0] + "=" + x[1]).collect(Collectors.joining(" ")) + "; nested: " + ivyExcludes.entrySet().stream().map(x -> x.getKey() + x.getValue().stream().map(v -> " " + v[0] + "=" + v[1]).collect(Collectors.joining())).collect(Collectors.joining("; ")) + ", non transitive: " + nonTransitive);
        for (Map.Entry<String, Module> e : mods.entrySet()) {
            Module mod = e.getValue();
            for (String lib : mod.compileOnlyLibs) {
                mod.compileOnlyDeps.addAll(libraries.get(lib));
            }
            for (String c : mod.compileOnlyCoords) {
                mod.compileOnlyDeps.add(new Dep(c.replaceFirst("@jar$", ""), !c.endsWith("@jar")));
            }
            for (String lib : mod.libs) {
                if (!libraries.containsKey(lib)) {
                    throw new IllegalStateException(e.getKey() + ": unknown libraries." + lib);
                }
                mod.deps.addAll(libraries.get(lib));
            }
            for (String c : mod.coords) {
                boolean jarOnly = c.endsWith("@jar");
                String coord = jarOnly ? c.substring(0, c.length() - 4) : c;
                int colon = coord.lastIndexOf(':');
                String ga = coord.substring(0, colon);
                if (MOVED.containsKey(ga)) {
                    coord = MOVED.get(ga) + coord.substring(colon);
                }
                mod.deps.add(new Dep(coord, !jarOnly));
            }
        }
    }

    /** root externalModules("g:a:${BuildEnvironment.x}"): the external modules of the distribution (Kotlin DSL) */
    List<String> externalModules() throws IOException {
        List<String> coords = new ArrayList<>();
        String root = read("build.gradle.kts") + read("build.gradle");
        for (String c : findAll("externalModules\\(?\\s*\"([^\"]+)\"", root)) {
            Matcher m = Pattern.compile("\\$\\{\\w+\\.(\\w+)\\}").matcher(c);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(buildSrcValue(m.group(1))));
            }
            m.appendTail(sb);
            coords.add(sb.toString());
        }
        return coords;
    }

    /** the value of a constant (val name = "value") in the buildSrc Kotlin sources */
    String buildSrcValue(String name) throws IOException {
        for (Path p : walk(src.resolve("buildSrc"))) {
            if (p.toString().endsWith(".kt")) {
                Matcher m = Pattern.compile("\\bval\\s+" + name + "\\s*=\\s*\"([^\"]+)\"").matcher(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        throw new IllegalStateException("no val " + name + " in buildSrc");
    }

    void resolve() throws Exception {
        Path depsDir = work.resolve("deps").resolve("jars");
        if (!Files.isDirectory(depsDir)) {
            Map<String, List<String>> confsByCoord = new TreeMap<>();
            Map<String, Boolean> transitiveByCoord = new HashMap<>();
            for (String c : externalModules()) {
                confsByCoord.computeIfAbsent(c, k -> new ArrayList<>()).add(EXTERNAL_MODULES + "--" + c.split(":")[1]);
                transitiveByCoord.put(c, true);
            }
            // the Kotlin compiler (to compile the Kotlin modules, and the jar of kotlin-compiler-embeddable), and the
            // embedded Kotlin of the Kotlin DSL (stdlib-jdk8 and reflect, gradle-kotlin-dsl-embedded-kotlin.properties)
            String compiler = "org.jetbrains.kotlin:kotlin-compiler-embeddable:" + kotlinVersion;
            confsByCoord.computeIfAbsent(compiler, k -> new ArrayList<>()).add(KOTLIN_COMPILER);
            transitiveByCoord.merge(compiler, true, Boolean::logicalOr);
            for (String k : Arrays.asList("stdlib-jdk8", "reflect")) {
                String c = "org.jetbrains.kotlin:kotlin-" + k + ":" + kotlinVersion;
                confsByCoord.computeIfAbsent(c, x -> new ArrayList<>()).add("embedded-kotlin");
                transitiveByCoord.merge(c, true, Boolean::logicalOr);
            }
            for (Map.Entry<String, Module> e : mods.entrySet()) {
                for (Dep dep : e.getValue().compileOnlyDeps) {
                    confsByCoord.computeIfAbsent(dep.coord, k -> new ArrayList<>()).add(e.getKey() + COMPILE_ONLY);
                    transitiveByCoord.merge(dep.coord, dep.transitive, Boolean::logicalOr);
                }
                for (Dep dep : e.getValue().deps) {
                    List<String> confs = confsByCoord.computeIfAbsent(dep.coord, k -> new ArrayList<>());
                    if (!confs.contains(e.getKey())) {
                        confs.add(e.getKey());
                    }
                    transitiveByCoord.merge(dep.coord, dep.transitive, Boolean::logicalOr);
                }
            }
            for (String coord : transitiveByCoord.keySet()) {
                String[] gav = coord.split(":");
                if (nonTransitive.contains(gav[0] + ":" + gav[1])) {
                    transitiveByCoord.put(coord, false);
                }
            }
            StringBuilder xml = new StringBuilder("<ivy-module version=\"2.0\"><info organisation=\"org.gradle\" module=\"gradle-bootstrap\"/><configurations>");
            for (String d : mods.keySet()) {
                xml.append("<conf name=\"").append(d).append("\"/>");
            }
            for (String c : externalModules()) {
                xml.append("<conf name=\"").append(EXTERNAL_MODULES).append("--").append(c.split(":")[1]).append("\"/>");
            }
            xml.append("<conf name=\"").append(KOTLIN_COMPILER).append("\"/><conf name=\"embedded-kotlin\"/>");
            for (Map.Entry<String, Module> e : mods.entrySet()) {
                if (!e.getValue().compileOnlyDeps.isEmpty()) {
                    xml.append("<conf name=\"").append(e.getKey()).append(COMPILE_ONLY).append("\"/>");
                }
            }
            xml.append("</configurations><dependencies>\n");
            for (Map.Entry<String, List<String>> e : confsByCoord.entrySet()) {
                String[] gav = e.getKey().split(":");
                xml.append("<dependency org=\"").append(gav[0]).append("\" name=\"").append(gav[1]).append("\" rev=\"").append(gav[2])
                    .append("\" transitive=\"").append(transitiveByCoord.get(e.getKey())).append("\" conf=\"")
                    .append(e.getValue().stream().map(c -> c + "->default").collect(Collectors.joining(";"))).append("\">");
                for (String[] x : ivyExcludes.getOrDefault(gav[0] + ":" + gav[1], Collections.emptyList())) {
                    xml.append("<exclude ").append(x[0]).append("=\"").append(x[1]).append("\"/>");
                }
                xml.append("</dependency>\n");
            }
            for (String[] x : globalExcludes) {
                xml.append("<exclude");
                for (int i = 0; i < x.length; i += 2) {
                    xml.append(' ').append(x[i]).append("=\"").append(x[i + 1]).append('"');
                }
                xml.append("/>\n");
            }
            // the library versions are a platform (distributionsDependencies): they win over transitive versions
            Set<String> overridden = new TreeSet<>();
            for (Map.Entry<String, String> u : UPGRADED.entrySet()) {
                String[] ga = u.getKey().split(":");
                overridden.add(u.getKey());
                xml.append("<override org=\"").append(ga[0]).append("\" module=\"").append(ga[1]).append("\" rev=\"").append(u.getValue()).append("\"/>\n");
            }
            for (List<Dep> deps : libraries.values()) {
                for (Dep dep : deps) {
                    String[] gav = dep.coord.split(":");
                    if (overridden.add(gav[0] + ":" + gav[1])) {
                        xml.append("<override org=\"").append(gav[0]).append("\" module=\"").append(gav[1]).append("\" rev=\"").append(gav[2]).append("\"/>\n");
                    }
                }
            }
            xml.append("</dependencies></ivy-module>\n");
            Path ivyXml = work.resolve("deps").resolve("ivy.xml");
            write(ivyXml, xml.toString());
            Path settings = work.resolve("deps").resolve("ivysettings.xml");
            write(settings, "<ivysettings><settings defaultResolver=\"chain\"/><resolvers><chain name=\"chain\">"
                + "<ibiblio name=\"central\" m2compatible=\"true\" root=\"" + CENTRAL + "/\"/>"
                + "<ibiblio name=\"gradle\" m2compatible=\"true\" root=\"" + GRADLE_LIBS + "/\"/>"
                + "</chain></resolvers></ivysettings>\n");
            log("resolve " + confsByCoord.size() + " dependencies for " + mods.size() + " modules with ivy");
            List<String> cmd = new ArrayList<>(Arrays.asList(javaCmd(), "-jar", ivyJar.toString(), "-settings", settings.toString(),
                "-ivy", ivyXml.toString(), "-cache", work.resolve("deps").resolve("ivy-cache").toString(), "-confs"));
            cmd.addAll(mods.keySet());
            for (String c : externalModules()) {
                cmd.add(EXTERNAL_MODULES + "--" + c.split(":")[1]);
            }
            cmd.add(KOTLIN_COMPILER);
            cmd.add("embedded-kotlin");
            for (Map.Entry<String, Module> e : mods.entrySet()) {
                if (!e.getValue().compileOnlyDeps.isEmpty()) {
                    cmd.add(e.getKey() + COMPILE_ONLY);
                }
            }
            cmd.addAll(Arrays.asList("-retrieve", depsDir + "/[conf]/[organisation]/[artifact]-[revision](-[classifier]).[ext]", "-types", "jar", "bundle"));
            Path ivyLog = work.resolve("deps").resolve("ivy.log");
            int rc = execStatus(ivyLog, cmd.toArray(new String[0]));
            String out = new String(Files.readAllBytes(ivyLog), StandardCharsets.UTF_8);
            if (rc != 0 || out.contains("UNRESOLVED DEPENDENCIES")) {
                deleteTree(depsDir);
                String problems = Arrays.stream(out.split("\n")).filter(l -> l.contains("::") || l.contains("not found")).collect(Collectors.joining("\n"));
                throw new IllegalStateException("ivy resolution failed, see " + ivyLog + "\n" + tail(problems, 3000));
            }
        }
        // per module: jar files, with the group substitutions
        Map<String, Path> subFiles = new HashMap<>();
        for (Map.Entry<String, String> s : substitutions.entrySet()) {
            String[] gav = s.getValue().split(":");
            for (Path p : walk(depsDir)) {
                if (p.getParent().getFileName().toString().equals(gav[0]) && p.getFileName().toString().equals(gav[1] + "-" + gav[2] + ".jar")) {
                    subFiles.put(s.getKey(), p);
                }
            }
        }
        kotlinCompilerJars.addAll(walk(depsDir.resolve(KOTLIN_COMPILER)));
        for (Path p : walk(depsDir.resolve("embedded-kotlin"))) {
            Matcher nv = Pattern.compile("^(.+?)-(\\d.*)\\.jar$").matcher(p.getFileName().toString());
            if (nv.matches()) {
                embeddedKotlin.put(p.getParent().getFileName() + ":" + nv.group(1), nv.group(2));
            }
        }
        for (String c : externalModules()) {
            String artifact = c.split(":")[1];
            Set<String> closure = new TreeSet<>();
            for (Path p : walk(depsDir.resolve(EXTERNAL_MODULES + "--" + artifact))) {
                externalJars.put(p.getFileName().toString(), p);
                closure.add(p.getFileName().toString());
            }
            externalClosures.put(artifact, closure);
        }
        for (Map.Entry<String, Module> e : mods.entrySet()) {
            e.getValue().compileOnlyJars.addAll(walk(depsDir.resolve(e.getKey() + COMPILE_ONLY)));
            Path mdir = depsDir.resolve(e.getKey());
            if (!Files.isDirectory(mdir)) {
                continue;
            }
            for (Path orgDir : list(mdir)) {
                String org = orgDir.getFileName().toString();
                for (Path p : list(orgDir)) {
                    String fn = p.getFileName().toString();
                    if (substitutions.containsKey(org) && subFiles.containsKey(org) && !p.equals(subFiles.get(org))
                        && !fn.startsWith(substitutions.get(org).split(":")[1] + "-")) {
                        p = subFiles.get(org);
                    }
                    e.getValue().jars.put(p.getFileName().toString(), p);
                }
            }
        }
    }

    // ---- 4. compilation
    void compile() throws Exception {
        compileJavaGroovy();
        compileKotlin();
    }

    /** the compile classpath of a single compilation: the newest version of every jar, base modules first (see
     * compileJavaGroovy), then the compileOnly jars */
    List<String> compileClasspath() {
        List<String> order = new ArrayList<>();
        for (String d : mods.keySet()) {
            visit(d, order, new ArrayDeque<>());
        }
        Set<Path> all = new LinkedHashSet<>();
        for (Module m : mods.values()) {
            all.addAll(m.jars.values());
        }
        Set<Path> keep = new HashSet<>(newest(all));
        List<String> jars = new ArrayList<>();
        for (String d : order) {
            for (Path p : mods.get(d).jars.values()) {
                if (keep.contains(p) && !jars.contains(p.toString())) {
                    jars.add(p.toString());
                }
            }
        }
        for (String d : order) {
            for (Path p : mods.get(d).compileOnlyJars) {
                if (!jars.contains(p.toString())) {
                    jars.add(p.toString());
                }
            }
        }
        return jars;
    }

    /** kotlinc for the Kotlin modules, one at a time in dependency order, against the jars, the Java/Groovy classes
     * and the Kotlin modules compiled before (output: kotlin-classes/<module>) */
    void compileKotlin() throws Exception {
        List<String> order = new ArrayList<>();
        for (String d : mods.keySet()) {
            visit(d, order, new ArrayDeque<>());
        }
        List<String> cp = compileClasspath();
        cp.add(work.resolve("classes").toString());
        // project(":kotlinCompilerEmbeddable"): its classes are those of the Kotlin compiler jar
        for (Path p : kotlinCompilerJars) {
            if (p.getFileName().toString().equals("kotlin-compiler-embeddable-" + kotlinVersion + ".jar")) {
                cp.add(p.toString());
            }
        }
        String compilerCp = kotlinCompilerJars.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        for (String d : order) {
            if (!mods.get(d).kotlin) {
                continue;
            }
            Path out = work.resolve("kotlin-classes").resolve(d);
            if (!Files.isRegularFile(out.resolve(".done"))) {
                deleteTree(out);
                Files.createDirectories(out);
                List<String> files = new ArrayList<>();
                for (Path p : walk(src.resolve("subprojects").resolve(d).resolve("src").resolve("main").resolve("kotlin"))) {
                    if (p.toString().endsWith(".kt")) {
                        files.add(p.toString());
                    }
                }
                for (Path p : generatedKotlin(d)) {
                    files.add(p.toString());
                }
                // options of buildSrc configureKotlinCompilerForGradleBuild
                List<String> args = new ArrayList<>(Arrays.asList("-d", out.toString(), "-classpath", String.join(File.pathSeparator, cp),
                    "-no-stdlib", "-no-reflect", "-jvm-target", "1.8", "-api-version", "1.3", "-language-version", "1.3",
                    "-Xjsr305=strict", "-java-parameters", "-Xskip-runtime-version-check", "-progressive", "-module-name", mods.get(d).archive));
                args.addAll(files);
                Path argfile = work.resolve("kotlin-classes").resolve(d + ".args");
                write(argfile, args.stream().map(a -> "\"" + a.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").collect(Collectors.joining("\n")));
                log("kotlinc " + d + ": " + files.size() + " sources");
                Path logFile = work.resolve("kotlin-classes").resolve(d + ".log");
                int rc = execStatus(logFile, javaCmd(), "-Xmx3g", "-cp", compilerCp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "@" + argfile);
                if (rc != 0) {
                    throw new IllegalStateException("kotlinc " + d + " failed, see " + logFile + "\n" + tail(new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8), 4000));
                }
                Files.createFile(out.resolve(".done"));
            }
            cp.add(out.toString());
        }
    }

    /** sources generated by the build of a Kotlin module: kotlin-dsl (buildSrc GenerateKotlinDependencyExtensions) */
    List<Path> generatedKotlin(String d) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!d.equals("kotlin-dsl")) {
            return files;
        }
        String generator = "";
        for (Path p : walk(src.resolve("buildSrc"))) {
            if (p.getFileName().toString().equals("GenerateKotlinDependencyExtensions.kt")) {
                generator = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
        }
        String pluginsVersion = find("publishedKotlinDslPluginVersion\\s*=\\s*\"([^\"]+)\"", mods.get(d).build, 0);
        Matcher m = Pattern.compile("(?s)writeFile\\(\\s*\"([^\"]+)\",\\s*\"\"\"(.*?)\"\"\"\\)").matcher(generator);
        while (m.find()) {
            String text = m.group(2).replace("$licenseHeader", "")
                .replace("${embeddedKotlinVersion}", kotlinVersion).replace("$embeddedKotlinVersion", kotlinVersion)
                .replace("${kotlinDslPluginsVersion}", pluginsVersion).replace("$kotlinDslPluginsVersion", pluginsVersion)
                .replace("${'$'}", "$");
            Path target = work.resolve("generated").resolve(d).resolve(m.group(1));
            write(target, text);
            files.add(target);
        }
        log("generated for " + d + ": " + files.size() + " Kotlin sources (kotlin-dsl plugins " + pluginsVersion + ")");
        return files;
    }

    void compileJavaGroovy() throws Exception {
        Path out = work.resolve("classes");
        for (String d : mods.keySet()) {
            if (NO_SOURCES.contains(d)) {
                continue;
            }
            for (String lang : Arrays.asList("java", "groovy")) {
                Path base = src.resolve("subprojects").resolve(d).resolve("src").resolve("main").resolve(lang);
                for (Path p : walk(base)) {
                    String fn = p.getFileName().toString();
                    if ((fn.endsWith(".java") || fn.endsWith(".groovy")) && !fn.equals("package-info.java")) {
                        String rel = base.relativize(p).toString();
                        sources.put(p, new Source(d, rel.substring(0, rel.lastIndexOf('.'))));
                    }
                }
            }
        }
        if (Files.isRegularFile(out.resolve(".done"))) {
            return;
        }
        deleteTree(out);
        Files.createDirectories(out);
        // modules in dependency order (base modules first): their jars come first on the single classpath, so a fat
        // jar of a high level module (e.g. sbt's incremental-compiler bundles jansi) does not shadow a base library
        List<String> order = new ArrayList<>();
        for (String d : mods.keySet()) {
            visit(d, order, new ArrayDeque<>());
        }
        Set<Path> all = new LinkedHashSet<>();
        for (Module m : mods.values()) {
            all.addAll(m.jars.values());
        }
        Set<Path> keep = new HashSet<>(newest(all));
        List<String> jars = new ArrayList<>();
        for (String d : order) {
            for (Path p : mods.get(d).jars.values()) {
                if (keep.contains(p) && !jars.contains(p.toString())) {
                    jars.add(p.toString());
                }
            }
        }
        for (String d : order) {
            for (Path p : mods.get(d).compileOnlyJars) {
                if (!jars.contains(p.toString())) {
                    jars.add(p.toString());
                }
            }
        }
        jars.add(toolsJar());
        // the target's Groovy compiles the sources (libraries.groovy, e.g. org.gradle.groovy:groovy-all:1.0-2.5.4)
        String[] groovy = libraries.get("groovy").get(0).coord.split(":");
        for (Path p : all) {
            if (p.getFileName().toString().equals(groovy[1] + "-" + groovy[2] + ".jar")) {
                compilerJar = p;
            }
        }
        if (compilerJar == null) {
            throw new IllegalStateException("libraries.groovy " + String.join(":", groovy) + " not resolved");
        }
        Path listing = work.resolve("sources.txt");
        write(listing, sources.keySet().stream().map(Path::toString).collect(Collectors.joining("\n")) + "\n");
        Path cpFile = work.resolve("classpath.txt");
        write(cpFile, String.join(File.pathSeparator, jars));
        Path script = work.resolve("compile.groovy");
        write(script, COMPILE);
        log("compile " + sources.size() + " sources (" + (jars.size() - 1) + " jars on the classpath, " + compilerJar.getFileName() + ")");
        Path compileLog = work.resolve("compile.log");
        int rc = execStatus(compileLog, javaCmd(), "-Xmx4g", "-cp", compilerJar + File.pathSeparator + toolsJar(), "groovy.ui.GroovyMain",
            script.toString(), listing.toString(), cpFile.toString(), out.toString());
        if (rc != 0) {
            throw new IllegalStateException("compilation failed, see " + compileLog + "\n" + tail(new String(Files.readAllBytes(compileLog), StandardCharsets.UTF_8), 4000));
        }
        Files.createFile(out.resolve(".done"));
    }

    void visit(String d, List<String> order, Deque<String> stack) {
        if (order.contains(d) || stack.contains(d)) {
            return;
        }
        stack.push(d);
        for (String p : mods.get(d).projects) {
            visit(p, order, stack);
        }
        stack.pop();
        order.add(d);
    }

    // ---- 5. distribution
    Map<String, List<String>> splitClasses() throws IOException {
        Path out = work.resolve("classes");
        Map<String, String> owner = new HashMap<>();
        Map<String, Set<String>> byPkg = new HashMap<>();
        for (Source s : sources.values()) {
            owner.put(s.rel, s.module);
            byPkg.computeIfAbsent(dirname(s.rel), k -> new TreeSet<>()).add(s.module);
        }
        Map<String, List<String>> classes = new TreeMap<>();
        for (String d : mods.keySet()) {
            classes.put(d, new ArrayList<>());
        }
        List<String> orphans = new ArrayList<>();
        for (Path p : walk(out)) {
            if (!p.toString().endsWith(".class")) {
                continue;
            }
            String rel = out.relativize(p).toString();
            String base = rel.substring(0, rel.length() - 6).split("\\$")[0];
            String d = owner.get(base);
            if (d == null) {
                Set<String> candidates = byPkg.getOrDefault(dirname(base), Collections.emptySet());
                d = candidates.size() == 1 ? candidates.iterator().next() : findDeclaringModule(base, candidates);
            }
            if (d == null) {
                orphans.add(rel);
            } else {
                classes.get(d).add(rel);
            }
        }
        if (!orphans.isEmpty()) {
            throw new IllegalStateException("classes without a module: " + orphans.subList(0, Math.min(10, orphans.size())));
        }
        return classes;
    }

    String findDeclaringModule(String base, Set<String> candidates) throws IOException {
        String name = base.substring(base.lastIndexOf('/') + 1);
        Pattern decl = Pattern.compile("\\b(class|interface|enum)\\s+" + Pattern.quote(name) + "\\b");
        for (Map.Entry<Path, Source> e : sources.entrySet()) {
            Source s = e.getValue();
            if (candidates.contains(s.module) && dirname(s.rel).equals(dirname(base))
                && decl.matcher(new String(Files.readAllBytes(e.getKey()), StandardCharsets.UTF_8)).find()) {
                return s.module;
            }
        }
        return null;
    }

    List<String> projectClosure(List<String> roots) {
        List<String> seen = new ArrayList<>();
        Deque<String> todo = new ArrayDeque<>(roots);
        while (!todo.isEmpty()) {
            String d = todo.removeFirst();
            if (seen.contains(d) || !mods.containsKey(d)) {
                continue;
            }
            seen.add(d);
            todo.addAll(mods.get(d).projects);
        }
        return seen;
    }

    Path distribution() throws Exception {
        Path dist = work.resolve("dist");
        deleteTree(dist);
        Files.createDirectories(dist.resolve("lib").resolve("plugins"));
        Files.createDirectories(dist.resolve("bin"));
        Map<String, List<String>> classes = splitClasses();
        String rootBuild = read("build.gradle.kts") + read("build.gradle");
        List<String> coreRoots = new ArrayList<>();
        for (String p : findAll("(?m)^\\s*(?:coreRuntime|runtime)\\s*\\(?\\s*project\\(['\"]:(\\w+)['\"]\\)", rootBuild)) {
            coreRoots.add(kebab(p));
        }
        List<String> core = projectClosure(coreRoots);
        log("lib/: " + core.size() + " modules (" + String.join(", ", coreRoots) + " and their project dependencies), lib/plugins/: " + (mods.size() - core.size()));
        Map<String, Map<String, byte[]>> generated = new TreeMap<>();
        for (String d : mods.keySet()) {
            generated.put(d, new LinkedHashMap<>());
        }
        // classpath manifests
        for (Map.Entry<String, Module> e : mods.entrySet()) {
            Module m = e.getValue();
            text(generated.get(e.getKey()), m.archive + "-classpath.properties",
                (m.optional.isEmpty() ? "" : "optional=" + String.join(",", m.optional) + "\n")
                    + "projects=" + m.projects.stream().map(p -> mods.get(p).archive).collect(Collectors.joining(",")) + "\n"
                    + "runtime=" + String.join(",", m.jars.keySet()) + "\n");
        }
        // build receipt
        String commit = execOutput("git", "-C", repo.toString(), "rev-list", "-n1", tag).trim();
        text(generated.get("base-services"), "org/gradle/build-receipt.properties",
            "baseVersion=" + baseVersion + "\ncommitId=" + commit + "\nbuildTimestamp=" + timestamp + "+0000\n"
                + "buildTimestampIso=" + timestamp.substring(0, 4) + "-" + timestamp.substring(4, 6) + "-" + timestamp.substring(6, 8) + " "
                + timestamp.substring(8, 10) + "\\:" + timestamp.substring(10, 12) + "\\:" + timestamp.substring(12, 14) + " UTC\n"
                + "versionNumber=" + version + "\nisSnapshot=true\n");
        // plugin lists (core.gradle pluginsManifest / implementationPluginsManifest)
        // (5.0: buildSrc ProjectGroups, val Project.pluginProjects get() = setOf("announce", ...))
        String[][] pluginLists = {{"pluginProjects", "gradle-plugins.properties"}, {"implementationPluginProjects", "gradle-implementation-plugins.properties"}};
        String groups = rootBuild + buildSrcKotlin();
        for (String[] pl : pluginLists) {
            // (6.0: setOf(rootProject.project("buildProfile"), ...): up to the matching closing parenthesis)
            Matcher m = Pattern.compile("(?s)\\b" + pl[0] + "\\s*(?:get\\(\\)\\s*)?=\\s*(?:setOf|listOf)?([(\\[])").matcher(groups);
            if (m.find()) {
                List<String> names = findAll("['\"](\\w+)['\"]", balanced(groups, m.start(1))).stream().map(n -> "gradle-" + kebab(n)).sorted().collect(Collectors.toList());
                text(generated.get("core"), pl[1], "plugins=" + String.join(",", names) + "\n");
            }
        }
        // parameter names index of every module: left empty (bootstrap), see api-metadata
        for (Map.Entry<String, Module> e : mods.entrySet()) {
            text(generated.get(e.getKey()), e.getValue().archive + "-parameter-names.properties", "");
        }
        // kotlin-dsl: versions manifest and embedded Kotlin (kotlin-dsl.gradle.kts writeVersionsManifest,
        // writeEmbeddedKotlinDependencies)
        if (mods.containsKey("kotlin-dsl")) {
            text(generated.get("kotlin-dsl"), "gradle-kotlin-dsl-versions.properties", "kotlin=" + kotlinVersion + "\n");
            StringBuilder ek = new StringBuilder();
            for (Map.Entry<String, String> k : embeddedKotlin.entrySet()) {
                ek.append(k.getKey().replace(":", "\\:")).append('=').append(k.getValue()).append('\n');
            }
            text(generated.get("kotlin-dsl"), "gradle-kotlin-dsl-embedded-kotlin.properties", ek.toString());
        }
        // default imports and api mapping (docs project)
        String[] imports = defaultImports();
        generated.computeIfAbsent("docs", k -> new LinkedHashMap<>());
        text(generated.get("docs"), "default-imports.txt", imports[0]);
        text(generated.get("docs"), "api-mapping.txt", imports[1]);
        // docs: release features (docs.gradle releaseFeaturesFile)
        String features = read("subprojects/docs/src/docs/release/release-features.txt");
        if (!features.isEmpty()) {
            text(generated.get("docs"), "release-features.txt", features);
        }
        // api-metadata: the public API declaration; the parameter names index is left empty (bootstrap: the Kotlin DSL
        // extensions then use generated parameter names)
        if (mods.containsKey("api-metadata")) {
            List<String>[] api = publicApi();
            text(generated.get("api-metadata"), "gradle-api-declaration.properties",
                "excludes=" + String.join("\\:", api[1]) + "\nincludes=" + String.join("\\:", api[0]) + "\n");
            text(generated.get("api-metadata"), "gradle-api-parameter-names.properties", "");
        }
        // relocated packages (runtime-api-info)
        Set<Path> allJars = new TreeSet<>();
        Set<Path> coreJars = new TreeSet<>();
        for (Map.Entry<String, Module> e : mods.entrySet()) {
            allJars.addAll(e.getValue().jars.values());
            if (core.contains(e.getKey())) {
                coreJars.addAll(e.getValue().jars.values());
            }
        }
        if (mods.containsKey("runtime-api-info")) {
            text(generated.get("runtime-api-info"), "org/gradle/api/internal/runtimeshaded/api-relocated.txt", relocatedPackages(allJars));
            text(generated.get("runtime-api-info"), "org/gradle/api/internal/runtimeshaded/test-kit-relocated.txt", relocatedPackages(coreJars));
        }
        Path clsDir = work.resolve("classes");
        // gradle-wrapper.jar embedded in the project that copies :wrapper's executableJar (plugins)
        for (Map.Entry<String, Module> e : mods.entrySet()) {
            if (Pattern.compile("project\\([\"']:wrapper[\"']\\)\\.executableJar").matcher(e.getValue().build).find()) {
                List<String> parts = new ArrayList<>();
                parts.add("wrapper");
                parts.addAll(mods.get("wrapper").projects);
                List<String[]> entries = new ArrayList<>();
                for (String p : parts) {
                    for (String c : sorted(classes.get(p))) {
                        entries.add(new String[]{clsDir.resolve(c).toString(), c});
                    }
                    entries.addAll(resources(p));
                }
                Path wrapperJar = work.resolve("gradle-wrapper.jar");
                writeJar(wrapperJar, new LinkedHashMap<>(), entries, new LinkedHashMap<>());
                generated.get(e.getKey()).put("gradle-wrapper.jar", Files.readAllBytes(wrapperJar));
            }
        }
        // jars
        for (Map.Entry<String, Module> e : mods.entrySet()) {
            String d = e.getKey();
            Path target = (core.contains(d) ? dist.resolve("lib") : dist.resolve("lib").resolve("plugins")).resolve(e.getValue().archive + "-" + version + ".jar");
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("Implementation-Title", "Gradle");
            attrs.put("Implementation-Version", version);
            if (d.equals("launcher")) {
                Matcher cp = Pattern.compile("'Class-Path':\\s*\"([^\"]+)\"").matcher(e.getValue().build);
                // 5.0: val classpath = listOf(":baseServices", ":coreApi", ":core").joinToString(" ") ... "Class-Path" to classpath
                Matcher cpList = Pattern.compile("(?s)listOf\\(([^)]*)\\)\\s*\\.joinToString.{0,400}?\"Class-Path\"").matcher(e.getValue().build);
                List<String> refs = cp.find() ? findAll("project\\(['\"]:(\\w+)['\"]\\)", cp.group(1))
                    : cpList.find() ? findAll("\":(\\w+)\"", cpList.group(1)) : Arrays.asList("core", "baseServices");
                attrs.put("Class-Path", refs.stream().map(r -> "gradle-" + kebab(r) + "-" + version + ".jar").collect(Collectors.joining(" ")));
                attrs.put("Main-Class", "org.gradle.launcher.GradleMain");
            }
            List<String[]> entries = new ArrayList<>();
            for (String c : sorted(classes.get(d))) {
                entries.add(new String[]{clsDir.resolve(c).toString(), c});
            }
            Path kotlinOut = work.resolve("kotlin-classes").resolve(d);
            for (Path p : walk(kotlinOut)) {
                if (!p.getFileName().toString().equals(".done")) {
                    entries.add(new String[]{p.toString(), kotlinOut.relativize(p).toString()});
                }
            }
            entries.addAll(resources(d));
            if (d.equals("kotlin-compiler-embeddable")) {
                entries.addAll(patchedKotlinCompiler());
            }
            writeJar(target, attrs, entries, generated.get(d));
        }
        // external jars: lib/ for the core runtime, lib/plugins/ for the rest
        Set<String> coreNames = new HashSet<>();
        for (String d : core) {
            for (Map.Entry<String, Path> j : mods.get(d).jars.entrySet()) {
                Files.copy(j.getValue(), dist.resolve("lib").resolve(j.getKey()), StandardCopyOption.REPLACE_EXISTING);
                coreNames.add(j.getKey());
            }
        }
        for (Module m : mods.values()) {
            for (Map.Entry<String, Path> j : m.jars.entrySet()) {
                if (!coreNames.contains(j.getKey())) {
                    Files.copy(j.getValue(), dist.resolve("lib").resolve("plugins").resolve(j.getKey()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        // external modules (Kotlin DSL) in lib/, the org.gradle ones with a classpath manifest (patchExternalModules)
        for (Map.Entry<String, Path> j : externalJars.entrySet()) {
            String name = j.getKey();
            if (coreNames.contains(name)) {
                continue;
            }
            Path target = dist.resolve("lib").resolve(name);
            Files.deleteIfExists(dist.resolve("lib").resolve("plugins").resolve(name));
            Map<String, byte[]> add = new LinkedHashMap<>();
            Set<String> remove = new HashSet<>();
            if (j.getValue().getParent().getFileName().toString().equals("org.gradle")) {
                String module = name.replaceFirst("-\\d.*\\.jar$", "");
                // its own transitive dependencies only (the jar of another external module must not leak into the
                // Gradle API classpath: its plugin ids would get Kotlin DSL accessors)
                Set<String> closure = externalClosures.getOrDefault(module, Collections.emptySet());
                List<String> runtime = closure.stream().filter(n -> !n.equals(name) && !coreNames.contains(n)).collect(Collectors.toList());
                text(add, module + "-classpath.properties", "projects=\nruntime=" + String.join(",", runtime) + "\n");
            }
            if (name.startsWith("kotlin-compiler-embeddable-")) {
                remove.add("META-INF/services/java.nio.charset.spi.CharsetProvider");
            }
            if (add.isEmpty() && remove.isEmpty()) {
                Files.copy(j.getValue(), target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                patchJar(j.getValue(), target, add, remove);
            }
        }
        Path start = dist.resolve("bin").resolve("gradle");
        write(start, START_SCRIPT.replace("@LAUNCHER@", "gradle-launcher-" + version + ".jar"));
        start.toFile().setExecutable(true, false);
        return dist;
    }

    /** kotlin-compiler-embeddable: the content of the Kotlin compiler jar without the entries its build file excludes
     * (PatchKotlinCompilerEmbeddable excludes), unpacked under work/kotlin-compiler-embeddable */
    List<String[]> patchedKotlinCompiler() throws IOException {
        List<String> excludes = new ArrayList<>();
        Matcher m = Pattern.compile("(?s)excludes\\.set\\(listOf\\((.*?)\\)\\)").matcher(mods.get("kotlin-compiler-embeddable").build);
        if (m.find()) {
            excludes.addAll(findAll("\"([^\"]+)\"", m.group(1)));
        }
        Path jar = kotlinCompilerJars.stream().filter(p -> p.getFileName().toString().equals("kotlin-compiler-embeddable-" + kotlinVersion + ".jar"))
            .findFirst().orElseThrow(() -> new IllegalStateException("kotlin-compiler-embeddable " + kotlinVersion + " not resolved"));
        Path dir = work.resolve("kotlin-compiler-embeddable");
        deleteTree(dir);
        List<String[]> entries = new ArrayList<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (ZipEntry e : Collections.list(z.entries())) {
                String n = e.getName();
                if (e.isDirectory() || n.equals("META-INF/MANIFEST.MF") || excludes.stream().anyMatch(x -> globMatch(x, n))) {
                    continue;
                }
                Path t = dir.resolve(n);
                Files.createDirectories(t.getParent());
                try (InputStream in = z.getInputStream(e)) {
                    Files.copy(in, t, StandardCopyOption.REPLACE_EXISTING);
                }
                entries.add(new String[]{t.toString(), n});
            }
        }
        return entries;
    }

    List<String[]> resources(String d) throws IOException {
        Path base = src.resolve("subprojects").resolve(d).resolve("src").resolve("main").resolve("resources");
        List<String[]> res = new ArrayList<>();
        for (Path p : walk(base)) {
            res.add(new String[]{p.toString(), base.relativize(p).toString()});
        }
        return res;
    }

    /** docs.gradle: defaultImports (GenerateDefaultImportsTask) over the javadocAll sources of the public projects */
    String[] defaultImports() throws IOException {
        String docs = read("subprojects/docs/docs.gradle");
        List<String>[] api = publicApi();
        List<String> includes = api[0];
        List<String> excludes = api[1];
        String di = find("(?s)(?:task defaultImports|tasks\\.register\\(\"defaultImports\")[^{]*\\{(.*?)\\n\\}", docs, 0);
        List<String> excluded = findAll("excludePackage\\s+'([^']+)'", di);
        Set<String> packages = new TreeSet<>(findAll("extraPackage\\s+'([^']+)'", di));
        Map<String, List<String>> names = new TreeMap<>();
        for (Map.Entry<Path, Source> e : sources.entrySet()) {
            String fn = e.getKey().getFileName().toString();
            String rel = e.getValue().rel;
            String path = rel + fn.substring(fn.lastIndexOf('.'));
            if (includes.stream().noneMatch(i -> globMatch(i, path)) || excludes.stream().anyMatch(x -> globMatch(x, path))) {
                continue;
            }
            String pkg = dirname(rel).replace('/', '.');
            boolean skip = false;
            for (String x : excluded) {
                if (x.endsWith(".**") ? pkg.equals(x.substring(0, x.length() - 3)) || pkg.startsWith(x.substring(0, x.length() - 2)) : pkg.equals(x)) {
                    skip = true;
                }
            }
            if (skip) {
                continue;
            }
            packages.add(pkg);
            String simple = rel.substring(rel.lastIndexOf('/') + 1);
            names.computeIfAbsent(simple, k -> new ArrayList<>()).add(pkg + "." + simple);
        }
        StringBuilder imports = new StringBuilder();
        for (String p : packages) {
            imports.append("import ").append(p).append(".*\n");
        }
        StringBuilder mapping = new StringBuilder();
        for (Map.Entry<String, List<String>> e : names.entrySet()) {
            mapping.append(e.getKey()).append(':');
            for (String c : sorted(e.getValue())) {
                mapping.append(c).append(';');
            }
            mapping.append('\n');
        }
        return new String[]{imports.toString(), mapping.toString()};
    }

    /** the public API includes and excludes: docs.gradle javadocAll (4.x), or buildSrc object PublicApi (5.0) */
    @SuppressWarnings("unchecked")
    List<String>[] publicApi() throws IOException {
        String docs = read("subprojects/docs/docs.gradle");
        Matcher jd = Pattern.compile("(?s)task javadocAll[^{]*\\{(.*?)\\n\\}").matcher(docs);
        if (jd.find() && !findAll("(?m)^\\s*include\\s+'([^']+)'", jd.group(1)).isEmpty()) {
            return new List[]{findAll("(?m)^\\s*include\\s+'([^']+)'", jd.group(1)), findAll("(?m)^\\s*exclude\\s+'([^']+)'", jd.group(1))};
        }
        String kt = buildSrcKotlin();
        String obj = find("(?s)object PublicApi\\s*\\{(.*?)\\n\\}", kt, 0);
        return new List[]{findAll("\"([^\"]+)\"", find("(?s)val includes\\s*=\\s*listOf\\((.*?)\\)", obj, 0)),
            findAll("\"([^\"]+)\"", find("(?s)val excludes\\s*=\\s*listOf\\((.*?)\\)", obj, 0))};
    }

    /** all the Kotlin sources of buildSrc, concatenated (to read constants and lists with regular expressions) */
    String buildSrcKotlin() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Path p : walk(src.resolve("buildSrc"))) {
            if (p.toString().endsWith(".kt")) {
                sb.append(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).append('\n');
            }
        }
        return sb.toString();
    }

    /** copies a jar, adding entries and leaving some out */
    static void patchJar(Path from, Path to, Map<String, byte[]> add, Set<String> remove) throws IOException {
        try (ZipFile in = new ZipFile(from.toFile());
             java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(to))) {
            Set<String> seen = new HashSet<>();  // some external jars hold an entry twice: the first one is kept
            for (ZipEntry e : Collections.list(in.entries())) {
                if (remove.contains(e.getName()) || add.containsKey(e.getName()) || !seen.add(e.getName())) {
                    continue;
                }
                out.putNextEntry(new ZipEntry(e.getName()));
                try (InputStream is = in.getInputStream(e)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
                out.closeEntry();
            }
            for (Map.Entry<String, byte[]> a : add.entrySet()) {
                out.putNextEntry(new ZipEntry(a.getKey()));
                out.write(a.getValue());
                out.closeEntry();
            }
        }
    }

    // ---- helpers
    /** the text between the bracket at index open and its matching closing bracket */
    static String balanced(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if ((c == ')' || c == ']') && --depth == 0) {
                return text.substring(open + 1, i);
            }
        }
        throw new IllegalStateException("unbalanced brackets at " + open);
    }

    /** ant style include pattern (** any directories, * within one directory) */
    static boolean globMatch(String pattern, String path) {
        StringBuilder rx = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            if (pattern.startsWith("**/", i)) {
                rx.append("(.*/)?");
                i += 2;
            } else if (pattern.startsWith("**", i)) {
                rx.append(".*");
                i += 1;
            } else if (pattern.charAt(i) == '*') {
                rx.append("[^/]*");
            } else {
                rx.append(Pattern.quote(String.valueOf(pattern.charAt(i))));
            }
        }
        return path.matches(rx.toString());
    }

    /** PackageListGenerator + Trie.dump(false): the package directories of the classes, without the excluded prefixes,
     * only the shortest words (a word that has another word as prefix is not listed), sorted */
    static String relocatedPackages(Collection<Path> jars) throws IOException {
        Set<String> words = new TreeSet<>();
        for (Path j : jars) {
            try (ZipFile z = new ZipFile(j.toFile())) {
                for (ZipEntry e : Collections.list(z.entries())) {
                    String n = e.getName();
                    if (n.endsWith(".class") && n.contains("/")) {
                        String pkg = n.substring(0, n.lastIndexOf('/'));
                        if (RELOCATION_EXCLUDES.stream().noneMatch(pkg::startsWith)) {
                            words.add(pkg);
                        }
                    }
                }
            }
        }
        StringBuilder out = new StringBuilder();
        String last = null;
        for (String w : words) {
            if (last != null && w.startsWith(last)) {
                continue;
            }
            out.append(w).append('\n');
            last = w;
        }
        return out.toString();
    }

    /** one jar per organisation/artifact (the newest version), like the conflict resolution of a single classpath */
    static List<Path> newest(Collection<Path> jars) {
        Pattern nameVersion = Pattern.compile("^(.+?)-(\\d.*)\\.jar$");
        Map<String, Object[]> best = new HashMap<>();
        for (Path p : jars) {
            String org = p.getParent().getFileName().toString();
            String fn = p.getFileName().toString();
            Matcher m = nameVersion.matcher(fn);
            String key = org + ":" + (m.matches() ? m.group(1) : fn);
            String v = m.matches() ? m.group(2) : "";
            if (!best.containsKey(key) || compareVersions(v, (String) best.get(key)[0]) > 0) {
                best.put(key, new Object[]{v, p});
            }
        }
        return best.values().stream().map(o -> (Path) o[1]).sorted().collect(Collectors.toList());
    }

    static int compareVersions(String a, String b) {
        String[] x = a.split("[.\\-]");
        String[] y = b.split("[.\\-]");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            boolean dx = x[i].matches("\\d+");
            boolean dy = y[i].matches("\\d+");
            int c;
            if (dx && dy) {
                c = new java.math.BigInteger(x[i]).compareTo(new java.math.BigInteger(y[i]));
            } else if (dx != dy) {
                c = dx ? -1 : 1;  // numbers before qualifiers
            } else {
                c = x[i].compareTo(y[i]);
            }
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    static void writeJar(Path path, Map<String, String> attrs, List<String[]> entries, Map<String, byte[]> generated) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        for (Map.Entry<String, String> a : attrs.entrySet()) {
            manifest.getMainAttributes().putValue(a.getKey(), a.getValue());
        }
        Set<String> seen = new HashSet<>();
        seen.add("META-INF/MANIFEST.MF");
        try (JarOutputStream z = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            for (Map.Entry<String, byte[]> g : generated.entrySet()) {
                z.putNextEntry(new ZipEntry(g.getKey()));
                z.write(g.getValue());
                z.closeEntry();
                seen.add(g.getKey());
            }
            for (String[] e : entries) {
                if (seen.add(e[1])) {
                    z.putNextEntry(new ZipEntry(e[1]));
                    Files.copy(Paths.get(e[0]), z);
                    z.closeEntry();
                }
            }
        }
    }

    static void text(Map<String, byte[]> m, String name, String content) {
        m.put(name, content.getBytes(StandardCharsets.UTF_8));
    }

    static String kebab(String name) {
        return name.replaceAll("(?<!^)(?=[A-Z])", "-").toLowerCase(Locale.ROOT);
    }

    static String dirname(String rel) {
        int i = rel.lastIndexOf('/');
        return i < 0 ? "" : rel.substring(0, i);
    }

    static int count(String s, char c) {
        return (int) s.chars().filter(x -> x == c).count();
    }

    static String find(String regex, String text, int flags) {
        Matcher m = Pattern.compile(regex, flags).matcher(text);
        if (!m.find()) {
            throw new IllegalStateException("not found: " + regex);
        }
        return m.group(1);
    }

    static List<String> findAll(String regex, String text) {
        List<String> r = new ArrayList<>();
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) {
            r.add(m.group(1));
        }
        return r;
    }

    static List<String> sorted(Collection<String> c) {
        List<String> l = new ArrayList<>(c);
        Collections.sort(l);
        return l;
    }

    static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    static List<Path> walk(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
    }

    static List<Path> list(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().collect(Collectors.toList());
        }
    }

    static void write(Path p, String content) throws IOException {
        Files.createDirectories(p.toAbsolutePath().getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }

    /** deletes a directory tree of this script's own work (an absolute path under the work or target directory) */
    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        if (!dir.isAbsolute()) {
            throw new IllegalArgumentException("not an absolute path: " + dir);
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void copyTree(Path from, Path to) throws IOException {
        for (Path p : walk(from)) {
            Path t = to.resolve(from.relativize(p).toString());
            Files.createDirectories(t.getParent());
            Files.copy(p, t, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    /** runs a command, its output goes to the file (or is discarded when null); fails on a non-zero exit code */
    static void exec(Path output, String... cmd) throws Exception {
        int rc = execStatus(output, cmd);
        if (rc != 0) {
            String out = output != null && Files.isRegularFile(output) ? new String(Files.readAllBytes(output), StandardCharsets.UTF_8) : "";
            throw new IllegalStateException("failed (" + rc + "): " + String.join(" ", Arrays.asList(cmd).subList(0, Math.min(6, cmd.length))) + " ...\n" + tail(out, 3000));
        }
    }

    static int execStatus(Path output, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.redirectOutput(output != null ? ProcessBuilder.Redirect.to(output.toFile()) : ProcessBuilder.Redirect.INHERIT);
        return pb.start().waitFor();
    }

    static String execOutput(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        p.waitFor();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static void log(String msg) {
        System.out.println("[minimal] " + msg);
        System.out.flush();
    }

    static final String EVAL_DEPENDENCIES = String.join("\n",
        "// Evaluates gradle/dependencies.gradle with a stubbed Gradle API, prints the libraries, one line per artifact:",
        "// name <tab> coordinates <tab> transitive (a client module is its own artifact plus the listed dependencies)",
        "class ClientModule { String coord; List deps = []",
        "    void dependency(Object d) { deps << d } }",
        "class DependenciesStub {",
        "    def module(String c) { new ClientModule(coord: c) }",
        "    def module(String c, Closure cl) { def m = new ClientModule(coord: c); cl.delegate = m; cl.resolveStrategy = Closure.DELEGATE_FIRST; cl(); m }",
        "    // create(coord) { exclude ... }: a plain transitive dependency, the excludes are not kept (simplification)",
        "    def create(Object c) { c }",
        "    def create(Object c, Closure cl) { c }",
        "}",
        "// 6.0: a license check throws GradleException (a Gradle default import)",
        "class GradleExceptionStub extends RuntimeException { GradleExceptionStub(String m) { super(m) } }",
        "abstract class DependenciesScript extends Script {",
        "    def dependencies = new DependenciesStub()",
        "    void ext(Closure c) { c.delegate = this; c.resolveStrategy = Closure.DELEGATE_FIRST; c() }",
        "    void allprojects(Closure c) {}",
        "    void subprojects(Closure c) {}",
        "    void configurations(Closure c) {}",
        "}",
        "def config = new org.codehaus.groovy.control.CompilerConfiguration(scriptBaseClass: DependenciesScript.name)",
        "config.addCompilationCustomizers(new org.codehaus.groovy.control.customizers.ImportCustomizer().addImport('GradleException', 'GradleExceptionStub'))",
        "def binding = new Binding()",
        "// another script name: a script named 'dependencies' makes dependencies.module() a static call on itself",
        "new GroovyShell(this.class.classLoader, binding, config).evaluate(new File(args[0]).text, 'DependenciesGradle')",
        "def flatten",
        "flatten = { d ->",
        "    if (d instanceof ClientModule) {",
        "        return [[coord: d.coord.replaceAll(/@jar$/, ''), transitive: false]] + d.deps.collectMany { flatten(it) }",
        "    }",
        "    if (d instanceof Collection) { return d.collectMany { flatten(it) } }",
        "    if (d instanceof Map) { d = d.coordinates ? \"${d.coordinates}:${d.version}\" : \"${d.group}:${d.name}:${d.version}\" }",
        "    def s = d.toString()",
        "    return [[coord: s.replaceAll(/@jar$/, ''), transitive: !s.endsWith('@jar')]]",
        "}",
        "binding.getVariable('libraries').each { k, v -> flatten(v).each { println \"${k}\\t${it.coord}\\t${it.transitive}\" } }",
        "");

    static final String COMPILE = String.join("\n",
        "// Joint Java/Groovy compilation of all the main sources: args = source list file, classpath file, output dir",
        "def sources = new File(args[0]).readLines().findAll { it }",
        "def cp = new File(args[1]).text.trim()",
        "def args2 = ['-j', '-Jsource=1.8', '-Jtarget=1.8', '-Jencoding=UTF-8', '--encoding', 'UTF-8',",
        "             '-cp', cp, '-d', args[2]] + sources",
        "org.codehaus.groovy.tools.FileSystemCompiler.commandLineCompile(args2 as String[], false)",
        "");

    static final String START_SCRIPT = String.join("\n",
        "#!/usr/bin/env sh",
        "# minimal start script of a bootstrap distribution (scripts/GradleMinimalBootstrap60.java)",
        "APP_HOME=$(cd \"$(dirname \"$0\")/..\" && pwd -P)",
        "JAVACMD=java",
        "[ -n \"$JAVA_HOME\" ] && JAVACMD=\"$JAVA_HOME/bin/java\"",
        "exec \"$JAVACMD\" $JAVA_OPTS $GRADLE_OPTS -Dorg.gradle.appname=gradle -classpath \"$APP_HOME/lib/@LAUNCHER@\" org.gradle.launcher.GradleMain \"$@\"",
        "");

    public static void main(String[] args) throws Exception {
        String tag = null;
        Path java = Paths.get("/data/devel/jdk8u482-b08");
        Path workRoot = Paths.get("work", "minimal").toAbsolutePath();
        Path target = null;
        boolean clean = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--java": java = Paths.get(args[++i]).toAbsolutePath(); break;
                case "--work": workRoot = Paths.get(args[++i]).toAbsolutePath(); break;
                case "--target": target = Paths.get(args[++i]).toAbsolutePath(); break;
                case "--clean": clean = true; break;
                default:
                    if (args[i].startsWith("-") || tag != null) {
                        System.err.println("usage: GradleMinimalBootstrap60 <tag> [--java JDK] [--work DIR] [--target DIR] [--clean]");
                        System.exit(2);
                    }
                    tag = args[i];
            }
        }
        if (tag == null) {
            System.err.println("usage: GradleMinimalBootstrap60 <tag> [--java JDK] [--work DIR] [--target DIR] [--clean]");
            System.exit(2);
        }
        GradleMinimalBootstrap60 b = new GradleMinimalBootstrap60(tag, java, workRoot);
        if (!Files.isDirectory(b.repo)) {
            System.err.println("run from the project directory: no gradle/ repository in " + b.root);
            System.exit(2);
        }
        try {
            if (clean) {
                deleteTree(b.work);
            }
            b.export();
            b.tools();
            b.modules();
            b.dependencies();
            b.resolve();
            b.compile();
            Path dist = b.distribution();
            if (target == null) {
                target = b.root.resolve("target").resolve("gradle-" + b.baseVersion + "-minimal");
            }
            deleteTree(target);
            copyTree(dist, target);
            target.resolve("bin").resolve("gradle").toFile().setExecutable(true, false);
            log("distribution: " + target + " (version " + b.version + ")");
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}
