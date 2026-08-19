package arch.lst;

import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.tree.ParseError;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Source-полка на OpenRewrite LST: Java и Kotlin одним визитором, со статическим
 * резолвом URL/топиков (константы чужих классов, конкатенации, string templates,
 * base у WebClient.create/baseUrl, @Value-плейсхолдеры остаются `${...}`).
 *
 * Аргументы: <classpath-dir|NONE> <repo-root>
 * Сканируются src/main/java и src/main/kotlin (рекурсивно все .java/.kt под src/main).
 *
 * Выход (stdout) — строки контракта jqassistant:
 *   TYPE|attr=value|...|source|confidence
 * Typed-факты (тип из classpath подтверждён) — confidence 0.85,
 * untyped-эвристики — 0.6. Диагностика — в stderr.
 */
public class Extractor {

    static final double TYPED = 0.85;
    static final double UNTYPED = 0.6;

    static final Set<String> REST_TEMPLATE_METHODS = Set.of(
        "getForObject", "getForEntity", "postForObject", "postForEntity", "postForLocation",
        "put", "delete", "exchange", "execute", "patchForObject", "headForHeaders", "optionsForAllow");

    static final Set<String> HTTP_VERBS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    static final Map<String, String> MAPPINGS = Map.of(
        "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
        "DeleteMapping", "DELETE", "PatchMapping", "PATCH");

    final Set<String> lines = new TreeSet<>(); // сортированный вывод -> детерминизм

    public static void main(String[] args) throws Exception {
        List<Path> classpath = new ArrayList<>();
        if (args.length > 0 && !args[0].equals("NONE")) {
            try (Stream<Path> s = Files.list(Path.of(args[0]))) {
                classpath = s.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
            }
        }
        Path repoRoot = Path.of(args[1]).toAbsolutePath().normalize();
        Path srcMain = repoRoot.resolve("src/main");

        List<Path> javaFiles = new ArrayList<>();
        List<Path> kotlinFiles = new ArrayList<>();
        if (Files.isDirectory(srcMain)) {
            try (Stream<Path> s = Files.walk(srcMain)) {
                for (Path p : s.toList()) {
                    if (p.toString().endsWith(".java")) javaFiles.add(p);
                    if (p.toString().endsWith(".kt")) kotlinFiles.add(p);
                }
            }
        }
        javaFiles.sort(Path::compareTo);
        kotlinFiles.sort(Path::compareTo);

        // Компиляторный парсинг — дорогой; факты живут в малой доле файлов.
        // Парсим только кандидатов (HTTP/messaging/контроллеры) + держатели констант.
        // LST_ALL_FILES=1 — парсить всё (старое поведение).
        boolean parseAll = System.getenv("LST_ALL_FILES") != null;
        final List<Path> javaSrc = parseAll ? javaFiles : javaFiles.stream().filter(Extractor::isCandidate).toList();
        final List<Path> kotlinSrc = parseAll ? kotlinFiles : kotlinFiles.stream().filter(Extractor::isCandidate).toList();

        System.err.printf("вход: java=%d/%d, kotlin=%d/%d файлов-кандидатов, classpath=%d jar%n",
            javaSrc.size(), javaFiles.size(), kotlinSrc.size(), kotlinFiles.size(), classpath.size());

        ExecutionContext ctx = new InMemoryExecutionContext(t -> System.err.println("[parse-error] " + t.getMessage()));
        List<SourceFile> all = new ArrayList<>();
        long t0 = System.nanoTime();
        if (!javaSrc.isEmpty()) {
            JavaParser jp = JavaParser.fromJavaVersion().classpath(classpath).build();
            int[] done = {0};
            jp.parse(javaSrc, repoRoot, ctx).forEach(sf -> {
                all.add(sf);
                if (++done[0] % 50 == 0) System.err.printf("java: %d/%d%n", done[0], javaSrc.size());
            });
            System.err.printf("java распарсен: %d файлов за %.1fс%n", javaSrc.size(), (System.nanoTime() - t0) / 1e9);
        }
        long t1 = System.nanoTime();
        if (!kotlinSrc.isEmpty()) {
            System.err.println("kotlin: старт компилятора…");
            KotlinParser kp = KotlinParser.builder().classpath(classpath).build();
            int[] done = {0};
            kp.parse(kotlinSrc, repoRoot, ctx).forEach(sf -> {
                all.add(sf);
                if (++done[0] % 25 == 0) System.err.printf("kotlin: %d/%d%n", done[0], kotlinSrc.size());
            });
            System.err.printf("kotlin распарсен: %d файлов за %.1fс%n", kotlinSrc.size(), (System.nanoTime() - t1) / 1e9);
        }
        for (SourceFile sf : all) {
            if (sf instanceof ParseError pe) System.err.println("[PARSE ERROR] " + pe.getSourcePath());
        }

        Extractor x = new Extractor();
        x.run(all);
        x.lines.forEach(System.out::println);
    }

