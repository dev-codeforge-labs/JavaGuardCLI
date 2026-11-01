# JavaGuardCLI rules

JavaGuardCLI checks the code an AI agent writes, while it writes it. This document explains every check it performs: what
it catches, why that matters in production, and what the agent is told to do about it.

Other languages: [Español](rules_es.md) · [Français](rules_fr.md).

The rules are meant to be **conservative**: each one fires only on a specific syntactic pattern, never on a "maybe". The
trade-off is that they reduce risk but do not replace code review, tests or load testing.

- [How the rules run](#how-the-rules-run)
- [1. Syntax and build](#1-syntax-and-build) - [2. Security](#2-security-jg-sec) - [3. Performance](#3-performance-jg-perf) -
  [4. Reliability](#4-reliability-jg-rel) - [5. Concurrency](#5-concurrency-jg-conc) - [6. Resilience](#6-resilience-jg-res) -
  [7. Spring and JPA](#7-spring-and-jpa-jg-spr) - [8. Test integrity](#8-test-integrity-jg-tst) - [9. AI debris](#9-ai-debris-jg-ai) -
  [10. Dependencies](#10-dependencies-jg-dep) - [11. API compatibility](#11-api-compatibility-jg-api) -
  [12. Database migrations](#12-database-migrations-jg-db) - [13. Configuration](#13-configuration-jg-cfg) -
  [14. Architecture and suppressions](#14-architecture-and-suppressions) - [15. Other engines](#15-other-engines)
- [Tuning the rules](#tuning-the-rules)

## How the rules run

JavaGuardCLI runs as a hook inside the agent (Claude Code, Gemini CLI, Codex) at two moments:

| Gate | When | What runs |
|---|---|---|
| **Post-edit** (`F` in this document) | After every file the agent writes | The file-local rules on the changed file, in about a second |
| **End of turn** (`E`) | When the agent says it is done | Compilation, the affected tests, deep analysis (SpotBugs/FindSecBugs, dependency vulnerabilities, API and endpoint comparison, configuration keys) and any file-local rule not yet applied to a file |

A rule marked `F` also runs at the end of the turn on files the post-edit gate did not see. A rule marked `E` runs only
then, because it needs the whole build or a comparison with the version before the session.

### Severity and what blocks

Every finding has a severity: **BLOCKER**, **CRITICAL**, **MAJOR** or **INFO**. The severity says how serious the problem
is; the gate decides whether the agent is stopped:

| Gate | Blocks the agent | Everything else |
|---|---|---|
| Post-edit | BLOCKER findings, source that does not parse (`JG-SYN-001`), and test-integrity findings (`JG-TST-*`) of CRITICAL or higher | The agent is told and keeps going |
| End of turn | Compilation errors, an unresolvable dependency (`JG-DEP-003`), CRITICAL or higher, test-integrity findings of MAJOR or higher, and failing tests | A one-line summary for you |

So a CRITICAL rule such as `JG-SEC-002` only advises right after the edit and blocks at the end of the turn. To make a rule
block in both gates, raise its severity in `javaguard.yml` (see [Tuning the rules](#tuning-the-rules)).

### What an entry shows

Each rule lists its **severity**, its **gate** (`F` or `E`) and, where it matters, its **scope** (`main` code, `test` code,
or both). Where a rule is limited to what the agent added during the session, it says so: JavaGuardCLI compares each file
with its version before the session and reports only what the agent introduced, not the problems that were already there.

### How the agent is told to fix a finding

Every finding carries a remediation. The agent is also given a general guidance: fix the root cause and rewrite the code so
the finding disappears while keeping the behavior that was asked for; do not change JavaGuard's configuration or hooks; do
not weaken, disable or delete tests; add `// javaguard:ignore <rule> -- <reason>` only for genuine false positives; and if
the only way to satisfy a rule contradicts what you asked for, stop and explain it to you instead of retrying. Turning a
rule off in `javaguard.yml` is always your decision, never the agent's.

---

## 1. Syntax and build

| Id | Severity | Gate |
|---|---|---|
| JG-SYN-001 | BLOCKER | F |
| JAVAC | CRITICAL | F advises, E blocks |
| TEST | CRITICAL | E |

**JG-SYN-001, Source does not parse.** The file is not valid Java: JavaParser could not read it. The message gives the first
problem with its line and column. Nothing else can be analyzed or compiled until it is fixed, so it blocks immediately.
*Fix:* repair the syntax error first.

**JAVAC, Compilation error.** `javac` reports an error. After an edit JavaGuardCLI compiles in-process and advises; at the
end of the turn the project's own build compiles and the error blocks. The finding carries the compiler's `symbol`,
`location`, `required` and `found`, and a hint adapted to the error (for "cannot find symbol": read the declaring class
instead of guessing). A file that starts with a UTF-8 byte order mark is reported as such, because `javac` rejects it.

**TEST, Test failure.** A JUnit test failed or errored in the affected tests run at the end of the turn. The report shows the
test and the expected and actual values. *Fix:* read the failing test and the production code it calls, and reason from the
test's setup; do not change the test to make it pass.

## 2. Security (JG-SEC)

All apply to `main` code and run in the post-edit gate, except where noted.

**JG-SEC-001, SQL built by concatenation. BLOCKER.** A non-constant string built with `+`, `concat`, `String.format`,
`formatted` or `StringBuilder.append` is passed to a JDBC or SQL-executing call. This is the classic SQL injection: any
user-controlled piece rewrites the query. *Fix:* use a parameterized query (`?` placeholders or a named-parameter API) and
bind the values; fragments fixed in the code (column lists, table names from constants) are fine to concatenate. At the end
of the turn SpotBugs/FindSecBugs gives a second opinion on SQL injection; it only blocks flows it is sure about.

**JG-SEC-002, Command built from variable input. CRITICAL.** `Runtime.exec(String)` with a non-constant string, or
`ProcessBuilder`/`exec` with `"sh","-c"` or `"cmd","/c"` followed by a non-constant. Input that reaches a shell can run any
command. *Fix:* pass the program and each argument as separate array elements, validate or allow-list the input, or avoid
the shell altogether.

**JG-SEC-003, Unsafe deserialization. CRITICAL.** `ObjectInputStream.readObject/readUnshared` without
`setObjectInputFilter` in the same method; `XMLDecoder.readObject`; Jackson default typing or `Id.CLASS`; SnakeYAML
constructed without a safe constructor. Deserializing untrusted data can instantiate arbitrary classes and execute code.
*Fix:* set an input filter, use a data format with an explicit schema, a `PolymorphicTypeValidator` with an allow-list,
`SafeConstructor`, or `Id.NAME` with registered subtypes.

**JG-SEC-004, XML parser open to XXE. CRITICAL.** A `DocumentBuilderFactory`, `SAXParserFactory`, `XMLInputFactory`,
`TransformerFactory`, `SchemaFactory` or `XMLReader` is created and used without disabling DTDs or external entities in the
same method. External entities let an attacker read local files or reach internal services. *Fix:* disable DTDs before
parsing: `setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)`, `XMLInputFactory.SUPPORT_DTD` set to
`false`, and `ACCESS_EXTERNAL_DTD`, `ACCESS_EXTERNAL_STYLESHEET` and `ACCESS_EXTERNAL_SCHEMA` set to `""`.

**JG-SEC-005, Weak cryptography. CRITICAL.** `MessageDigest` with MD5 or SHA-1 (INFO when the name suggests a checksum, etag
or cache key); `Cipher.getInstance` with a weak or default transformation; a hard-coded or reused IV; `KeyPairGenerator`
under 2048 bits. *Fix:* an explicit strong transformation such as `AES/GCM/NoPadding`, a fresh random IV for every
encryption, SHA-256 or stronger (passwords need a slow hash such as bcrypt or Argon2), and RSA keys of at least 2048 bits.
PMD's `HardCodedCryptoKey` and `InsecureCryptoIv` are reported under this id.

**JG-SEC-006, Predictable randomness for secrets. MAJOR.** `new Random()`, `Math.random()` or `ThreadLocalRandom` whose result
is assigned to or returned from an identifier that looks like a token, password, secret, salt, nonce, OTP, session or key.
These generators can be predicted from a few outputs. *Fix:* `java.security.SecureRandom`.

**JG-SEC-007, TLS verification disabled. BLOCKER.** An `X509TrustManager` whose `checkServerTrusted` is empty; a
`HostnameVerifier` or lambda that always returns `true`; `NoopHostnameVerifier`, `TrustAllStrategy`,
`TrustSelfSignedStrategy`, `ALLOW_ALL_HOSTNAME_VERIFIER`, `InsecureTrustManagerFactory`. With these the connection is
encrypted but anyone can impersonate the server. *Fix:* remove the custom verifier or trust manager and keep the platform
defaults; for a private CA, add its certificate to a truststore.

**JG-SEC-008, Hard-coded secret. BLOCKER (MAJOR in test code).** On added lines only: a string literal of eight or more
characters assigned to an identifier that looks like a password, secret, API key, access key, token, private key or
credential (and not a placeholder such as `changeme`, `${...}` or `***`), or a literal in a known credential format (AWS
access keys, GitHub and Slack tokens, Google API keys, PEM private keys, JWTs, JDBC URLs with `password=`). A secret in
source ends up in version control and in the artifact. *Fix:* load it from a secret manager, environment variable or vault,
and rotate it if it was ever committed.

**JG-SEC-009, Path from request input. MAJOR.** A parameter annotated `@RequestParam`, `@PathVariable`, `@RequestHeader`,
`@QueryParam` or `@PathParam`, or `MultipartFile.getOriginalFilename()`, used directly in `new File(...)`, `Paths.get`,
`Path.of`, `Path.resolve`, `Files.*` or a file stream. A `../` in the input reads or overwrites files outside the intended
directory. *Fix:* resolve against a fixed base and check the result, `Path p = base.resolve(name).normalize()` and reject it
unless `p.startsWith(base)`, rejecting `..`, separators and absolute paths first; or map the input through an allow-list.

**JG-SEC-010, Sensitive data logged. MAJOR.** A logger call (or `System.out/err.print*`) with an argument whose name looks
like a password, secret, token or key. Logs are copied, indexed and shipped, and secrets in them leak. *Fix:* do not log it;
log a redacted placeholder or omit the value.

**JG-SEC-011, Web security weakened. MAJOR.** Added in the session: Spring Security `csrf().disable()` in any spelling,
`.anyRequest().permitAll()` or `requestMatchers("/**").permitAll()`. *Fix:* keep CSRF protection or scope the exemption with
`ignoringRequestMatchers(...)`; require authentication by default and permit only the endpoints that must be public.

**JG-SEC-012, Permissive CORS with credentials. CRITICAL.** `allowedOrigins("*")`, `addAllowedOrigin("*")` or
`@CrossOrigin(origins="*")` together with `allowCredentials(true)`. Any website could then call the API with the user's
cookies. *Fix:* list explicit allowed origins.

**JG-SEC-013, LDAP/XPath/EL built by concatenation. MAJOR.** A concatenated string passed to a `DirContext.search` filter,
`XPath.compile/evaluate`, `ExpressionParser.parseExpression` or `ScriptEngine.eval`. The same injection as in SQL, in other
languages (a SpEL injection can execute code). *Fix:* keep the expression constant and pass the value separately: LDAP
`search(name, "(uid={0})", new Object[]{value}, controls)`, XPath variables through a `XPathVariableResolver`, SpEL variables
on the `EvaluationContext` (or a `SimpleEvaluationContext`), script `Bindings`.

**JG-SEC-014, Open redirect. MAJOR.** `"redirect:" + <request parameter>` or `response.sendRedirect(<request parameter>)`.
Attackers use your domain to send victims to a phishing site. *Fix:* validate the target against an allow-list of known
paths.

**JG-SEC-015, Endpoint without authorization where its siblings have it. MAJOR.** In a controller with at least three
handlers and no class-level authorization, a newly added handler is the only one without `@PreAuthorize`, `@PostAuthorize`,
`@Secured`, `@RolesAllowed`, `@PermitAll` or `@DenyAll`. The likely cause is an endpoint the agent forgot to protect.
*Fix:* add the authorization the endpoint needs, or `@PermitAll` if it is meant to be public. Spring projects only.

## 3. Performance (JG-PERF)

"In a loop" means inside `for`, enhanced `for`, `while`, `do`, or a lambda passed to `forEach` or a stream `map`/`filter`.
All apply to `main` code and run in the post-edit gate.

**JG-PERF-001, String concatenation in a loop. MAJOR.** `s += x` or `s = s + x` where `s` is a `String` declared outside the
loop. Each pass copies the whole string, so the cost grows quadratically. *Fix:* a `StringBuilder` before the loop, append
inside, `toString()` once after.

**JG-PERF-002, Regex compiled in a loop. MAJOR.** `Pattern.compile`, `String.matches`, `replaceAll`, `replaceFirst` or
`split` with a real regex inside a loop: the pattern is compiled on every pass. *Fix:* compile the `Pattern` once before the
loop and reuse its `Matcher`.

**JG-PERF-003, Expensive object created repeatedly. MAJOR.** In a loop, or in a method of a Spring bean called per request:
`new ObjectMapper()`, `new XmlMapper()`, `new Gson()`, `JAXBContext.newInstance`, `new RestTemplate()`, `WebClient` and
`HttpClient` builders; in loops only, `DateTimeFormatter.ofPattern`, `new SimpleDateFormat`,
`DocumentBuilderFactory.newInstance`. These are costly to build and safe to share. *Fix:* create it once (a field, or before
the loop) and reuse it.

**JG-PERF-004, Unbuffered stream I/O. MAJOR.** `FileInputStream`, `FileReader`, `FileOutputStream` or `FileWriter` not
wrapped in a `Buffered*` class whose `read()` or `write(int)` is called in a loop: one system call per byte or character.
*Fix:* wrap it in a buffered class or read into an array.

**JG-PERF-005, Linear lookup in a loop. INFO.** `contains`, `indexOf` or `remove(Object)` on a `List` inside a loop over
another collection. *Fix:* a `HashSet` or `HashMap`, or index the collection before the loop.

**JG-PERF-006, Head removal on an ArrayList in a loop. INFO.** `list.remove(0)` on an `ArrayList` in a loop shifts every
element each time. *Fix:* an `ArrayDeque` and `removeFirst()`.

**JG-PERF-007, Boxed accumulator in a loop. INFO.** A `Long`, `Integer` or `Double` local updated with `+=` or `++` in a
loop allocates an object per pass. *Fix:* use the primitive type.

**JG-PERF-008, Busy wait. MAJOR.** A `while` whose body only sleeps and checks a flag, or polls a collection or field with
`Thread.sleep`. It wastes CPU and adds latency. *Fix:* `wait()` and `notifyAll()`, a `java.util.concurrent` primitive
(`CountDownLatch`, `Condition`) or a blocking call.

**JG-PERF-009, Whole file or stream materialized. INFO.** `Files.readAllBytes/readAllLines/readString` on a path inside a
loop, or an HTTP body read fully into a `String` inside a loop. *Fix:* read once outside the loop, or stream it.

## 4. Reliability (JG-REL)

All apply to `main` code unless noted and run in the post-edit gate.

**JG-REL-001, Exception swallowed. MAJOR (CRITICAL for `Exception`, `Throwable`, `RuntimeException`).** A `catch` whose body is
empty or only comments; or a catch of `Exception` or `Throwable` that neither rethrows, nor logs the exception, nor returns
or assigns an error value. The failure disappears and the program carries on in an unknown state. *Fix:* rethrow, log with
the exception attached, or return an explicit fallback value.

**JG-REL-002, Catching Throwable or Error. MAJOR.** `catch (Throwable | Error | OutOfMemoryError | StackOverflowError)`
without a rethrow. JVM-level errors usually mean the program cannot safely continue. *Fix:* catch a narrower type, or
rethrow after handling.

**JG-REL-003, Resource not closed. CRITICAL (main and test code).** A local `AutoCloseable` created with `new` or by a
JDBC, `Files.new*`, `Files.lines/list/walk/find`, `Scanner` or `ZipFile` call, not declared in try-with-resources, not
closed in `finally`, not returned, not stored in a field and not handed to another managed closeable. Leaked connections and
file handles exhaust the pool or the operating system under load. *Fix:* try-with-resources, or close it in `finally`.

**JG-REL-004, Cause dropped when rethrowing. MAJOR.** In `catch (X e)`, `throw new Y(...)` whose arguments do not include `e`
and no `initCause(e)` or `addSuppressed(e)`. The original stack trace is lost, which makes the incident hard to diagnose.
*Fix:* pass the caught exception to the new one's constructor.

**JG-REL-005, Unchecked Optional.get. MAJOR.** `get()` on an `Optional` variable not guarded in the same method by an
`isPresent()` or `isEmpty()` check. It throws `NoSuchElementException` when empty. *Fix:* guard it, or use
`orElseThrow()` or `orElse(...)` to make the empty case explicit.

**JG-REL-006, Null returned as Optional or collection. CRITICAL.** `return null;` in a method declared to return
`Optional`, a `List`, `Set`, `Map` or `Collection`, or a `Stream`. Callers that do not expect it get a
`NullPointerException`. *Fix:* return `Optional.empty()`, an empty collection or `Stream.empty()`.

**JG-REL-007, String compared with ==. CRITICAL (main and test code).** `==` or `!=` between two `String`-typed operands,
neither `null`. It compares identity, so it works in a test and fails with other data. *Fix:* `.equals(...)`, or
`Objects.equals(a, b)` if either side may be null.

**JG-REL-008, BigDecimal misuse. MAJOR.** `new BigDecimal(double)`; `BigDecimal.equals`, which is scale-sensitive; `divide(x)`
without a `MathContext` or `RoundingMode`, which throws on non-terminating results. *Fix:* `BigDecimal.valueOf(double)` or a
`String` literal; `compareTo(...) == 0`; `divide(divisor, RoundingMode.HALF_UP)`.

**JG-REL-009, Floating point for money. MAJOR.** A field, parameter or local of type `double`, `float`, `Double` or `Float`
named like an amount, price, balance, cost, total, fee, tax, salary, payment or money. Floating point cannot represent most
decimal fractions exactly, so the error is silent and accumulates. *Fix:* `BigDecimal`, or a fixed-point integer such as
cents in a `long`.

**JG-REL-010, Interrupt status lost. MAJOR.** `catch (InterruptedException e)` that neither rethrows nor calls
`Thread.currentThread().interrupt()`. The thread's cancellation request is swallowed. *Fix:* restore the flag before
returning, or rethrow.

**JG-REL-011, Control flow in finally. CRITICAL (main and test code).** `return`, `throw`, `break` or `continue` inside
`finally`: it silently discards any exception the `try` was propagating. *Fix:* move it after the `try` statement; `finally`
should only release resources, and try-with-resources does that while keeping the original exception.

**JG-REL-012, System.exit outside main. MAJOR.** `System.exit` or `Runtime.halt` outside a `public static void main`. A library
or service that kills the JVM takes everything else down with it. *Fix:* throw an exception or return an error result and
let the entry point decide.

**JG-REL-013, equals without hashCode. MAJOR.** A class overrides `equals` without `hashCode`, or the reverse. It breaks
`HashMap` and `HashSet`. Reported by Error Prone and PMD under this id.

**JG-REL-014, Mutable static state exposed. MAJOR.** A `public static` field that is not `final`, or a `public static final`
array or mutable collection. Any code can change it, with no synchronization. *Fix:* make it private with controlled
access, or expose an unmodifiable view or a defensive copy.

**JG-REL-015, `toMap` without a merge function. INFO.** `Collectors.toMap(key, value)`: a duplicate key in real data throws
`IllegalStateException`. Maps built in static initializers or from `values()` of an enum, and keys that are identifiers, are
not reported. *Fix:* add a merge function, `(first, second) -> first`, or use `groupingBy` if duplicates are expected.

**JG-REL-016, Unboxing a possibly absent map value. INFO.** `map.get(key)` assigned to a primitive, returned from a
primitive-returning method or used in arithmetic or a comparison, when the method never checks `containsKey`. A missing key
becomes a `NullPointerException`. *Fix:* `getOrDefault(key, fallback)`, or check `containsKey` or the null result first.

**JG-REL-017, First element of a query result. MAJOR.** `get(0)`, `getFirst()` or `iterator().next()` on the result of a
repository, DAO, mapper, `JdbcTemplate` or `EntityManager` call, in a method that never checks that the result is not empty.
An empty result becomes an `IndexOutOfBoundsException`. *Fix:* check `isEmpty()` first, or read it as an `Optional`
(`list.stream().findFirst()`, or a `findFirstBy...` method) and then `orElseThrow(...)` or `orElse(...)`.

**JG-REL-018, Exception logged without the exception. MAJOR.** In a `catch (X e)`, an error-level logger call uses
`e.getMessage()` or `e.toString()` but never passes `e`, so the stack trace is lost. *Fix:* pass the exception as the last
argument, `log.error("message", e)`.

**JG-REL-019, Log placeholders do not match. MAJOR.** A logger call whose literal message has a different number of `{}` than
arguments (a trailing exception does not need a placeholder). Missing values print as `{}`; extra ones are dropped. *Fix:*
make the number of `{}` equal to the number of arguments.

## 5. Concurrency (JG-CONC)

A *singleton bean* is a class annotated `@Service`, `@Component`, `@Repository`, `@Controller`, `@RestController`,
`@Configuration`, `@Singleton` or `@ApplicationScoped`, without a non-singleton `@Scope`. One instance serves every request
at once, so its fields are shared state. All apply to `main` code and run in the post-edit gate.

**JG-CONC-001, Thread-unsafe shared formatter. CRITICAL.** A `static` field, or an instance field of a singleton bean, of type
`SimpleDateFormat`, `DateFormat`, `DecimalFormat` or `MessageFormat`. These are not thread-safe and produce wrong dates and
numbers under concurrency. *Fix:* a `ThreadLocal`, the immutable `DateTimeFormatter`, or one instance per use.

**JG-CONC-002, Mutable state in a singleton bean. MAJOR.** A non-final instance field of a singleton bean (not injected, not
only constructor-assigned) assigned in a regular method, or a `final` field holding a `HashMap`, `ArrayList`, `HashSet`,
`LinkedList` or `TreeMap` that is mutated in a regular method. Requests overwrite each other's data. *Fix:* make it
immutable and constructor-assigned, use a concurrent holder (`AtomicReference`, a concurrent collection) or synchronize.

**JG-CONC-003, Double-checked locking without volatile. CRITICAL.** The classic lazy-initialization pattern on a field that is
not `volatile`: another thread can see a partly built object. *Fix:* declare the field `volatile`, or use a holder class.

**JG-CONC-004, Bad lock object. MAJOR.** `synchronized` on a `String` literal, a boxed primitive, `getClass()` or a non-final
field. Unrelated code can share the same lock, or the lock object can change. *Fix:* a `private final Object` dedicated to the
purpose, or a `java.util.concurrent` lock.

**JG-CONC-005, Check-then-act on a concurrent map. MAJOR.** `containsKey` or `get == null` followed by `put` on the same key of a
`ConcurrentMap` in the same block. The map is thread-safe but the two steps are not atomic. *Fix:* `computeIfAbsent` or
`putIfAbsent`.

**JG-CONC-006, Unmanaged threads or executors. MAJOR.** `new Thread(...).start()` or `Executors.new*` inside a method of a
singleton bean, or an executor created in a method and never shut down there. Threads pile up with every call. *Fix:* inject
a shared container-managed `ExecutorService` or `TaskExecutor` bean, or shut it down.

**JG-CONC-007, wait/notify misuse. MAJOR.** `wait()` not inside a `while` loop (spurious wakeups), or `notify()` instead of
`notifyAll()`. *Fix:* `while (!condition) { lock.wait(); }` and `notifyAll()`, or a `java.util.concurrent` primitive.

**JG-CONC-008, Side effects in a parallel stream. CRITICAL.** `parallelStream()` or `.parallel()` followed by
`forEach`, `map` or `peek` whose lambda adds to or modifies a non-concurrent collection captured from outside. Lost updates
and corrupted collections. *Fix:* `collect()`, or a concurrent collection.

**JG-CONC-009, Non-atomic update of a shared field. MAJOR.** `++`, `--` or `+=` on a non-volatile, non-atomic instance field of
a singleton bean or on a `static` field, outside `synchronized`. Concurrent increments are lost. *Fix:* `AtomicInteger` or
`AtomicLong`, or synchronize the update.

**JG-CONC-010, Lock not released on every path. CRITICAL.** `lock()` or `lockInterruptibly()` on a `Lock`, `ReentrantLock`,
`ReadLock`, `WriteLock` or `StampedLock` not followed by a `try` whose `finally` calls `unlock()`. One exception leaves the
lock held and the application deadlocks. Not reported when acquire and release are in different methods. *Fix:*
`lock.lock(); try { ... } finally { lock.unlock(); }` with the `try` right after the lock call.

**JG-CONC-011, ThreadLocal not removed. MAJOR.** A private `ThreadLocal` on which the class calls `set(...)` and never
`remove()`. In a thread pool the value leaks into later requests and the thread keeps it alive. *Fix:* call `remove()` in a
`finally` block, or in a filter's `finally`, when the work that needs the value is done.

## 6. Resilience (JG-RES)

The missing safety nets behind production incidents. All apply to `main` code and run in the post-edit gate.

**JG-RES-001, HTTP client without timeouts. CRITICAL.** A client built without connect or read timeouts: `new RestTemplate()`
with no request factory, `HttpClient` without `connectTimeout`, `HttpRequest` without `timeout`, `WebClient` without a
connector or timeout, `url.openConnection()` without both `setConnectTimeout` and `setReadTimeout`, Apache `HttpClients`
without a request configuration. One slow dependency then holds every thread of the caller and the outage spreads. *Fix:*
set both timeouts: `RestTemplateBuilder.setConnectTimeout/setReadTimeout`,
`HttpClient.newBuilder().connectTimeout(Duration)` plus a timeout on every request, a `ReactorClientHttpConnector` with
`responseTimeout`, or `RequestConfig` for Apache.

**JG-RES-002, Unbounded wait. MAJOR.** `get()` or `join()` on a future or thread, `await()` on a latch, barrier or phaser,
`acquire()` on a `Semaphore`, `take()` on a `BlockingQueue`, with no timeout, outside `main`. A future the method created
itself, and `get()` under `if (f.isDone())`, are not reported. *Fix:* use the overload with a timeout and handle the timeout.

**JG-RES-003, Remote call inside a transaction. MAJOR.** Spring only. A call on a `RestTemplate`, `WebClient`, `HttpClient`,
`KafkaTemplate`, `RabbitTemplate`, `JmsTemplate`, SQS/SNS template or any `*Client` variable, inside a `@Transactional` method
or class. The database connection stays open while waiting for the network. *Fix:* make the remote call before or after the
transactional method, or publish an event or outbox record and call from outside.

**JG-RES-004, Remote call in a loop. MAJOR.** The same receivers called in a loop body or stream-iteration lambda, outside
`main`: one network round trip per element. Asynchronous `KafkaTemplate.send` is not reported. *Fix:* a batch endpoint, one
request for what the loop needs, or concurrent calls with a bounded pool.

**JG-RES-005, Unbounded thread pool or queue. MAJOR (INFO for fixed and single pools).** `Executors.newCachedThreadPool()`; a
`ThreadPoolExecutor` given a `LinkedBlockingQueue`, `LinkedBlockingDeque` or `PriorityBlockingQueue` with no capacity;
INFO for `newFixedThreadPool` and `newSingleThreadExecutor` (bounded threads, unbounded queue). Under load the application
runs out of memory or threads. *Fix:* a `ThreadPoolExecutor` with a maximum size, a bounded queue and a rejection policy.

**JG-RES-006, Blocking work on the common pool. MAJOR.** `CompletableFuture.supplyAsync` or `runAsync` without an executor
whose lambda calls data access, a remote client, `Thread.sleep` or file reads. It starves the shared `ForkJoinPool` used by
parallel streams and every other async task. *Fix:* pass a dedicated, bounded executor as the second argument.

## 7. Spring and JPA (JG-SPR)

Active when Spring or JPA is detected in the project. All apply to `main` code.

**JG-SPR-001, Proxy annotation has no effect. CRITICAL.** `@Transactional`, `@Async`, `@Cacheable`, `@CacheEvict` or
`@Retryable` on a `private`, `final` or `static` method. Spring cannot proxy it, so there is no transaction, no cache, no
retry, and nothing says so. *Fix:* make the method public, non-final and non-static and call it from another bean, or move it
to a separate `@Service` the callers inject.

**JG-SPR-002, Self-invocation bypasses the proxy. MAJOR.** A method calls, unqualified or through `this.`, another method of
the same class annotated `@Transactional`, `@Async`, `@Cacheable` or `@Retryable`, and the caller itself lacks the
annotation. The call skips the proxy, so the annotation is ignored. *Fix:* move the called method to a separate `@Service`
and call it through the injected reference.

**JG-SPR-003, Query inside a loop (N+1). MAJOR.** In a loop, a call on a `*Repository`, `*Dao`, `*Mapper`, `JdbcTemplate`,
`NamedParameterJdbcTemplate`, `EntityManager` or `Session`; or (INFO) a getter of a `@OneToMany` or `@ManyToMany` field of an
entity in a loop. One query per element is invisible with a few rows and brings the database down with many. *Fix:* fetch
what the loop needs in one query before it (`findAllById`, a join fetch, an entity graph, a batch).

**JG-SPR-004, Eager to-many association. MAJOR.** `@OneToMany` or `@ManyToMany` with `fetch = FetchType.EAGER`: every load of
the entity also loads the whole collection. *Fix:* keep the default lazy fetch and fetch the collection explicitly (join
fetch, entity graph) where it is needed.

**JG-SPR-005, Load all then filter in memory. MAJOR.** `findAll()` with no `Pageable`, `Specification` or `Example`, followed
by a stream `filter` or a loop with `if` in the same method. *Fix:* put the condition in the query: a derived query,
`@Query`, a `Specification` or a `Pageable`.

**JG-SPR-006, Exception swallowed in a transaction. MAJOR.** In a `@Transactional` method or class, a `catch` that neither
rethrows nor calls `setRollbackOnly()`: the transaction commits even though something failed. *Fix:* rethrow, or
`TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`.

**JG-SPR-007, @Async with a non-future return. MAJOR.** An `@Async` method returning anything other than `void`, `Future`,
`CompletableFuture` or `ListenableFuture`: the caller receives `null`. *Fix:* return `CompletableFuture<T>`, or `void`.

**JG-SPR-008, Entity equality pitfalls. MAJOR.** An `@Entity` with Lombok `@Data` or `@EqualsAndHashCode` without
`onlyExplicitlyIncluded`, or `equals`/`hashCode` based only on a `@GeneratedValue` id without null handling. Entities then
compare wrongly before they are saved or pull lazy collections into `hashCode`. *Fix:* `@Getter`/`@Setter` and an explicit
`equals`/`hashCode` on a business key, or treat an unassigned id as "not equal".

**JG-SPR-009, Blocking call in a reactive pipeline. MAJOR.** `.block()`, `.blockFirst()`, `.blockLast()`, `Thread.sleep` or
non-reactive JDBC and repository calls inside a lambda of a `Mono` or `Flux` operator. It blocks the few event-loop threads.
*Fix:* the reactive equivalent, or move the blocking work to `Schedulers.boundedElastic()`.

**JG-SPR-010, Entity exposed directly. INFO.** A `@RequestBody` parameter or controller return type that is an `@Entity`:
clients can set fields they should not (mass assignment), and lazy associations or private columns get serialized. *Fix:*
bind and return a DTO and map it.

**JG-SPR-011, Checked exception without rollback. MAJOR.** A public or protected method under `@Transactional` whose `throws`
names a checked exception not covered by `rollbackFor`: Spring commits on checked exceptions by default. Read-only
transactions are not reported. *Fix:* add `rollbackFor = X.class` (`rollbackOn` for `jakarta.transaction`), or wrap it in an
unchecked exception.

**JG-SPR-012, Write in a read-only transaction. MAJOR.** Under `@Transactional(readOnly = true)`, a `save*`, `delete*`,
`persist`, `merge`, `remove`, `update*`, `insert*` or `batchUpdate` on a repository, `EntityManager`, `Session` or
`JdbcTemplate`. Depending on the provider the write is lost or fails. *Fix:* remove `readOnly = true`, or move the write to a
method with a read-write transaction.

**JG-SPR-013, `javax` API in a Jakarta project. CRITICAL.** Active only when the build is Jakarta EE (Spring Boot 3+, Spring
Framework 6+, Hibernate ORM 6+ or a jakarta-namespace API). Imports from `javax.persistence`, `javax.validation`,
`javax.servlet`, `javax.ws.rs` or `javax.transaction.Transactional` compile against a leftover dependency and fail at run
time. `javax.annotation.PostConstruct` and `PreDestroy` are not reported, Spring 6 still honours them. *Fix:* use the
`jakarta.*` equivalent and remove the `javax` dependency that lets it compile.

**JG-SPR-014, Request body not validated. MAJOR.** A `@RequestBody` parameter without `@Valid` or `@Validated` whose type is
declared in the module with Bean Validation constraints. The constraints are never checked. PATCH handlers are not
reported. *Fix:* annotate the parameter with `@Valid`.

**JG-SPR-015, Derived query on an unknown property. BLOCKER (post-edit and end of turn).** A method of a Spring Data
repository whose name (`findByNameAndAge`...) names a property that does not exist in the entity, or declares fewer
parameters than the conditions consume. Spring Data fails at application start-up. A common agent mistake: it invents a
field name. *Fix:* use the entity's attribute names exactly, or write the query with `@Query`; one parameter per
condition value, in order (`Between` takes two, `IsNull` and `True` none).

**JG-SPR-016, JPQL on an unknown entity or attribute. CRITICAL (post-edit and end of turn).** A JPQL literal (in `@Query`
without `nativeQuery`, `@NamedQuery`, `createQuery`) that names an entity or an `alias.attribute` path that does not
resolve, or an entity name within two edits of a known one. The query fails when it is created, at start-up. *Fix:* open the
entity class and use its entity and field names exactly (they are case-sensitive and are not table or column names); if the
query is SQL, set `nativeQuery = true`.

**JG-SPR-017, Unpaged `findAll()` returned to the client. MAJOR.** A controller handler returns the result of a
zero-argument `findAll()`. With real data volume the response grows without bound. Repositories of small reference tables
(roles, countries, currencies, statuses) are not reported. *Fix:* take a `Pageable` and return `findAll(pageable)`, or bound
the query.

## 8. Test integrity (JG-TST)

These rules compare the tests with their version before the session. They exist because an agent that cannot make a test
pass may change the test instead of the code. At the post-edit gate they block from CRITICAL; at the end of the turn from
MAJOR. They apply to test code unless noted.

**JG-TST-001, Test method removed. CRITICAL.** A `@Test`, `@ParameterizedTest`, `@RepeatedTest` or `@TestFactory` method that
existed is gone (a rename with an identical body is not reported). *Fix:* restore it; if the test is obsolete, say so to the
user instead of deleting it.

**JG-TST-002, Test disabled. BLOCKER.** `@Disabled`, `@Ignore`, `@DisabledIf*` or `@EnabledIf*` added to a test method or
class. *Fix:* remove it and fix the cause.

**JG-TST-003, Assertions removed. CRITICAL.** The count of assertion calls (JUnit, AssertJ, Hamcrest, Mockito `verify`,
`expect`, `fail`) in a test method decreased.

**JG-TST-004, Expected value changed. MAJOR.** In an assertion whose "actual" expression is unchanged, the expected value
changed. Reported so the agent must justify the new expectation: it is how a failing test gets "fixed" without fixing the
code.

**JG-TST-005, Trivial assertion. CRITICAL.** Added `assertTrue(true)`, `assertFalse(false)`, `assertEquals(x, x)`,
`assertNotNull(<literal or new>)` or `assertThat(true).isTrue()`. The test can never fail. *Fix:* assert a concrete result of
the code under test (a return value, a state change, an interaction); do not just delete it.

**JG-TST-006, Test body emptied. CRITICAL.** A test that had statements and now has none, or only comments.

**JG-TST-007, Assertion failure swallowed. CRITICAL.** A `try` containing assertions with a `catch` of `AssertionError`,
`Throwable` or `Error` that does not rethrow. *Fix:* remove the `try`/`catch`, or catch only the specific exception the
test expects.

**JG-TST-008, Test file deleted. CRITICAL.** A test source file deleted in the session.

**JG-TST-009, Tests skipped by build configuration. BLOCKER.** Added or changed in `pom.xml` or Gradle files: `skipTests`,
`maven.test.skip`, `testFailureIgnore`, a `skip` in surefire or failsafe, new `<excludes>`, `test { enabled = false }`,
`ignoreFailures = true`, a test filter `exclude` or `onlyIf { false }`. Tests must run and must fail the build when they fail.

**JG-TST-010, Assumption added. MAJOR.** `Assumptions.assume*` or `Assume.assume*` added to a test that did not have one: the
test is silently skipped when the assumption is false.

**JG-TST-011, Timeout greatly increased. INFO.** A `@Timeout` or `assertTimeout` value multiplied by ten or more.

**JG-TST-012, Expected-exception test neutralized. MAJOR.** `assertThrows` removed from a test that had it, or `fail(...)`
after the call under test removed.

**JG-TST-013, Production code detects tests. CRITICAL (main code).** Main code importing `org.junit` or `org.mockito`,
probing for them with `Class.forName`, inspecting the stack trace for "Test", or reading a `test-mode`-style switch,
introduced in the session. Production behavior then differs from what the tests exercise. *Fix:* remove the special case; if
behavior must differ in tests, inject it (a constructor parameter or a configuration property) and pass a fake from the
test.

**JG-TST-014, Class under test mocked. MAJOR.** `@Mock`, `mock(X.class)` or `@MockBean X` where `X` is the class the test is
named after (`XTest`). The test then checks the mock, not the code. *Fix:* use a real `X` and mock only its collaborators:
`@InjectMocks`, or `new X(mock(Collaborator.class))`.

## 9. AI debris (JG-AI)

Leftovers that agents characteristically leave behind. All concern code the agent added.

**JG-AI-001, Console output in production code. MAJOR.** `System.out` or `System.err` `print*` added outside `main`
methods and outside classes in a `cli` package. No level, no correlation, no way to redirect or switch it off. *Fix:* log
through the class's SLF4J logger. If the project has no logger, the agent is told not to add a library on its own: it asks you
whether to add SLF4J or whether console output is fine for this program, in which case you turn the rule off.

**JG-AI-002, printStackTrace. MAJOR.** `e.printStackTrace()` added. *Fix:* log the exception, `log.error("...", e)`.

**JG-AI-003, Unimplemented stub. CRITICAL.** An added method whose body only throws `UnsupportedOperationException` or
`RuntimeException("not implemented")`, or only returns `null` or a default beside a TODO, placeholder or stub comment.
A `// TODO implement` style comment is reported at MAJOR. The agent gave up half way. *Fix:* implement the method; if what
it should do is unclear, stop and ask the user; if subclasses are meant to implement it, declare it `abstract`.

**JG-AI-004, Commented-out code added. INFO.** Three or more consecutive added comment lines that parse as Java statements.
*Fix:* delete dead code, version control keeps the history; if it is an explanation, rewrite it as prose.

**JG-AI-005, Placeholder literal. MAJOR.** String literals such as `"TODO"`, `"FIXME"`, `"changeme"`, `"lorem ipsum..."`,
`"foo"`, `"bar"`, `"your-api-key"`, `"xxx"` or an `example.com` URL used as a value in production code. *Fix:* read the value
from configuration under a named key; if the real value is unknown, stop and ask the user instead of inventing one.

**JG-AI-006, Broad warning suppression added. INFO.** `@SuppressWarnings("all")`, `"unchecked"` or `"rawtypes"` added at class
level. *Fix:* narrow it to the member or statement that needs it and to a specific warning.

**JG-AI-007, Unused code added. INFO.** An added private method, field or local that is never used (reported by Error
Prone's `UnusedVariable` and `UnusedMethod`).

## 10. Dependencies (JG-DEP)

Changes to build files (`pom.xml`, Gradle).

**JG-DEP-001, Dependency added. INFO (post-edit).** A new dependency appeared. Agents add libraries too easily and sometimes
the coordinates are wrong. *Fix:* prefer the JDK or a library the build already declares, and remove it if the change works
without it; if a new library is really needed, stop and tell the user which one and why; never guess coordinates or versions.

**JG-DEP-002, Known vulnerability. Severity from the CVSS score (end of turn).** A dependency with a known vulnerability in
the OSV database. Only the coordinates are sent to `api.osv.dev`; it can be turned off in the configuration. *Fix:* upgrade
to a fixed version.

**JG-DEP-003, Dependency cannot be resolved (possibly hallucinated). BLOCKER (end of turn).** The build cannot download an
artifact. Models invent libraries and versions that do not exist. It is reported only when the repositories gave a real "not
found"; offline or a proxy error is an infrastructure note, never a block. *Fix:* copy the coordinates from another module of
the build or from the library's documentation, prefer the version the parent or BOM manages, or use a library the build
already declares; if none fits, ask the user.

**JG-DEP-004, Unpinned or snapshot version. MAJOR (post-edit).** A `LATEST`, `RELEASE`, range or `-SNAPSHOT` version. Builds
stop being reproducible. *Fix:* pin an exact released version.

**JG-DEP-005, Dependency downgraded. MAJOR (post-edit).** A version lower than before. Downgrades often reintroduce fixed bugs
and vulnerabilities. *Fix:* keep the newer version unless the downgrade is deliberate.

## 11. API compatibility (JG-API)

End of turn only. They compare the code with its version before the session and protect the clients you cannot see.

**JG-API-001, Public API changed. MAJOR.** A public signature of a changed file changed or disappeared. *Fix:* keep each old
signature next to the new (deprecated, delegating to it), or update every consumer in the same change. When no module of the
build depends on it, external consumers may.

**JG-API-002, HTTP endpoint removed or moved. MAJOR.** Spring modules: an endpoint (method and combined path) of a changed
controller no longer exists; an unrestricted `@RequestMapping` was restricted to some methods; a declared `consumes` or
`produces` media type was dropped. Moves to another changed controller or a renamed handler are not reported. *Fix:* keep
the old mapping (it can delegate to the new one) until every client has moved, or make the change on purpose and tell the
clients.

**JG-API-003, Endpoint requires a new parameter. MAJOR.** Same endpoint, a `@RequestParam` or `@RequestHeader` that is now
required (no `required = false`, no `defaultValue`, not `Optional`), or a required `@RequestBody` where there was none.
Existing clients start failing. *Fix:* make it optional so existing clients keep working, or add a new endpoint.

**JG-API-004, JSON field renamed or removed. MAJOR.** A type used as a request body or response by a controller loses a JSON
property: a record component, a public field, a getter, a Lombok field; `@JsonProperty`, `@JsonIgnore` and
`@JsonIgnoreProperties` are honoured. *Fix:* keep the JSON name with `@JsonProperty("old")` on the renamed member, or keep
the property.

## 12. Database migrations (JG-DB)

A migration is **applied** when it existed before the session's first edit of it and, in a git repository, is committed. One
that exists only in the working tree has not reached a shared database and may be edited freely. Supported: Flyway (SQL and
Java migrations) and Liquibase (XML, YAML, JSON, formatted SQL). The checks mirror what Flyway and Liquibase themselves
validate at start-up, and were checked against Flyway 10.10 and Liquibase 4.27.

**JG-DB-001, Applied migration modified. BLOCKER.** An applied Flyway SQL migration whose checksum changed (line endings and
blank lines are accepted, like Flyway; anything else, comments and trailing spaces included, fails validation at start-up), or
an applied Liquibase change set (same id and author) whose changes differ. In production the application then refuses to
start, or the schema drifts. `runOnChange` change sets are allowed. *Fix:* restore the file exactly as committed and put the
change in a new migration with the next version. If the migration was never applied to any shared database, tell the user:
only they can exclude it in `javaguard.yml`.

**JG-DB-002, Migration renamed or deleted. BLOCKER.** An applied Flyway versioned migration was deleted or renamed; a
Liquibase change set whose id or author changed (it runs again). Deleting the newest applied migration, or removing an
applied change set or a whole changelog, is MAJOR: Flyway and Liquibase start, but databases drift. *Fix:* restore it; to
undo a migration, add a new one that reverses it.

**JG-DB-003, Duplicate migration version. CRITICAL.** A new Flyway versioned migration whose version (`1`, `1.0` and `1_0` are
the same) is already used by another migration of the same location. Flyway fails at start-up. *Fix:* give it the next free
version.

## 13. Configuration (JG-CFG)

**JG-CFG-001, Secret in configuration. BLOCKER.** In `application*.properties`, `.yml`, `.yaml` and `bootstrap*`: a key whose
name looks like a secret holding a literal of eight or more characters that is not a `${...}` reference, a placeholder, a
URL, number, duration, boolean or path; or any value in a known credential format whatever its key. Keys that merely
mention a secret (`token-uri`, `api-key-header`, `*-validity-seconds`...) are not reported; in development-like profiles
(`dev`, `local`, `test`, `h2`...) the severity is lower. Configuration files are committed and packaged into the artifact.
*Fix:* replace the value with a reference such as `${ENV_NAME}` supplied from the environment or a secret manager; rotate it
if it was ever committed.

**JG-CFG-002, Configuration key never defined. MAJOR (end of turn, advisory).** Spring Boot: a `${key}` without default in
`@Value`, or a mandatory property of a `@Validated @ConfigurationProperties` class, whose key no configuration file of the
module defines. The application fails to start unless the environment provides it. Only what the turn introduced is
reported. *Fix:* define the key in `application.yml` (per profile if it differs), or give the placeholder a default
(`${key:...}`) if it is optional.

**JG-CFG-003, Hard-coded host. MAJOR.** A URL literal to `localhost`, a loopback address, `0.0.0.0` or a private network
address, or a JDBC URL naming a host, added in the session. It works on the developer's machine and nowhere else. Annotation
defaults, `@ConfigurationProperties` and `@Configuration` classes, embedded databases and test code are not reported. *Fix:*
read it from configuration (`@Value("${...}")` or a `@ConfigurationProperties` class) and define it per environment.

## 14. Architecture and suppressions

**JG-ARCH-001, Forbidden dependency. MAJOR.** The project's `javaguard.yml` can declare architecture rules
(`from: com.acme.domain..`, `mustNotDependOn: [com.acme.web..]`). An import that crosses a forbidden boundary is reported.
*Fix:* declare an interface in a package the file may use, implement it in the forbidden package and depend on the
interface; or move the code to a package allowed to use it.

**JG-SUP-001, Suppression without a reason. MAJOR.** `// javaguard:ignore <rule>` with no `-- <reason>`. *Fix:* add
`-- <reason>` explaining why the finding is a false positive.

**JG-SUP-002, Agent-added suppression. BLOCKER (when disallowed).** With `suppressions.allowAgentAdded: false`, a
`javaguard:ignore` the agent added during the session is itself a BLOCKER: it is the easiest way for an agent to silence a
rule. *Fix:* remove the suppression and fix the finding; if it really is a false positive, stop and explain it to the user,
who can add the suppression.

**JG-SUP-003, JavaGuard's configuration or hooks changed. BLOCKER.** At the first hook of a session JavaGuardCLI records the
project's `javaguard.yml`, `~/.javaguard/config.yml` and its own hook entries in `.claude/settings.json`,
`.gemini/settings.json` and `.codex/hooks.json`. For the rest of the session it reads the recorded copy, so an edit changes
nothing, and any change to those files is a BLOCKER until the agent restores them. Other settings in the agent files
(permissions, theme) are not compared. A change you do want mid-session is accepted by `javaguard baseline reset` or by
starting a new session. *Fix:* restore the file exactly; rewrite the code instead of changing JavaGuardCLI.

## 15. Other engines

Besides its own rules, JavaGuardCLI uses three mature engines, with a curated selection so the noise stays low. Style,
naming, formatting, Javadoc, complexity and similar rules are excluded on purpose.

- **Error Prone** runs in the in-process compile after each edit. Every ERROR-level check is enabled (CRITICAL), plus a
  selected set of warnings (for example `BigDecimalEquals`, `BoxedPrimitiveEquality`, `FutureReturnValueIgnored`,
  `CatchAndPrintStackTrace`, `WaitNotInLoop`, `SynchronizeOnNonFinalField`). Findings are `EP:<Check>`.
- **PMD 7** runs a small ruleset (empty catch blocks, `PreserveStackTrace`, `CloseResource`, `ReturnFromFinallyBlock`,
  `DoubleCheckedLocking`, insecure crypto and a few performance rules). Findings are `PMD:<Rule>`.
- **SpotBugs with FindSecBugs** runs at the end of the turn on the changed classes: correctness, multithreading
  correctness and security patterns. SpotBugs' own rank sets the severity (1 to 4 CRITICAL, 5 to 9 MAJOR, 10 to 14 INFO).
  Findings are `SB:<Pattern>`.

When an engine overlaps one of the rules above, the finding is reported once, under the JavaGuardCLI id, and lists the other
engine in its detail.

## Tuning the rules

Every rule can be adjusted in `javaguard.yml`. Disabling, lowering or restricting a rule is a decision for you, not for the
agent:

```yaml
rules:
  JG-PERF-005: { enabled: false }                       # off
  JG-AI-001: { severity: INFO, exclude: ["**/cli/**"] }  # lower it, or skip some paths
  JG-SEC-002: { severity: BLOCKER }                      # block right after the edit as well
  JG-PERF-002: { severity: BLOCKER }
```

`javaguard rules` shows every rule with its effective severity and whether it is enabled. For the full configuration
(thresholds, which findings block, suppressions, notifications) see the user manual, `USAGE.md`.
