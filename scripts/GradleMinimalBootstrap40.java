/*
 * Minimal bootstrap of Gradle 4.0 (verified on v4.0.0, frozen: later versions get their own copy): compile its sources without its own build, into a raw distribution that
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
 * Tests, docs, test-only and performance projects are left out. The Kotlin DSL (an external module) is left out:
 * the bootstrap only runs Groovy build scripts.
 *
 * Java 8 source, only the JDK. Run from the project directory (it holds gradle/ and target/):
 *   JDK 8:   mkdir -p work/minimal-tools && javac -d work/minimal-tools scripts/GradleMinimalBootstrap40.java
 *            java -cp work/minimal-tools GradleMinimalBootstrap40 v4.0.0
 *   JDK 11+: java scripts/GradleMinimalBootstrap40.java v4.0.0
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

public class GradleMinimalBootstrap40 {

    static final String CENTRAL = "https://repo1.maven.org/maven2";
    static final String GRADLE_LIBS = "https://repo.gradle.org/gradle/libs-releases";
    static final String[] IVY = {"org.apache.ivy", "ivy", "2.5.2"};
    static final List<String> MAIN_CONFS = Arrays.asList("compile", "runtime", "compileOnly", "api", "implementation", "runtimeOnly", "publishCompile");
    // projects that only hold tests, performance tests or the distribution build
    static final Pattern LEFT_OUT = Pattern.compile("^(internal-.*|integ-test|performance|smoke-test|soak|distributions|build-scan-performance)$");
    // coordinates that are on no repository anymore under their original group (same version elsewhere)
    static final Map<String, String> MOVED = Collections.singletonMap("org.samba.jcifs:jcifs", "jcifs:jcifs");
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
    Path groovyJar;

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
        List<Dep> deps = new ArrayList<>();
        Map<String, Path> jars = new LinkedHashMap<>();  // file name -> file
    }

    static class Source {
        final String module;
        final String rel;  // path in the source root, without extension
        Source(String module, String rel) { this.module = module; this.rel = rel; }
    }

    final Map<String, Module> mods = new TreeMap<>();
    Map<String, List<Dep>> libraries;
    final Map<String, String> substitutions = new LinkedHashMap<>();
    final Map<Path, Source> sources = new TreeMap<>();

    GradleMinimalBootstrap40(String tag, Path java, Path workRoot) {
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
        String groovy = find("versions\\.groovy\\s*=\\s*[\"']([^\"']+)", read("gradle/dependencies.gradle"), 0);
        groovyJar = download("org.codehaus.groovy", "groovy-all", groovy, d);
        log("tools: ivy " + IVY[2] + ", groovy-all " + groovy);
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
                m.build = read("subprojects/" + d + "/" + d + ".gradle");
                mods.put(d, m);
            }
        }
        Pattern confLine = Pattern.compile("^\\s*(\\w+)\\s*\\(?\\s*(.*)$");
        for (Map.Entry<String, Module> e : mods.entrySet()) {
            String d = e.getKey();
            Module m = e.getValue();
            // classpathManifest { additionalProjects = [...] }: projects only listed in the classpath manifest
            for (String refs : findAll("additionalProjects\\s*=\\s*\\[([^\\]]*)\\]", m.build)) {
                for (String p : findAll("project\\(['\"]:(\\w+)['\"]\\)", refs)) {
                    if (mods.containsKey(kebab(p))) {
                        m.projects.add(kebab(p));
                    }
                }
            }
            for (String line : m.build.split("\n")) {
                Matcher mm = confLine.matcher(line);
                if (!mm.matches() || !MAIN_CONFS.contains(mm.group(1))) {
                    continue;
                }
                String args = mm.group(2);
                m.libs.addAll(findAll("libraries\\.(\\w+)", args));
                for (String p : findAll("project\\(\\s*(?:path:\\s*)?[\"']:(\\w+)[\"']", args)) {
                    String k = kebab(p);
                    if (mods.containsKey(k) && !k.equals(d) && !m.projects.contains(k)) {
                        m.projects.add(k);
                    }
                }
                m.coords.addAll(findAll("[\"']([\\w.\\-]+:[\\w.\\-]+:[\\w.\\-]+(?:@jar)?)[\"']", args));
            }
        }
    }

    void dependencies() throws Exception {
        Path out = work.resolve("deps").resolve("libraries.tsv");
        if (!Files.isRegularFile(out)) {
            Files.createDirectories(out.getParent());
            Path script = work.resolve("deps").resolve("eval-dependencies.groovy");
            write(script, EVAL_DEPENDENCIES);
            exec(out, javaCmd(), "-Xmx1g", "-cp", groovyJar + File.pathSeparator + toolsJar(), "groovy.ui.GroovyMain",
                script.toString(), src.resolve("gradle").resolve("dependencies.gradle").toString());
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
        log(libraries.size() + " libraries, substitutions: " + substitutions);
        for (Map.Entry<String, Module> e : mods.entrySet()) {
            Module mod = e.getValue();
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

    void resolve() throws Exception {
        Path depsDir = work.resolve("deps").resolve("jars");
        if (!Files.isDirectory(depsDir)) {
            Map<String, List<String>> confsByCoord = new TreeMap<>();
            Map<String, Boolean> transitiveByCoord = new HashMap<>();
            for (Map.Entry<String, Module> e : mods.entrySet()) {
                for (Dep dep : e.getValue().deps) {
                    List<String> confs = confsByCoord.computeIfAbsent(dep.coord, k -> new ArrayList<>());
                    if (!confs.contains(e.getKey())) {
                        confs.add(e.getKey());
                    }
                    transitiveByCoord.merge(dep.coord, dep.transitive, Boolean::logicalOr);
                }
            }
            StringBuilder xml = new StringBuilder("<ivy-module version=\"2.0\"><info organisation=\"org.gradle\" module=\"gradle-bootstrap\"/><configurations>");
            for (String d : mods.keySet()) {
                xml.append("<conf name=\"").append(d).append("\"/>");
            }
            xml.append("</configurations><dependencies>\n");
            for (Map.Entry<String, List<String>> e : confsByCoord.entrySet()) {
                String[] gav = e.getKey().split(":");
                xml.append("<dependency org=\"").append(gav[0]).append("\" name=\"").append(gav[1]).append("\" rev=\"").append(gav[2])
                    .append("\" transitive=\"").append(transitiveByCoord.get(e.getKey())).append("\" conf=\"")
                    .append(e.getValue().stream().map(c -> c + "->default").collect(Collectors.joining(";"))).append("\"/>\n");
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
        for (Map.Entry<String, Module> e : mods.entrySet()) {
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
        Path out = work.resolve("classes");
        for (String d : mods.keySet()) {
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
        jars.add(toolsJar());
        Path listing = work.resolve("sources.txt");
        write(listing, sources.keySet().stream().map(Path::toString).collect(Collectors.joining("\n")) + "\n");
        Path cpFile = work.resolve("classpath.txt");
        write(cpFile, String.join(File.pathSeparator, jars));
        Path script = work.resolve("compile.groovy");
        write(script, COMPILE);
        log("compile " + sources.size() + " sources (" + (jars.size() - 1) + " jars on the classpath)");
        Path compileLog = work.resolve("compile.log");
        int rc = execStatus(compileLog, javaCmd(), "-Xmx4g", "-cp", groovyJar + File.pathSeparator + toolsJar(), "groovy.ui.GroovyMain",
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
        String rootBuild = read("build.gradle");
        List<String> coreRoots = new ArrayList<>();
        for (String p : findAll("(?m)^\\s*(?:coreRuntime|runtime)\\s+project\\(['\"]:(\\w+)['\"]\\)", rootBuild)) {
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
            text(generated.get(e.getKey()), "gradle-" + e.getKey() + "-classpath.properties",
                "projects=" + m.projects.stream().map(p -> "gradle-" + p).collect(Collectors.joining(",")) + "\n"
                    + "runtime=" + String.join(",", m.jars.keySet()) + "\n");
        }
        // build receipt
        String commit = execOutput("git", "-C", repo.toString(), "rev-list", "-n1", tag).trim();
        text(generated.get("base-services"), "org/gradle/build-receipt.properties",
            "baseVersion=" + baseVersion + "\ncommitId=" + commit + "\nbuildTimestamp=" + timestamp + "+0000\n"
                + "versionNumber=" + version + "\nisSnapshot=true\n");
        // plugin lists (core.gradle pluginsManifest / implementationPluginsManifest)
        String[][] pluginLists = {{"pluginProjects", "gradle-plugins.properties"}, {"implementationPluginProjects", "gradle-implementation-plugins.properties"}};
        for (String[] pl : pluginLists) {
            Matcher m = Pattern.compile(pl[0] + "\\s*=\\s*\\[([^\\]]*)\\]").matcher(rootBuild);
            if (m.find()) {
                List<String> names = findAll("['\"](\\w+)['\"]", m.group(1)).stream().map(n -> "gradle-" + kebab(n)).sorted().collect(Collectors.toList());
                text(generated.get("core"), pl[1], "plugins=" + String.join(",", names) + "\n");
            }
        }
        // default imports and api mapping (docs project)
        String[] imports = defaultImports();
        generated.computeIfAbsent("docs", k -> new LinkedHashMap<>());
        text(generated.get("docs"), "default-imports.txt", imports[0]);
        text(generated.get("docs"), "api-mapping.txt", imports[1]);
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
            Path target = (core.contains(d) ? dist.resolve("lib") : dist.resolve("lib").resolve("plugins")).resolve("gradle-" + d + "-" + version + ".jar");
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("Implementation-Title", "Gradle");
            attrs.put("Implementation-Version", version);
            if (d.equals("launcher")) {
                Matcher cp = Pattern.compile("'Class-Path':\\s*\"([^\"]+)\"").matcher(e.getValue().build);
                List<String> refs = cp.find() ? findAll("project\\(['\"]:(\\w+)['\"]\\)", cp.group(1)) : Arrays.asList("core", "baseServices");
                attrs.put("Class-Path", refs.stream().map(r -> "gradle-" + kebab(r) + "-" + version + ".jar").collect(Collectors.joining(" ")));
                attrs.put("Main-Class", "org.gradle.launcher.GradleMain");
            }
            List<String[]> entries = new ArrayList<>();
            for (String c : sorted(classes.get(d))) {
                entries.add(new String[]{clsDir.resolve(c).toString(), c});
            }
            entries.addAll(resources(d));
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
        Path start = dist.resolve("bin").resolve("gradle");
        write(start, START_SCRIPT.replace("@LAUNCHER@", "gradle-launcher-" + version + ".jar"));
        start.toFile().setExecutable(true, false);
        return dist;
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
        String jd = find("(?s)task javadocAll[^{]*\\{(.*?)\\n\\}", docs, 0);
        List<String> includes = findAll("(?m)^\\s*include\\s+'([^']+)'", jd);
        List<String> excludes = findAll("(?m)^\\s*exclude\\s+'([^']+)'", jd);
        String di = find("(?s)task defaultImports[^{]*\\{(.*?)\\n\\}", docs, 0);
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

    // ---- helpers
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
        "abstract class DependenciesScript extends Script {",
        "    def dependencies = new DependenciesStub()",
        "    void ext(Closure c) { c.delegate = this; c.resolveStrategy = Closure.DELEGATE_FIRST; c() }",
        "    void allprojects(Closure c) {}",
        "    void subprojects(Closure c) {}",
        "    void configurations(Closure c) {}",
        "}",
        "def config = new org.codehaus.groovy.control.CompilerConfiguration(scriptBaseClass: DependenciesScript.name)",
        "def binding = new Binding()",
        "// another script name: a script named 'dependencies' makes dependencies.module() a static call on itself",
        "new GroovyShell(this.class.classLoader, binding, config).evaluate(new File(args[0]).text, 'DependenciesGradle')",
        "def flatten",
        "flatten = { d ->",
        "    if (d instanceof ClientModule) {",
        "        return [[coord: d.coord.replaceAll(/@jar$/, ''), transitive: false]] + d.deps.collectMany { flatten(it) }",
        "    }",
        "    if (d instanceof Collection) { return d.collectMany { flatten(it) } }",
        "    if (d instanceof Map) { d = \"${d.group}:${d.name}:${d.version}\" }",
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
        "# minimal start script of a bootstrap distribution (scripts/GradleMinimalBootstrap40.java)",
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
                        System.err.println("usage: GradleMinimalBootstrap40 <tag> [--java JDK] [--work DIR] [--target DIR] [--clean]");
                        System.exit(2);
                    }
                    tag = args[i];
            }
        }
        if (tag == null) {
            System.err.println("usage: GradleMinimalBootstrap40 <tag> [--java JDK] [--work DIR] [--target DIR] [--clean]");
            System.exit(2);
        }
        GradleMinimalBootstrap40 b = new GradleMinimalBootstrap40(tag, java, workRoot);
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