    void run(List<SourceFile> all) {
        // фаза 1: таблица символов (поле/константа -> инициализатор или @Value-плейсхолдер)
        Map<String, Object> symbols = new HashMap<>();
        Map<String, List<Object>> byName = new HashMap<>();
        for (SourceFile sf : all) {
            if (sf instanceof ParseError) continue;
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations vd, Integer p) {
                    String valuePlaceholder = null;
                    for (J.Annotation a : vd.getLeadingAnnotations()) {
                        if (a.getSimpleName().equals("Value") && a.getArguments() != null && !a.getArguments().isEmpty()) {
                            Object v = literalOf(a.getArguments().get(0));
                            if (v != null) valuePlaceholder = v.toString();
                        }
                    }
                    for (J.VariableDeclarations.NamedVariable nv : vd.getVariables()) {
                        Object val = valuePlaceholder != null ? valuePlaceholder : nv.getInitializer();
                        if (val == null) continue;
                        String owner = null;
                        if (nv.getVariableType() != null && nv.getVariableType().getOwner() instanceof JavaType.FullyQualified fq)
                            owner = fq.getFullyQualifiedName();
                        if (owner != null) symbols.put(owner + "#" + nv.getSimpleName(), val);
                        byName.computeIfAbsent(nv.getSimpleName(), k -> new ArrayList<>()).add(val);
                    }
                    return super.visitVariableDeclarations(vd, p);
                }
            }.visit(sf, 0);
        }
        Resolver resolver = new Resolver(symbols, byName);

        // фаза 2: извлечение фактов
        for (SourceFile sf : all) {
            if (sf instanceof ParseError) continue;
            String file = sf.getSourcePath().toString().replace('\\', '/');
            new FactVisitor(file, resolver, symbols).visit(sf, 0);
        }
    }

    class FactVisitor extends JavaIsoVisitor<Integer> {
        final String file;
        final Resolver resolver;
        final Map<String, Object> symbols;

        FactVisitor(String file, Resolver resolver, Map<String, Object> symbols) {
            this.file = file;
            this.resolver = resolver;
            this.symbols = symbols;
        }

        String where() {
            J.MethodDeclaration md = getCursor().firstEnclosing(J.MethodDeclaration.class);
            J.ClassDeclaration cd = getCursor().firstEnclosing(J.ClassDeclaration.class);
            String cls = cd != null ? cd.getSimpleName() : "?";
            return md != null ? cls + "." + md.getSimpleName() : cls;
        }

        String src() { return file + "#" + where(); }

        // ---- контроллеры и Feign ----
        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd, Integer p) {
            boolean controller = false;
            boolean typedController = false;
            String feignName = null;
            String feignUrl = null;
            boolean typedFeign = false;
            String prefix = "";

            for (J.Annotation a : cd.getLeadingAnnotations()) {
                String fq = fqOf(a);
                String simple = a.getSimpleName();
                if ("org.springframework.web.bind.annotation.RestController".equals(fq)
                    || "org.springframework.stereotype.Controller".equals(fq)) {
                    controller = typedController = true;
                } else if (fq == null && (simple.equals("RestController") || simple.equals("Controller"))) {
                    controller = true;
                }
                if ("org.springframework.cloud.openfeign.FeignClient".equals(fq)
                    || (fq == null && simple.equals("FeignClient"))) {
                    typedFeign = fq != null;
                    feignName = annArg(a, "name", "value");
                    feignUrl = annArg(a, "url", null);
                    prefix = Objects.toString(annArg(a, "path", null), "");
                }
                if (simple.equals("RequestMapping")) {
                    prefix = Objects.toString(annArg(a, "value", "path"), prefix);
                }
            }

            if (controller || feignName != null || feignUrl != null) {
                for (Statement st : cd.getBody().getStatements()) {
                    if (!(st instanceof J.MethodDeclaration md)) continue;
                    for (J.Annotation ma : md.getLeadingAnnotations()) {
                        String verb = MAPPINGS.get(ma.getSimpleName());
                        if (verb == null && ma.getSimpleName().equals("RequestMapping")) verb = requestMethod(ma);
                        if (verb == null) continue;
                        String op = Objects.toString(annArg(ma, "value", "path"), "");
                        String path = joinPath(prefix, op);
                        if (controller) {
                            boolean deprecated = md.getLeadingAnnotations().stream()
                                .anyMatch(x -> x.getSimpleName().equals("Deprecated"));
                            List<String> kv = new ArrayList<>(List.of("method=" + verb, "path=" + path));
                            if (deprecated) kv.add("deprecated=true");
                            emit("ENDPOINT", file + "#" + cd.getSimpleName() + "." + md.getSimpleName(),
                                typedController ? TYPED : UNTYPED, kv);
                        } else {
                            List<String> kv = new ArrayList<>(List.of("method=" + verb, "path=" + path));
                            if (feignName != null) kv.add("feignName=" + feignName);
                            if (feignUrl != null) kv.add("urlTemplate=" + feignUrl);
                            emit("OUTGOING_CALL", file + "#" + cd.getSimpleName() + "." + md.getSimpleName(),
                                typedFeign ? TYPED : UNTYPED, kv);
                        }
                    }
                }
            }
            return super.visitClassDeclaration(cd, p);
        }

        // ---- слушатели Kafka/Rabbit ----
        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration md, Integer p) {
            for (J.Annotation a : md.getLeadingAnnotations()) {
                String simple = a.getSimpleName();
                boolean typed = fqOf(a) != null;
                if (simple.equals("KafkaListener")) {
                    String group = annArg(a, "groupId", null);
                    for (String topic : annArgMany(a, "topics", "value", resolver)) {
                        List<String> kv = new ArrayList<>(List.of("channel=" + topic, "protocol=kafka"));
                        if (group != null) kv.add("group=" + group);
                        emit("SUBSCRIBE", src(md), typed ? TYPED : UNTYPED, kv);
                    }
                } else if (simple.equals("RabbitListener")) {
                    for (String queue : annArgMany(a, "queues", "value", resolver)) {
                        emit("SUBSCRIBE", src(md), typed ? TYPED : UNTYPED,
                            List.of("channel=" + queue, "protocol=amqp"));
                    }
                }
            }
            return super.visitMethodDeclaration(md, p);
        }

        String src(J.MethodDeclaration md) {
            J.ClassDeclaration cd = getCursor().firstEnclosing(J.ClassDeclaration.class);
            return file + "#" + (cd != null ? cd.getSimpleName() + "." : "") + md.getSimpleName();
        }

        // ---- исходящие вызовы и продюсеры ----
        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
            JavaType.Method mt = mi.getMethodType();
            String decl = mt != null && mt.getDeclaringType() != null
                ? mt.getDeclaringType().getFullyQualifiedName() : null;
            String name = mi.getSimpleName();
            List<Expression> args = mi.getArguments();

            if (decl != null) {
                if (decl.equals("org.springframework.web.client.RestTemplate")
                    && REST_TEMPLATE_METHODS.contains(name) && !args.isEmpty()) {
                    emitCall(verbOf(name), resolver.resolve(args.get(0)), TYPED);
                } else if (name.equals("uri")
                    && (decl.startsWith("org.springframework.web.reactive.function.client.WebClient")
                        || decl.startsWith("org.springframework.web.client.RestClient"))
                    && !args.isEmpty()) {
                    String base = baseUrlOfChain(mi.getSelect());
                    String u = resolver.resolve(args.get(0));
                    emitCall(verbOfChain(mi.getSelect()), base != null && u.startsWith("/") ? base + u : u, TYPED);
                } else if (decl.startsWith("org.springframework.kafka.core.KafkaTemplate") && name.equals("send") && !args.isEmpty()) {
                    emitPublish(resolver.resolve(args.get(0)), "kafka", TYPED);
                } else if (decl.startsWith("org.springframework.cloud.stream.function.StreamBridge") && name.equals("send") && !args.isEmpty()) {
                    emitPublish(resolver.resolve(args.get(0)), "kafka", TYPED);
                } else if (decl.startsWith("org.springframework.amqp.rabbit.core.RabbitTemplate")
                    && (name.equals("convertAndSend") || name.equals("send")) && !args.isEmpty()) {
                    emitPublish(resolver.resolve(args.get(0)), "amqp", TYPED);
                }
            } else {
                // тип не атрибутирован (нет classpath) — эвристика по имени получателя
                String recv = receiverName(mi.getSelect());
                if (recv != null) {
                    if (recv.toLowerCase(Locale.ROOT).contains("kafkatemplate") && name.equals("send") && !args.isEmpty()) {
                        emitPublish(resolver.resolve(args.get(0)), "kafka", UNTYPED);
                    } else if (recv.toLowerCase(Locale.ROOT).contains("streambridge") && name.equals("send") && !args.isEmpty()) {
                        emitPublish(resolver.resolve(args.get(0)), "kafka", UNTYPED);
                    } else if (recv.toLowerCase(Locale.ROOT).contains("rabbittemplate")
                        && (name.equals("convertAndSend") || name.equals("send")) && !args.isEmpty()) {
                        emitPublish(resolver.resolve(args.get(0)), "amqp", UNTYPED);
                    } else if (recv.toLowerCase(Locale.ROOT).contains("resttemplate")
                        && REST_TEMPLATE_METHODS.contains(name) && !args.isEmpty()) {
                        emitCall(verbOf(name), resolver.resolve(args.get(0)), UNTYPED);
                    }
                }
                if (name.equals("uri") && !args.isEmpty()) {
                    String u = resolver.resolve(args.get(0));
                    if (u.startsWith("http") || u.startsWith("/")) {
                        String base = baseUrlOfChain(mi.getSelect());
                        emitCall(verbOfChain(mi.getSelect()), base != null && u.startsWith("/") ? base + u : u, UNTYPED);
                    }
                }
            }
            return super.visitMethodInvocation(mi, p);
        }

        String receiverName(Expression sel) {
            if (sel instanceof J.Identifier id) return id.getSimpleName();
            if (sel instanceof J.FieldAccess fa) return fa.getSimpleName();
            return null;
        }

        void emitCall(String verb, String url, double conf) {
            if (url == null || url.isEmpty() || url.equals("{?}")) return;
            List<String> kv = new ArrayList<>();
            if (verb != null && HTTP_VERBS.contains(verb)) kv.add("method=" + verb);
            kv.add("urlTemplate=" + url);
            String host = hostOf(url);
            if (host != null) kv.add("host=" + host);
            String path = pathOf(url);
            if (path != null) kv.add("path=" + path);
            emit("OUTGOING_CALL", src(), conf, kv);
        }

        void emitPublish(String channel, String protocol, double conf) {
            if (channel == null || channel.isEmpty() || channel.equals("{?}")) return;
            emit("PUBLISH", src(), conf, List.of("channel=" + channel, "protocol=" + protocol));
        }

        // спуск по цепочке .retrieve().get()... до WebClient.create(x)/baseUrl(x)
        String baseUrlOfChain(Expression sel) {
            while (sel != null) {
                if (sel instanceof J.MethodInvocation m) {
                    if ((m.getSimpleName().equals("create") || m.getSimpleName().equals("baseUrl"))
                        && !m.getArguments().isEmpty() && !(m.getArguments().get(0) instanceof J.Empty))
                        return resolver.resolve(m.getArguments().get(0));
                    sel = m.getSelect();
                } else if (sel instanceof J.Identifier id && id.getFieldType() != null) {
                    Object init = symbols.get(ownerOf(id.getFieldType()) + "#" + id.getSimpleName());
                    if (init instanceof Expression e) sel = e;
                    else return null;
                } else return null;
            }
            return null;
        }

        String verbOfChain(Expression sel) {
            while (sel instanceof J.MethodInvocation m) {
                String n = m.getSimpleName().toUpperCase(Locale.ROOT);
                if (HTTP_VERBS.contains(n)) return n;
                sel = m.getSelect();
            }
            return null;
        }
    }

    void emit(String type, String source, double conf, List<String> kv) {
        StringBuilder sb = new StringBuilder(type);
        for (String pair : kv) sb.append('|').append(sanitize(pair));
        sb.append('|').append(sanitize(source)).append('|').append(conf);
        lines.add(sb.toString());
    }

    static String sanitize(String s) {
        return s.replace('|', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    static String verbOf(String m) {
        if (m.startsWith("get")) return "GET";
        if (m.startsWith("post")) return "POST";
        if (m.startsWith("put")) return "PUT";
        if (m.startsWith("delete")) return "DELETE";
        if (m.startsWith("patch")) return "PATCH";
        return null;
    }

    static String joinPath(String prefix, String op) {
        String a = prefix == null ? "" : prefix.trim();
        String b = op == null ? "" : op.trim();
        if (a.isEmpty()) return b.isEmpty() ? "/" : b;
        if (b.isEmpty()) return a;
        if (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        return b.startsWith("/") ? a + b : a + "/" + b;
    }

    /** RequestMapping(method = RequestMethod.GET) -> GET; без аргумента — null (метод неизвестен). */
    static String requestMethod(J.Annotation a) {
        if (a.getArguments() == null) return null;
        for (Expression arg : a.getArguments()) {
            if (arg instanceof J.Assignment as && as.getVariable() instanceof J.Identifier id
                && id.getSimpleName().equals("method")) {
                Expression v = as.getAssignment();
                String n = null;
                if (v instanceof J.FieldAccess fa) n = fa.getSimpleName();
                else if (v instanceof J.Identifier i2) n = i2.getSimpleName();
                if (n != null && HTTP_VERBS.contains(n)) return n;
            }
        }
        return null;
    }

    /** path-часть URL: после хоста, после ведущего плейсхолдера-base или как есть; без query. */
    static String pathOf(String url) {
        String path = null;
        if (url.startsWith("http://") || url.startsWith("https://")) {
            String rest = url.substring(url.indexOf("//") + 2);
            int i = rest.indexOf('/');
            if (i >= 0) path = rest.substring(i);
        } else if (url.startsWith("{")) {
            int i = url.indexOf('}');
            if (i >= 0 && i + 1 < url.length() && url.charAt(i + 1) == '/') path = url.substring(i + 1);
        } else if (url.startsWith("/")) {
            path = url;
        }
        if (path == null) return null;
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }

    static String hostOf(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null;
        String rest = url.substring(url.indexOf("//") + 2);
        int cut = rest.length();
        for (char c : new char[]{'/', ':', '?'}) {
            int i = rest.indexOf(c);
            if (i >= 0 && i < cut) cut = i;
        }
        String host = rest.substring(0, cut);
        // плейсхолдеры и подстановки — не хост
        if (host.isEmpty() || host.contains("{") || host.contains("$")) return null;
        return host;
    }

    /** Признаки файлов, где живут наши факты. Всё остальное не парсим. */
    private static final String[] MARKERS = {
        "WebClient", "RestTemplate", "RestClient", "FeignClient",
        "RestController", "Controller", "RequestMapping",
        "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping",
        "KafkaTemplate", "RabbitTemplate", "StreamBridge", "KafkaListener", "RabbitListener",
    };

    static boolean isCandidate(Path p) {
        final String text;
        try {
            text = Files.readString(p);
        } catch (Exception e) {
            return true; // не смогли прочитать — пусть решает парсер
        }
        for (String m : MARKERS) {
            if (text.contains(m)) return true;
        }
        // держатели констант (Topics.ORDERS и т.п.) нужны резолверу; большие файлы — вряд ли они
        return text.length() < 65536 && (text.contains("const val") || text.contains("static final"));
    }

    /** Строка литерала; rewrite-kotlin отдаёт Kotlin-эскейп `\$` сырым — нормализуем. */
    static String lit(Object v) {
        return v == null ? null : String.valueOf(v).replace("\\$", "$");
    }

    static Object literalOf(Expression e) {
        if (e instanceof J.Literal l) return lit(l.getValue());
        if (e instanceof J.Assignment a && a.getAssignment() instanceof J.Literal l) return lit(l.getValue());
        return null;
    }

    static String annArg(J.Annotation a, String key, String altKey) {
        if (a.getArguments() == null) return null;
        for (Expression arg : a.getArguments()) {
            if (arg instanceof J.Assignment as && as.getVariable() instanceof J.Identifier id) {
                if (id.getSimpleName().equals(key) || (altKey != null && id.getSimpleName().equals(altKey))) {
                    if (as.getAssignment() instanceof J.Literal l) return lit(l.getValue());
                }
            } else if (arg instanceof J.Literal l && (key.equals("value") || (altKey != null && altKey.equals("value")))) {
                return lit(l.getValue());
            }
        }
        return null;
    }

    /** Массивные аргументы аннотаций: topics = {"a", B.C} / topics = ["a"] / одиночное значение. */
    static List<String> annArgMany(J.Annotation a, String key, String altKey, Resolver resolver) {
        List<String> out = new ArrayList<>();
        if (a.getArguments() == null) return out;
        for (Expression arg : a.getArguments()) {
            Expression value = null;
            if (arg instanceof J.Assignment as && as.getVariable() instanceof J.Identifier id) {
                if (id.getSimpleName().equals(key) || (altKey != null && id.getSimpleName().equals(altKey))) {
                    value = as.getAssignment();
                }
            } else if (key.equals("value") || (altKey != null && altKey.equals("value"))) {
                value = arg;
            }
            if (value == null) continue;
            for (Expression el : elementsOf(value)) {
                String s = resolver.resolve(el);
                if (s != null && !s.isEmpty() && !s.equals("{?}")) out.add(s);
            }
        }
        return out;
    }

    static List<Expression> elementsOf(Expression e) {
        if (e instanceof J.NewArray na && na.getInitializer() != null) {
            return na.getInitializer().stream().filter(x -> x instanceof Expression).map(x -> (Expression) x).toList();
        }
        if (e instanceof K.ListLiteral kl) { // Kotlin: topics = ["a", B.C]
            return kl.getElements().stream().filter(x -> x instanceof Expression).map(x -> (Expression) x).toList();
        }
        return List.of(e);
    }

    /** FQ типа аннотации; JavaType.Unknown (нет classpath) — это НЕ тип. */
    static String fqOf(J.Annotation a) {
        return a.getType() instanceof JavaType.FullyQualified f && !(f instanceof JavaType.Unknown)
            ? f.getFullyQualifiedName() : null;
    }

    static String ownerOf(JavaType.Variable v) {
        return v.getOwner() instanceof JavaType.FullyQualified fq ? fq.getFullyQualifiedName() : "?";
    }

    /** Статический резолв выражения до строки; нерезолвнутое — {имя} или ${...} у @Value. */
    static class Resolver {
        final Map<String, Object> symbols;
        final Map<String, List<Object>> byName;

        Resolver(Map<String, Object> s, Map<String, List<Object>> n) { symbols = s; byName = n; }

        String resolve(Expression e) { return resolve(e, new HashSet<>()); }

        String resolve(J e, Set<String> seen) {
            if (e instanceof J.Literal l) return lit(l.getValue());
            if (e instanceof J.Parentheses<?> p) return resolve(p.getTree(), seen);
            if (e instanceof J.Binary b && b.getOperator() == J.Binary.Type.Addition)
                return resolve(b.getLeft(), seen) + resolve(b.getRight(), seen);
            if (e instanceof K.StringTemplate st) {
                StringBuilder sb = new StringBuilder();
                for (J part : st.getStrings()) {
                    if (part instanceof J.Literal l) sb.append(l.getValue());
                    else if (part instanceof K.StringTemplate.Expression ex) sb.append(resolve(ex.getTree(), seen));
                    else sb.append(resolve(part, seen));
                }
                return sb.toString();
            }
            if (e instanceof J.Identifier id) return resolveVar(id.getFieldType(), id.getSimpleName(), seen);
            if (e instanceof J.FieldAccess fa) return resolveVar(fa.getName().getFieldType(), fa.getSimpleName(), seen);
            if (e instanceof J.MethodInvocation mi) return "{" + mi.getSimpleName() + "()}";
            return "{?}";
        }

        String resolveVar(JavaType.Variable vt, String simpleName, Set<String> seen) {
            Object init = null;
            if (vt != null) init = symbols.get(ownerOf(vt) + "#" + simpleName);
            if (init == null) {
                List<Object> cands = byName.get(simpleName);
                if (cands != null && cands.size() == 1) init = cands.get(0);
            }
            if (init == null || !seen.add(simpleName)) return "{" + simpleName + "}";
            if (init instanceof String placeholder) return placeholder; // @Value: ${...} остаётся как есть
            return resolve((Expression) init, seen);
        }
    }
}
