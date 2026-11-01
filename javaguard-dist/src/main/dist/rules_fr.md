# Règles de JavaGuardCLI

JavaGuardCLI contrôle le code qu'un agent d'IA écrit, au moment où il l'écrit. Ce document explique chaque contrôle qu'il
effectue : ce qu'il détecte, pourquoi cela compte en production et ce qu'on demande à l'agent de faire.

Les règles sont conçues pour être **prudentes** : chacune ne se déclenche que sur un motif syntaxique précis, jamais sur un
« peut-être ». La contrepartie est qu'elles réduisent le risque sans remplacer la revue de code, les tests ni les tests de
charge.

Autres langues : [English](rules.md) · [Español](rules_es.md).

- [Comment les règles s'exécutent](#comment-les-règles-sexécutent)
- [1. Syntaxe et compilation](#1-syntaxe-et-compilation) - [2. Sécurité](#2-sécurité-jg-sec) - [3. Performance](#3-performance-jg-perf) -
  [4. Fiabilité](#4-fiabilité-jg-rel) - [5. Concurrence](#5-concurrence-jg-conc) - [6. Résilience](#6-résilience-jg-res) -
  [7. Spring et JPA](#7-spring-et-jpa-jg-spr) - [8. Intégrité des tests](#8-intégrité-des-tests-jg-tst) - [9. Résidus d'IA](#9-résidus-dia-jg-ai) -
  [10. Dépendances](#10-dépendances-jg-dep) - [11. Compatibilité de l'API](#11-compatibilité-de-lapi-jg-api) -
  [12. Migrations de base de données](#12-migrations-de-base-de-données-jg-db) - [13. Configuration](#13-configuration-jg-cfg) -
  [14. Architecture et suppressions](#14-architecture-et-suppressions) - [15. Autres moteurs](#15-autres-moteurs)
- [Régler les règles](#régler-les-règles)

## Comment les règles s'exécutent

JavaGuardCLI s'exécute comme un hook dans l'agent (Claude Code, Gemini CLI, Codex) à deux moments :

| Barrière | Quand | Ce qui s'exécute |
|---|---|---|
| **Après une édition** (post-edit, `F` dans ce document) | Après chaque fichier écrit par l'agent | Les règles locales au fichier, sur le fichier modifié, en environ une seconde |
| **Fin de tour** (end-of-turn, `E`) | Quand l'agent dit avoir terminé | La compilation, les tests concernés, l'analyse approfondie (SpotBugs/FindSecBugs, vulnérabilités des dépendances, comparaison de l'API et des endpoints, clés de configuration) et toute règle locale à un fichier qui ne lui a pas encore été appliquée |

Une règle marquée `F` s'exécute aussi en fin de tour sur les fichiers que la barrière post-edit n'a pas vus. Une règle
marquée `E` ne s'exécute qu'à ce moment-là, car elle a besoin de toute la compilation ou d'une comparaison avec la version
antérieure à la session.

### Gravité et ce qui bloque

Chaque constat a une gravité : **BLOCKER**, **CRITICAL**, **MAJOR** ou **INFO**. La gravité dit à quel point le problème est
sérieux ; la barrière décide si l'agent est arrêté :

| Barrière | Bloque l'agent | Tout le reste |
|---|---|---|
| Après une édition (post-edit) | Constats BLOCKER, source qui ne se parse pas (`JG-SYN-001`) et constats d'intégrité des tests (`JG-TST-*`) de CRITICAL ou plus | L'agent est prévenu et continue |
| Fin de tour | Erreurs de compilation, dépendance impossible à résoudre (`JG-DEP-003`), CRITICAL ou plus, constats d'intégrité des tests de MAJOR ou plus, et tests en échec | Un résumé d'une ligne pour vous |

Ainsi, une règle CRITICAL comme `JG-SEC-002` ne fait que prévenir juste après l'édition et bloque en fin de tour. Pour qu'une
règle bloque aux deux barrières, relevez sa gravité dans `javaguard.yml` (voir [Régler les règles](#régler-les-règles)).

### Ce que montre chaque entrée

Chaque règle indique sa **gravité**, sa **barrière** (`F` ou `E`) et, quand c'est utile, son **périmètre** (code `main`, code
de `test`, ou les deux). Quand une règle se limite à ce que l'agent a ajouté pendant la session, elle le dit : JavaGuardCLI
compare chaque fichier à sa version antérieure à la session et ne signale que ce que l'agent a introduit, pas les problèmes
déjà présents.

### Comment on demande à l'agent de corriger un constat

Chaque constat porte un remède. L'agent reçoit aussi une consigne générale : corriger la cause racine et réécrire le code pour
que le constat disparaisse en gardant le comportement demandé ; ne pas modifier la configuration ni les hooks de JavaGuard ;
ne pas affaiblir, désactiver ni supprimer de tests ; n'ajouter `// javaguard:ignore <règle> -- <raison>` que pour un vrai
faux positif ; et, si la seule façon de respecter une règle contredit ce que vous avez demandé, s'arrêter et vous
l'expliquer au lieu de réessayer. Désactiver une règle dans `javaguard.yml` est toujours votre décision, jamais celle de
l'agent.

---

## 1. Syntaxe et compilation

| Id | Gravité | Barrière |
|---|---|---|
| JG-SYN-001 | BLOCKER | F |
| JAVAC | CRITICAL | F prévient, E bloque |
| TEST | CRITICAL | E |

**JG-SYN-001, Le code source ne s'analyse pas.** Le fichier n'est pas du Java valide : JavaParser n'a pas pu le lire. Le
message donne le premier problème avec sa ligne et sa colonne. Rien d'autre ne peut être analysé ni compilé tant qu'il n'est
pas corrigé, donc il bloque immédiatement. *Correction :* réparer d'abord l'erreur de syntaxe.

**JAVAC, Erreur de compilation.** `javac` signale une erreur. Après une édition, JavaGuardCLI compile dans le processus et
prévient ; en fin de tour, c'est le build du projet lui-même qui compile et l'erreur bloque. Le constat porte les
`symbol`, `location`, `required` et `found` du compilateur, et une piste adaptée à l'erreur (pour « cannot find symbol » :
lire la classe qui le déclare plutôt que deviner). Un fichier qui commence par une marque d'ordre des octets UTF-8 est signalé
comme tel, car `javac` la rejette.

**TEST, Échec d'un test.** Un test JUnit a échoué ou est en erreur lors de l'exécution des tests concernés en fin de tour. Le
rapport montre le test et les valeurs attendue et obtenue. *Correction :* lire le test en échec et le code de production qu'il
appelle, et raisonner à partir de la préparation du test ; ne pas modifier le test pour qu'il passe.

## 2. Sécurité (JG-SEC)

Toutes s'appliquent au code `main` et s'exécutent à la barrière post-edit, sauf indication contraire.

**JG-SEC-001, SQL construit par concaténation. BLOCKER.** Une chaîne non constante construite avec `+`, `concat`,
`String.format`, `formatted` ou `StringBuilder.append` est passée à un appel JDBC ou qui exécute du SQL. C'est l'injection SQL
classique : tout fragment contrôlé par l'utilisateur réécrit la requête. *Correction :* utiliser une requête paramétrée
(marqueurs `?` ou API à paramètres nommés) et lier les valeurs ; les fragments fixés dans le code (listes de colonnes, noms de
tables issus de constantes) peuvent être concaténés. En fin de tour, SpotBugs/FindSecBugs donne un second avis sur
l'injection SQL ; il ne bloque que les flux dont il est sûr.

**JG-SEC-002, Commande construite à partir d'une entrée variable. CRITICAL.** `Runtime.exec(String)` avec une chaîne non
constante, ou `ProcessBuilder`/`exec` avec `"sh","-c"` ou `"cmd","/c"` suivi d'une valeur non constante. Une entrée qui
atteint un shell peut exécuter n'importe quelle commande. *Correction :* passer le programme et chaque argument comme éléments
distincts d'un tableau, valider l'entrée ou utiliser une liste autorisée, ou éviter complètement le shell.

**JG-SEC-003, Désérialisation non sûre. CRITICAL.** `ObjectInputStream.readObject/readUnshared` sans
`setObjectInputFilter` dans la même méthode ; `XMLDecoder.readObject` ; le typage par défaut de Jackson ou `Id.CLASS` ;
SnakeYAML construit sans constructeur sûr. Désérialiser des données non fiables peut instancier des classes arbitraires et
exécuter du code. *Correction :* définir un filtre d'entrée, utiliser un format de données au schéma explicite, un
`PolymorphicTypeValidator` avec liste autorisée, `SafeConstructor`, ou `Id.NAME` avec des sous-types enregistrés.

**JG-SEC-004, Analyseur XML ouvert à XXE. CRITICAL.** Un `DocumentBuilderFactory`, `SAXParserFactory`, `XMLInputFactory`,
`TransformerFactory`, `SchemaFactory` ou `XMLReader` est créé et utilisé sans désactiver les DTD ni les entités externes dans
la même méthode. Les entités externes permettent à un attaquant de lire des fichiers locaux ou d'atteindre des services
internes. *Correction :* désactiver les DTD avant l'analyse : `setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)`,
`XMLInputFactory.SUPPORT_DTD` à `false`, et `ACCESS_EXTERNAL_DTD`, `ACCESS_EXTERNAL_STYLESHEET` et `ACCESS_EXTERNAL_SCHEMA` à
`""`.

**JG-SEC-005, Cryptographie faible. CRITICAL.** `MessageDigest` avec MD5 ou SHA-1 (INFO quand le nom suggère une somme de
contrôle, un etag ou une clé de cache) ; `Cipher.getInstance` avec une transformation faible ou par défaut ; un IV codé en
dur ou réutilisé ; `KeyPairGenerator` sous 2048 bits. *Correction :* une transformation forte et explicite comme
`AES/GCM/NoPadding`, un IV aléatoire neuf à chaque chiffrement, SHA-256 ou plus (les mots de passe demandent un hachage lent
comme bcrypt ou Argon2) et des clés RSA d'au moins 2048 bits. `HardCodedCryptoKey` et `InsecureCryptoIv` de PMD sont
signalés sous cet identifiant.

**JG-SEC-006, Aléatoire prévisible pour des secrets. MAJOR.** `new Random()`, `Math.random()` ou `ThreadLocalRandom` dont le
résultat est affecté à, ou renvoyé par, un identifiant qui ressemble à un jeton, mot de passe, secret, sel, nonce, OTP,
session ou clé. Ces générateurs peuvent être prédits à partir de quelques sorties. *Correction :*
`java.security.SecureRandom`.

**JG-SEC-007, Vérification TLS désactivée. BLOCKER.** Un `X509TrustManager` dont `checkServerTrusted` est vide ; un
`HostnameVerifier` ou une lambda qui renvoie toujours `true` ; `NoopHostnameVerifier`, `TrustAllStrategy`,
`TrustSelfSignedStrategy`, `ALLOW_ALL_HOSTNAME_VERIFIER`, `InsecureTrustManagerFactory`. Avec eux la connexion est chiffrée,
mais n'importe qui peut se faire passer pour le serveur. *Correction :* supprimer le vérificateur ou le gestionnaire de
confiance personnalisé et garder les valeurs par défaut de la plateforme ; pour une AC privée, ajouter son certificat à un
magasin de confiance.

**JG-SEC-008, Secret codé en dur. BLOCKER (MAJOR dans le code de test).** Sur les lignes ajoutées uniquement : un littéral
chaîne de huit caractères ou plus affecté à un identifiant qui ressemble à un mot de passe, secret, clé d'API, clé d'accès,
jeton, clé privée ou identifiant d'accès (et qui n'est pas un marqueur comme `changeme`, `${...}` ou `***`), ou un littéral
au format d'identifiant connu (clés d'accès AWS, jetons GitHub et Slack, clés d'API Google, clés privées PEM, JWT, URL JDBC
avec `password=`). Un secret dans le code finit dans le gestionnaire de versions et dans l'artefact. *Correction :* le charger
depuis un gestionnaire de secrets, une variable d'environnement ou un coffre, et le renouveler s'il a déjà été validé dans git.

**JG-SEC-009, Chemin construit à partir d'une entrée de requête. MAJOR.** Un paramètre annoté `@RequestParam`,
`@PathVariable`, `@RequestHeader`, `@QueryParam` ou `@PathParam`, ou `MultipartFile.getOriginalFilename()`, utilisé
directement dans `new File(...)`, `Paths.get`, `Path.of`, `Path.resolve`, `Files.*` ou un flux de fichier. Un `../` dans
l'entrée lit ou écrase des fichiers hors du répertoire prévu. *Correction :* résoudre par rapport à un répertoire de base
fixe et vérifier le résultat, `Path p = base.resolve(name).normalize()` et le rejeter sauf si `p.startsWith(base)`, en
rejetant d'abord `..`, les séparateurs et les chemins absolus ; ou faire correspondre l'entrée à une liste autorisée de noms
connus.

**JG-SEC-010, Données sensibles dans les logs. MAJOR.** Un appel à un logger (ou `System.out/err.print*`) avec un argument
dont le nom ressemble à un mot de passe, secret, jeton ou clé. Les logs sont copiés, indexés et expédiés, et les secrets
qu'ils contiennent fuient. *Correction :* ne pas le journaliser ; journaliser un marqueur masqué ou omettre la valeur.

**JG-SEC-011, Sécurité web affaiblie. MAJOR.** Ajouté pendant la session : `csrf().disable()` de Spring Security sous toutes
ses formes, `.anyRequest().permitAll()` ou `requestMatchers("/**").permitAll()`. *Correction :* garder la protection CSRF ou
limiter l'exception avec `ignoringRequestMatchers(...)` ; exiger l'authentification par défaut et n'autoriser que les
endpoints qui doivent être publics.

**JG-SEC-012, CORS permissif avec identifiants. CRITICAL.** `allowedOrigins("*")`, `addAllowedOrigin("*")` ou
`@CrossOrigin(origins="*")` avec `allowCredentials(true)`. N'importe quel site web pourrait alors appeler l'API avec les
cookies de l'utilisateur. *Correction :* lister explicitement les origines autorisées.

**JG-SEC-013, LDAP/XPath/EL construits par concaténation. MAJOR.** Une chaîne concaténée passée à un filtre de
`DirContext.search`, à `XPath.compile/evaluate`, à `ExpressionParser.parseExpression` ou à `ScriptEngine.eval`. La même
injection qu'en SQL, dans d'autres langages (une injection SpEL peut exécuter du code). *Correction :* garder l'expression
constante et passer la valeur séparément : LDAP `search(name, "(uid={0})", new Object[]{value}, controls)`, variables XPath
via un `XPathVariableResolver`, variables SpEL sur l'`EvaluationContext` (ou un `SimpleEvaluationContext`), `Bindings` pour
les scripts.

**JG-SEC-014, Redirection ouverte. MAJOR.** `"redirect:" + <paramètre de requête>` ou
`response.sendRedirect(<paramètre de requête>)`. Les attaquants utilisent votre domaine pour envoyer les victimes vers un site
d'hameçonnage. *Correction :* valider la destination par rapport à une liste autorisée de chemins connus.

**JG-SEC-015, Endpoint sans autorisation alors que ses voisins en ont. MAJOR.** Dans un contrôleur d'au moins trois
gestionnaires et sans autorisation au niveau de la classe, un gestionnaire nouvellement ajouté est le seul sans
`@PreAuthorize`, `@PostAuthorize`, `@Secured`, `@RolesAllowed`, `@PermitAll` ou `@DenyAll`. La cause probable est un endpoint
que l'agent a oublié de protéger. *Correction :* ajouter l'autorisation dont l'endpoint a besoin, ou `@PermitAll` s'il doit
être public. Projets Spring uniquement.

## 3. Performance (JG-PERF)

« Dans une boucle » signifie à l'intérieur de `for`, `for` amélioré, `while`, `do`, ou d'une lambda passée à `forEach` ou à un
`map`/`filter` de stream. Toutes s'appliquent au code `main` et s'exécutent à la barrière post-edit.

**JG-PERF-001, Concaténation de chaînes dans une boucle. MAJOR.** `s += x` ou `s = s + x` où `s` est un `String` déclaré en
dehors de la boucle. Chaque passage recopie toute la chaîne : le coût croît de façon quadratique. *Correction :* un
`StringBuilder` avant la boucle, ajouter à l'intérieur, `toString()` une seule fois après.

**JG-PERF-002, Expression régulière compilée dans une boucle. MAJOR.** `Pattern.compile`, `String.matches`, `replaceAll`,
`replaceFirst` ou `split` avec une vraie expression régulière dans une boucle : le motif est compilé à chaque passage.
*Correction :* compiler le `Pattern` une fois avant la boucle et réutiliser son `Matcher`.

**JG-PERF-003, Objet coûteux créé de façon répétée. MAJOR.** Dans une boucle, ou dans une méthode d'un bean Spring appelée à
chaque requête : `new ObjectMapper()`, `new XmlMapper()`, `new Gson()`, `JAXBContext.newInstance`, `new RestTemplate()`,
constructeurs de `WebClient` et `HttpClient` ; dans les boucles seulement, `DateTimeFormatter.ofPattern`,
`new SimpleDateFormat`, `DocumentBuilderFactory.newInstance`. Ils coûtent cher à construire et se partagent sans risque.
*Correction :* le créer une fois (un champ, ou avant la boucle) et le réutiliser.

**JG-PERF-004, E/S de flux sans tampon. MAJOR.** `FileInputStream`, `FileReader`, `FileOutputStream` ou `FileWriter` non
enveloppé dans une classe `Buffered*` dont `read()` ou `write(int)` est appelé dans une boucle : un appel système par octet ou
caractère. *Correction :* l'envelopper dans une classe à tampon ou lire dans un tableau.

**JG-PERF-005, Recherche linéaire dans une boucle. INFO.** `contains`, `indexOf` ou `remove(Object)` sur une `List` dans une
boucle sur une autre collection. *Correction :* un `HashSet` ou un `HashMap`, ou indexer la collection avant la boucle.

**JG-PERF-006, Suppression en tête d'un ArrayList dans une boucle. INFO.** `list.remove(0)` sur un `ArrayList` dans une
boucle décale tous les éléments à chaque fois. *Correction :* un `ArrayDeque` et `removeFirst()`.

**JG-PERF-007, Accumulateur de type enveloppe (boxing) dans une boucle. INFO.** Une variable locale `Long`, `Integer` ou `Double` mise à jour
avec `+=` ou `++` dans une boucle alloue un objet à chaque passage. *Correction :* utiliser le type primitif.

**JG-PERF-008, Attente active. MAJOR.** Un `while` dont le corps ne fait que dormir et tester un indicateur, ou interroger une
collection ou un champ avec `Thread.sleep`. Il gaspille du CPU et ajoute de la latence. *Correction :* `wait()` et
`notifyAll()`, une primitive de `java.util.concurrent` (`CountDownLatch`, `Condition`) ou un appel bloquant.

**JG-PERF-009, Fichier ou flux entier matérialisé. INFO.** `Files.readAllBytes/readAllLines/readString` sur un chemin dans
une boucle, ou un corps HTTP lu en entier dans un `String` dans une boucle. *Correction :* lire une seule fois hors de la
boucle, ou le traiter en flux.

## 4. Fiabilité (JG-REL)

Toutes s'appliquent au code `main` sauf indication contraire et s'exécutent à la barrière post-edit.

**JG-REL-001, Exception avalée. MAJOR (CRITICAL pour `Exception`, `Throwable`, `RuntimeException`).** Un `catch` au corps vide
ou ne contenant que des commentaires ; ou un catch de `Exception` ou `Throwable` qui ne relance pas, ne journalise pas
l'exception et ne renvoie ni n'affecte de valeur d'erreur. L'échec disparaît et le programme continue dans un état inconnu.
*Correction :* relancer, journaliser en joignant l'exception, ou renvoyer une valeur de repli explicite.

**JG-REL-002, Capture de Throwable ou Error. MAJOR.** `catch (Throwable | Error | OutOfMemoryError | StackOverflowError)` sans
relance. Les erreurs au niveau de la JVM signifient généralement que le programme ne peut pas continuer en sécurité.
*Correction :* capturer un type plus étroit, ou relancer après traitement.

**JG-REL-003, Ressource non fermée. CRITICAL (code main et de test).** Une variable locale `AutoCloseable` créée avec `new` ou
par un appel JDBC, `Files.new*`, `Files.lines/list/walk/find`, `Scanner` ou `ZipFile`, non déclarée dans un
try-with-resources, non fermée dans `finally`, non renvoyée, non stockée dans un champ et non confiée à une autre ressource
fermable gérée. Les connexions et descripteurs de fichiers qui fuient épuisent le pool ou le système d'exploitation sous
charge. *Correction :* try-with-resources, ou la fermer dans `finally`.

**JG-REL-004, Cause perdue à la relance. MAJOR.** Dans `catch (X e)`, `throw new Y(...)` dont les arguments n'incluent pas `e`
et sans `initCause(e)` ni `addSuppressed(e)`. La trace d'origine est perdue, ce qui rend l'incident difficile à diagnostiquer.
*Correction :* passer l'exception capturée au constructeur de la nouvelle.

**JG-REL-005, Optional.get non vérifié. MAJOR.** `get()` sur une variable `Optional` non protégée dans la même méthode par un
contrôle `isPresent()` ou `isEmpty()`. Il lève `NoSuchElementException` quand il est vide. *Correction :* le protéger, ou
utiliser `orElseThrow()` ou `orElse(...)` pour expliciter le cas vide.

**JG-REL-006, Null renvoyé à la place d'un Optional ou d'une collection. CRITICAL.** `return null;` dans une méthode déclarée
pour renvoyer un `Optional`, une `List`, un `Set`, une `Map` ou une `Collection`, ou un `Stream`. Les appelants qui ne s'y
attendent pas reçoivent une `NullPointerException`. *Correction :* renvoyer `Optional.empty()`, une collection vide ou
`Stream.empty()`.

**JG-REL-007, Chaîne comparée avec ==. CRITICAL (code main et de test).** `==` ou `!=` entre deux opérandes de type `String`,
aucun n'étant `null`. Cela compare l'identité : ça marche dans un test et échoue avec d'autres données. *Correction :*
`.equals(...)`, ou `Objects.equals(a, b)` si l'un des côtés peut être nul.

**JG-REL-008, Mauvais usage de BigDecimal. MAJOR.** `new BigDecimal(double)` ; `BigDecimal.equals`, qui dépend de l'échelle ;
`divide(x)` sans `MathContext` ni `RoundingMode`, qui lève une exception si le résultat est non terminant. *Correction :*
`BigDecimal.valueOf(double)` ou un littéral `String` ; `compareTo(...) == 0` ; `divide(divisor, RoundingMode.HALF_UP)`.

**JG-REL-009, Virgule flottante pour de l'argent. MAJOR.** Un champ, paramètre ou variable locale de type `double`, `float`,
`Double` ou `Float` nommé comme un montant, prix, solde, coût, total, frais, taxe, salaire, paiement ou argent. La virgule
flottante ne peut pas représenter exactement la plupart des fractions décimales : l'erreur est silencieuse et s'accumule.
*Correction :* `BigDecimal`, ou un entier à virgule fixe comme des centimes dans un `long`.

**JG-REL-010, Statut d'interruption perdu. MAJOR.** `catch (InterruptedException e)` qui ne relance pas et n'appelle pas
`Thread.currentThread().interrupt()`. La demande d'annulation du thread est avalée. *Correction :* rétablir l'indicateur
avant de retourner, ou relancer.

**JG-REL-011, Contrôle de flux dans finally. CRITICAL (code main et de test).** `return`, `throw`, `break` ou `continue` dans
`finally` : il écarte silencieusement toute exception que le `try` propageait. *Correction :* le déplacer après l'instruction
`try` ; `finally` ne doit que libérer des ressources, et try-with-resources le fait en conservant l'exception d'origine.

**JG-REL-012, System.exit hors de main. MAJOR.** `System.exit` ou `Runtime.halt` hors d'un `public static void main`. Une
bibliothèque ou un service qui tue la JVM entraîne tout le reste avec lui. *Correction :* lever une exception ou renvoyer un
résultat d'erreur et laisser le point d'entrée décider.

**JG-REL-013, equals sans hashCode. MAJOR.** Une classe redéfinit `equals` sans `hashCode`, ou l'inverse. Cela casse `HashMap`
et `HashSet`. Signalé par Error Prone et PMD sous cet identifiant.

**JG-REL-014, État statique modifiable exposé. MAJOR.** Un champ `public static` non `final`, ou un tableau ou une collection
modifiable `public static final`. N'importe quel code peut le changer, sans synchronisation. *Correction :* le rendre privé
avec un accès contrôlé, ou exposer une vue non modifiable ou une copie défensive.

**JG-REL-015, `toMap` sans fonction de fusion. INFO.** `Collectors.toMap(key, value)` : une clé en double dans des données
réelles lève `IllegalStateException`. Les maps construites dans des initialiseurs statiques ou depuis `values()` d'un enum, et
les clés qui sont des identifiants, ne sont pas signalées. *Correction :* ajouter une fonction de fusion,
`(first, second) -> first`, ou utiliser `groupingBy` si des doublons sont attendus.

**JG-REL-016, Unboxing d'une valeur de map possiblement absente. INFO.** `map.get(key)` affecté à un primitif, renvoyé par
une méthode qui renvoie un primitif, ou utilisé dans une opération arithmétique ou une comparaison, alors que la méthode ne
vérifie jamais `containsKey`. Une clé absente devient une `NullPointerException`. *Correction :*
`getOrDefault(key, fallback)`, ou vérifier d'abord `containsKey` ou le résultat nul.

**JG-REL-017, Premier élément du résultat d'une requête. MAJOR.** `get(0)`, `getFirst()` ou `iterator().next()` sur le
résultat d'un appel à un repository, DAO, mapper, `JdbcTemplate` ou `EntityManager`, dans une méthode qui ne vérifie jamais
que le résultat n'est pas vide. Un résultat vide devient une `IndexOutOfBoundsException`. *Correction :* vérifier
`isEmpty()` d'abord, ou le lire comme un `Optional` (`list.stream().findFirst()`, ou une méthode `findFirstBy...`) puis
`orElseThrow(...)` ou `orElse(...)`.

**JG-REL-018, Exception journalisée sans l'exception. MAJOR.** Dans un `catch (X e)`, un appel au logger de niveau erreur
utilise `e.getMessage()` ou `e.toString()` mais ne passe jamais `e`, si bien que la trace est perdue. *Correction :* passer
l'exception en dernier argument, `log.error("message", e)`.

**JG-REL-019, Les marqueurs du log ne correspondent pas. MAJOR.** Un appel au logger dont le message littéral a un nombre de
`{}` différent du nombre d'arguments (une exception finale n'a pas besoin de marqueur). Les valeurs manquantes s'impriment
sous la forme `{}` ; les valeurs en trop sont ignorées. *Correction :* faire en sorte que le nombre de `{}` soit égal au
nombre d'arguments.

## 5. Concurrence (JG-CONC)

Un *bean singleton* est une classe annotée `@Service`, `@Component`, `@Repository`, `@Controller`, `@RestController`,
`@Configuration`, `@Singleton` ou `@ApplicationScoped`, sans `@Scope` autre que singleton. Une seule instance sert toutes les
requêtes à la fois : ses champs sont donc un état partagé. Toutes s'appliquent au code `main` et s'exécutent à la barrière post-edit.

**JG-CONC-001, Formateur partagé non thread-safe. CRITICAL.** Un champ `static`, ou un champ d'instance d'un bean singleton,
de type `SimpleDateFormat`, `DateFormat`, `DecimalFormat` ou `MessageFormat`. Ils ne sont pas thread-safe et produisent des
dates et des nombres faux sous concurrence. *Correction :* un `ThreadLocal`, le `DateTimeFormatter` immuable, ou une
instance par usage.

**JG-CONC-002, État modifiable dans un bean singleton. MAJOR.** Un champ d'instance non final d'un bean singleton (ni
injecté, ni affecté uniquement dans le constructeur) affecté dans une méthode ordinaire, ou un champ `final` contenant un
`HashMap`, `ArrayList`, `HashSet`, `LinkedList` ou `TreeMap` modifié dans une méthode ordinaire. Les requêtes s'écrasent
mutuellement leurs données. *Correction :* le rendre immuable et affecté dans le constructeur, utiliser un conteneur
concurrent (`AtomicReference`, une collection concurrente) ou synchroniser.

**JG-CONC-003, Verrouillage à double vérification sans volatile. CRITICAL.** Le motif classique d'initialisation paresseuse
sur un champ non `volatile` : un autre thread peut voir un objet à moitié construit. *Correction :* déclarer le champ
`volatile`, ou utiliser une classe conteneur.

**JG-CONC-004, Mauvais objet de verrou. MAJOR.** `synchronized` sur un littéral `String`, un type enveloppe (`Integer`, `Long`...), `getClass()`
ou un champ non final. Du code sans rapport peut partager le même verrou, ou l'objet de verrou peut changer. *Correction :*
un `private final Object` dédié à cet usage, ou un verrou de `java.util.concurrent`.

**JG-CONC-005, Tester puis agir sur une map concurrente. MAJOR.** `containsKey` ou `get == null` suivi de `put` sur la même
clé d'une `ConcurrentMap` dans le même bloc. La map est thread-safe mais les deux étapes ne sont pas atomiques. *Correction :*
`computeIfAbsent` ou `putIfAbsent`.

**JG-CONC-006, Threads ou exécuteurs non gérés. MAJOR.** `new Thread(...).start()` ou `Executors.new*` dans une méthode d'un
bean singleton, ou un exécuteur créé dans une méthode et jamais arrêté dedans. Les threads s'accumulent à chaque appel.
*Correction :* injecter un bean `ExecutorService` ou `TaskExecutor` partagé et géré par le conteneur, ou l'arrêter.

**JG-CONC-007, Mauvais usage de wait/notify. MAJOR.** `wait()` hors d'une boucle `while` (réveils intempestifs), ou `notify()`
au lieu de `notifyAll()`. *Correction :* `while (!condition) { lock.wait(); }` et `notifyAll()`, ou une primitive de
`java.util.concurrent`.

**JG-CONC-008, Effets de bord dans un stream parallèle. CRITICAL.** `parallelStream()` ou `.parallel()` suivi de `forEach`,
`map` ou `peek` dont la lambda ajoute à, ou modifie, une collection non concurrente capturée de l'extérieur. Mises à jour
perdues et collections corrompues. *Correction :* `collect()`, ou une collection concurrente.

**JG-CONC-009, Mise à jour non atomique d'un champ partagé. MAJOR.** `++`, `--` ou `+=` sur un champ d'instance non volatile
et non atomique d'un bean singleton, ou sur un champ `static`, hors de `synchronized`. Les incréments concurrents sont perdus.
*Correction :* `AtomicInteger` ou `AtomicLong`, ou synchroniser la mise à jour.

**JG-CONC-010, Verrou non libéré sur tous les chemins. CRITICAL.** `lock()` ou `lockInterruptibly()` sur un `Lock`,
`ReentrantLock`, `ReadLock`, `WriteLock` ou `StampedLock` non suivi d'un `try` dont le `finally` appelle `unlock()`. Une seule
exception laisse le verrou tenu et l'application se bloque. Non signalé quand l'acquisition et la libération sont dans des
méthodes différentes. *Correction :* `lock.lock(); try { ... } finally { lock.unlock(); }` avec le `try` juste après l'appel
à lock.

**JG-CONC-011, ThreadLocal non supprimé. MAJOR.** Un `ThreadLocal` privé sur lequel la classe appelle `set(...)` et jamais
`remove()`. Dans un pool de threads, la valeur fuit vers les requêtes suivantes et le thread la garde en vie. *Correction :*
appeler `remove()` dans un bloc `finally`, ou dans le `finally` d'un filtre, quand le travail qui a besoin de la valeur est
terminé.

## 6. Résilience (JG-RES)

Les filets de sécurité qui manquent derrière les incidents de production. Toutes s'appliquent au code `main` et s'exécutent à
la barrière post-edit.

**JG-RES-001, Client HTTP sans timeouts. CRITICAL.** Un client construit sans timeouts de connexion ou de lecture :
`new RestTemplate()` sans fabrique de requêtes, `HttpClient` sans `connectTimeout`, `HttpRequest` sans `timeout`, `WebClient`
sans connecteur ni timeout, `url.openConnection()` sans `setConnectTimeout` et `setReadTimeout`, `HttpClients` d'Apache sans
configuration de requête. Une seule dépendance lente retient alors tous les threads de l'appelant et la panne se propage.
*Correction :* définir les deux timeouts : `RestTemplateBuilder.setConnectTimeout/setReadTimeout`,
`HttpClient.newBuilder().connectTimeout(Duration)` plus un timeout sur chaque requête, un `ReactorClientHttpConnector` avec
`responseTimeout`, ou `RequestConfig` pour Apache.

**JG-RES-002, Attente sans limite. MAJOR.** `get()` ou `join()` sur un future ou un thread, `await()` sur un latch, une
barrière ou un phaser, `acquire()` sur un `Semaphore`, `take()` sur une `BlockingQueue`, sans timeout, hors de `main`. Un
future que la méthode a créé elle-même, et `get()` sous `if (f.isDone())`, ne sont pas signalés. *Correction :* utiliser la
surcharge avec timeout et gérer le timeout.

**JG-RES-003, Appel distant dans une transaction. MAJOR.** Spring uniquement. Un appel sur une variable `RestTemplate`,
`WebClient`, `HttpClient`, `KafkaTemplate`, `RabbitTemplate`, `JmsTemplate`, modèle SQS/SNS ou tout type `*Client`, dans une
méthode ou une classe `@Transactional`. La connexion à la base de données reste ouverte pendant l'attente du réseau.
*Correction :* faire l'appel distant avant ou après la méthode transactionnelle, ou publier un événement ou un enregistrement
outbox et appeler depuis l'extérieur.

**JG-RES-004, Appel distant dans une boucle. MAJOR.** Les mêmes récepteurs appelés dans le corps d'une boucle ou dans une
lambda d'itération de stream, hors de `main` : un aller-retour réseau par élément. Le `KafkaTemplate.send` asynchrone n'est
pas signalé. *Correction :* un endpoint par lots, une seule requête pour ce dont la boucle a besoin, ou des appels
concurrents avec un pool borné.

**JG-RES-005, Pool de threads ou file sans limite. MAJOR (INFO pour les pools fixes et à un seul thread).**
`Executors.newCachedThreadPool()` ; un `ThreadPoolExecutor` avec une `LinkedBlockingQueue`, `LinkedBlockingDeque` ou
`PriorityBlockingQueue` sans capacité ; INFO pour `newFixedThreadPool` et `newSingleThreadExecutor` (threads bornés, file non
bornée). Sous charge, l'application manque de mémoire ou de threads. *Correction :* un `ThreadPoolExecutor` avec une taille
maximale, une file bornée et une politique de rejet.

**JG-RES-006, Travail bloquant sur le pool commun. MAJOR.** `CompletableFuture.supplyAsync` ou `runAsync` sans exécuteur dont
la lambda appelle un accès aux données, un client distant, `Thread.sleep` ou des lectures de fichiers. Il affame le
`ForkJoinPool` partagé qu'utilisent les streams parallèles et toutes les autres tâches asynchrones. *Correction :* passer un
exécuteur dédié et borné en second argument.

## 7. Spring et JPA (JG-SPR)

Actives quand Spring ou JPA est détecté dans le projet. Toutes s'appliquent au code `main`.

**JG-SPR-001, L'annotation de proxy n'a aucun effet. CRITICAL.** `@Transactional`, `@Async`, `@Cacheable`, `@CacheEvict` ou
`@Retryable` sur une méthode `private`, `final` ou `static`. Spring ne peut pas l'intercepter par un proxy : pas de transaction, pas de
cache, pas de nouvelle tentative, et rien ne le signale. *Correction :* rendre la méthode publique, non finale et non
statique et l'appeler depuis un autre bean, ou la déplacer dans un `@Service` distinct que les appelants injectent.

**JG-SPR-002, L'auto-invocation contourne le proxy. MAJOR.** Une méthode appelle, sans qualificatif ou via `this.`, une autre
méthode de la même classe annotée `@Transactional`, `@Async`, `@Cacheable` ou `@Retryable`, et l'appelant n'a pas
l'annotation. L'appel contourne le proxy : l'annotation est ignorée. *Correction :* déplacer la méthode appelée dans un
`@Service` distinct et l'appeler via la référence injectée.

**JG-SPR-003, Requête dans une boucle (N+1). MAJOR.** Dans une boucle, un appel sur un `*Repository`, `*Dao`, `*Mapper`,
`JdbcTemplate`, `NamedParameterJdbcTemplate`, `EntityManager` ou `Session` ; ou (INFO) un getter d'un champ `@OneToMany` ou
`@ManyToMany` d'une entité dans une boucle. Une requête par élément est invisible avec quelques lignes et fait tomber la
base avec beaucoup. *Correction :* récupérer ce dont la boucle a besoin en une seule requête avant elle (`findAllById`, un
join fetch, un entity graph, un lot).

**JG-SPR-004, Association vers plusieurs en chargement immédiat (EAGER). MAJOR.** `@OneToMany` ou `@ManyToMany` avec
`fetch = FetchType.EAGER` : chaque chargement de l'entité charge aussi toute la collection. *Correction :* garder le
chargement paresseux par défaut et récupérer la collection explicitement (join fetch, entity graph) là où c'est nécessaire.

**JG-SPR-005, Tout charger puis filtrer en mémoire. MAJOR.** `findAll()` sans `Pageable`, `Specification` ni `Example`, suivi
d'un `filter` de stream ou d'une boucle avec `if` dans la même méthode. *Correction :* mettre la condition dans la requête :
une requête dérivée, `@Query`, une `Specification` ou un `Pageable`.

**JG-SPR-006, Exception avalée dans une transaction. MAJOR.** Dans une méthode ou classe `@Transactional`, un `catch` qui ne
relance pas et n'appelle pas `setRollbackOnly()` : la transaction est validée alors que quelque chose a échoué.
*Correction :* relancer, ou `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`.

**JG-SPR-007, @Async avec un retour qui n'est pas un future. MAJOR.** Une méthode `@Async` qui renvoie autre chose que
`void`, `Future`, `CompletableFuture` ou `ListenableFuture` : l'appelant reçoit `null`. *Correction :* renvoyer
`CompletableFuture<T>`, ou `void`.

**JG-SPR-008, Pièges d'égalité des entités. MAJOR.** Une `@Entity` avec `@Data` ou `@EqualsAndHashCode` de Lombok sans
`onlyExplicitlyIncluded`, ou `equals`/`hashCode` fondés uniquement sur un id `@GeneratedValue` sans gestion du nul. Les
entités se comparent mal avant d'être enregistrées ou entraînent des collections paresseuses dans `hashCode`. *Correction :*
`@Getter`/`@Setter` et un `equals`/`hashCode` explicite sur une clé métier, ou traiter un id non attribué comme « non égal ».

**JG-SPR-009, Appel bloquant dans une chaîne réactive. MAJOR.** `.block()`, `.blockFirst()`, `.blockLast()`, `Thread.sleep`
ou des appels JDBC et de repository non réactifs dans une lambda d'un opérateur de `Mono` ou `Flux`. Cela bloque les rares
threads de la boucle d'événements. *Correction :* l'équivalent réactif, ou déplacer le travail bloquant vers
`Schedulers.boundedElastic()`.

**JG-SPR-010, Entité exposée directement. INFO.** Un paramètre `@RequestBody` ou un type de retour de contrôleur qui est une
`@Entity` : les clients peuvent définir des champs qu'ils ne devraient pas (affectation de masse), et des associations
paresseuses ou des colonnes privées sont sérialisées. *Correction :* lier et renvoyer un DTO et le convertir.

**JG-SPR-011, Exception contrôlée sans rollback. MAJOR.** Une méthode publique ou protégée sous `@Transactional` dont le
`throws` nomme une exception contrôlée non couverte par `rollbackFor` : Spring valide par défaut sur les exceptions
contrôlées. Les transactions en lecture seule ne sont pas signalées. *Correction :* ajouter `rollbackFor = X.class`
(`rollbackOn` pour `jakarta.transaction`), ou l'envelopper dans une exception non contrôlée.

**JG-SPR-012, Écriture dans une transaction en lecture seule. MAJOR.** Sous `@Transactional(readOnly = true)`, un `save*`,
`delete*`, `persist`, `merge`, `remove`, `update*`, `insert*` ou `batchUpdate` sur un repository, un `EntityManager`, une
`Session` ou un `JdbcTemplate`. Selon le fournisseur, l'écriture est perdue ou échoue. *Correction :* retirer
`readOnly = true`, ou déplacer l'écriture dans une méthode à transaction en lecture-écriture.

**JG-SPR-013, API `javax` dans un projet Jakarta. CRITICAL.** Active uniquement quand le build est Jakarta EE (Spring
Boot 3+, Spring Framework 6+, Hibernate ORM 6+ ou une API dans l'espace de noms jakarta). Les imports de `javax.persistence`,
`javax.validation`, `javax.servlet`, `javax.ws.rs` ou `javax.transaction.Transactional` compilent contre une dépendance
résiduelle et échouent à l'exécution. `javax.annotation.PostConstruct` et `PreDestroy` ne sont pas signalés, Spring 6 les
respecte encore. *Correction :* utiliser l'équivalent `jakarta.*` et retirer la dépendance `javax` qui permet la compilation.

**JG-SPR-014, Corps de requête non validé. MAJOR.** Un paramètre `@RequestBody` sans `@Valid` ni `@Validated` dont le type est
déclaré dans le module avec des contraintes Bean Validation. Les contraintes ne sont jamais vérifiées. Les gestionnaires PATCH
ne sont pas signalés. *Correction :* annoter le paramètre avec `@Valid`.

**JG-SPR-015, Requête dérivée sur une propriété inconnue. BLOCKER (post-edit et fin de tour).** Une méthode d'un
repository Spring Data dont le nom (`findByNameAndAge`...) nomme une propriété qui n'existe pas dans l'entité, ou qui déclare
moins de paramètres que les conditions n'en consomment. Spring Data échoue au démarrage de l'application. Une erreur
fréquente des agents : ils inventent le nom d'un champ. *Correction :* utiliser exactement les noms d'attributs de l'entité,
ou écrire la requête avec `@Query` ; un paramètre par valeur de condition, dans l'ordre (`Between` en prend deux, `IsNull` et
`True` aucun).

**JG-SPR-016, JPQL sur une entité ou un attribut inconnu. CRITICAL (post-edit et fin de tour).** Un littéral JPQL (dans
`@Query` sans `nativeQuery`, `@NamedQuery`, `createQuery`) qui nomme une entité ou un chemin `alias.attribut` qui ne se
résout pas, ou un nom d'entité à deux éditions d'un nom connu. La requête échoue à sa création, au démarrage. *Correction :*
ouvrir la classe de l'entité et utiliser exactement son nom d'entité et de champs (sensibles à la casse, et qui ne sont pas
des noms de table ou de colonne) ; si la requête est du SQL, mettre `nativeQuery = true`.

**JG-SPR-017, `findAll()` non paginé renvoyé au client. MAJOR.** Un gestionnaire de contrôleur renvoie le résultat d'un
`findAll()` sans argument. Avec un vrai volume de données, la réponse grandit sans limite. Les repositories de petites tables
de référence (rôles, pays, devises, statuts) ne sont pas signalés. *Correction :* prendre un `Pageable` et renvoyer
`findAll(pageable)`, ou borner la requête.

## 8. Intégrité des tests (JG-TST)

Ces règles comparent les tests à leur version antérieure à la session. Elles existent parce qu'un agent qui n'arrive pas à
faire passer un test peut modifier le test au lieu du code. À la barrière post-edit, elles bloquent à partir de
CRITICAL ; en fin de tour, à partir de MAJOR. Elles s'appliquent au code de test sauf indication contraire.

**JG-TST-001, Méthode de test supprimée. CRITICAL.** Une méthode `@Test`, `@ParameterizedTest`, `@RepeatedTest` ou
`@TestFactory` qui existait a disparu (un renommage à corps identique n'est pas signalé). *Correction :* la rétablir ; si le
test est obsolète, le dire à l'utilisateur au lieu de le supprimer.

**JG-TST-002, Test désactivé. BLOCKER.** `@Disabled`, `@Ignore`, `@DisabledIf*` ou `@EnabledIf*` ajouté à une méthode ou une
classe de test. *Correction :* le retirer et corriger la cause.

**JG-TST-003, Assertions supprimées. CRITICAL.** Le nombre d'appels d'assertion (JUnit, AssertJ, Hamcrest, `verify` de
Mockito, `expect`, `fail`) a diminué dans une méthode de test.

**JG-TST-004, Valeur attendue modifiée. MAJOR.** Dans une assertion dont l'expression « réelle » n'a pas changé, la valeur
attendue a changé. Signalé pour que l'agent doive justifier la nouvelle attente : c'est ainsi qu'un test en échec est
« réparé » sans réparer le code.

**JG-TST-005, Assertion triviale. CRITICAL.** Ajout de `assertTrue(true)`, `assertFalse(false)`, `assertEquals(x, x)`,
`assertNotNull(<littéral ou new>)` ou `assertThat(true).isTrue()`. Le test ne peut jamais échouer. *Correction :* affirmer un
résultat concret du code testé (une valeur renvoyée, un changement d'état, une interaction) ; ne pas se contenter de la
supprimer.

**JG-TST-006, Corps de test vidé. CRITICAL.** Un test qui avait des instructions et n'en a plus aucune, ou seulement des
commentaires.

**JG-TST-007, Échec d'assertion avalé. CRITICAL.** Un `try` contenant des assertions avec un `catch` de `AssertionError`,
`Throwable` ou `Error` qui ne relance pas. *Correction :* supprimer le `try`/`catch`, ou n'attraper que l'exception précise
qu'attend le test.

**JG-TST-008, Fichier de test supprimé. CRITICAL.** Un fichier de code de test supprimé pendant la session.

**JG-TST-009, Tests ignorés par la configuration du build. BLOCKER.** Ajouté ou modifié dans `pom.xml` ou les fichiers
Gradle : `skipTests`, `maven.test.skip`, `testFailureIgnore`, un `skip` dans surefire ou failsafe, de nouveaux `<excludes>`,
`test { enabled = false }`, `ignoreFailures = true`, un `exclude` dans le filtre de tests ou `onlyIf { false }`. Les tests
doivent s'exécuter et faire échouer le build quand ils échouent.

**JG-TST-010, Hypothèse ajoutée. MAJOR.** `Assumptions.assume*` ou `Assume.assume*` ajouté à un test qui n'en avait pas : le
test est ignoré en silence quand l'hypothèse est fausse.

**JG-TST-011, Timeout fortement augmenté. INFO.** Une valeur de `@Timeout` ou `assertTimeout` multipliée par dix ou plus.

**JG-TST-012, Test d'exception attendue neutralisé. MAJOR.** `assertThrows` retiré d'un test qui en avait un, ou `fail(...)`
retiré après l'appel testé.

**JG-TST-013, Le code de production détecte les tests. CRITICAL (code main).** Du code main qui importe `org.junit` ou
`org.mockito`, les recherche avec `Class.forName`, inspecte la pile d'appels à la recherche de « Test », ou lit un
interrupteur du type `test-mode`, introduit pendant la session. Le comportement en production diffère alors de ce que les
tests exercent. *Correction :* supprimer le cas particulier ; si le comportement doit différer dans les tests, l'injecter
(un paramètre de constructeur ou une propriété de configuration) et passer un faux depuis le test.

**JG-TST-014, Classe testée remplacée par un mock. MAJOR.** `@Mock`, `mock(X.class)` ou `@MockBean X` où `X` est la classe dont
le test porte le nom (`XTest`). Le test vérifie alors le mock, pas le code. *Correction :* utiliser un vrai `X` et ne simuler
que ses collaborateurs : `@InjectMocks`, ou `new X(mock(Collaborator.class))`.

## 9. Résidus d'IA (JG-AI)

Des restes que les agents laissent typiquement. Toutes concernent du code que l'agent a ajouté.

**JG-AI-001, Sortie console dans le code de production. MAJOR.** `print*` de `System.out` ou `System.err` ajouté hors des
méthodes `main` et hors des classes d'un paquet `cli`. Pas de niveau, pas de corrélation, aucun moyen de le rediriger ou de
l'éteindre. *Correction :* journaliser via le logger SLF4J de la classe. Si le projet n'a pas de logger, on dit à l'agent de
ne pas ajouter de bibliothèque de son propre chef : il vous demande s'il faut ajouter SLF4J ou si la sortie console convient
à ce programme, auquel cas c'est vous qui désactivez la règle.

**JG-AI-002, printStackTrace. MAJOR.** `e.printStackTrace()` ajouté. *Correction :* journaliser l'exception,
`log.error("...", e)`.

**JG-AI-003, Squelette non implémenté. CRITICAL.** Une méthode ajoutée dont le corps ne fait que lever
`UnsupportedOperationException` ou `RuntimeException("not implemented")`, ou ne renvoie que `null` ou une valeur par défaut à
côté d'un commentaire TODO, placeholder ou stub. Un commentaire du style `// TODO implement` est signalé en MAJOR. L'agent a
abandonné en cours de route. *Correction :* implémenter la méthode ; si ce qu'elle doit faire n'est pas clair, s'arrêter et
demander à l'utilisateur ; si les sous-classes doivent l'implémenter, la déclarer `abstract`.

**JG-AI-004, Code commenté ajouté. INFO.** Trois lignes de commentaire consécutives ajoutées, ou plus, qui s'analysent comme
des instructions Java. *Correction :* supprimer le code mort, le gestionnaire de versions garde l'historique ; si c'est une
explication, la réécrire en prose.

**JG-AI-005, Littéral de remplissage. MAJOR.** Des littéraux chaîne comme `"TODO"`, `"FIXME"`, `"changeme"`,
`"lorem ipsum..."`, `"foo"`, `"bar"`, `"your-api-key"`, `"xxx"` ou une URL `example.com` utilisés comme valeur dans le code
de production. *Correction :* lire la valeur dans la configuration sous une clé nommée ; si la vraie valeur est inconnue,
s'arrêter et demander à l'utilisateur au lieu de l'inventer.

**JG-AI-006, Suppression large d'avertissements ajoutée. INFO.** `@SuppressWarnings("all")`, `"unchecked"` ou `"rawtypes"`
ajouté au niveau de la classe. *Correction :* la limiter au membre ou à l'instruction concernés et à un avertissement précis.

**JG-AI-007, Code inutilisé ajouté. INFO.** Une méthode privée, un champ ou une variable locale ajoutés qui ne sont jamais
utilisés (signalé par `UnusedVariable` et `UnusedMethod` d'Error Prone).

## 10. Dépendances (JG-DEP)

Modifications des fichiers de build (`pom.xml`, Gradle).

**JG-DEP-001, Dépendance ajoutée. INFO (post-edit).** Une nouvelle dépendance est apparue. Les agents ajoutent des
bibliothèques trop facilement et les coordonnées sont parfois fausses. *Correction :* préférer le JDK ou une bibliothèque que le build déclare déjà, et la retirer si le changement fonctionne sans elle ; si une nouvelle bibliothèque est vraiment
nécessaire, s'arrêter et dire à l'utilisateur laquelle et pourquoi ; ne jamais deviner coordonnées ni versions.

**JG-DEP-002, Vulnérabilité connue. Gravité selon le score CVSS (fin de tour).** Une dépendance présentant une vulnérabilité
connue dans la base OSV. Seules les coordonnées sont envoyées à `api.osv.dev` ; on peut le désactiver dans la configuration.
*Correction :* passer à une version corrigée.

**JG-DEP-003, Dépendance impossible à résoudre (peut-être inventée). BLOCKER (fin de tour).** Le build ne peut pas
télécharger un artefact. Les modèles inventent des bibliothèques et des versions qui n'existent pas. Signalé uniquement quand
les dépôts ont donné un vrai « introuvable » ; hors ligne ou une erreur de proxy est une note d'infrastructure, jamais un
blocage. *Correction :* copier les coordonnées d'un autre module du build ou de la documentation de la
bibliothèque, préférer la version que gère le parent ou le BOM, ou utiliser une bibliothèque que le build déclare déjà ; si aucune ne convient, demander à l'utilisateur.

**JG-DEP-004, Version non figée ou snapshot. MAJOR (post-edit).** Une version `LATEST`, `RELEASE`, une plage ou
`-SNAPSHOT`. Les builds cessent d'être reproductibles. *Correction :* figer une version publiée exacte.

**JG-DEP-005, Dépendance rétrogradée. MAJOR (post-edit).** Une version inférieure à la précédente. Les rétrogradations
réintroduisent souvent des bogues et des vulnérabilités déjà corrigés. *Correction :* garder la version la plus récente sauf
si la rétrogradation est délibérée.

## 11. Compatibilité de l'API (JG-API)

Fin de tour uniquement. Elles comparent le code à sa version antérieure à la session et protègent les clients que vous ne
voyez pas.

**JG-API-001, API publique modifiée. MAJOR.** Une signature publique d'un fichier modifié a changé ou disparu. *Correction :*
garder chaque ancienne signature à côté de la nouvelle (dépréciée, déléguant à elle), ou mettre à jour tous les consommateurs
dans le même changement. Quand aucun module du build n'en dépend, des consommateurs externes peuvent exister.

**JG-API-002, Endpoint HTTP supprimé ou déplacé. MAJOR.** Modules Spring : un endpoint (méthode et chemin combiné) d'un
contrôleur modifié n'existe plus ; un `@RequestMapping` sans restriction a été restreint à certaines méthodes ; un type de
média `consumes` ou `produces` déclaré a été retiré. Les déplacements vers un autre contrôleur modifié ou un gestionnaire
renommé ne sont pas signalés. *Correction :* garder l'ancien mapping (il peut déléguer au nouveau) jusqu'à ce que tous les
clients aient migré, ou faire le changement volontairement et prévenir les clients.

**JG-API-003, L'endpoint exige un nouveau paramètre. MAJOR.** Le même endpoint, un `@RequestParam` ou `@RequestHeader` qui est
maintenant obligatoire (sans `required = false`, sans `defaultValue`, non `Optional`), ou un `@RequestBody` obligatoire là où
il n'y en avait pas. Les clients existants se mettent à échouer. *Correction :* le rendre optionnel pour que les clients
existants continuent de fonctionner, ou ajouter un nouvel endpoint.

**JG-API-004, Champ JSON renommé ou supprimé. MAJOR.** Un type utilisé comme corps de requête ou de réponse par un contrôleur
perd une propriété JSON : un composant de record, un champ public, un getter, un champ Lombok ; `@JsonProperty`, `@JsonIgnore`
et `@JsonIgnoreProperties` sont respectés. *Correction :* garder le nom JSON avec `@JsonProperty("old")` sur le membre
renommé, ou garder la propriété.

## 12. Migrations de base de données (JG-DB)

Une migration est **appliquée** quand elle existait avant la première édition de la session et, dans un dépôt git, est validée. Une migration qui n'existe que dans le répertoire de travail n'a pas atteint une base partagée et peut être
modifiée librement. Sont pris en charge : Flyway (migrations SQL et Java) et Liquibase (XML, YAML, JSON, SQL formaté). Les
contrôles reflètent ce que Flyway et Liquibase valident eux-mêmes au démarrage, et ont été vérifiés avec Flyway 10.10 et
Liquibase 4.27.

**JG-DB-001, Migration appliquée modifiée. BLOCKER.** Une migration SQL Flyway appliquée dont le checksum a changé (les fins
de ligne et les lignes vides sont acceptées, comme le fait Flyway ; tout le reste, commentaires et espaces finaux compris,
fait échouer la validation au démarrage), ou un change set Liquibase appliqué (mêmes id et auteur) dont les changements
diffèrent. En production, l'application refuse alors de démarrer, ou le schéma dérive. Les change sets `runOnChange` sont
autorisés. *Correction :* rétablir le fichier exactement tel qu'il a été validé et mettre le changement dans une nouvelle
migration avec la version suivante. Si la migration n'a jamais été appliquée à une base partagée, le dire à l'utilisateur :
lui seul peut l'exclure dans `javaguard.yml`.

**JG-DB-002, Migration renommée ou supprimée. BLOCKER.** Une migration versionnée Flyway appliquée a été supprimée ou
renommée ; un change set Liquibase dont l'id ou l'auteur a changé (il s'exécute à nouveau). Supprimer la dernière migration
appliquée, ou retirer un change set appliqué ou un changelog entier, est MAJOR : Flyway et Liquibase démarrent, mais les
bases dérivent. *Correction :* la rétablir ; pour annuler une migration, en ajouter une nouvelle qui l'inverse.

**JG-DB-003, Version de migration en double. CRITICAL.** Une nouvelle migration versionnée Flyway dont la version (`1`, `1.0`
et `1_0` sont la même) est déjà utilisée par une autre migration du même emplacement. Flyway échoue au démarrage.
*Correction :* lui donner la prochaine version libre.

## 13. Configuration (JG-CFG)

**JG-CFG-001, Secret dans la configuration. BLOCKER.** Dans `application*.properties`, `.yml`, `.yaml` et `bootstrap*` : une
clé dont le nom ressemble à un secret et qui porte un littéral de huit caractères ou plus qui n'est ni une référence
`${...}`, ni un marqueur, ni une URL, un nombre, une durée, un booléen ou un chemin ; ou toute valeur au format
d'identifiant connu, quelle que soit sa clé. Les clés qui ne font que mentionner un secret (`token-uri`, `api-key-header`,
`*-validity-seconds`...) ne sont pas signalées ; dans les profils de type développement (`dev`, `local`, `test`, `h2`...) la
gravité est moindre. Les fichiers de configuration sont validés dans git et empaquetés dans l'artefact. *Correction :* remplacer la
valeur par une référence telle que `${NOM_ENV}` fournie par l'environnement ou un gestionnaire de secrets ; la renouveler si elle a déjà été validée dans git.

**JG-CFG-002, Clé de configuration jamais définie. MAJOR (fin de tour, simple avertissement).** Spring Boot : un `${key}` sans
valeur par défaut dans `@Value`, ou une propriété obligatoire d'une classe `@Validated @ConfigurationProperties`, dont la clé
n'est définie par aucun fichier de configuration du module. L'application ne démarre pas sauf si l'environnement la fournit.
Seul ce que le tour a introduit est signalé. *Correction :* définir la clé dans `application.yml` (par profil si elle
diffère), ou donner au marqueur une valeur par défaut (`${key:...}`) si elle est facultative.

**JG-CFG-003, Hôte codé en dur. MAJOR.** Un littéral d'URL vers `localhost`, une adresse de bouclage, `0.0.0.0` ou une adresse
de réseau privé, ou une URL JDBC nommant un hôte, ajouté pendant la session. Cela marche sur la machine du développeur et
nulle part ailleurs. Les valeurs par défaut d'annotations, les classes `@ConfigurationProperties` et `@Configuration`, les
bases embarquées et le code de test ne sont pas signalés. *Correction :* le lire dans la configuration (`@Value("${...}")` ou
une classe `@ConfigurationProperties`) et le définir par environnement.

## 14. Architecture et suppressions

**JG-ARCH-001, Dépendance interdite. MAJOR.** Le `javaguard.yml` du projet peut déclarer des règles d'architecture
(`from: com.acme.domain..`, `mustNotDependOn: [com.acme.web..]`). Un import qui franchit une frontière interdite est signalé.
*Correction :* déclarer une interface dans un paquet que le fichier peut utiliser, l'implémenter dans le paquet interdit et
dépendre de l'interface ; ou déplacer le code vers un paquet autorisé à l'utiliser.

**JG-SUP-001, Suppression sans raison. MAJOR.** `// javaguard:ignore <règle>` sans `-- <raison>`. *Correction :* ajouter
`-- <raison>` expliquant pourquoi le constat est un faux positif.

**JG-SUP-002, Suppression ajoutée par l'agent. BLOCKER (quand elle n'est pas autorisée).** Avec
`suppressions.allowAgentAdded: false`, un `javaguard:ignore` que l'agent a ajouté pendant la session est lui-même un BLOCKER :
c'est le moyen le plus simple pour un agent de faire taire une règle. *Correction :* retirer la suppression et corriger le
constat ; si c'est vraiment un faux positif, s'arrêter et l'expliquer à l'utilisateur, qui peut ajouter la suppression.

**JG-SUP-003, Configuration ou hooks de JavaGuard modifiés. BLOCKER.** Au premier hook d'une session, JavaGuardCLI enregistre le
`javaguard.yml` du projet, `~/.javaguard/config.yml` et ses propres entrées de hooks dans `.claude/settings.json`,
`.gemini/settings.json` et `.codex/hooks.json`. Pendant le reste de la session, il lit la copie enregistrée : une
modification ne change donc rien, et tout changement de ces fichiers est un BLOCKER tant que l'agent ne les a pas rétablis.
Les autres réglages des fichiers de l'agent (permissions, thème) ne sont pas comparés. Un changement que vous voulez en cours
de session s'accepte avec `javaguard baseline reset` ou en ouvrant une nouvelle session. *Correction :* rétablir le fichier
exactement ; réécrire le code plutôt que de modifier JavaGuardCLI.

## 15. Autres moteurs

En plus de ses propres règles, JavaGuardCLI utilise trois moteurs éprouvés, avec une sélection soignée pour garder un faible
niveau de bruit. Le style, les noms, le formatage, la Javadoc, la complexité et les règles similaires sont exclus
volontairement.

- **Error Prone** s'exécute dans la compilation en processus après chaque édition. Tous les contrôles de niveau ERROR sont
  activés (CRITICAL), plus un ensemble choisi d'avertissements (par exemple `BigDecimalEquals`, `BoxedPrimitiveEquality`,
  `FutureReturnValueIgnored`, `CatchAndPrintStackTrace`, `WaitNotInLoop`, `SynchronizeOnNonFinalField`). Les constats sont
  `EP:<Contrôle>`.
- **PMD 7** exécute un petit jeu de règles (blocs catch vides, `PreserveStackTrace`, `CloseResource`,
  `ReturnFromFinallyBlock`, `DoubleCheckedLocking`, cryptographie non sûre et quelques règles de performance). Les constats
  sont `PMD:<Règle>`.
- **SpotBugs avec FindSecBugs** s'exécute en fin de tour sur les classes modifiées : exactitude, exactitude multithread et
  motifs de sécurité. Le rang propre à SpotBugs fixe la gravité (1 à 4 CRITICAL, 5 à 9 MAJOR, 10 à 14 INFO). Les constats
  sont `SB:<Motif>`.

Quand un moteur recoupe l'une des règles ci-dessus, le constat est signalé une seule fois, sous l'identifiant de JavaGuardCLI,
et l'autre moteur figure dans son détail.

## Régler les règles

Chaque règle peut être ajustée dans `javaguard.yml`. Désactiver, abaisser ou restreindre une règle est une décision qui vous
revient, pas à l'agent :

```yaml
rules:
  JG-PERF-005: { enabled: false }                       # désactivée
  JG-AI-001: { severity: INFO, exclude: ["**/cli/**"] }  # l'abaisser, ou ignorer certains chemins
  JG-SEC-002: { severity: BLOCKER }                      # bloquer aussi juste après l'édition
  JG-PERF-002: { severity: BLOCKER }
```

`javaguard rules` montre chaque règle avec sa gravité effective et son état d'activation. Pour la configuration complète
(seuils, constats qui bloquent, suppressions, notifications), voir le manuel d'utilisation, `usage_fr.md`.
