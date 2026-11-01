# Reglas de JavaGuardCLI

JavaGuardCLI revisa el código que escribe un agente de IA mientras lo escribe. Este documento explica cada comprobación que
realiza: qué detecta, por qué importa en producción y qué se le dice al agente que haga al respecto.

Las reglas están pensadas para ser **conservadoras**: cada una se dispara solo ante un patrón sintáctico concreto, nunca ante
un "quizá". La contrapartida es que reducen el riesgo, pero no sustituyen a la revisión de código, a los tests ni a las
pruebas de carga.

Otros idiomas: [English](rules.md) · [Français](rules_fr.md).

- [Cómo se ejecutan las reglas](#cómo-se-ejecutan-las-reglas)
- [1. Sintaxis y compilación](#1-sintaxis-y-compilación) - [2. Seguridad](#2-seguridad-jg-sec) - [3. Rendimiento](#3-rendimiento-jg-perf) -
  [4. Fiabilidad](#4-fiabilidad-jg-rel) - [5. Concurrencia](#5-concurrencia-jg-conc) - [6. Resiliencia](#6-resiliencia-jg-res) -
  [7. Spring y JPA](#7-spring-y-jpa-jg-spr) - [8. Integridad de los tests](#8-integridad-de-los-tests-jg-tst) - [9. Restos de IA](#9-restos-de-ia-jg-ai) -
  [10. Dependencias](#10-dependencias-jg-dep) - [11. Compatibilidad de la API](#11-compatibilidad-de-la-api-jg-api) -
  [12. Migraciones de base de datos](#12-migraciones-de-base-de-datos-jg-db) - [13. Configuración](#13-configuración-jg-cfg) -
  [14. Arquitectura y supresiones](#14-arquitectura-y-supresiones) - [15. Otros motores](#15-otros-motores)
- [Ajustar las reglas](#ajustar-las-reglas)

## Cómo se ejecutan las reglas

JavaGuardCLI se ejecuta como un hook dentro del agente (Claude Code, Gemini CLI, Codex) en dos momentos:

| Puerta | Cuándo | Qué se ejecuta |
|---|---|---|
| **Tras una edición** (post-edit, `F` en este documento) | Después de cada fichero que escribe el agente | Las reglas locales al fichero sobre el fichero modificado, en aproximadamente un segundo |
| **Fin de turno** (end-of-turn, `E`) | Cuando el agente dice que ha terminado | Compilación, los tests afectados, análisis profundo (SpotBugs/FindSecBugs, vulnerabilidades de dependencias, comparación de API y endpoints, claves de configuración) y cualquier regla local a un fichero que aún no se haya aplicado a él |

Una regla marcada `F` también se ejecuta al final del turno sobre los ficheros que la puerta post-edit no vio.
Una regla marcada `E` se ejecuta solo entonces, porque necesita toda la compilación o una comparación con la versión anterior a
la sesión.

### Severidad y qué bloquea

Cada hallazgo tiene una severidad: **BLOCKER**, **CRITICAL**, **MAJOR** o **INFO**. La severidad indica la gravedad del
problema; la puerta decide si se detiene al agente:

| Puerta | Bloquea al agente | Todo lo demás |
|---|---|---|
| Tras una edición (post-edit) | Hallazgos BLOCKER, código que no se parsea (`JG-SYN-001`) y hallazgos de integridad de tests (`JG-TST-*`) de CRITICAL o superior | Se avisa al agente y sigue adelante |
| Fin de turno | Errores de compilación, una dependencia que no se puede resolver (`JG-DEP-003`), CRITICAL o superior, hallazgos de integridad de tests de MAJOR o superior y tests que fallan | Un resumen de una línea para ti |

Así, una regla CRITICAL como `JG-SEC-002` solo avisa justo después de la edición y bloquea al final del turno. Para que una
regla bloquee en ambas puertas, sube su severidad en `javaguard.yml` (ver [Ajustar las reglas](#ajustar-las-reglas)).

### Qué muestra cada entrada

Cada regla indica su **severidad**, su **puerta** (`F` o `E`) y, cuando importa, su **ámbito** (código `main`, código de
`test`, o ambos). Si una regla se limita a lo que el agente añadió durante la sesión, lo dice: JavaGuardCLI compara cada
fichero con su versión anterior a la sesión y solo informa de lo que introdujo el agente, no de los problemas que ya había.

### Cómo se le dice al agente que arregle un hallazgo

Cada hallazgo lleva un remedio. Además, el agente recibe una guía general: arreglar la causa raíz y reescribir el código para
que el hallazgo desaparezca manteniendo el comportamiento que se pidió; no cambiar la configuración ni los hooks de
JavaGuard; no debilitar, desactivar ni borrar tests; añadir `// javaguard:ignore <regla> -- <motivo>` solo para falsos
positivos genuinos; y, si la única forma de cumplir una regla contradice lo que pediste, parar y explicártelo en vez de
reintentar. Desactivar una regla en `javaguard.yml` es siempre decisión tuya, nunca del agente.

---

## 1. Sintaxis y compilación

| Id | Severidad | Puerta |
|---|---|---|
| JG-SYN-001 | BLOCKER | F |
| JAVAC | CRITICAL | F avisa, E bloquea |
| TEST | CRITICAL | E |

**JG-SYN-001, El código fuente no se puede analizar.** El fichero no es Java válido: JavaParser no pudo leerlo. El mensaje da
el primer problema con su línea y columna. No se puede analizar ni compilar nada más hasta que se arregle, así que bloquea
de inmediato. *Arreglo:* corregir primero el error de sintaxis.

**JAVAC, Error de compilación.** `javac` informa de un error. Tras una edición JavaGuardCLI compila en el propio proceso y
avisa; al final del turno compila el build del propio proyecto y el error bloquea. El hallazgo incluye `symbol`,
`location`, `required` y `found` del compilador, y una pista adaptada al error (para "cannot find symbol": leer la clase que
lo declara en vez de adivinar). Un fichero que empieza con una marca de orden de bytes UTF-8 se informa como tal, porque
`javac` la rechaza.

**TEST, Fallo de un test.** Un test JUnit falló o dio error en la ejecución de los tests afectados al final del turno. El
informe muestra el test y los valores esperado y real. *Arreglo:* leer el test que falla y el código de producción al que
llama, y razonar a partir de la preparación del test; no cambiar el test para que pase.

## 2. Seguridad (JG-SEC)

Todas se aplican al código `main` y se ejecutan en la puerta post-edit, salvo donde se indique.

**JG-SEC-001, SQL construido por concatenación. BLOCKER.** Una cadena no constante construida con `+`, `concat`,
`String.format`, `formatted` o `StringBuilder.append` se pasa a una llamada JDBC o que ejecuta SQL. Es la inyección SQL
clásica: cualquier fragmento controlado por el usuario reescribe la consulta. *Arreglo:* usar una consulta parametrizada
(marcadores `?` o una API de parámetros con nombre) y enlazar los valores; los fragmentos fijos en el código (listas de
columnas, nombres de tabla desde constantes) se pueden concatenar. Al final del turno SpotBugs/FindSecBugs da una segunda
opinión sobre la inyección SQL; solo bloquea los flujos de los que está seguro.

**JG-SEC-002, Comando construido con una entrada variable. CRITICAL.** `Runtime.exec(String)` con una cadena no constante, o
`ProcessBuilder`/`exec` con `"sh","-c"` o `"cmd","/c"` seguido de un valor no constante. Una entrada que llega a un shell
puede ejecutar cualquier comando. *Arreglo:* pasar el programa y cada argumento como elementos separados de un array, validar
la entrada o usar una lista permitida, o evitar el shell por completo.

**JG-SEC-003, Deserialización insegura. CRITICAL.** `ObjectInputStream.readObject/readUnshared` sin `setObjectInputFilter`
en el mismo método; `XMLDecoder.readObject`; el tipado por defecto de Jackson o `Id.CLASS`; SnakeYAML construido sin un
constructor seguro. Deserializar datos no confiables puede instanciar clases arbitrarias y ejecutar código. *Arreglo:*
establecer un filtro de entrada, usar un formato de datos con esquema explícito, un `PolymorphicTypeValidator` con lista
permitida, `SafeConstructor`, o `Id.NAME` con subtipos registrados.

**JG-SEC-004, Analizador XML abierto a XXE. CRITICAL.** Se crea y usa un `DocumentBuilderFactory`, `SAXParserFactory`,
`XMLInputFactory`, `TransformerFactory`, `SchemaFactory` o `XMLReader` sin desactivar los DTD ni las entidades externas en el
mismo método. Las entidades externas permiten a un atacante leer ficheros locales o llegar a servicios internos. *Arreglo:*
desactivar los DTD antes de analizar: `setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)`,
`XMLInputFactory.SUPPORT_DTD` a `false`, y `ACCESS_EXTERNAL_DTD`, `ACCESS_EXTERNAL_STYLESHEET` y `ACCESS_EXTERNAL_SCHEMA` a
`""`.

**JG-SEC-005, Criptografía débil. CRITICAL.** `MessageDigest` con MD5 o SHA-1 (INFO cuando el nombre sugiere una suma de
comprobación, un etag o una clave de caché); `Cipher.getInstance` con una transformación débil o por defecto; un IV fijo en el
código o reutilizado; `KeyPairGenerator` por debajo de 2048 bits. *Arreglo:* una transformación fuerte y explícita como
`AES/GCM/NoPadding`, un IV aleatorio nuevo en cada cifrado, SHA-256 o superior (las contraseñas necesitan un hash lento como
bcrypt o Argon2) y claves RSA de al menos 2048 bits. `HardCodedCryptoKey` e `InsecureCryptoIv` de PMD se informan bajo este
id.

**JG-SEC-006, Aleatoriedad predecible para secretos. MAJOR.** `new Random()`, `Math.random()` o `ThreadLocalRandom` cuyo
resultado se asigna o devuelve desde un identificador que parece un token, contraseña, secreto, sal, nonce, OTP, sesión o
clave. Estos generadores se pueden predecir a partir de unas pocas salidas. *Arreglo:* `java.security.SecureRandom`.

**JG-SEC-007, Verificación TLS desactivada. BLOCKER.** Un `X509TrustManager` cuyo `checkServerTrusted` está vacío; un
`HostnameVerifier` o lambda que siempre devuelve `true`; `NoopHostnameVerifier`, `TrustAllStrategy`,
`TrustSelfSignedStrategy`, `ALLOW_ALL_HOSTNAME_VERIFIER`, `InsecureTrustManagerFactory`. Con ellos la conexión va cifrada,
pero cualquiera puede suplantar al servidor. *Arreglo:* quitar el verificador o el gestor de confianza personalizado y
mantener los valores por defecto de la plataforma; para una CA privada, añadir su certificado a un almacén de confianza.

**JG-SEC-008, Secreto en el código. BLOCKER (MAJOR en código de test).** Solo en líneas añadidas: un literal de cadena de
ocho o más caracteres asignado a un identificador que parece una contraseña, secreto, clave de API, clave de acceso, token,
clave privada o credencial (y que no es un marcador como `changeme`, `${...}` o `***`), o un literal con un formato de
credencial conocido (claves de acceso de AWS, tokens de GitHub y Slack, claves de API de Google, claves privadas PEM, JWT,
URL JDBC con `password=`). Un secreto en el código acaba en el control de versiones y en el artefacto. *Arreglo:* cargarlo
desde un gestor de secretos, una variable de entorno o una bóveda, y rotarlo si alguna vez llegó a un commit.

**JG-SEC-009, Ruta construida desde la entrada de una petición. MAJOR.** Un parámetro anotado con `@RequestParam`,
`@PathVariable`, `@RequestHeader`, `@QueryParam` o `@PathParam`, o `MultipartFile.getOriginalFilename()`, usado directamente
en `new File(...)`, `Paths.get`, `Path.of`, `Path.resolve`, `Files.*` o un flujo de fichero. Un `../` en la entrada lee o
sobrescribe ficheros fuera del directorio previsto. *Arreglo:* resolver contra un directorio base fijo y comprobar el
resultado, `Path p = base.resolve(name).normalize()` y rechazarlo salvo que `p.startsWith(base)`, rechazando antes `..`,
separadores y rutas absolutas; o mapear la entrada mediante una lista permitida de nombres conocidos.

**JG-SEC-010, Datos sensibles en los logs. MAJOR.** Una llamada a un logger (o `System.out/err.print*`) con un argumento cuyo
nombre parece una contraseña, secreto, token o clave. Los logs se copian, se indexan y se envían, y los secretos que
contienen se filtran. *Arreglo:* no registrarlo; registrar un valor enmascarado o prescindir del valor.

**JG-SEC-011, Seguridad web debilitada. MAJOR.** Añadido en la sesión: `csrf().disable()` de Spring Security en cualquiera de
sus formas, `.anyRequest().permitAll()` o `requestMatchers("/**").permitAll()`. *Arreglo:* mantener la protección CSRF o
acotar la excepción con `ignoringRequestMatchers(...)`; exigir autenticación por defecto y permitir solo los endpoints que
deben ser públicos.

**JG-SEC-012, CORS permisivo con credenciales. CRITICAL.** `allowedOrigins("*")`, `addAllowedOrigin("*")` o
`@CrossOrigin(origins="*")` junto con `allowCredentials(true)`. Cualquier sitio web podría entonces llamar a la API con las
cookies del usuario. *Arreglo:* enumerar los orígenes permitidos explícitamente.

**JG-SEC-013, LDAP/XPath/EL construidos por concatenación. MAJOR.** Una cadena concatenada pasada a un filtro de
`DirContext.search`, a `XPath.compile/evaluate`, a `ExpressionParser.parseExpression` o a `ScriptEngine.eval`. La misma
inyección que en SQL, en otros lenguajes (una inyección SpEL puede ejecutar código). *Arreglo:* mantener la expresión
constante y pasar el valor por separado: LDAP `search(name, "(uid={0})", new Object[]{value}, controls)`, variables XPath
mediante un `XPathVariableResolver`, variables SpEL en el `EvaluationContext` (o un `SimpleEvaluationContext`), `Bindings`
en los scripts.

**JG-SEC-014, Redirección abierta. MAJOR.** `"redirect:" + <parámetro de la petición>` o
`response.sendRedirect(<parámetro de la petición>)`. Los atacantes usan tu dominio para enviar a las víctimas a un sitio de
phishing. *Arreglo:* validar el destino contra una lista permitida de rutas conocidas.

**JG-SEC-015, Endpoint sin autorización cuando sus hermanos la tienen. MAJOR.** En un controlador con al menos tres
manejadores y sin autorización a nivel de clase, un manejador recién añadido es el único sin `@PreAuthorize`,
`@PostAuthorize`, `@Secured`, `@RolesAllowed`, `@PermitAll` o `@DenyAll`. La causa probable es un endpoint que el agente
olvidó proteger. *Arreglo:* añadir la autorización que necesita el endpoint, o `@PermitAll` si debe ser público. Solo en
proyectos Spring.

## 3. Rendimiento (JG-PERF)

"En un bucle" significa dentro de `for`, `for` mejorado, `while`, `do`, o una lambda pasada a `forEach` o a un `map`/`filter`
de un stream. Todas se aplican al código `main` y se ejecutan en la puerta post-edit.

**JG-PERF-001, Concatenación de cadenas en un bucle. MAJOR.** `s += x` o `s = s + x` donde `s` es un `String` declarado fuera
del bucle. Cada pasada copia la cadena entera, así que el coste crece de forma cuadrática. *Arreglo:* un `StringBuilder`
antes del bucle, añadir dentro y `toString()` una sola vez después.

**JG-PERF-002, Expresión regular compilada en un bucle. MAJOR.** `Pattern.compile`, `String.matches`, `replaceAll`,
`replaceFirst` o `split` con una expresión regular real dentro de un bucle: el patrón se compila en cada pasada. *Arreglo:*
compilar el `Pattern` una vez antes del bucle y reutilizar su `Matcher`.

**JG-PERF-003, Objeto costoso creado repetidamente. MAJOR.** En un bucle, o en un método de un bean de Spring llamado en cada
petición: `new ObjectMapper()`, `new XmlMapper()`, `new Gson()`, `JAXBContext.newInstance`, `new RestTemplate()`, builders de
`WebClient` y `HttpClient`; solo en bucles, `DateTimeFormatter.ofPattern`, `new SimpleDateFormat`,
`DocumentBuilderFactory.newInstance`. Son caros de construir y seguros de compartir. *Arreglo:* crearlo una vez (un campo, o
antes del bucle) y reutilizarlo.

**JG-PERF-004, E/S de flujos sin búfer. MAJOR.** `FileInputStream`, `FileReader`, `FileOutputStream` o `FileWriter` sin
envolver en una clase `Buffered*` cuyo `read()` o `write(int)` se llama en un bucle: una llamada al sistema por byte o
carácter. *Arreglo:* envolverlo en una clase con búfer o leer en un array.

**JG-PERF-005, Búsqueda lineal en un bucle. INFO.** `contains`, `indexOf` o `remove(Object)` sobre una `List` dentro de un
bucle sobre otra colección. *Arreglo:* un `HashSet` o `HashMap`, o indexar la colección antes del bucle.

**JG-PERF-006, Eliminación de la cabeza de un ArrayList en un bucle. INFO.** `list.remove(0)` sobre un `ArrayList` en un bucle
desplaza todos los elementos cada vez. *Arreglo:* un `ArrayDeque` y `removeFirst()`.

**JG-PERF-007, Acumulador con boxing en un bucle. INFO.** Una variable local `Long`, `Integer` o `Double` actualizada con `+=`
o `++` en un bucle crea un objeto por pasada. *Arreglo:* usar el tipo primitivo.

**JG-PERF-008, Espera activa. MAJOR.** Un `while` cuyo cuerpo solo duerme y comprueba un indicador, o consulta una colección o
un campo con `Thread.sleep`. Gasta CPU y añade latencia. *Arreglo:* `wait()` y `notifyAll()`, una primitiva de
`java.util.concurrent` (`CountDownLatch`, `Condition`) o una llamada bloqueante.

**JG-PERF-009, Fichero o flujo completo materializado. INFO.** `Files.readAllBytes/readAllLines/readString` sobre una ruta
dentro de un bucle, o un cuerpo HTTP leído entero en un `String` dentro de un bucle. *Arreglo:* leer una sola vez fuera del
bucle, o procesarlo como flujo.

## 4. Fiabilidad (JG-REL)

Todas se aplican al código `main`, salvo que se indique, y se ejecutan en la puerta post-edit.

**JG-REL-001, Excepción tragada. MAJOR (CRITICAL para `Exception`, `Throwable`, `RuntimeException`).** Un `catch` cuyo cuerpo
está vacío o solo tiene comentarios; o un catch de `Exception` o `Throwable` que ni relanza, ni registra la excepción, ni
devuelve ni asigna un valor de error. El fallo desaparece y el programa continúa en un estado desconocido. *Arreglo:*
relanzar, registrar con la excepción adjunta, o devolver un valor alternativo explícito.

**JG-REL-002, Captura de Throwable o Error. MAJOR.** `catch (Throwable | Error | OutOfMemoryError | StackOverflowError)` sin
relanzar. Los errores a nivel de JVM suelen significar que el programa no puede continuar con seguridad. *Arreglo:* capturar
un tipo más específico, o relanzar tras gestionarlo.

**JG-REL-003, Recurso sin cerrar. CRITICAL (código main y de test).** Una variable local `AutoCloseable` creada con `new` o
con una llamada de JDBC, `Files.new*`, `Files.lines/list/walk/find`, `Scanner` o `ZipFile`, no declarada en
try-with-resources, no cerrada en `finally`, no devuelta, no guardada en un campo y no entregada a otro recurso cerrable
gestionado. Las conexiones y los manejadores de fichero que se fugan agotan el pool o el sistema operativo bajo carga.
*Arreglo:* try-with-resources, o cerrarlo en `finally`.

**JG-REL-004, Causa perdida al relanzar. MAJOR.** En `catch (X e)`, `throw new Y(...)` cuyos argumentos no incluyen `e` y sin
`initCause(e)` ni `addSuppressed(e)`. Se pierde la traza original, lo que dificulta diagnosticar el incidente. *Arreglo:*
pasar la excepción capturada al constructor de la nueva.

**JG-REL-005, Optional.get sin comprobar. MAJOR.** `get()` sobre una variable `Optional` no protegida en el mismo método por
una comprobación `isPresent()` o `isEmpty()`. Lanza `NoSuchElementException` cuando está vacío. *Arreglo:* protegerlo, o usar
`orElseThrow()` u `orElse(...)` para explicitar el caso vacío.

**JG-REL-006, Null devuelto como Optional o colección. CRITICAL.** `return null;` en un método declarado para devolver
`Optional`, una `List`, `Set`, `Map` o `Collection`, o un `Stream`. Los llamadores que no lo esperan reciben una
`NullPointerException`. *Arreglo:* devolver `Optional.empty()`, una colección vacía o `Stream.empty()`.

**JG-REL-007, Cadena comparada con ==. CRITICAL (código main y de test).** `==` o `!=` entre dos operandos de tipo `String`,
ninguno `null`. Compara identidad, así que funciona en un test y falla con otros datos. *Arreglo:* `.equals(...)`, o
`Objects.equals(a, b)` si alguno puede ser nulo.

**JG-REL-008, Mal uso de BigDecimal. MAJOR.** `new BigDecimal(double)`; `BigDecimal.equals`, que depende de la escala;
`divide(x)` sin `MathContext` ni `RoundingMode`, que lanza una excepción con resultados no terminantes. *Arreglo:*
`BigDecimal.valueOf(double)` o un literal `String`; `compareTo(...) == 0`; `divide(divisor, RoundingMode.HALF_UP)`.

**JG-REL-009, Coma flotante para dinero. MAJOR.** Un campo, parámetro o variable local de tipo `double`, `float`, `Double` o
`Float` con un nombre como importe, precio, saldo, coste, total, comisión, impuesto, salario, pago o dinero. La coma flotante
no puede representar con exactitud la mayoría de las fracciones decimales, así que el error es silencioso y se acumula.
*Arreglo:* `BigDecimal`, o un entero de coma fija como céntimos en un `long`.

**JG-REL-010, Estado de interrupción perdido. MAJOR.** `catch (InterruptedException e)` que ni relanza ni llama a
`Thread.currentThread().interrupt()`. Se traga la petición de cancelación del hilo. *Arreglo:* restaurar el indicador antes
de volver, o relanzar.

**JG-REL-011, Control de flujo en finally. CRITICAL (código main y de test).** `return`, `throw`, `break` o `continue`
dentro de `finally`: descarta en silencio cualquier excepción que el `try` estuviera propagando. *Arreglo:* moverlo después
de la sentencia `try`; `finally` solo debe liberar recursos, y try-with-resources lo hace conservando la excepción original.

**JG-REL-012, System.exit fuera de main. MAJOR.** `System.exit` o `Runtime.halt` fuera de un `public static void main`. Una
biblioteca o un servicio que mata la JVM se lleva todo lo demás por delante. *Arreglo:* lanzar una excepción o devolver un
resultado de error y dejar que decida el punto de entrada.

**JG-REL-013, equals sin hashCode. MAJOR.** Una clase sobrescribe `equals` sin `hashCode`, o al revés. Rompe `HashMap` y
`HashSet`. Lo informan Error Prone y PMD bajo este id.

**JG-REL-014, Estado estático mutable expuesto. MAJOR.** Un campo `public static` que no es `final`, o un array o colección
mutable `public static final`. Cualquier código puede cambiarlo, sin sincronización. *Arreglo:* hacerlo privado con acceso
controlado, o exponer una vista inmodificable o una copia defensiva.

**JG-REL-015, `toMap` sin función de combinación. INFO.** `Collectors.toMap(key, value)`: una clave duplicada en datos reales
lanza `IllegalStateException`. No se informa de los mapas construidos en inicializadores estáticos ni desde `values()` de un
enum, ni de las claves que son identificadores. *Arreglo:* añadir una función de combinación, `(first, second) -> first`, o
usar `groupingBy` si se esperan duplicados.

**JG-REL-016, Unboxing de un valor de mapa posiblemente ausente. INFO.** `map.get(key)` asignado a un primitivo, devuelto desde
un método que devuelve un primitivo o usado en una operación aritmética o una comparación, cuando el método nunca comprueba
`containsKey`. Una clave ausente se convierte en una `NullPointerException`. *Arreglo:* `getOrDefault(key, fallback)`, o
comprobar antes `containsKey` o el resultado nulo.

**JG-REL-017, Primer elemento del resultado de una consulta. MAJOR.** `get(0)`, `getFirst()` o `iterator().next()` sobre el
resultado de una llamada a un repositorio, DAO, mapper, `JdbcTemplate` o `EntityManager`, en un método que nunca comprueba
que el resultado no esté vacío. Un resultado vacío se convierte en una `IndexOutOfBoundsException`. *Arreglo:* comprobar
`isEmpty()` antes, o leerlo como un `Optional` (`list.stream().findFirst()`, o un método `findFirstBy...`) y después
`orElseThrow(...)` u `orElse(...)`.

**JG-REL-018, Excepción registrada sin la excepción. MAJOR.** En un `catch (X e)`, una llamada al logger de nivel error usa
`e.getMessage()` o `e.toString()` pero nunca pasa `e`, así que se pierde la traza. *Arreglo:* pasar la excepción como último
argumento, `log.error("mensaje", e)`.

**JG-REL-019, Los marcadores del log no coinciden. MAJOR.** Una llamada al logger cuyo mensaje literal tiene un número de `{}`
distinto del de argumentos (una excepción final no necesita marcador). Los valores que faltan se imprimen como `{}`; los
sobrantes se descartan. *Arreglo:* hacer que el número de `{}` sea igual al de argumentos.

## 5. Concurrencia (JG-CONC)

Un *bean singleton* es una clase anotada con `@Service`, `@Component`, `@Repository`, `@Controller`, `@RestController`,
`@Configuration`, `@Singleton` o `@ApplicationScoped`, sin un `@Scope` que no sea singleton. Una única instancia atiende todas
las peticiones a la vez, así que sus campos son estado compartido. Todas se aplican al código `main` y se ejecutan en la
puerta post-edit.

**JG-CONC-001, Formateador compartido no seguro entre hilos. CRITICAL.** Un campo `static`, o un campo de instancia de un bean
singleton, de tipo `SimpleDateFormat`, `DateFormat`, `DecimalFormat` o `MessageFormat`. No son seguros entre hilos y
producen fechas y números erróneos bajo concurrencia. *Arreglo:* un `ThreadLocal`, el `DateTimeFormatter` inmutable, o una
instancia por uso.

**JG-CONC-002, Estado mutable en un bean singleton. MAJOR.** Un campo de instancia no final de un bean singleton (no
inyectado, no asignado solo en el constructor) asignado en un método normal, o un campo `final` que contiene un `HashMap`,
`ArrayList`, `HashSet`, `LinkedList` o `TreeMap` que se modifica en un método normal. Las peticiones se pisan los datos
unas a otras. *Arreglo:* hacerlo inmutable y asignado en el constructor, usar un contenedor concurrente (`AtomicReference`,
una colección concurrente) o sincronizar.

**JG-CONC-003, Bloqueo de doble comprobación sin volatile. CRITICAL.** El patrón clásico de inicialización perezosa sobre un
campo que no es `volatile`: otro hilo puede ver un objeto a medio construir. *Arreglo:* declarar el campo `volatile`, o usar
una clase contenedora.

**JG-CONC-004, Objeto de bloqueo inadecuado. MAJOR.** `synchronized` sobre un literal `String`, un primitivo con boxing,
`getClass()` o un campo no final. Código no relacionado puede compartir el mismo bloqueo, o el objeto de bloqueo puede
cambiar. *Arreglo:* un `private final Object` dedicado a ese fin, o un bloqueo de `java.util.concurrent`.

**JG-CONC-005, Comprobar y luego actuar sobre un mapa concurrente. MAJOR.** `containsKey` o `get == null` seguido de `put`
sobre la misma clave de un `ConcurrentMap` en el mismo bloque. El mapa es seguro entre hilos, pero los dos pasos no son
atómicos. *Arreglo:* `computeIfAbsent` o `putIfAbsent`.

**JG-CONC-006, Hilos o ejecutores sin gestionar. MAJOR.** `new Thread(...).start()` o `Executors.new*` dentro de un método de
un bean singleton, o un ejecutor creado en un método y nunca cerrado en él. Los hilos se acumulan con cada llamada.
*Arreglo:* inyectar un bean `ExecutorService` o `TaskExecutor` compartido y gestionado por el contenedor, o cerrarlo.

**JG-CONC-007, Mal uso de wait/notify. MAJOR.** `wait()` fuera de un bucle `while` (despertares espurios), o `notify()` en vez
de `notifyAll()`. *Arreglo:* `while (!condition) { lock.wait(); }` y `notifyAll()`, o una primitiva de
`java.util.concurrent`.

**JG-CONC-008, Efectos secundarios en un stream paralelo. CRITICAL.** `parallelStream()` o `.parallel()` seguido de
`forEach`, `map` o `peek` cuya lambda añade o modifica una colección no concurrente capturada desde fuera. Actualizaciones
perdidas y colecciones corruptas. *Arreglo:* `collect()`, o una colección concurrente.

**JG-CONC-009, Actualización no atómica de un campo compartido. MAJOR.** `++`, `--` o `+=` sobre un campo de instancia no
volatile y no atómico de un bean singleton, o sobre un campo `static`, fuera de `synchronized`. Se pierden incrementos
concurrentes. *Arreglo:* `AtomicInteger` o `AtomicLong`, o sincronizar la actualización.

**JG-CONC-010, Bloqueo no liberado en todos los caminos. CRITICAL.** `lock()` o `lockInterruptibly()` sobre un `Lock`,
`ReentrantLock`, `ReadLock`, `WriteLock` o `StampedLock` no seguido de un `try` cuyo `finally` llame a `unlock()`. Una sola
excepción deja el bloqueo retenido y la aplicación se bloquea. No se informa cuando adquirir y liberar están en métodos
distintos. *Arreglo:* `lock.lock(); try { ... } finally { lock.unlock(); }` con el `try` justo después de la llamada a lock.

**JG-CONC-011, ThreadLocal sin eliminar. MAJOR.** Un `ThreadLocal` privado sobre el que la clase llama a `set(...)` y nunca a
`remove()`. En un pool de hilos el valor se filtra a peticiones posteriores y el hilo lo mantiene vivo. *Arreglo:* llamar a
`remove()` en un bloque `finally`, o en el `finally` de un filtro, cuando termine el trabajo que necesita el valor.

## 6. Resiliencia (JG-RES)

Las redes de seguridad que faltan detrás de los incidentes en producción. Todas se aplican al código `main` y se ejecutan en
la puerta post-edit.

**JG-RES-001, Cliente HTTP sin timeouts. CRITICAL.** Un cliente construido sin timeouts de conexión o de lectura:
`new RestTemplate()` sin fábrica de peticiones, `HttpClient` sin `connectTimeout`, `HttpRequest` sin `timeout`, `WebClient`
sin conector ni timeout, `url.openConnection()` sin `setConnectTimeout` y `setReadTimeout`, `HttpClients` de Apache sin una
configuración de petición. Una sola dependencia lenta retiene entonces todos los hilos del llamador y la caída se propaga.
*Arreglo:* establecer ambos timeouts: `RestTemplateBuilder.setConnectTimeout/setReadTimeout`,
`HttpClient.newBuilder().connectTimeout(Duration)` más un timeout en cada petición, un `ReactorClientHttpConnector` con
`responseTimeout`, o `RequestConfig` para Apache.

**JG-RES-002, Espera sin límite. MAJOR.** `get()` o `join()` sobre un future o un hilo, `await()` sobre un latch, barrera o
phaser, `acquire()` sobre un `Semaphore`, `take()` sobre una `BlockingQueue`, sin timeout, fuera de `main`. No se informa de
un future que el propio método creó, ni de `get()` bajo `if (f.isDone())`. *Arreglo:* usar la sobrecarga con timeout y
gestionar el timeout.

**JG-RES-003, Llamada remota dentro de una transacción. MAJOR.** Solo Spring. Una llamada sobre una variable `RestTemplate`,
`WebClient`, `HttpClient`, `KafkaTemplate`, `RabbitTemplate`, `JmsTemplate`, plantilla de SQS/SNS o cualquier tipo `*Client`,
dentro de un método o clase `@Transactional`. La conexión a la base de datos queda abierta mientras se espera a la red.
*Arreglo:* hacer la llamada remota antes o después del método transaccional, o publicar un evento o un registro outbox y
llamar desde fuera.

**JG-RES-004, Llamada remota en un bucle. MAJOR.** Los mismos receptores llamados en el cuerpo de un bucle o en una lambda de
iteración de un stream, fuera de `main`: un viaje de red por elemento. No se informa del `KafkaTemplate.send` asíncrono.
*Arreglo:* un endpoint por lotes, una sola petición con lo que necesita el bucle, o llamadas concurrentes con un pool
acotado.

**JG-RES-005, Pool de hilos o cola sin límite. MAJOR (INFO para pools fijos y de un solo hilo).**
`Executors.newCachedThreadPool()`; un `ThreadPoolExecutor` con una `LinkedBlockingQueue`, `LinkedBlockingDeque` o
`PriorityBlockingQueue` sin capacidad; INFO para `newFixedThreadPool` y `newSingleThreadExecutor` (hilos acotados, cola sin
límite). Bajo carga la aplicación se queda sin memoria o sin hilos. *Arreglo:* un `ThreadPoolExecutor` con tamaño máximo, una
cola acotada y una política de rechazo.

**JG-RES-006, Trabajo bloqueante en el pool común. MAJOR.** `CompletableFuture.supplyAsync` o `runAsync` sin ejecutor cuya
lambda llama a acceso a datos, a un cliente remoto, a `Thread.sleep` o a lecturas de ficheros. Deja sin hilos al
`ForkJoinPool` compartido que usan los streams paralelos y el resto de las tareas asíncronas. *Arreglo:* pasar un ejecutor
propio y acotado como segundo argumento.

## 7. Spring y JPA (JG-SPR)

Activas cuando se detecta Spring o JPA en el proyecto. Todas se aplican al código `main`.

**JG-SPR-001, La anotación de proxy no tiene efecto. CRITICAL.** `@Transactional`, `@Async`, `@Cacheable`, `@CacheEvict` o
`@Retryable` sobre un método `private`, `final` o `static`. Spring no puede crear un proxy para él, así que no hay
transacción, ni caché, ni reintento, y nada lo avisa. *Arreglo:* hacer el método público, no final y no estático y llamarlo
desde otro bean, o moverlo a un `@Service` aparte que inyecten los llamadores.

**JG-SPR-002, La autoinvocación se salta el proxy. MAJOR.** Un método llama, sin calificar o mediante `this.`, a otro método
de la misma clase anotado con `@Transactional`, `@Async`, `@Cacheable` o `@Retryable`, y el llamador no tiene la anotación.
La llamada se salta el proxy, así que la anotación se ignora. *Arreglo:* mover el método llamado a un `@Service` aparte y
llamarlo a través de la referencia inyectada.

**JG-SPR-003, Consulta dentro de un bucle (N+1). MAJOR.** En un bucle, una llamada sobre un `*Repository`, `*Dao`, `*Mapper`,
`JdbcTemplate`, `NamedParameterJdbcTemplate`, `EntityManager` o `Session`; o (INFO) un getter de un campo `@OneToMany` o
`@ManyToMany` de una entidad en un bucle. Una consulta por elemento es invisible con pocas filas y tumba la base de datos
con muchas. *Arreglo:* traer lo que necesita el bucle en una sola consulta antes de él (`findAllById`, un join fetch, un
entity graph, un lote).

**JG-SPR-004, Asociación a muchos con carga EAGER. MAJOR.** `@OneToMany` o `@ManyToMany` con `fetch = FetchType.EAGER`:
cada carga de la entidad carga también la colección entera. *Arreglo:* mantener la carga perezosa por defecto y traer la
colección explícitamente (join fetch, entity graph) donde se necesite.

**JG-SPR-005, Cargar todo y filtrar en memoria. MAJOR.** `findAll()` sin `Pageable`, `Specification` ni `Example`, seguido de
un `filter` de stream o un bucle con `if` en el mismo método. *Arreglo:* poner la condición en la consulta: una consulta
derivada, `@Query`, una `Specification` o un `Pageable`.

**JG-SPR-006, Excepción tragada en una transacción. MAJOR.** En un método o clase `@Transactional`, un `catch` que ni
relanza ni llama a `setRollbackOnly()`: la transacción se confirma aunque algo haya fallado. *Arreglo:* relanzar, o
`TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`.

**JG-SPR-007, @Async con un retorno que no es un future. MAJOR.** Un método `@Async` que devuelve algo distinto de `void`,
`Future`, `CompletableFuture` o `ListenableFuture`: el llamador recibe `null`. *Arreglo:* devolver `CompletableFuture<T>`, o
`void`.

**JG-SPR-008, Trampas de igualdad en entidades. MAJOR.** Una `@Entity` con `@Data` o `@EqualsAndHashCode` de Lombok sin
`onlyExplicitlyIncluded`, o `equals`/`hashCode` basados solo en un id `@GeneratedValue` sin tratar el nulo. Las entidades
se comparan mal antes de guardarse o arrastran colecciones perezosas al `hashCode`. *Arreglo:* `@Getter`/`@Setter` y un
`equals`/`hashCode` explícito sobre una clave de negocio, o tratar un id sin asignar como "no igual".

**JG-SPR-009, Llamada bloqueante en una cadena reactiva. MAJOR.** `.block()`, `.blockFirst()`, `.blockLast()`, `Thread.sleep`
o llamadas JDBC y de repositorio no reactivas dentro de una lambda de un operador de `Mono` o `Flux`. Bloquea los pocos
hilos del bucle de eventos. *Arreglo:* el equivalente reactivo, o mover el trabajo bloqueante a
`Schedulers.boundedElastic()`.

**JG-SPR-010, Entidad expuesta directamente. INFO.** Un parámetro `@RequestBody` o un tipo de retorno de controlador que es
una `@Entity`: los clientes pueden establecer campos que no deberían (asignación masiva), y se serializan asociaciones
perezosas o columnas privadas. *Arreglo:* enlazar y devolver un DTO y mapearlo.

**JG-SPR-011, Excepción comprobada sin rollback. MAJOR.** Un método público o protegido bajo `@Transactional` cuyo `throws`
nombra una excepción comprobada no cubierta por `rollbackFor`: Spring confirma por defecto ante excepciones comprobadas. No
se informa de las transacciones de solo lectura. *Arreglo:* añadir `rollbackFor = X.class` (`rollbackOn` para
`jakarta.transaction`), o envolverla en una excepción no comprobada.

**JG-SPR-012, Escritura en una transacción de solo lectura. MAJOR.** Bajo `@Transactional(readOnly = true)`, un `save*`,
`delete*`, `persist`, `merge`, `remove`, `update*`, `insert*` o `batchUpdate` sobre un repositorio, `EntityManager`,
`Session` o `JdbcTemplate`. Según el proveedor, la escritura se pierde o falla. *Arreglo:* quitar `readOnly = true`, o mover
la escritura a un método con una transacción de lectura y escritura.

**JG-SPR-013, API `javax` en un proyecto Jakarta. CRITICAL.** Activa solo cuando el build es Jakarta EE (Spring Boot
3+, Spring Framework 6+, Hibernate ORM 6+ o una API en el espacio de nombres jakarta). Los imports de `javax.persistence`,
`javax.validation`, `javax.servlet`, `javax.ws.rs` o `javax.transaction.Transactional` compilan contra una dependencia
sobrante y fallan en ejecución. No se informa de `javax.annotation.PostConstruct` ni `PreDestroy`, Spring 6 aún los respeta.
*Arreglo:* usar el equivalente `jakarta.*` y quitar la dependencia `javax` que permite que compile.

**JG-SPR-014, Cuerpo de la petición sin validar. MAJOR.** Un parámetro `@RequestBody` sin `@Valid` ni `@Validated` cuyo tipo
está declarado en el módulo con restricciones de Bean Validation. Las restricciones nunca se comprueban. No se informa de los
manejadores PATCH. *Arreglo:* anotar el parámetro con `@Valid`.

**JG-SPR-015, Consulta derivada sobre una propiedad desconocida. BLOCKER (post-edit y fin de turno).** Un método de
un repositorio de Spring Data cuyo nombre (`findByNameAndAge`...) nombra una propiedad que no existe en la entidad, o declara
menos parámetros de los que consumen las condiciones. Spring Data falla al arrancar la aplicación. Un error habitual de los
agentes: se inventa el nombre de un campo. *Arreglo:* usar exactamente los nombres de atributo de la entidad, o escribir la
consulta con `@Query`; un parámetro por cada valor de condición, en orden (`Between` toma dos, `IsNull` y `True` ninguno).

**JG-SPR-016, JPQL sobre una entidad o atributo desconocido. CRITICAL (post-edit y fin de turno).** Un literal JPQL
(en `@Query` sin `nativeQuery`, `@NamedQuery`, `createQuery`) que nombra una entidad o una ruta `alias.atributo` que no se
resuelve, o un nombre de entidad a dos ediciones de una conocida. La consulta falla al crearse, al arrancar. *Arreglo:* abrir
la clase de la entidad y usar exactamente su nombre de entidad y de campos (distinguen mayúsculas de minúsculas y no son
nombres de tabla ni de columna); si la consulta es SQL, poner `nativeQuery = true`.

**JG-SPR-017, `findAll()` sin paginar devuelto al cliente. MAJOR.** Un manejador de controlador devuelve el resultado de un
`findAll()` sin argumentos. Con volumen de datos real la respuesta crece sin límite. No se informa de los repositorios de
tablas de referencia pequeñas (roles, países, monedas, estados). *Arreglo:* recibir un `Pageable` y devolver
`findAll(pageable)`, o acotar la consulta.

## 8. Integridad de los tests (JG-TST)

Estas reglas comparan los tests con su versión anterior a la sesión. Existen porque un agente que no consigue que un test
pase puede cambiar el test en vez del código. En la puerta post-edit bloquean desde CRITICAL; al final del turno,
desde MAJOR. Se aplican al código de test salvo que se indique.

**JG-TST-001, Método de test eliminado. CRITICAL.** Un método `@Test`, `@ParameterizedTest`, `@RepeatedTest` o `@TestFactory`
que existía ha desaparecido (no se informa de un renombrado con cuerpo idéntico). *Arreglo:* restaurarlo; si el test está
obsoleto, decírselo al usuario en vez de borrarlo.

**JG-TST-002, Test desactivado. BLOCKER.** `@Disabled`, `@Ignore`, `@DisabledIf*` o `@EnabledIf*` añadido a un método o
clase de test. *Arreglo:* quitarlo y arreglar la causa.

**JG-TST-003, Aserciones eliminadas. CRITICAL.** Ha disminuido el número de llamadas de aserción (JUnit, AssertJ, Hamcrest,
`verify` de Mockito, `expect`, `fail`) en un método de test.

**JG-TST-004, Valor esperado cambiado. MAJOR.** En una aserción cuya expresión "real" no ha cambiado, ha cambiado el valor
esperado. Se informa para que el agente tenga que justificar la nueva expectativa: es la forma de "arreglar" un test que
falla sin arreglar el código.

**JG-TST-005, Aserción trivial. CRITICAL.** Se ha añadido `assertTrue(true)`, `assertFalse(false)`, `assertEquals(x, x)`,
`assertNotNull(<literal o new>)` o `assertThat(true).isTrue()`. El test no puede fallar nunca. *Arreglo:* afirmar un
resultado concreto del código bajo prueba (un valor devuelto, un cambio de estado, una interacción); no limitarse a borrarla.

**JG-TST-006, Cuerpo de test vaciado. CRITICAL.** Un test que tenía sentencias y ahora no tiene ninguna, o solo comentarios.

**JG-TST-007, Fallo de aserción tragado. CRITICAL.** Un `try` que contiene aserciones con un `catch` de `AssertionError`,
`Throwable` o `Error` que no relanza. *Arreglo:* quitar el `try`/`catch`, o capturar solo la excepción concreta que espera el
test.

**JG-TST-008, Fichero de test borrado. CRITICAL.** Un fichero de código de test borrado en la sesión.

**JG-TST-009, Tests omitidos por la configuración del build. BLOCKER.** Añadido o cambiado en `pom.xml` o en ficheros
de Gradle: `skipTests`, `maven.test.skip`, `testFailureIgnore`, un `skip` en surefire o failsafe, nuevos `<excludes>`,
`test { enabled = false }`, `ignoreFailures = true`, un `exclude` en el filtro de tests u `onlyIf { false }`. Los tests deben
ejecutarse y deben hacer fallar el build cuando fallan.

**JG-TST-010, Suposición añadida. MAJOR.** `Assumptions.assume*` o `Assume.assume*` añadido a un test que no lo tenía: el test
se omite en silencio cuando la suposición es falsa.

**JG-TST-011, Timeout aumentado en exceso. INFO.** Un valor de `@Timeout` o `assertTimeout` multiplicado por diez o más.

**JG-TST-012, Test de excepción esperada neutralizado. MAJOR.** `assertThrows` eliminado de un test que lo tenía, o `fail(...)`
eliminado tras la llamada bajo prueba.

**JG-TST-013, El código de producción detecta los tests. CRITICAL (código main).** Código main que importa `org.junit` u
`org.mockito`, los busca con `Class.forName`, inspecciona la traza de pila en busca de "Test", o lee un interruptor del tipo
`test-mode`, introducido en la sesión. El comportamiento en producción difiere entonces de lo que ejercitan los tests.
*Arreglo:* quitar el caso especial; si el comportamiento debe diferir en los tests, inyectarlo (un parámetro del constructor o
una propiedad de configuración) y pasar un doble de prueba desde el test.

**JG-TST-014, Clase bajo prueba simulada con mock. MAJOR.** `@Mock`, `mock(X.class)` o `@MockBean X` donde `X` es la clase de
la que toma nombre el test (`XTest`). El test comprueba entonces el mock, no el código. *Arreglo:* usar un `X` real y simular
solo sus colaboradores: `@InjectMocks`, o `new X(mock(Collaborator.class))`.

## 9. Restos de IA (JG-AI)

Restos que los agentes suelen dejar. Todas se refieren a código que añadió el agente.

**JG-AI-001, Salida por consola en código de producción. MAJOR.** `print*` de `System.out` o `System.err` añadido fuera de los
métodos `main` y fuera de las clases de un paquete `cli`. Sin nivel, sin correlación y sin forma de redirigirlo o apagarlo.
*Arreglo:* registrar mediante el logger SLF4J de la clase. Si el proyecto no tiene logger, se le dice al agente que no añada
una biblioteca por su cuenta: te pregunta si añadir SLF4J o si la salida por consola es aceptable para este programa, en cuyo
caso desactivas la regla tú.

**JG-AI-002, printStackTrace. MAJOR.** `e.printStackTrace()` añadido. *Arreglo:* registrar la excepción,
`log.error("...", e)`.

**JG-AI-003, Esqueleto sin implementar. CRITICAL.** Un método añadido cuyo cuerpo solo lanza `UnsupportedOperationException`
o `RuntimeException("not implemented")`, o solo devuelve `null` o un valor por defecto junto a un comentario TODO,
placeholder o stub. Un comentario del estilo `// TODO implement` se informa como MAJOR. El agente se rindió a medias.
*Arreglo:* implementar el método; si no está claro qué debe hacer, parar y preguntar al usuario; si se espera que lo
implementen las subclases, declararlo `abstract`.

**JG-AI-004, Código comentado añadido. INFO.** Tres o más líneas de comentario consecutivas añadidas que se leen como
sentencias Java. *Arreglo:* borrar el código muerto, el control de versiones guarda el historial; si es una explicación,
reescribirla como prosa.

**JG-AI-005, Literal de relleno. MAJOR.** Literales de cadena como `"TODO"`, `"FIXME"`, `"changeme"`, `"lorem ipsum..."`,
`"foo"`, `"bar"`, `"your-api-key"`, `"xxx"` o una URL de `example.com` usados como valor en código de producción. *Arreglo:*
leer el valor de la configuración con una clave con nombre; si se desconoce el valor real, parar y preguntar al usuario en
vez de inventarlo.

**JG-AI-006, Supresión amplia de avisos añadida. INFO.** `@SuppressWarnings("all")`, `"unchecked"` o `"rawtypes"` añadido a
nivel de clase. *Arreglo:* acotarlo al miembro o sentencia que lo necesita y a un aviso concreto.

**JG-AI-007, Código sin usar añadido. INFO.** Un método privado, campo o variable local añadido que nunca se usa (lo informan
`UnusedVariable` y `UnusedMethod` de Error Prone).

## 10. Dependencias (JG-DEP)

Cambios en los ficheros de build (`pom.xml`, Gradle).

**JG-DEP-001, Dependencia añadida. INFO (post-edit).** Ha aparecido una dependencia nueva. Los agentes añaden
bibliotecas con demasiada facilidad y a veces las coordenadas son erróneas. *Arreglo:* preferir el JDK o una biblioteca que el build ya declara, y quitarla si el cambio funciona sin ella; si de verdad hace falta una biblioteca nueva, parar y
decir al usuario cuál y por qué; nunca adivinar coordenadas ni versiones.

**JG-DEP-002, Vulnerabilidad conocida. Severidad según la puntuación CVSS (fin de turno).** Una dependencia con una
vulnerabilidad conocida en la base de datos OSV. Solo se envían las coordenadas a `api.osv.dev`; se puede desactivar en la
configuración. *Arreglo:* actualizar a una versión corregida.

**JG-DEP-003, Dependencia que no se puede resolver (posiblemente inventada). BLOCKER (fin de turno).** El build no puede descargar un artefacto. Los modelos inventan bibliotecas y versiones que no existen. Solo se informa cuando los
repositorios dieron un "no encontrado" real; sin conexión o un error de proxy es una nota de infraestructura, nunca un
bloqueo. *Arreglo:* copiar las coordenadas de otro módulo del build o de la documentación de la biblioteca,
preferir la versión que gestiona el padre o BOM, o usar una biblioteca que el build ya declara; si ninguna encaja,
preguntar al usuario.

**JG-DEP-004, Versión sin fijar o snapshot. MAJOR (post-edit).** Una versión `LATEST`, `RELEASE`, un rango o
`-SNAPSHOT`. Los builds dejan de ser reproducibles. *Arreglo:* fijar una versión publicada exacta.

**JG-DEP-005, Dependencia degradada. MAJOR (post-edit).** Una versión inferior a la anterior. Las degradaciones suelen
reintroducir errores y vulnerabilidades ya corregidos. *Arreglo:* mantener la versión más nueva salvo que la degradación sea
deliberada.

## 11. Compatibilidad de la API (JG-API)

Solo al fin de turno. Comparan el código con su versión anterior a la sesión y protegen a los clientes que no ves.

**JG-API-001, API pública cambiada. MAJOR.** Una firma pública de un fichero modificado cambió o desapareció. *Arreglo:*
mantener cada firma antigua junto a la nueva (obsoleta, delegando en ella), o actualizar todos los consumidores en el mismo
cambio. Cuando ningún módulo del build depende de ella, puede haber consumidores externos.

**JG-API-002, Endpoint HTTP eliminado o movido. MAJOR.** Módulos Spring: un endpoint (método y ruta combinada) de un
controlador modificado ya no existe; un `@RequestMapping` sin restricciones se restringió a algunos métodos; se eliminó un
tipo de medio `consumes` o `produces` declarado. No se informa de los movimientos a otro controlador modificado ni de un
manejador renombrado. *Arreglo:* mantener el mapeo antiguo (puede delegar en el nuevo) hasta que todos los clientes hayan
migrado, o hacer el cambio a propósito y avisar a los clientes.

**JG-API-003, El endpoint exige un parámetro nuevo. MAJOR.** El mismo endpoint, un `@RequestParam` o `@RequestHeader` que ahora
es obligatorio (sin `required = false`, sin `defaultValue`, que no sea `Optional`), o un `@RequestBody` obligatorio donde no
había ninguno. Los clientes existentes empiezan a fallar. *Arreglo:* hacerlo opcional para que los clientes existentes sigan
funcionando, o añadir un endpoint nuevo.

**JG-API-004, Campo JSON renombrado o eliminado. MAJOR.** Un tipo usado como cuerpo de petición o respuesta por un controlador
pierde una propiedad JSON: un componente de record, un campo público, un getter, un campo de Lombok; se respetan
`@JsonProperty`, `@JsonIgnore` y `@JsonIgnoreProperties`. *Arreglo:* mantener el nombre JSON con `@JsonProperty("old")` en el
miembro renombrado, o mantener la propiedad.

## 12. Migraciones de base de datos (JG-DB)

Una migración está **aplicada** cuando existía antes de la primera edición de la sesión y, en un repositorio git, está
confirmada. Una que existe solo en el directorio de trabajo no ha llegado a una base de datos compartida y se puede editar
libremente. Se admiten Flyway (migraciones SQL y Java) y Liquibase (XML, YAML, JSON, SQL con formato). Las comprobaciones
reflejan lo que validan Flyway y Liquibase por sí mismos al arrancar, y se verificaron con Flyway 10.10 y Liquibase 4.27.

**JG-DB-001, Migración aplicada modificada. BLOCKER.** Una migración SQL de Flyway aplicada cuyo checksum cambió (los finales
de línea y las líneas en blanco se aceptan, como hace Flyway; cualquier otra cosa, incluidos los comentarios y los espacios
finales, falla la validación al arrancar), o un change set de Liquibase aplicado (mismo id y autor) cuyos cambios difieren. En
producción la aplicación se niega entonces a arrancar, o el esquema se desvía. Los change sets `runOnChange` están permitidos.
*Arreglo:* restaurar el fichero exactamente como se confirmó y poner el cambio en una migración nueva con la versión
siguiente. Si la migración nunca se aplicó a una base de datos compartida, decírselo al usuario: solo él puede excluirla en
`javaguard.yml`.

**JG-DB-002, Migración renombrada o borrada. BLOCKER.** Una migración versionada de Flyway aplicada se borró o renombró; un
change set de Liquibase cuyo id o autor cambió (se ejecuta de nuevo). Borrar la última migración aplicada, o eliminar un
change set aplicado o un changelog entero, es MAJOR: Flyway y Liquibase arrancan, pero las bases de datos se desvían.
*Arreglo:* restaurarla; para deshacer una migración, añadir una nueva que la revierta.

**JG-DB-003, Versión de migración duplicada. CRITICAL.** Una migración versionada nueva de Flyway cuya versión (`1`, `1.0` y
`1_0` son la misma) ya la usa otra migración de la misma ubicación. Flyway falla al arrancar. *Arreglo:* darle la siguiente
versión libre.

## 13. Configuración (JG-CFG)

**JG-CFG-001, Secreto en la configuración. BLOCKER.** En `application*.properties`, `.yml`, `.yaml` y `bootstrap*`: una clave
cuyo nombre parece un secreto con un literal de ocho o más caracteres que no es una referencia `${...}`, un marcador, una URL,
un número, una duración, un booleano o una ruta; o cualquier valor con un formato de credencial conocido, sea cual sea su
clave. No se informa de las claves que solo mencionan un secreto (`token-uri`, `api-key-header`, `*-validity-seconds`...);
en perfiles de tipo desarrollo (`dev`, `local`, `test`, `h2`...) la severidad es menor. Los ficheros de configuración se
suben al control de versiones y se empaquetan en el artefacto. *Arreglo:* sustituir el valor por una referencia como
`${NOMBRE_ENTORNO}` suministrada desde el entorno o un gestor de secretos; rotarlo si alguna vez llegó a un commit.

**JG-CFG-002, Clave de configuración nunca definida. MAJOR (fin de turno, solo aviso).** Spring Boot: un `${key}` sin valor
por defecto en `@Value`, o una propiedad obligatoria de una clase `@Validated @ConfigurationProperties`, cuya clave no define
ningún fichero de configuración del módulo. La aplicación no arranca salvo que el entorno la proporcione. Solo se informa de lo
que introdujo el turno. *Arreglo:* definir la clave en `application.yml` (por perfil si difiere), o dar al marcador un valor
por defecto (`${key:...}`) si es opcional.

**JG-CFG-003, Host fijo en el código. MAJOR.** Un literal de URL a `localhost`, una dirección de bucle local, `0.0.0.0` o una
dirección de red privada, o una URL JDBC que nombra un host, añadido en la sesión. Funciona en la máquina del desarrollador y
en ningún otro sitio. No se informa de los valores por defecto de anotaciones, las clases `@ConfigurationProperties` y
`@Configuration`, las bases de datos embebidas ni el código de test. *Arreglo:* leerlo de la configuración (`@Value("${...}")`
o una clase `@ConfigurationProperties`) y definirlo por entorno.

## 14. Arquitectura y supresiones

**JG-ARCH-001, Dependencia prohibida. MAJOR.** El `javaguard.yml` del proyecto puede declarar reglas de arquitectura
(`from: com.acme.domain..`, `mustNotDependOn: [com.acme.web..]`). Se informa de un import que cruza una frontera prohibida.
*Arreglo:* declarar una interfaz en un paquete que el fichero pueda usar, implementarla en el paquete prohibido y depender de
la interfaz; o mover el código a un paquete al que se le permita usarlo.

**JG-SUP-001, Supresión sin motivo. MAJOR.** `// javaguard:ignore <regla>` sin `-- <motivo>`. *Arreglo:* añadir `-- <motivo>`
explicando por qué el hallazgo es un falso positivo.

**JG-SUP-002, Supresión añadida por el agente. BLOCKER (cuando no está permitida).** Con `suppressions.allowAgentAdded: false`,
un `javaguard:ignore` que el agente añadió durante la sesión es en sí mismo un BLOCKER: es la forma más fácil que tiene un
agente de silenciar una regla. *Arreglo:* quitar la supresión y arreglar el hallazgo; si de verdad es un falso positivo, parar
y explicárselo al usuario, que puede añadir la supresión.

**JG-SUP-003, Configuración o hooks de JavaGuard modificados. BLOCKER.** En el primer hook de una sesión, JavaGuardCLI
registra el `javaguard.yml` del proyecto, `~/.javaguard/config.yml` y sus propias entradas de hooks en
`.claude/settings.json`, `.gemini/settings.json` y `.codex/hooks.json`. Durante el resto de la sesión lee la copia
registrada, así que una edición no cambia nada, y cualquier cambio en esos ficheros es un BLOCKER hasta que el agente los
restaura. Los demás ajustes de los ficheros del agente (permisos, tema) no se comparan. Un cambio que sí quieras a mitad de
sesión se acepta con `javaguard baseline reset` o iniciando una sesión nueva. *Arreglo:* restaurar el fichero exactamente;
reescribir el código en vez de cambiar JavaGuardCLI.

## 15. Otros motores

Además de sus propias reglas, JavaGuardCLI usa tres motores maduros, con una selección cuidada para que el ruido sea bajo.
El estilo, los nombres, el formato, el Javadoc, la complejidad y reglas similares están excluidos a propósito.

- **Error Prone** se ejecuta en la compilación en el propio proceso tras cada edición. Se activan todas las comprobaciones de
  nivel ERROR (CRITICAL), más un conjunto seleccionado de avisos (por ejemplo `BigDecimalEquals`, `BoxedPrimitiveEquality`,
  `FutureReturnValueIgnored`, `CatchAndPrintStackTrace`, `WaitNotInLoop`, `SynchronizeOnNonFinalField`). Los hallazgos son
  `EP:<Comprobación>`.
- **PMD 7** ejecuta un conjunto pequeño de reglas (bloques catch vacíos, `PreserveStackTrace`, `CloseResource`,
  `ReturnFromFinallyBlock`, `DoubleCheckedLocking`, criptografía insegura y unas pocas reglas de rendimiento). Los hallazgos
  son `PMD:<Regla>`.
- **SpotBugs con FindSecBugs** se ejecuta al final del turno sobre las clases modificadas: corrección, corrección
  multihilo y patrones de seguridad. El rango propio de SpotBugs fija la severidad (1 a 4 CRITICAL, 5 a 9 MAJOR, 10 a 14
  INFO). Los hallazgos son `SB:<Patrón>`.

Cuando un motor se solapa con una de las reglas anteriores, el hallazgo se informa una sola vez, bajo el id de JavaGuardCLI, y
el otro motor aparece en su detalle.

## Ajustar las reglas

Cada regla se puede ajustar en `javaguard.yml`. Desactivar, bajar o restringir una regla es una decisión tuya, no del agente:

```yaml
rules:
  JG-PERF-005: { enabled: false }                       # desactivada
  JG-AI-001: { severity: INFO, exclude: ["**/cli/**"] }  # bajarla, o saltarse algunas rutas
  JG-SEC-002: { severity: BLOCKER }                      # bloquear también justo tras la edición
  JG-PERF-002: { severity: BLOCKER }
```

`javaguard rules` muestra cada regla con su severidad efectiva y si está activada. Para la configuración completa (umbrales,
qué hallazgos bloquean, supresiones, notificaciones) consulta el manual de usuario, `usage_es.md`.
