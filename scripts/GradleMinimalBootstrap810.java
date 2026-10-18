/*
 * Minimal bootstrap of Gradle 8.10 (copy of GradleMinimalBootstrap80.java, adapted to 8.10): compile its sources without its own build, into a raw distribution that
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
 * Changes for 7.0 (from GradleMinimalBootstrap60.java):
 * - build logic in build-logic* (included builds) instead of buildSrc: read the same way (Kotlin sources)
 * - libraries: build-logic ExternalModulesExtension.kt (val name = "group:artifact", kotlinVersion, futureKotlin), versions
 *   from the platform subprojects/distributions-dependencies (api(libs.x) { version { strictly(...) } }); no Groovy
 *   evaluation; module build files subprojects/<dir>/build.gradle.kts with kebab project paths, libs.x, moduleIdentity
 * - Groovy 3 (org.codehaus.groovy:groovy, no groovy-all) compiles the sources; Kotlin options of 7.0 (api 1.4)
 * - distribution: lib/ = core-platform (installation-beacon, api-metadata, launcher, kotlin-dsl and their projects),
 *   lib/plugins/ = the rest; plugin lists from the plugin ids of the modules (gradlebuild.distribution.api-* /
 *   implementation-*), in a generated gradle-runtime-api-info jar with default imports, api mapping and relocated
 *   packages (no docs jar anymore); test-kit-relocated.txt in test-kit; gradle-wrapper.jar in the wrapper jar;
 *   launcher Class-Path from manifestClasspath(project(...))
 * Changes for 8.0 (from GradleMinimalBootstrap70.java):
 * - Kotlin options read from build-logic configureKotlinCompilerForGradleBuild (apiVersion, languageVersion,
 *   freeCompilerArgs, jvmTarget) instead of fixed values
 * - Kotlin in three phases, Java code now uses Kotlin code (enterprise uses configuration-cache's InputTrackingState):
 *   1. the Kotlin-only modules a Java/Groovy module depends on (configuration-cache, kotlin-dsl), kotlinc reading the
 *      Java sources of their project dependencies (no Kotlin code uses Groovy code: Groovy is down to code-quality
 *      and ide-native); 2. the Java/Groovy pass with those classes on its classpath; 3. the other Kotlin sources
 *      (kotlin-dsl-provider-plugins, kotlin-dsl-tooling-builders, the Kotlin file of the mixed platform-jvm) against
 *      the compiled classes (kotlinc cannot use compiled Kotlin whose signatures refer to types only present as Java
 *      sources: "cannot access class org.gradle.api.Project")
 * - Java compiled with -source 8 -target 8 against JDK 11, as for 5.0-7.0: the original build compiles most modules
 *   with --release 8 but 25 without it (Java 9+ / JDK internal APIs), a single compilation cannot do both; the one line
 *   that is ambiguous against the JDK 11 API is patched in the source export only (PATCHES, bootstrap compile only, the
 *   real rebuild uses the original sources)
 * - resources unpacked from a library into a module jar (configuration-cache: processResources from(zipTree(<conf>))
 *   into("org/gradle/configurationcache/problems"), the configuration-cache-report jar): resolved and unpacked; library
 *   constants with an interpolated version ("g:a:$xVersion") are read too
 * - the repositories of build-logic gradlebuild.repositories.gradle.kts are added to the Ivy chain (8.0:
 *   releases.usethesource.io for capsule, repo.gradle.org/gradle/public)
 * - new generated resources: api-metadata (gradle-api-declaration.properties, an empty parameter names index),
 *   docs release-features.txt
 * Changes for 8.10 (from GradleMinimalBootstrap80.java):
 * - project directories: settings subproject("x") under platforms/<platform>/x, subprojects/x, testing/x (a leaf name
 *   is unique); the modules are those of the distribution: subprojects/distributions-full and the platforms it uses
 *   (distributions-*, core-platform: their projects are expanded), with their project dependencies; type-safe
 *   project accessors (projects.camelCase); "org.gradle:x" without version: the build-logic project x (included build
 *   substitution: java-api-extractor, kotlin-dsl-shared-runtime are part of the distribution)
 * - annotation processors (annotationProcessor(projects.x), also through a gradlebuild.* plugin of build-logic:
 *   gradlebuild.instrumented-java-project, the internal-instrumentation-processor with -A...project=<name>): the
 *   processor modules are compiled with the others (tool modules, not distributed); then one javac -proc:only pass
 *   per processed module over its Java sources against the compiled classes, and a compilation of the generated
 *   sources (no hand-written code refers to them: they are found through META-INF/services)
 * - runtime-api-info: instrumented-super-types.properties and upgraded-properties.json (gradlebuild.instrumentation-
 *   metadata: the direct org/gradle super types of every class, kept for the instrumented classes the processor lists)
 * - gradle-kotlin-dsl-extensions jar (lib/): generated with the kotlin-dsl-shared-runtime facades (as build-logic
 *   GenerateKotlinExtensionsForGradleApi, through a small Java driver; no @since lookup), compiled with kotlinc
 * - lib/agents/ (distributions-full agentsRuntimeOnly, Premain-Class), -javaagent in bin/gradle; launchable jars
 *   (manifestClasspath(...), app { mainClassName }): gradle-cli-main is the entry point of bin/gradle
 * - Kotlin options in compilerOptions { } (KotlinVersion.KOTLIN_1_8, JvmTarget.JVM_1_8, freeCompilerArgs.addAll),
 *   also those of the gradlebuild.* plugins a module applies; library versions with if (isBundleGroovy4) .. else ..
 *   (the else branch: Groovy 3) and "$group:name" constants; embedded Kotlin from embeddedKotlinBaseDependencies
 * - zip resources: from(zipTree(conf.elements.map { ... })) { into(...) }
 *
 * Java 8 source, only the JDK. Run from the project directory (it holds gradle/ and target/):
 *   JDK 8:   mkdir -p work/minimal-tools && javac -d work/minimal-tools scripts/GradleMinimalBootstrap810.java
 *            java -cp work/minimal-tools GradleMinimalBootstrap810 v4.0.0
 *   JDK 11+: java scripts/GradleMinimalBootstrap810.java v4.0.0
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

public class GradleMinimalBootstrap810 {

    static final String CENTRAL = "https://repo1.maven.org/maven2";
    static final String GRADLE_LIBS = "https://repo.gradle.org/gradle/libs-releases";
    static final String[] IVY = {"org.apache.ivy", "ivy", "2.5.2"};
    static final List<String> MAIN_CONFS = Arrays.asList("compile", "runtime", "compileOnly", "api", "implementation", "runtimeOnly", "publishCompile");
    // projects in the distribution whose sources are not compiled (docs: documentation tooling, only generated resources)
    static final Set<String> NO_SOURCES = Collections.singleton("docs");
    static final String KOTLIN_VERSION = "@KOTLIN@";  // placeholder in coordinates, replaced by dependencies.gradle kotlinVersion
    static final String KOTLIN_COMPILER = "kotlin-compiler";  // Ivy configuration of the Kotlin compiler (a tool)
    // Groovy used to evaluate gradle/dependencies.gradle (any Groovy 2.4 runs it); the sources are compiled with the
    // target's own Groovy
    static final String[] EVAL_GROOVY = {"org.codehaus.groovy", "groovy-all", "2.4.15"};
    static final String EXTERNAL_MODULES = "external-modules";
    // source patches applied to the export (work/minimal/gradle-<v>/src) before the bootstrap compile only:
    // {file, original text, patched text, reason}
    static final String[][] PATCHES = {
        {"subprojects/core/src/main/java/org/gradle/api/internal/tasks/execution/DescribingAndSpec.java",
            "super(specHolder.getSpecs().toArray(Cast.uncheckedCast(new Spec[0])));",
            "super(specHolder.getSpecs().toArray(Cast.<Spec<? super T>[]>uncheckedCast(new Spec[0])));",
            "ambiguous against JDK 11 (Collection.toArray(IntFunction)); the original build compiles core with --release 8"},
    };
    static final String COMPILE_ONLY = "--compile-only";
    static final String ZIP_RESOURCES = "--zip-resources";  // 8.0: Ivy configuration of the libraries unpacked into a module jar  // Ivy configuration of the compileOnly dependencies of a module
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
        Path dir;           // 8.10: project directory
        boolean tool;       // 8.10: annotation processor, compiled but not distributed
        boolean agent;      // 8.10: Java agent, in lib/agents/
        List<String> processors = new ArrayList<>();         // 8.10: annotation processor modules
        List<String> processorOptions = new ArrayList<>();   // 8.10: -A options
        List<String> manifestClasspath = new ArrayList<>();  // 8.10: launchable jar Class-Path (modules)
        Map<String, String> manifest = new LinkedHashMap<>(); // 8.10: Main-Class, Premain-Class
        String build;
        List<String> projects = new ArrayList<>();
        List<String> libs = new ArrayList<>();
        List<String> coords = new ArrayList<>();
        List<String> optional = new ArrayList<>();  // classpathManifest optionalProjects
        String archive;     // jar / module name: gradle-<dir>, or base.archivesBaseName
        boolean javaToo;    // 8.0: also Java or Groovy sources (mixed module)
        List<String[]> zipResources = new ArrayList<>();  // 8.0: {library, into}: from(zipTree(<conf>)) { into(...) }
        List<Dep> zipDeps = new ArrayList<>();
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
    // 7.0: package-info.java are compiled (package annotations such as @NonNullApi: the Kotlin compiler needs them to
    // see the nullability of the Gradle API); one per package is compiled, its class goes into every module that has one
    final Map<String, Set<String>> packageInfoOwners = new HashMap<>();
    final Map<String, Path> externalJars = new TreeMap<>();  // the external modules (Kotlin DSL) and their dependencies
    // external module (artifact) -> the jar names of its own transitive dependencies (its classpath manifest)
    final Map<String, Set<String>> externalClosures = new TreeMap<>();

    GradleMinimalBootstrap810(String tag, Path java, Path workRoot) {
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

    /** applies PATCHES to the source export (once: the patched text is checked first) */
    void patchSources() throws IOException {
        for (String[] patch : PATCHES) {
            Path f = src.resolve(patch[0]);
            String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            if (text.contains(patch[2])) {
                continue;
            }
            if (!text.contains(patch[1])) {
                throw new IllegalStateException("patch: text not found in " + patch[0] + ": " + patch[1]);
            }
            Files.write(f, text.replace(patch[1], patch[2]).getBytes(StandardCharsets.UTF_8));
            log("patched " + patch[0] + " (" + patch[3] + ")");
        }
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
    /** 8.10: a project reference: projects.camelCase, project(":x") or "org.gradle:x" (build-logic project) */
    static final Pattern PROJECT_REF = Pattern.compile("\\bprojects\\.(\\w+)|project\\(\\s*(?:path:\\s*)?[\"']:([\\w-]+)[\"']|\"org\\.gradle:([\\w-]+)\"");

    static List<String> projectRefs(String text) {
        List<String> refs = new ArrayList<>();
        Matcher m = PROJECT_REF.matcher(text);
        while (m.find()) {
            refs.add(m.group(1) != null ? kebab(m.group(1)) : m.group(2) != null ? kebab(m.group(2)) : m.group(3));
        }
        return refs;
    }

    static boolean isPlatform(String d) {
        return d.startsWith("distributions-") || d.equals("core-platform");
    }

    /** 8.10: project references of the main configurations of a build file (one per line), and of its
     * annotationProcessor and manifestClasspath lines when asked */
    static List<String> mainProjectRefs(String build, String... confs) {
        List<String> wanted = new ArrayList<>(MAIN_CONFS);
        wanted.addAll(Arrays.asList(confs));
        List<String> refs = new ArrayList<>();
        Pattern confLine = Pattern.compile("^\\s*(\\w+)\\s*\\(?\\s*(.*)$");
        for (String line : build.split("\n")) {
            Matcher mm = confLine.matcher(line);
            if (mm.matches() && wanted.contains(mm.group(1))) {
                refs.addAll(projectRefs(mm.group(2)));
            }
        }
        return refs;
    }

    final Map<String, Path> projectDirs = new TreeMap<>();
    final List<String> coreModules = new ArrayList<>();  // 8.10: core-platform runtime(projects.x)

    Path mdir(String d) {
        return mods.get(d).dir;
    }

    /** the text of the build-logic precompiled script plugins (gradlebuild.x.gradle.kts) a build file applies */
    String appliedPlugins(String build) throws IOException {
        if (pluginScripts == null) {
            pluginScripts = new HashMap<>();
            for (Path p : walkAll("build-logic", "build-logic-commons")) {
                String fn = p.getFileName().toString();
                if (fn.startsWith("gradlebuild.") && fn.endsWith(".gradle.kts")) {
                    pluginScripts.put(fn.substring(0, fn.length() - ".gradle.kts".length()), new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String id : findAll("id\\(\"(gradlebuild\\.[\\w.-]+)\"\\)", build)) {
            sb.append(pluginScripts.getOrDefault(id, "")).append('\n');
        }
        return sb.toString();
    }

    Map<String, String> pluginScripts;

    void modules() throws IOException {
        // 8.10: project directories (platforms/<platform>/x, subprojects/x, testing/x; build-logic/x for "org.gradle:x")
        for (String root : Arrays.asList("platforms", "subprojects", "testing")) {
            for (Path build : walk(src.resolve(root))) {
                if (build.getFileName().toString().equals("build.gradle.kts") && !src.relativize(build).toString().contains("/src/")) {
                    String d = build.getParent().getFileName().toString();
                    if (projectDirs.containsKey(d)) {
                        throw new IllegalStateException("two projects named " + d + ": " + projectDirs.get(d) + ", " + build.getParent());
                    }
                    projectDirs.put(d, build.getParent());
                }
            }
        }
        // the distribution: distributions-full and the platforms it uses, then the project dependencies
        Deque<String> todo = new ArrayDeque<>();
        Set<String> agents = new TreeSet<>();
        Set<String> platforms = new TreeSet<>();
        Deque<String> platformTodo = new ArrayDeque<>(Collections.singletonList("distributions-full"));
        while (!platformTodo.isEmpty()) {
            String p = platformTodo.removeFirst();
            if (!platforms.add(p)) {
                continue;
            }
            String build = new String(Files.readAllBytes(projectDirs.get(p).resolve("build.gradle.kts")), StandardCharsets.UTF_8);
            for (String line : build.split("\n")) {
                Matcher mm = Pattern.compile("^\\s*(\\w+)\\s*\\((.*)$").matcher(line);
                if (!mm.matches() || mm.group(1).startsWith("test") || mm.group(1).startsWith("integTest")) {
                    continue;
                }
                for (String ref : projectRefs(mm.group(2))) {
                    if (isPlatform(ref)) {
                        platformTodo.add(ref);
                    } else if (mm.group(1).equals("agentsRuntimeOnly")) {
                        agents.add(ref);
                        todo.add(ref);
                    } else {
                        todo.add(ref);
                    }
                    if (p.equals("core-platform") && !isPlatform(ref)) {
                        coreModules.add(ref);
                    }
                }
            }
        }
        Set<String> processorModules = new TreeSet<>();
        while (!todo.isEmpty()) {
            String d = todo.removeFirst();
            if (mods.containsKey(d)) {
                continue;
            }
            Path dir = projectDirs.containsKey(d) ? projectDirs.get(d) : src.resolve("build-logic").resolve(d);
            if (!Files.isRegularFile(dir.resolve("build.gradle.kts"))) {
                throw new IllegalStateException("no project directory for " + d);
            }
            Module m = new Module();
            m.dir = dir;
            m.build = new String(Files.readAllBytes(dir.resolve("build.gradle.kts")), StandardCharsets.UTF_8);
            m.agent = agents.contains(d);
            Matcher an = Pattern.compile("(?:base\\.archivesBaseName\\s*=|moduleIdentity\\.baseName\\.set\\()\\s*\"([^\"]+)\"").matcher(m.build);
            m.archive = an.find() ? an.group(1).replace("${libs.kotlinVersion}", KOTLIN_VERSION).replace("$kotlinVersion", KOTLIN_VERSION) : "gradle-" + d;
            Path main = dir.resolve("src").resolve("main");
            m.kotlin = !walk(main.resolve("kotlin")).isEmpty();  // 8.0: also mixed modules (platform-jvm)
            m.javaToo = !walk(main.resolve("java")).isEmpty() || !walk(main.resolve("groovy")).isEmpty();
            // annotation processors, of the build file or of an applied gradlebuild.* plugin (with its -A options)
            String withPlugins = m.build + "\n" + appliedPlugins(m.build);
            for (String line : withPlugins.split("\n")) {
                if (line.trim().startsWith("annotationProcessor(")) {
                    for (String ref : projectRefs(line)) {
                        if (!isPlatform(ref) && !m.processors.contains(ref)) {
                            m.processors.add(ref);
                            processorModules.add(ref);
                            todo.add(ref);
                        }
                    }
                }
            }
            for (String opt : findAll("compilerArgs\\.add\\(\"(-A[^\"]+)\"\\)", withPlugins)) {
                m.processorOptions.add(opt.replace("${project.name}", d));
            }
            // launchable jar: manifestClasspath(projects.x), app { mainClassName = "..." }; jar manifest attributes
            for (String line : m.build.split("\n")) {
                if (line.trim().startsWith("manifestClasspath(")) {
                    m.manifestClasspath.addAll(projectRefs(line));
                }
            }
            String mainClass = findOrNull("mainClassName\\s*=\\s*\"([^\"]+)\"", m.build);
            if (mainClass != null) {
                m.manifest.put("Main-Class", mainClass);
            }
            String premain = findOrNull("\"Premain-Class\"\\s+to\\s+\"([^\"]+)\"", m.build);
            if (premain != null) {
                m.manifest.put("Premain-Class", premain);
            }
            mods.put(d, m);
            for (String ref : mainProjectRefs(m.build)) {
                if (!isPlatform(ref) && !ref.equals(d)) {
                    todo.add(ref);
                }
            }
        }
        for (String p : processorModules) {
            // a processor only reached as a processor is a tool
            boolean used = false;
            for (Map.Entry<String, Module> e : mods.entrySet()) {
                used |= mainProjectRefs(e.getValue().build).contains(p);
            }
            mods.get(p).tool = !used && !coreModules.contains(p);
        }
        log(mods.size() + " modules (" + agents.size() + " agents, tools: "
            + mods.entrySet().stream().filter(x -> x.getValue().tool).map(Map.Entry::getKey).collect(Collectors.joining(", "))
            + "), platforms " + platforms);
        Pattern confLine = Pattern.compile("^\\s*(\\w+)\\s*\\(?\\s*(.*)$");
        for (Map.Entry<String, Module> e : mods.entrySet()) {
            String d = e.getKey();
            Module m = e.getValue();
            // classpathManifest { additionalProjects = [...] }: projects only listed in the classpath manifest
            for (String refs : findAll("additionalProjects\\s*=\\s*(?:listOf\\(|\\[)([^\\]\\n]*)", m.build)) {
                for (String p : findAll("project\\(['\"]:([\\w-]+)['\"]\\)", refs)) {
                    if (mods.containsKey(kebab(p))) {
                        m.projects.add(kebab(p));
                    }
                }
            }
            for (String ref : findAll("additionalProjects\\.add\\(\"(:[\\w-]+)\"\\)", m.build)) {  // 6.0 form
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
            // 8.0: processResources { from(zipTree(provider { conf.files.first() })) { into("path") } } with conf(libs.x)
            // 8.10: from(zipTree(conf.elements.map { it.first().asFile })) { into("path") }
            Matcher zt = Pattern.compile("(?s)from\\(zipTree\\((?:provider\\s*\\{\\s*(\\w+)\\.files\\.first\\(\\)\\s*\\}|(\\w+)\\.elements\\.map\\s*\\{[^}]*\\})\\)\\)\\s*\\{\\s*into\\(\"([^\"]+)\"\\)").matcher(m.build);
            while (zt.find()) {
                String conf = zt.group(1) != null ? zt.group(1) : zt.group(2);
                for (String lib : findAll("\\b" + conf + "\\(libs\\.(\\w+)\\)", m.build)) {
                    m.zipResources.add(new String[]{lib, zt.group(3)});
                }
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
                libs.addAll(findAll("\\blibs\\.(\\w+)\\b(?!\\s*\\()", args));  // 7.0: libs.x (not libs.futureKotlin(...))
                for (String k : projectRefs(args)) {  // 8.10: projects.x, "org.gradle:x"
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

    /** 7.0: the libraries of build-logic ExternalModulesExtension.kt (val name = "group:artifact") with the versions of
     * the platform subprojects/distributions-dependencies, written as libraries.tsv (name, coordinates, transitive) */
    boolean kotlinLibraries(Path out) throws IOException {
        Path ext = null;
        for (String dir : Arrays.asList("build-logic", "build-logic-commons", "buildSrc")) {
            for (Path p : walk(src.resolve(dir))) {
                if (p.getFileName().toString().equals("ExternalModulesExtension.kt")) {
                    ext = p;
                }
            }
        }
        if (ext == null) {
            return false;
        }
        String e = new String(Files.readAllBytes(ext), StandardCharsets.UTF_8);
        Map<String, String> constants = new HashMap<>();
        Matcher c = Pattern.compile("\\bval\\s+(\\w+)\\s*=\\s*\"([^\"$]+)\"").matcher(e);
        while (c.find()) {
            constants.put(c.group(1), c.group(2));
        }
        // 8.10: val x = if (isBundleGroovy4) "a" else "b": the else branch (the default build, Groovy 3)
        Pattern conditional = Pattern.compile("\\bval\\s+(\\w+)\\s*=\\s*if\\s*\\([^)]*\\)\\s*\"[^\"]*\"\\s*else\\s*\"([^\"$]+)\"");
        Matcher cc = conditional.matcher(e);
        while (cc.find()) {
            constants.put(cc.group(1), cc.group(2));
        }
        // 8.10: val groovy = "$groovyGroup:groovy" (constants interpolated in other constants)
        Matcher ic = Pattern.compile("\\bval\\s+(\\w+)\\s*=\\s*\"([^\"]*\\$[^\"]*)\"").matcher(e);
        while (ic.find()) {
            Matcher ref = Pattern.compile("\\$\\{?(\\w+)\\}?").matcher(ic.group(2));
            StringBuffer sb = new StringBuffer();
            boolean resolved = true;
            while (ref.find()) {
                String value = constants.get(ref.group(1));
                resolved &= value != null;
                ref.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : value));
            }
            ref.appendTail(sb);
            if (resolved && sb.toString().split(":").length == 2) {
                constants.put(ic.group(1), sb.toString());
            }
        }
        String platform = read("subprojects/distributions-dependencies/build.gradle.kts");
        Map<String, String> locals = new HashMap<>();
        Matcher l = Pattern.compile("(?m)^val\\s+(\\w+)\\s*=\\s*\"([^\"]+)\"").matcher(platform);
        while (l.find()) {
            locals.put(l.group(1), l.group(2));
        }
        Matcher lc = conditional.matcher(platform);
        while (lc.find()) {
            locals.put(lc.group(1), lc.group(2));
        }
        StringBuilder tsv = new StringBuilder();
        // 8.0: constants with an interpolated version, val x = "g:a:$xVersion"
        Matcher iv = Pattern.compile("\\bval\\s+(\\w+)\\s*=\\s*\"([^\":]+:[^\":]+):\\$(\\w+)\"").matcher(e);
        while (iv.find()) {
            if (constants.containsKey(iv.group(3))) {
                tsv.append(iv.group(1)).append('\t').append(iv.group(2)).append(':').append(constants.get(iv.group(3))).append("\ttrue\n");
            }
        }
        // 8.10: val kotlinStdlib = futureKotlin("stdlib") (version kotlinVersion)
        Matcher fk = Pattern.compile("\\bval\\s+(\\w+)\\s*=\\s*futureKotlin\\(\"([\\w-]+)\"\\)").matcher(e);
        while (fk.find()) {
            tsv.append(fk.group(1)).append("\torg.jetbrains.kotlin:kotlin-").append(fk.group(2)).append(':').append(constants.get("kotlinVersion")).append("\ttrue\n");
        }
        Matcher v = Pattern.compile("api\\(libs\\.(\\w+)\\)\\s*\\{\\s*version\\s*\\{\\s*(?:strictly|require|prefer)\\(\\s*([^)]+?)\\s*\\)").matcher(platform);
        while (v.find()) {
            String name = v.group(1);
            String ver = v.group(2);
            if (ver.startsWith("\"")) {
                ver = ver.substring(1, ver.length() - 1);
            } else if (ver.startsWith("libs.")) {
                ver = constants.get(ver.substring(5));
            } else {
                ver = locals.get(ver);
            }
            String ga = constants.get(name);
            if (ga == null || ver == null || ga.split(":").length != 2) {
                log("library " + name + ": no coordinates or version (" + ga + ", " + v.group(2) + "), left out");
                continue;
            }
            tsv.append(name).append('\t').append(ga).append(':').append(ver).append("\ttrue\n");
        }
        write(out, tsv.toString());
        return true;
    }

    void dependencies() throws Exception {
        Path out = work.resolve("deps").resolve("libraries.tsv");
        if (!Files.isRegularFile(out) && !kotlinLibraries(out)) {
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
        // (7.0: the selected module can be one of the providers, e.g. groovy: it is kept)
        Matcher cap = Pattern.compile("\\{[^{}]*\"providedBy\"\\s*:\\s*\\[([^\\]]*)\\][^{}]*\"selected\"\\s*:\\s*\"([^\"]+)\"[^{}]*\\}")
            .matcher(read("gradle/dependency-management/capabilities.json"));
        while (cap.find()) {
            String providers = cap.group(1);
            String selected = cap.group(2);
            for (String ga : findAll("\"([^\":]+:[^\"]+)\"", providers)) {
                if (ga.equals(selected)) {
                    continue;
                }
                String[] x = ga.split(":");
                globalExcludes.add(new String[]{"org", x[0], "module", x[1]});
            }
        }
        // 7.0: withLibraryDependencies<DependencyRemovalByNameRule>(libs.x | "g:a", setOf(...)), withModule<ExcludeDependencies>("g:a")
        Matcher rule7 = Pattern.compile("withLibraryDependencies<DependencyRemovalBy(Group|Name)Rule>\\(\\s*(?:libs\\.(\\w+)|\"([^\"]+)\")\\s*,\\s*setOf\\(([^)]*)\\)\\)").matcher(rules);
        while (rule7.find()) {
            for (String v : findAll("\"([^\"]+)\"", rule7.group(4))) {
                String[] x = {rule7.group(1).equals("Group") ? "org" : "module", v};
                if (rule7.group(2) != null) {
                    globalExcludes.add(x);
                } else {
                    ivyExcludes.computeIfAbsent(rule7.group(3), k -> new ArrayList<>()).add(x);
                }
            }
        }
        nonTransitive.addAll(findAll("withModule<ExcludeDependencies>\\(\\s*\"([^\"]+)\"\\s*\\)", rules));
        nonTransitive.addAll(findAll("withModule\\(\\s*\"([^\"]+)\"\\s*,\\s*ExcludeDependencies::class", rules));
        // kotlinVersion (ext of dependencies.gradle) replaces the placeholder of futureKotlin() and archive names
        String versionsSource = read("gradle/dependencies.gradle");
        if (versionsSource.isEmpty()) {  // 7.0: build-logic ExternalModulesExtension.kt
            versionsSource = buildSrcKotlin();
        }
        kotlinVersion = find("kotlinVersion\\s*=\\s*['\"]([^'\"]+)['\"]", versionsSource, 0);
        for (Module mod : mods.values()) {
            mod.archive = mod.archive.replace(KOTLIN_VERSION, kotlinVersion);
            mod.coords.replaceAll(c -> c.replace(KOTLIN_VERSION, kotlinVersion));
            mod.compileOnlyCoords.replaceAll(c -> c.replace(KOTLIN_VERSION, kotlinVersion));
        }
        log(libraries.size() + " libraries, kotlin " + kotlinVersion + ", substitutions: " + substitutions + ", excludes: "
            + globalExcludes.stream().map(x -> x[0] + "=" + x[1]).collect(Collectors.joining(" ")) + "; nested: " + ivyExcludes.entrySet().stream().map(x -> x.getKey() + x.getValue().stream().map(v -> " " + v[0] + "=" + v[1]).collect(Collectors.joining())).collect(Collectors.joining("; ")) + ", non transitive: " + nonTransitive);
        for (Map.Entry<String, Module> e : mods.entrySet()) {
            Module mod = e.getValue();
            for (String[] z : mod.zipResources) {
                if (!libraries.containsKey(z[0])) {
                    throw new IllegalStateException(e.getKey() + ": unknown library for zip resources: " + z[0]);
                }
                for (Dep dep : libraries.get(z[0])) {
                    mod.zipDeps.add(new Dep(dep.coord, false));
                }
            }
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
            // 7.0: the sam-with-receiver compiler plugin (gradlebuild.kotlin-dsl-sam-with-receiver), the variant for the
            // embeddable (shaded) compiler: kotlin-sam-with-receiver, not kotlin-sam-with-receiver-compiler-plugin
            // 8.10 (Kotlin 1.9): kotlin-sam-with-receiver is only the Gradle subplugin, the compiler plugin of the
            // embeddable compiler is kotlin-sam-with-receiver-compiler-plugin-embeddable
            String samWithReceiver = "org.jetbrains.kotlin:kotlin-sam-with-receiver-compiler-plugin-embeddable:" + kotlinVersion;
            confsByCoord.computeIfAbsent(samWithReceiver, k -> new ArrayList<>()).add(KOTLIN_COMPILER);
            transitiveByCoord.merge(samWithReceiver, false, Boolean::logicalOr);
            // 8.10: kotlin("plugin.serialization") (declarative-dsl): the kotlinx-serialization compiler plugin
            String serialization = "org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:" + kotlinVersion;
            confsByCoord.computeIfAbsent(serialization, k -> new ArrayList<>()).add(KOTLIN_COMPILER);
            transitiveByCoord.merge(serialization, false, Boolean::logicalOr);
            // 8.10: embeddedKotlinBaseDependencies(libs.kotlinStdlib) ... (gradlebuild.kotlin-dsl-dependencies-embedded)
            List<String> embedded = new ArrayList<>();
            for (String lib : findAll("embeddedKotlinBaseDependencies\\(libs\\.kotlin(\\w+)\\)", buildSrcKotlin())) {
                embedded.add(kebab(Character.toLowerCase(lib.charAt(0)) + lib.substring(1)));
            }
            for (String k : embedded.isEmpty() ? Arrays.asList("stdlib-jdk8", "reflect") : embedded) {
                String c = "org.jetbrains.kotlin:kotlin-" + k + ":" + kotlinVersion;
                confsByCoord.computeIfAbsent(c, x -> new ArrayList<>()).add("embedded-kotlin");
                transitiveByCoord.merge(c, true, Boolean::logicalOr);
            }
            for (Map.Entry<String, Module> e : mods.entrySet()) {
                for (Dep dep : e.getValue().zipDeps) {
                    confsByCoord.computeIfAbsent(dep.coord, k -> new ArrayList<>()).add(e.getKey() + ZIP_RESOURCES);
                    transitiveByCoord.merge(dep.coord, false, Boolean::logicalOr);
                }
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
                if (!e.getValue().zipDeps.isEmpty()) {
                    xml.append("<conf name=\"").append(e.getKey()).append(ZIP_RESOURCES).append("\"/>");
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
                + buildRepositories()
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
                if (!e.getValue().zipDeps.isEmpty()) {
                    cmd.add(e.getKey() + ZIP_RESOURCES);
                }
            }
            cmd.addAll(Arrays.asList("-retrieve", depsDir + "/[conf]/[organisation]/[artifact]-[revision](-[classifier]).[ext]", "-types", "jar", "bundle",
                // 7.0: packagings Ivy maps to a jar (org.eclipse.sisu.plexus is an eclipse-plugin)
                "eclipse-plugin", "maven-plugin", "ejb", "orbit", "hk2-jar"));
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
        collectSources();
        compileKotlin(true);
        compileJavaGroovy();
        compileKotlin(false);
        annotationProcessing();
    }

    String javacCmd() {
        return java.resolve("bin").resolve("javac").toString();
    }

    /** 8.10: the annotation processors of a module (the instrumentation processor) run over its Java sources against
     * the compiled classes (javac -proc:only), the generated sources are then compiled; output: apt/<module>/classes */
    void annotationProcessing() throws Exception {
        List<String> cp = compileClasspath();
        cp.add(work.resolve("classes").toString());
        for (String k : mods.keySet()) {
            Path kc = work.resolve("kotlin-classes").resolve(k);
            if (Files.isDirectory(kc)) {
                cp.add(kc.toString());
            }
        }
        String classpath = String.join(File.pathSeparator, cp);
        for (Map.Entry<String, Module> e : mods.entrySet()) {
            String d = e.getKey();
            Module m = e.getValue();
            if (m.processors.isEmpty()) {
                continue;
            }
            Path base = work.resolve("apt").resolve(d);
            Path out = base.resolve("classes");
            if (Files.isRegularFile(base.resolve(".done"))) {
                continue;
            }
            deleteTree(base);
            Path gen = base.resolve("generated");
            Files.createDirectories(out);
            Files.createDirectories(gen);
            List<String> processorClasses = new ArrayList<>();
            for (String p : m.processors) {
                Path services = mdir(p).resolve("src/main/resources/META-INF/services/javax.annotation.processing.Processor");
                for (String line : Files.readAllLines(services, StandardCharsets.UTF_8)) {
                    if (!line.trim().isEmpty() && !line.trim().startsWith("#")) {
                        processorClasses.add(line.trim());
                    }
                }
            }
            List<String> files = new ArrayList<>();
            for (Map.Entry<Path, Source> s : sources.entrySet()) {
                if (s.getValue().module.equals(d) && s.getKey().toString().endsWith(".java")) {
                    files.add(s.getKey().toString());
                }
            }
            List<String> args = new ArrayList<>(Arrays.asList("-proc:only", "-implicit:none", "-source", "8", "-target", "8",
                "-encoding", "UTF-8", "-nowarn", "-cp", classpath, "-processorpath", classpath,
                "-processor", String.join(",", processorClasses), "-s", gen.toString(), "-d", out.toString()));
            args.addAll(m.processorOptions);
            args.addAll(files);
            Path argfile = base.resolve("apt.args");
            write(argfile, args.stream().map(a -> "\"" + a.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").collect(Collectors.joining("\n")));
            log("annotation processing " + d + ": " + files.size() + " sources, " + processorClasses + " " + m.processorOptions);
            Path logFile = base.resolve("apt.log");
            int rc = execStatus(logFile, javacCmd(), "-J-Xmx2g", "@" + argfile);
            if (rc != 0) {
                throw new IllegalStateException("annotation processing " + d + " failed, see " + logFile + "\n" + tail(new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8), 4000));
            }
            List<String> generated = walk(gen).stream().map(Path::toString).filter(x -> x.endsWith(".java")).collect(Collectors.toList());
            if (!generated.isEmpty()) {
                List<String> cargs = new ArrayList<>(Arrays.asList("-proc:none", "-source", "8", "-target", "8", "-encoding", "UTF-8",
                    "-nowarn", "-cp", classpath, "-d", out.toString()));
                cargs.addAll(generated);
                Path cargfile = base.resolve("javac.args");
                write(cargfile, cargs.stream().map(a -> "\"" + a.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").collect(Collectors.joining("\n")));
                rc = execStatus(logFile, javacCmd(), "-J-Xmx2g", "@" + cargfile);
                if (rc != 0) {
                    throw new IllegalStateException("compilation of the generated sources of " + d + " failed, see " + logFile + "\n" + tail(new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8), 4000));
                }
            }
            log(d + ": " + generated.size() + " generated sources, " + walk(out).size() + " files");
            Files.createFile(base.resolve(".done"));
        }
    }

    /** 8.0: the Kotlin-only modules that a module with Java/Groovy code depends on, with their Kotlin-only dependencies */
    Set<String> earlyKotlin() {
        Set<String> early = new TreeSet<>();
        for (Module m : mods.values()) {
            if (m.javaToo) {
                for (String p : m.projects) {
                    if (mods.get(p).kotlin) {  // 8.10: also mixed modules (declarative-dsl-tooling-models)
                        early.add(p);
                    }
                }
            }
        }
        for (String k : new ArrayList<>(early)) {
            for (String p : projectClosure(Collections.singletonList(k))) {
                if (mods.get(p).kotlin) {
                    early.add(p);
                }
            }
        }
        return early;
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
    void compileKotlin(boolean early) throws Exception {
        Set<String> earlySet = earlyKotlin();
        List<String> order = new ArrayList<>();
        for (String d : mods.keySet()) {
            visit(d, order, new ArrayDeque<>());
        }
        List<String> cp = compileClasspath();
        if (!early) {
            cp.add(work.resolve("classes").toString());
            for (String k : earlySet) {
                cp.add(work.resolve("kotlin-classes").resolve(k).toString());
            }
        }
        // project(":kotlinCompilerEmbeddable"): its classes are those of the Kotlin compiler jar
        for (Path p : kotlinCompilerJars) {
            if (p.getFileName().toString().equals("kotlin-compiler-embeddable-" + kotlinVersion + ".jar")) {
                cp.add(p.toString());
            }
        }
        String compilerCp = kotlinCompilerJars.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        for (String d : order) {
            if (!mods.get(d).kotlin || earlySet.contains(d) != early) {
                continue;
            }
            Path out = work.resolve("kotlin-classes").resolve(d);
            if (!Files.isRegularFile(out.resolve(".done"))) {
                deleteTree(out);
                Files.createDirectories(out);
                List<String> files = new ArrayList<>();
                for (Path p : walk(mdir(d).resolve("src").resolve("main").resolve("kotlin"))) {
                    if (p.toString().endsWith(".kt")) {
                        files.add(p.toString());
                    }
                }
                for (Path p : generatedKotlin(d)) {
                    files.add(p.toString());
                }
                // 8.10: phase 1, the Kotlin sources of the early Kotlin modules it depends on are compiled again with it
                // (their compiled classes refer to types only present as Java sources: "cannot access class"); their
                // classes are removed from its output afterwards
                List<String> kotlinDeps = new ArrayList<>();
                if (early) {
                    for (String dep : projectClosure(Collections.singletonList(d))) {
                        if (!dep.equals(d) && earlySet.contains(dep)) {
                            kotlinDeps.add(dep);
                            for (Path p : walk(mdir(dep).resolve("src").resolve("main").resolve("kotlin"))) {
                                if (p.toString().endsWith(".kt")) {
                                    files.add(p.toString());
                                }
                            }
                            for (Path p : generatedKotlin(dep)) {
                                files.add(p.toString());
                            }
                        }
                    }
                }
                // phase 1: the Java sources of the module and of its project dependencies (not compiled yet: kotlinc reads them)
                for (String dep : early ? projectClosure(Collections.singletonList(d)) : Collections.<String>emptyList()) {
                    Path javaRoot = mdir(dep).resolve("src").resolve("main").resolve("java");
                    if (Files.isDirectory(javaRoot) && !NO_SOURCES.contains(dep)) {
                        files.add(javaRoot.toString());
                    }
                }
                // options of build-logic configureKotlinCompilerForGradleBuild (7.0)
                List<String> args = new ArrayList<>(Arrays.asList("-d", out.toString(), "-classpath", String.join(File.pathSeparator, cp),
                    "-no-stdlib", "-no-reflect", "-module-name", mods.get(d).archive));
                args.addAll(kotlinOptions());
                // 8.0: module options of its build file (configuration-cache: -opt-in=..., progressive mode)
                String build = mods.get(d).build;
                // 8.10: also those of the gradlebuild.* plugins it applies (gradlebuild.kotlin-experimental-contracts)
                // (and those of the early Kotlin modules compiled with it, phase 1)
                List<String> optionOwners = new ArrayList<>(Collections.singletonList(d));
                optionOwners.addAll(kotlinDeps);
                // 8.10: apiVersion.set(KotlinVersion.KOTLIN_1_9) / languageVersion of the module (the highest one of
                // the modules compiled together)
                for (String[] o : new String[][]{{"apiVersion", "-api-version"}, {"languageVersion", "-language-version"}}) {
                    String highest = null;
                    for (String owner : optionOwners) {
                        String v = findOrNull("\\b" + o[0] + "\\.set\\(KotlinVersion\\.KOTLIN_(\\d+_\\d+)\\)", mods.get(owner).build);
                        if (v != null && (highest == null || compareVersions(v.replace('_', '.'), highest) > 0)) {
                            highest = v.replace('_', '.');
                        }
                    }
                    int at = args.indexOf(o[1]);
                    if (highest != null && at >= 0 && compareVersions(highest, args.get(at + 1)) > 0) {
                        args.set(at + 1, highest);
                    }
                }
                boolean serializationPlugin = false;
                for (String owner : optionOwners) {
                    serializationPlugin |= mods.get(owner).build.contains("plugin.serialization");
                }
                if (serializationPlugin) {
                    Path plugin = kotlinCompilerJars.stream().filter(p -> p.getFileName().toString().equals("kotlin-serialization-compiler-plugin-embeddable-" + kotlinVersion + ".jar"))
                        .findFirst().orElseThrow(() -> new IllegalStateException("kotlin-serialization-compiler-plugin-embeddable " + kotlinVersion + " not resolved"));
                    args.add("-Xplugin=" + plugin);
                }
                for (String owner : optionOwners) {
                    String ownerBuild = mods.get(owner).build;
                    for (String extra : freeCompilerArgs(ownerBuild + "\n" + appliedPlugins(ownerBuild))) {
                        if (!args.contains(extra)) {
                            args.add(extra);
                        }
                    }
                }
                if (build.contains("progressiveMode = true")) {
                    args.add("-progressive");
                }
                // 7.0: modules with id("gradlebuild.kotlin-dsl-sam-with-receiver"): samWithReceiver { annotation("org.gradle.api.HasImplicitReceiver") }
                if (optionOwners.stream().anyMatch(o -> mods.get(o).build.contains("id(\"gradlebuild.kotlin-dsl-sam-with-receiver\")"))) {
                    Path plugin = kotlinCompilerJars.stream().filter(p -> p.getFileName().toString().equals("kotlin-sam-with-receiver-compiler-plugin-embeddable-" + kotlinVersion + ".jar"))
                        .findFirst().orElseThrow(() -> new IllegalStateException("kotlin-sam-with-receiver " + kotlinVersion + " not resolved"));
                    args.add("-Xplugin=" + plugin);
                    args.add("-P");
                    args.add("plugin:org.jetbrains.kotlin.samWithReceiver:annotation=org.gradle.api.HasImplicitReceiver");
                }
                args.addAll(files);
                Path argfile = work.resolve("kotlin-classes").resolve(d + ".args");
                write(argfile, args.stream().map(a -> "\"" + a.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").collect(Collectors.joining("\n")));
                log("kotlinc " + d + ": " + files.size() + " sources");
                Path logFile = work.resolve("kotlin-classes").resolve(d + ".log");
                int rc = execStatus(logFile, javaCmd(), "-Xmx4g", "-cp", compilerCp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "@" + argfile);
                if (rc != 0) {
                    throw new IllegalStateException("kotlinc " + d + " failed, see " + logFile + "\n" + tail(new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8), 4000));
                }
                int removed = 0;
                for (String dep : kotlinDeps) {
                    Path depOut = work.resolve("kotlin-classes").resolve(dep);
                    for (Path p : walk(depOut)) {
                        String rel = depOut.relativize(p).toString();
                        if (rel.endsWith(".class") && Files.deleteIfExists(out.resolve(rel))) {
                            removed++;
                        }
                    }
                }
                if (removed > 0) {
                    log(d + ": " + removed + " classes of " + kotlinDeps + " removed from its output");
                }
                Files.createFile(out.resolve(".done"));
            }
            if (!early) {
                cp.add(out.toString());
            }
        }
    }

    /** 8.0: the Maven repositories of build-logic gradlebuild.repositories.gradle.kts (url = uri("...")), as Ivy resolvers */
    String buildRepositories() throws IOException {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Path p : walkAll("build-logic")) {
            if (p.getFileName().toString().equals("gradlebuild.repositories.gradle.kts")) {
                for (String url : findAll("url\\s*=\\s*uri\\(\"(https://[^\"]+)\"\\)", new String(Files.readAllBytes(p), StandardCharsets.UTF_8))) {
                    if (!url.contains("bintray")) {
                        sb.append("<ibiblio name=\"build").append(i++).append("\" m2compatible=\"true\" root=\"")
                            .append(url.endsWith("/") ? url : url + "/").append("\"/>");
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 8.0: the options of build-logic configureKotlinCompilerForGradleBuild (kotlinOptions { apiVersion = ...;
     * languageVersion = ...; freeCompilerArgs += listOf(...); jvmTarget = ... }) as kotlinc arguments */
    List<String> kotlinOptions() throws IOException {
        String kt = buildSrcKotlin();
        Matcher f = Pattern.compile("(?s)fun KotlinCompile\\.configureKotlinCompilerForGradleBuild\\([^)]*\\)\\s*\\{(.*?)\\n\\}").matcher(kt);
        if (!f.find()) {
            throw new IllegalStateException("no configureKotlinCompilerForGradleBuild in build-logic");
        }
        String body = f.group(1);
        List<String> opts = new ArrayList<>();
        for (String[] o : new String[][]{{"jvmTarget", "-jvm-target"}, {"apiVersion", "-api-version"}, {"languageVersion", "-language-version"}}) {
            Matcher m = Pattern.compile("\\b" + o[0] + "\\s*=\\s*\"([^\"]+)\"").matcher(body);
            // 8.10: compilerOptions { apiVersion = KotlinVersion.KOTLIN_1_8; jvmTarget = JvmTarget.JVM_1_8 }
            Matcher e = Pattern.compile("\\b" + o[0] + "\\s*=\\s*(?:KotlinVersion\\.KOTLIN|JvmTarget\\.JVM)_(\\d+)_?(\\d*)").matcher(body);
            if (m.find()) {
                opts.add(o[1]);
                opts.add(m.group(1));
            } else if (e.find()) {
                opts.add(o[1]);
                opts.add(e.group(2).isEmpty() ? e.group(1) : e.group(1) + "." + e.group(2));
            }
        }
        opts.addAll(freeCompilerArgs(body));
        return opts;
    }

    /** freeCompilerArgs += listOf(...) / freeCompilerArgs.addAll(...) (8.10) */
    static List<String> freeCompilerArgs(String text) {
        List<String> args = new ArrayList<>();
        for (String list : findAll("(?s)freeCompilerArgs\\s*(?:\\+=\\s*listOf|\\.addAll)\\((.*?)\\)", text)) {
            args.addAll(findAll("\"([^\"]+)\"", list));
        }
        return args;
    }

    /** sources generated by the build of a Kotlin module: kotlin-dsl (buildSrc GenerateKotlinDependencyExtensions) */
    List<Path> generatedKotlin(String d) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!d.equals("kotlin-dsl")) {
            return files;
        }
        String generator = "";
        for (Path p : walkAll("buildSrc", "build-logic")) {
            if (p.getFileName().toString().equals("GenerateKotlinDependencyExtensions.kt")) {
                generator = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
        }
        String pluginsVersion = find("publishedKotlinDslPluginVersion\\s*=\\s*\"([^\"]+)\"", mods.get(d).build + buildSrcKotlin(), 0);
        Matcher m = Pattern.compile("(?s)writeFile\\(\\s*\"([^\"]+)\",\\s*\"\"\"(.*?)\"\"\"\\s*\\)").matcher(generator);
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

    void collectSources() throws IOException {
        for (String d : mods.keySet()) {
            if (NO_SOURCES.contains(d)) {
                continue;
            }
            for (String lang : Arrays.asList("java", "groovy")) {
                Path base = mdir(d).resolve("src").resolve("main").resolve(lang);
                for (Path p : walk(base)) {
                    String fn = p.getFileName().toString();
                    if (fn.equals("package-info.java")) {
                        String rel = base.relativize(p).toString();
                        String key = rel.substring(0, rel.lastIndexOf('.'));
                        Set<String> owners = packageInfoOwners.computeIfAbsent(key, k -> new TreeSet<>());
                        if (owners.isEmpty()) {
                            sources.put(p, new Source(d, key));
                        }
                        owners.add(d);
                    } else if (fn.endsWith(".java") || fn.endsWith(".groovy")) {
                        String rel = base.relativize(p).toString();
                        sources.put(p, new Source(d, rel.substring(0, rel.lastIndexOf('.'))));
                    }
                }
            }
        }
    }

    void compileJavaGroovy() throws Exception {
        Path out = work.resolve("classes");
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
        // 8.0: the Kotlin classes, compiled first
        for (String d : mods.keySet()) {
            Path k = work.resolve("kotlin-classes").resolve(d);
            if (Files.isDirectory(k)) {
                jars.add(k.toString());
            }
        }
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
            if (packageInfoOwners.containsKey(base)) {  // into every module with a package-info for that package
                for (String pkgOwner : packageInfoOwners.get(base)) {
                    classes.get(pkgOwner).add(rel);
                }
                continue;
            }
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
        // 7.0: subprojects/core-platform defines the core part of the distribution (runtime(project(":launcher")) ...)
        String corePlatform = read("subprojects/core-platform/build.gradle.kts");
        for (String p : findAll("(?m)^\\s*(?:coreRuntime|runtime)\\s*\\(?\\s*project\\(['\"]:([\\w-]+)['\"]\\)", corePlatform.isEmpty() ? rootBuild : corePlatform)) {
            coreRoots.add(kebab(p));
        }
        coreRoots.addAll(coreModules);  // 8.10: runtime(projects.x)
        List<String> core = projectClosure(coreRoots);
        log("lib/: " + core.size() + " modules (" + String.join(", ", coreRoots) + " and their project dependencies), lib/plugins/: " + (mods.size() - core.size()));
        Map<String, Map<String, byte[]>> generated = new TreeMap<>();
        for (String d : mods.keySet()) {
            generated.put(d, new LinkedHashMap<>());
        }
        // 7.0: no runtime-api-info project, its jar is generated by the distribution build (plugin lists, default
        // imports, api mapping, relocated packages); before 7.0 the plugin lists are in core, the imports in docs
        boolean generatedInfo = !mods.containsKey("runtime-api-info") && !corePlatform.isEmpty();
        String infoModule = generatedInfo ? "@runtime-api-info" : "runtime-api-info";
        generated.put(infoModule, generated.getOrDefault(infoModule, new LinkedHashMap<>()));
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
        String groups = generatedInfo ? "" : rootBuild + buildSrcKotlin();
        if (generatedInfo) {
            // 7.0 (PluginsManifest): the modules outside the core runtime, api or implementation by their plugin id
            for (String[] kind : new String[][]{{"api", "gradle-plugins.properties"}, {"implementation", "gradle-implementation-plugins.properties"}}) {
                List<String> names = mods.entrySet().stream()
                    .filter(x -> !core.contains(x.getKey()) && !x.getValue().tool && !x.getValue().agent && x.getValue().build.contains("id(\"gradlebuild.distribution." + kind[0] + "-"))
                    .map(x -> x.getValue().archive).sorted().collect(Collectors.toList());
                text(generated.get(infoModule), kind[1], "plugins=" + String.join(",", names) + "\n");
            }
        }
        for (String[] pl : pluginLists) {
            // (6.0: setOf(rootProject.project("buildProfile"), ...): up to the matching closing parenthesis)
            Matcher m = Pattern.compile("(?s)\\b" + pl[0] + "\\s*(?:get\\(\\)\\s*)?=\\s*(?:setOf|listOf)?([(\\[])").matcher(groups);
            if (m.find()) {
                List<String> names = findAll("['\"](\\w+)['\"]", balanced(groups, m.start(1))).stream().map(n -> "gradle-" + kebab(n)).sorted().collect(Collectors.toList());
                text(generated.get("core"), pl[1], "plugins=" + String.join(",", names) + "\n");
            }
        }
        // 8.0: libraries unpacked into a module jar (from(zipTree(...)) { into(...); exclude("META-INF/**") })
        for (Map.Entry<String, Module> e : mods.entrySet()) {
            for (String[] z : e.getValue().zipResources) {
                for (Path zip : walk(work.resolve("deps").resolve("jars").resolve(e.getKey() + ZIP_RESOURCES))) {
                    try (ZipFile zf = new ZipFile(zip.toFile())) {
                        for (ZipEntry ze : Collections.list(zf.entries())) {
                            if (ze.isDirectory() || ze.getName().startsWith("META-INF/")) {
                                continue;
                            }
                            try (InputStream in = zf.getInputStream(ze)) {
                                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                                byte[] b = new byte[8192];
                                int n;
                                while ((n = in.read(b)) > 0) {
                                    buf.write(b, 0, n);
                                }
                                generated.get(e.getKey()).put(z[1] + "/" + ze.getName(), buf.toByteArray());
                            }
                        }
                    }
                    log(e.getKey() + ": " + zip.getFileName() + " unpacked into " + z[1]);
                }
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
        String importsModule = generatedInfo ? infoModule : "docs";
        generated.computeIfAbsent(importsModule, k -> new LinkedHashMap<>());
        text(generated.get(importsModule), "default-imports.txt", imports[0]);
        text(generated.get(importsModule), "api-mapping.txt", imports[1]);
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
            if (e.getValue().tool) {
                continue;
            }
            allJars.addAll(e.getValue().jars.values());
            if (core.contains(e.getKey())) {
                coreJars.addAll(e.getValue().jars.values());
            }
        }
        if (mods.containsKey("runtime-api-info") || generatedInfo) {
            text(generated.get(infoModule), "org/gradle/api/internal/runtimeshaded/api-relocated.txt", relocatedPackages(allJars));
            String testKit = generatedInfo && mods.containsKey("test-kit") ? "test-kit" : infoModule;  // 7.0: in test-kit
            text(generated.get(testKit), "org/gradle/api/internal/runtimeshaded/test-kit-relocated.txt", relocatedPackages(coreJars));
        }
        Path clsDir = work.resolve("classes");
        // gradle-wrapper.jar embedded in the project that copies :wrapper's executableJar (plugins)
        for (Map.Entry<String, Module> e : mods.entrySet()) {
            // (7.0: the wrapper project itself, archiveFileName.set("gradle-wrapper.jar") ... from(executableJar))
            boolean self = e.getKey().equals("wrapper") && e.getValue().build.contains("archiveFileName.set(\"gradle-wrapper.jar\")");
            // 8.10: wrapper-main, gr8 { archiveName("gradle-wrapper.jar") } of its runtime classpath (not shrunk here)
            boolean gr8 = e.getValue().build.contains("archiveName(\"gradle-wrapper.jar\")");
            if (self || gr8 || Pattern.compile("project\\([\"']:wrapper[\"']\\)\\.executableJar").matcher(e.getValue().build).find()) {
                String w = gr8 ? e.getKey() : "wrapper";
                List<String> parts = gr8 ? projectClosure(Collections.singletonList(w)) : new ArrayList<>();
                if (!gr8) {
                    parts.add("wrapper");
                    parts.addAll(mods.get("wrapper").projects);
                }
                List<String[]> entries = new ArrayList<>();
                for (String p : parts) {
                    for (String c : sorted(classes.get(p))) {
                        entries.add(new String[]{clsDir.resolve(c).toString(), c});
                    }
                    entries.addAll(resources(p));
                }
                Map<String, String> wattrs = new LinkedHashMap<>();
                if (gr8) {
                    wattrs.put("Implementation-Title", "Gradle Wrapper");
                }
                Path wrapperJar = work.resolve("gradle-wrapper.jar");
                writeJar(wrapperJar, wattrs, entries, new LinkedHashMap<>());
                generated.get(e.getKey()).put("gradle-wrapper.jar", Files.readAllBytes(wrapperJar));
            }
        }
        // jars
        Files.createDirectories(dist.resolve("lib").resolve("agents"));
        for (Map.Entry<String, Module> e : mods.entrySet()) {
            String d = e.getKey();
            if (e.getValue().tool) {
                continue;
            }
            Path target = (e.getValue().agent ? dist.resolve("lib").resolve("agents") : core.contains(d) ? dist.resolve("lib") : dist.resolve("lib").resolve("plugins"))
                .resolve(e.getValue().archive + "-" + version + ".jar");
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("Implementation-Title", "Gradle");
            attrs.put("Implementation-Version", version);
            // 8.10: launchable jars (manifestClasspath(projects.x), app { mainClassName }), agents (Premain-Class)
            if (!e.getValue().manifestClasspath.isEmpty()) {
                attrs.put("Class-Path", e.getValue().manifestClasspath.stream().map(r -> mods.get(r).archive + "-" + version + ".jar").collect(Collectors.joining(" ")));
            }
            attrs.putAll(e.getValue().manifest);
            if (d.equals("launcher") && e.getValue().manifestClasspath.isEmpty()) {
                Matcher cp = Pattern.compile("'Class-Path':\\s*\"([^\"]+)\"").matcher(e.getValue().build);
                // 5.0: val classpath = listOf(":baseServices", ":coreApi", ":core").joinToString(" ") ... "Class-Path" to classpath
                Matcher cpList = Pattern.compile("(?s)listOf\\(([^)]*)\\)\\s*\\.joinToString.{0,400}?\"Class-Path\"").matcher(e.getValue().build);
                // 7.0: manifestClasspath(project(":bootstrap")) ...
                List<String> manifestClasspath = findAll("manifestClasspath\\(project\\(\":([\\w-]+)\"\\)\\)", e.getValue().build);
                List<String> refs = !manifestClasspath.isEmpty() ? manifestClasspath : cp.find() ? findAll("project\\(['\"]:(\\w+)['\"]\\)", cp.group(1))
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
            Path aptOut = work.resolve("apt").resolve(d).resolve("classes");  // 8.10: annotation processing output
            for (Path p : walk(aptOut)) {
                entries.add(new String[]{p.toString(), aptOut.relativize(p).toString()});
            }
            entries.addAll(resources(d));
            if (d.equals("kotlin-compiler-embeddable")) {
                entries.addAll(patchedKotlinCompiler());
            }
            writeJar(target, attrs, entries, generated.get(d));
        }
        if (generatedInfo) {
            Map<String, byte[]> info = generated.get(infoModule);
            text(info, "gradle-runtime-api-info-classpath.properties", "projects=\nruntime=\n");
            instrumentationMetadata(info);
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("Implementation-Title", "Gradle");
            attrs.put("Implementation-Version", version);
            writeJar(dist.resolve("lib").resolve("gradle-runtime-api-info-" + version + ".jar"), attrs, new ArrayList<>(), info);
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
            if (m.tool) {
                continue;
            }
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
        // 8.10: the generated Kotlin DSL extensions of the Gradle API
        kotlinDslExtensions(dist);
        // 8.10: the entry point is the launchable jar with org.gradle.launcher.GradleMain (gradle-cli-main), the agents
        // are passed with -javaagent
        String launcher = "gradle-launcher-" + version + ".jar";
        for (Map.Entry<String, Module> e : mods.entrySet()) {
            if ("org.gradle.launcher.GradleMain".equals(e.getValue().manifest.get("Main-Class"))) {
                launcher = e.getValue().archive + "-" + version + ".jar";
            }
        }
        StringBuilder agentOpts = new StringBuilder();
        for (Path a : list(dist.resolve("lib").resolve("agents"))) {
            agentOpts.append(" \"-javaagent:$APP_HOME/lib/agents/").append(a.getFileName()).append('"');
        }
        Path start = dist.resolve("bin").resolve("gradle");
        write(start, START_SCRIPT.replace("@LAUNCHER@", launcher).replace("@AGENTS@", agentOpts.toString()));
        start.toFile().setExecutable(true, false);
        return dist;
    }

    /** 8.10: gradlebuild.instrumentation-metadata: instrumented-super-types.properties (for each class, its org/gradle
     * super types that the instrumentation processor lists as instrumented) and upgraded-properties.json (the merged
     * lists of the processor) */
    void instrumentationMetadata(Map<String, byte[]> info) throws IOException {
        List<Path> roots = new ArrayList<>();
        roots.add(work.resolve("classes"));
        roots.addAll(list(work.resolve("kotlin-classes")).stream().filter(Files::isDirectory).collect(Collectors.toList()));
        List<Path> apt = new ArrayList<>();
        for (Path a : list(work.resolve("apt"))) {
            if (Files.isDirectory(a.resolve("classes"))) {
                apt.add(a.resolve("classes"));
            }
        }
        roots.addAll(apt);
        Set<String> instrumented = new TreeSet<>();
        List<String> upgraded = new ArrayList<>();
        for (Path a : apt) {
            Path classes = a.resolve("META-INF/gradle/instrumentation/instrumented-classes.txt");
            if (Files.isRegularFile(classes)) {
                for (String line : Files.readAllLines(classes, StandardCharsets.UTF_8)) {
                    if (!line.trim().isEmpty()) {
                        instrumented.add(line.trim());
                    }
                }
            }
            Path props = a.resolve("META-INF/gradle/instrumentation/upgraded-properties.json");
            if (Files.isRegularFile(props)) {
                String json = new String(Files.readAllBytes(props), StandardCharsets.UTF_8).trim();
                if (json.length() > 2) {
                    upgraded.add(json.substring(1, json.length() - 1).trim());
                }
            }
        }
        Map<String, Set<String>> direct = new HashMap<>();
        for (Path root : roots) {
            for (Path p : walk(root)) {
                if (!p.toString().endsWith(".class")) {
                    continue;
                }
                String rel = root.relativize(p).toString();
                String name = rel.substring(0, rel.length() - 6);
                Set<String> supers = direct.computeIfAbsent(name, k -> new LinkedHashSet<>());
                for (String s : superTypes(Files.readAllBytes(p))) {
                    if (s.startsWith("org/gradle")) {
                        supers.add(s);
                    }
                }
                if (name.startsWith("org/gradle")) {
                    supers.add(name);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String c : new TreeSet<>(direct.keySet())) {
            Set<String> all = new TreeSet<>();
            Deque<String> todo = new ArrayDeque<>(direct.get(c));
            Set<String> seen = new HashSet<>(Collections.singleton(c));
            while (!todo.isEmpty()) {
                String t = todo.removeFirst();
                if (seen.add(t)) {
                    todo.addAll(direct.getOrDefault(t, Collections.emptySet()));
                }
            }
            for (String t : seen) {
                if (instrumented.contains(t)) {
                    all.add(t);
                }
            }
            if (!all.isEmpty()) {
                sb.append(c).append('=').append(String.join(",", all)).append('\n');
            }
        }
        if (sb.length() > 0) {
            text(info, "instrumented-super-types.properties", sb.toString());
        }
        if (!upgraded.isEmpty()) {
            text(info, "upgraded-properties.json", "[" + String.join(",", upgraded) + "]");
        }
        log("instrumentation metadata: " + instrumented.size() + " instrumented classes, " + sb.toString().split("\n").length
            + " classes with instrumented super types, " + upgraded.size() + " upgraded properties lists");
    }

    /** the super class and the interfaces of a class file (internal names) */
    static List<String> superTypes(byte[] b) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
        in.readInt();
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] classIndex = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1: utf8[i] = in.readUTF(); break;
                case 7: classIndex[i] = in.readUnsignedShort(); break;
                case 8: case 16: case 19: case 20: in.readUnsignedShort(); break;
                case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: in.readInt(); break;
                case 5: case 6: in.readLong(); i++; break;
                default: throw new IOException("bad constant pool tag " + tag);
            }
        }
        in.readUnsignedShort();  // access
        in.readUnsignedShort();  // this
        List<String> supers = new ArrayList<>();
        int superClass = in.readUnsignedShort();
        if (superClass != 0) {
            supers.add(utf8[classIndex[superClass]]);
        }
        int interfaces = in.readUnsignedShort();
        for (int i = 0; i < interfaces; i++) {
            supers.add(utf8[classIndex[in.readUnsignedShort()]]);
        }
        return supers;
    }

    /** 8.10: gradle-kotlin-dsl-extensions (build-logic GenerateKotlinExtensionsForGradleApi): the facades of
     * kotlin-dsl-shared-runtime write the Kotlin sources (KOTLIN_DSL_EXTENSIONS_DRIVER, run against the distribution
     * jars), kotlinc compiles them; sources and classes in lib/gradle-kotlin-dsl-extensions-<v>.jar */
    void kotlinDslExtensions(Path dist) throws Exception {
        if (!mods.containsKey("kotlin-dsl-shared-runtime")) {
            return;
        }
        Path base = work.resolve("kotlin-dsl-extensions");
        deleteTree(base);
        Path gen = base.resolve("sources");
        Path classes = base.resolve("classes");
        Path tool = base.resolve("driver");
        Files.createDirectories(gen);
        Files.createDirectories(classes);
        Files.createDirectories(tool);
        List<Path> distro = new ArrayList<>();
        distro.addAll(list(dist.resolve("lib")).stream().filter(p -> p.toString().endsWith(".jar")).collect(Collectors.toList()));
        distro.addAll(list(dist.resolve("lib").resolve("plugins")).stream().filter(p -> p.toString().endsWith(".jar")).collect(Collectors.toList()));
        Collections.sort(distro);
        List<Path> shared = distro.stream().filter(p -> {
            String n = p.getFileName().toString();
            return n.startsWith("gradle-kotlin-dsl-shared-runtime-") || n.startsWith("kotlin-stdlib-") || n.startsWith("asm-") || n.startsWith("jsr305-")
                || n.startsWith("annotations-");
        }).collect(Collectors.toList());
        String cp = distro.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        Path driver = tool.resolve("KotlinDslExtensionsDriver.java");
        write(driver, KOTLIN_DSL_EXTENSIONS_DRIVER);
        exec(base.resolve("driver.log"), javacCmd(), "-nowarn", "-cp", cp, "-d", tool.toString(), driver.toString());
        Path sharedList = base.resolve("shared.txt");
        write(sharedList, shared.stream().map(Path::toString).collect(Collectors.joining("\n")) + "\n");
        Path distroList = base.resolve("distribution.txt");
        write(distroList, distro.stream().map(Path::toString).collect(Collectors.joining("\n")) + "\n");
        log("kotlin-dsl-extensions: generate sources");
        exec(base.resolve("generate.log"), javaCmd(), "-Xmx2g", "-cp", tool + File.pathSeparator + cp, "KotlinDslExtensionsDriver",
            gen.toString(), sharedList.toString(), distroList.toString());
        List<String> files = walk(gen).stream().map(Path::toString).filter(x -> x.endsWith(".kt")).collect(Collectors.toList());
        List<String> args = new ArrayList<>(Arrays.asList("-d", classes.toString(), "-classpath", cp, "-no-stdlib", "-no-reflect",
            "-module-name", "gradle-kotlin-dsl-extensions"));
        args.addAll(kotlinOptions());
        args.addAll(files);
        Path argfile = base.resolve("kotlinc.args");
        write(argfile, args.stream().map(a -> "\"" + a.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").collect(Collectors.joining("\n")));
        log("kotlin-dsl-extensions: kotlinc " + files.size() + " sources");
        String compilerCp = kotlinCompilerJars.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        Path logFile = base.resolve("kotlinc.log");
        int rc = execStatus(logFile, javaCmd(), "-Xmx4g", "-cp", compilerCp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "@" + argfile);
        if (rc != 0) {
            throw new IllegalStateException("kotlinc kotlin-dsl-extensions failed, see " + logFile + "\n" + tail(new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8), 4000));
        }
        List<String[]> entries = new ArrayList<>();
        for (Path root : Arrays.asList(gen, classes)) {
            for (Path p : walk(root)) {
                entries.add(new String[]{p.toString(), root.relativize(p).toString()});
            }
        }
        Map<String, byte[]> extra = new LinkedHashMap<>();
        text(extra, "gradle-kotlin-dsl-extensions-classpath.properties", "projects=\nruntime=\n");
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("Implementation-Title", "Gradle");
        attrs.put("Implementation-Version", version);
        writeJar(dist.resolve("lib").resolve("gradle-kotlin-dsl-extensions-" + version + ".jar"), attrs, entries, extra);
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
        Path base = mdir(d).resolve("src").resolve("main").resolve("resources");
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
        Matcher dim = Pattern.compile("(?s)(?:task defaultImports|tasks\\.register\\(\"defaultImports\")[^{]*\\{(.*?)\\n\\}").matcher(docs);
        List<String> excluded;
        Set<String> packages;
        if (dim.find()) {
            excluded = findAll("excludePackage\\s+'([^']+)'", dim.group(1));
            packages = new TreeSet<>(findAll("extraPackage\\s+'([^']+)'", dim.group(1)));
        } else {
            // 7.0: build-logic GradleUserManualPlugin.getDefaultExcludedPackages()
            String plugin = "";
            for (Path p : walkAll("build-logic")) {
                if (p.getFileName().toString().equals("GradleUserManualPlugin.java")) {
                    plugin = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                }
            }
            excluded = findAll("excludedPackages\\.add\\(\"([^\"]+)\"\\)", plugin);
            packages = new TreeSet<>();
        }
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
        for (String dir : Arrays.asList("buildSrc", "build-logic", "build-logic-commons", "build-logic-settings")) {  // 7.0: build-logic*
            for (Path p : walk(src.resolve(dir))) {
                if (p.toString().endsWith(".kt") || p.toString().endsWith(".kts")) {
                    sb.append(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).append('\n');
                }
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
    List<Path> walkAll(String... dirs) throws IOException {
        List<Path> all = new ArrayList<>();
        for (String d : dirs) {
            all.addAll(walk(src.resolve(d)));
        }
        return all;
    }

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
    /** 8.10: the relocation excludes of build-logic PackageListGenerator.DEFAULT_EXCLUDES (8.10 adds kotlin,
     * org/apache/groovy, org/jetbrains/annotations, javax/annotation and no longer excludes all of javax); the static
     * list when not found */
    List<String> relocationExcludes() throws IOException {
        if (relocationExcludes == null) {
            relocationExcludes = RELOCATION_EXCLUDES;
            for (Path p : walkAll("build-logic-commons", "build-logic")) {
                if (p.getFileName().toString().equals("PackageListGenerator.kt")) {
                    String body = findOrNull("(?s)DEFAULT_EXCLUDES[^=]*=\\s*listOf\\((.*?)\\)", new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                    if (body != null) {
                        relocationExcludes = findAll("\"([^\"]+)\"", body);
                        log("relocation excludes from " + src.relativize(p) + ": " + relocationExcludes);
                    }
                }
            }
        }
        return relocationExcludes;
    }

    List<String> relocationExcludes;

    String relocatedPackages(Collection<Path> jars) throws IOException {
        List<String> excludes = relocationExcludes();
        Set<String> words = new TreeSet<>();
        for (Path j : jars) {
            try (ZipFile z = new ZipFile(j.toFile())) {
                for (ZipEntry e : Collections.list(z.entries())) {
                    String n = e.getName();
                    if (n.endsWith(".class") && n.contains("/")) {
                        String pkg = n.substring(0, n.lastIndexOf('/'));
                        // (8.10: whole path segments, as PackageListGenerator: "$packageName/".startsWith("$exclude/"))
                        if (excludes.stream().noneMatch(x -> (pkg + "/").startsWith(x + "/"))) {
                            words.add(pkg);
                        }
                    }
                }
            }
        }
        StringBuilder out = new StringBuilder();
        String last = null;
        for (String w : words) {
            if (last != null && (w + "/").startsWith(last + "/")) {
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

    static String findOrNull(String regex, String text) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
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
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
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

    // 8.10: build-logic GenerateKotlinExtensionsForGradleApi, without @since lookup (FunctionSinceRepository: KDoc
    // only) and with the (empty, bootstrap) parameter names indexes; args: output dir, shared runtime classpath file,
    // distribution jars file
    static final String KOTLIN_DSL_EXTENSIONS_DRIVER = String.join("\n",
        "import java.io.File;",
        "import java.net.URL;",
        "import java.net.URLClassLoader;",
        "import java.nio.file.Files;",
        "import java.util.*;",
        "import java.util.function.Function;",
        "import java.util.jar.JarFile;",
        "import org.gradle.api.file.RelativePath;",
        "import org.gradle.api.internal.file.pattern.PatternMatcher;",
        "import org.gradle.api.internal.file.pattern.PatternMatcherFactory;",
        "import org.gradle.internal.classanalysis.AsmConstants;",
        "import org.gradle.internal.classloader.ClassLoaderUtils;",
        "import org.gradle.internal.hash.Hashing;",
        "public class KotlinDslExtensionsDriver {",
        "    static List<File> files(String list) throws Exception {",
        "        List<File> files = new ArrayList<>();",
        "        for (String l : Files.readAllLines(new File(list).toPath())) { if (!l.trim().isEmpty()) files.add(new File(l.trim())); }",
        "        return files;",
        "    }",
        "    static List<String> split(String s) { return s == null || s.isEmpty() ? new ArrayList<>() : Arrays.asList(s.split(\":\")); }",
        "    static void invoke(ClassLoader loader, String className, Map<String, Object> parameters) throws Exception {",
        "        Class<?> c = loader.loadClass(className);",
        "        Object facade = c.getConstructor().newInstance();",
        "        for (java.lang.reflect.Method m : c.getMethods()) {",
        "            if (m.getName().equals(\"generate\")) {",
        "                try { m.invoke(facade, parameters); } catch (java.lang.reflect.InvocationTargetException e) { throw (Exception) e.getTargetException(); }",
        "            }",
        "        }",
        "    }",
        "    public static void main(String[] args) throws Exception {",
        "        File out = new File(args[0]);",
        "        List<File> shared = files(args[1]);",
        "        List<File> api = new ArrayList<>();",
        "        List<File> deps = new ArrayList<>();",
        "        for (File f : files(args[2])) { (f.getName().startsWith(\"gradle-\") && !f.getName().contains(\"gradle-kotlin-\") ? api : deps).add(f); }",
        "        File metadata = api.stream().filter(f -> f.getName().startsWith(\"gradle-api-metadata\")).findFirst().get();",
        "        Properties declaration = new Properties();",
        "        try (JarFile jar = new JarFile(metadata)) { declaration.load(jar.getInputStream(jar.getJarEntry(\"gradle-api-declaration.properties\"))); }",
        "        List<String> includes = split(declaration.getProperty(\"includes\"));",
        "        List<String> excludes = split(declaration.getProperty(\"excludes\"));",
        "        PatternMatcher spec = includes.isEmpty() && excludes.isEmpty() ? PatternMatcher.MATCH_ALL",
        "            : includes.isEmpty() ? PatternMatcherFactory.getPatternsMatcher(true, true, excludes).negate()",
        "            : excludes.isEmpty() ? PatternMatcherFactory.getPatternsMatcher(true, true, includes)",
        "            : PatternMatcherFactory.getPatternsMatcher(true, true, includes).and(PatternMatcherFactory.getPatternsMatcher(true, true, excludes).negate());",
        "        Function<String, Boolean> apiSpec = name -> {",
        "            RelativePath path = RelativePath.parse(true, name.replace(\".\", File.separator));",
        "            return spec.test(path.getSegments(), path.isFile());",
        "        };",
        "        URL[] urls = new URL[shared.size()];",
        "        for (int i = 0; i < urls.length; i++) { urls[i] = shared.get(i).toURI().toURL(); }",
        "        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoaderUtils.getPlatformClassLoader())) {",
        "            File builtin = new File(out, \"org/gradle/kotlin/dsl/BuiltinPluginIdExtensions.kt\");",
        "            builtin.getParentFile().mkdirs();",
        "            Map<String, Object> p = new HashMap<>();",
        "            p.put(\"file\", builtin);",
        "            p.put(\"gradleJars\", api);",
        "            p.put(\"pluginDependenciesSpecQualifiedName\", \"org.gradle.plugin.use.PluginDependenciesSpec\");",
        "            p.put(\"pluginDependencySpecQualifiedName\", \"org.gradle.plugin.use.PluginDependencySpec\");",
        "            invoke(loader, \"org.gradle.kotlin.dsl.internal.sharedruntime.codegen.PluginIdExtensionsFacade\", p);",
        "            Map<String, Object> q = new HashMap<>();",
        "            q.put(\"asmLevel\", AsmConstants.ASM_LEVEL);",
        "            q.put(\"platformClassLoader\", ClassLoaderUtils.getPlatformClassLoader());",
        "            q.put(\"incubatingAnnotationTypeDescriptor\", \"Lorg/gradle/api/Incubating;\");",
        "            q.put(\"outputDirectory\", out);",
        "            q.put(\"packageName\", \"org.gradle.kotlin.dsl\");",
        "            q.put(\"sourceFilesBaseName\", \"GradleApiKotlinDslExtensions\");",
        "            q.put(\"hashTypeSourceName\", (Function<String, String>) s -> Hashing.hashString(s).toCompactString());",
        "            q.put(\"classPath\", api);",
        "            q.put(\"classPathDependencies\", deps);",
        "            q.put(\"apiSpec\", apiSpec);",
        "            q.put(\"parameterNamesSupplier\", (Function<String, List<String>>) key -> null);",
        "            q.put(\"functionSinceSupplier\", (Function<String, String>) key -> null);",
        "            invoke(loader, \"org.gradle.kotlin.dsl.internal.sharedruntime.codegen.ApiExtensionGeneratorFacade\", q);",
        "        }",
        "    }",
        "}",
        "");

    static final String START_SCRIPT = String.join("\n",
        "#!/usr/bin/env sh",
        "# minimal start script of a bootstrap distribution (scripts/GradleMinimalBootstrap810.java)",
        "APP_HOME=$(cd \"$(dirname \"$0\")/..\" && pwd -P)",
        "JAVACMD=java",
        "[ -n \"$JAVA_HOME\" ] && JAVACMD=\"$JAVA_HOME/bin/java\"",
        "exec \"$JAVACMD\"@AGENTS@ $JAVA_OPTS $GRADLE_OPTS -Dorg.gradle.appname=gradle -classpath \"$APP_HOME/lib/@LAUNCHER@\" org.gradle.launcher.GradleMain \"$@\"",
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
                        System.err.println("usage: GradleMinimalBootstrap810 <tag> [--java JDK] [--work DIR] [--target DIR] [--clean]");
                        System.exit(2);
                    }
                    tag = args[i];
            }
        }
        if (tag == null) {
            System.err.println("usage: GradleMinimalBootstrap810 <tag> [--java JDK] [--work DIR] [--target DIR] [--clean]");
            System.exit(2);
        }
        GradleMinimalBootstrap810 b = new GradleMinimalBootstrap810(tag, java, workRoot);
        if (!Files.isDirectory(b.repo)) {
            System.err.println("run from the project directory: no gradle/ repository in " + b.root);
            System.exit(2);
        }
        try {
            if (clean) {
                deleteTree(b.work);
            }
            b.export();
            b.patchSources();
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
