# JavaGuardCLI - manuel d'utilisation

> Traduction française de [USAGE.md](USAGE.md), qui est la version de référence. Les messages affichés par le programme, les
> identifiants des règles et leurs titres, les clés de configuration et les verdicts (`PASS`, `ADVISE`, `BLOCK`, `HALT`) sont
> laissés en anglais, tels que vous les verrez à l'écran.

JavaGuardCLI est une barrière de qualité (*quality gate*) pour les agents d'IA qui programment en Java. Il s'exécute comme un
**hook** de l'agent - une commande que l'agent appelle lui-même autour de ses modifications et à la fin de son tour - et dit à
l'agent, dans le protocole propre à l'agent, ce qui ne va pas dans ce qu'il vient d'écrire. Les agents pris en charge sont
**Claude Code**, **Gemini CLI** et **Codex**.

Il vérifie en deux étapes :

- **Après chaque modification** (la *barrière post-edit*, environ une seconde) : analyse syntaxique, règles de sécurité et de
  fiabilité, PMD, compilation en processus avec Error Prone, contrôles d'intégrité des tests (l'agent qui affaiblit un test
  pour le faire passer), pièges Spring/JPA, règles de résilience, secrets dans les fichiers de configuration, modifications
  de migrations de base de données.
- **À la fin du tour de l'agent** (la *barrière end-of-turn*) : compilation Maven ou Gradle des modules modifiés et de leurs
  dépendants, les tests JUnit concernés, SpotBugs + FindSecBugs, contrôles des dépendances auprès d'OSV, compatibilité de
  l'API publique et du contrat HTTP, clés de configuration, et tout ce que l'agent a modifié par le shell, qu'aucun hook
  d'édition ne peut voir.

Et il surveille l'agent lui-même : quand il tourne en rond, JavaGuard suggère un changement d'approche, interroge
éventuellement un second modèle et, enfin, l'arrête avec un document de passation (*handoff*) qu'un autre modèle (ou vous)
peut reprendre.

Quatre garanties : il ne signale que ce que la session a introduit (les constats hérités ne sont pas imputés à l'agent) ; il
ne modifie jamais votre code ; il n'exécute jamais de commande git qui change l'état ; et il **échoue en ouvert**
(*fail-open*) : si JavaGuard lui-même tombe en panne, l'agent continue.

## Sommaire

1. [Installation](#1-installation)
2. [Configurer un projet](#2-configurer-un-projet)
3. [Les hooks, agent par agent](#3-les-hooks-agent-par-agent)
4. [Ce que voit l'agent](#4-ce-que-voit-lagent)
5. [Quand l'agent est bloqué : escalade, passation, second avis, notifications](#5-quand-lagent-est-bloqué)
6. [Commandes](#6-commandes)
7. [Configuration](#7-configuration)
8. [Faux positifs et suppressions (FAQ)](#8-faux-positifs-et-suppressions-faq)
9. [Dépannage](#9-dépannage)
10. [Règles](#10-règles)
11. [Licence](#11-licence)

## 1. Installation

**Windows** - `javaguard-<version>-windows-x64.zip` : décompressez-le où vous voulez et gardez les fichiers ensemble
(`javaguard.exe`, `runtime\`, `javaguard-<version>-all.jar`, `plugins\`). Aucune installation de Java n'est
nécessaire.
Installez-le dans un chemin court : Windows ne peut pas démarrer un programme dont le chemin complet atteint 260 caractères,
laissez donc le dossier sous environ 200 caractères. Les espaces et les caractères accentués dans le chemin ne posent pas de problème.

**macOS, Linux ou Windows sans l'exe** - `javaguard-<version>-portable.zip` : nécessite Java 21 ou supérieur (`JAVA_HOME` ou
`java` dans le `PATH`). Utilisez `javaguard` (macOS/Linux) ou `javaguard.cmd` (Windows). Le script `javaguard` doit être
exécutable (`chmod +x javaguard` si votre outil de décompression a perdu la permission). Linux a été testé ; macOS n'a pas
encore été exécuté à la main, et un zip téléchargé avec un navigateur peut porter l'attribut de quarantaine
(`xattr -dr com.apple.quarantine <dossier>`).

Mettez le dossier dans le `PATH`, ou passez le chemin complet à `--command` lors de l'installation des hooks. Vérifiez avec
`javaguard --version`.

Sous la locale POSIX (`LANG=C`, courante dans les conteneurs et cron), la JVM ne sait pas gérer un chemin contenant des
accents ; le script `javaguard` passe à une locale `C.UTF-8` pour son propre processus lorsque le système en dispose.

Le projet vérifié a besoin de Maven ou de Gradle (un exécutable ou le wrapper) pour la barrière end-of-turn ; la barrière
post-edit n'a besoin que des sources.

## 2. Configurer un projet

Depuis la racine du projet :

```
javaguard install --agent claude --command C:\tools\javaguard\javaguard.exe     # Windows
javaguard install --agent claude --command /opt/javaguard/javaguard             # Linux, macOS
javaguard warmup
javaguard doctor
```

1. **`install`** fusionne les hooks de JavaGuard dans le fichier de configuration de l'agent (après une sauvegarde ; vos
   propres hooks et réglages sont conservés, et le relancer ne change rien) et crée un `javaguard.yml` commenté avec toutes
   les clés à leur valeur par défaut.
   Elle construit aussi l'archive de démarrage (`app.jsa`, à côté du jar) qui rend chaque hook environ 0,4 s plus rapide.
   L'archive mémorise l'emplacement de la distribution : après avoir déplacé le dossier, exécutez `javaguard optimize --force`
   (ou `install` de nouveau) ; une archive absente ou périmée coûte de la vitesse, jamais de l'exactitude, et `doctor` la signale.
2. **`warmup`** construit le cache de classpath de chaque module qui a des sources (tout le reactor d'un build multimodule,
   une exécution de Maven ou de Gradle par module) afin que les fichiers modifiés soient compilés en processus. Sans lui, la
   barrière post-edit saute la compilation et le dit ; la barrière end-of-turn reconstruit le cache d'elle-même.
3. **`doctor`** vérifie l'environnement d'exécution Java, la configuration, le projet, l'outil de build, le cache de
   classpath, git, les hooks de chaque agent, SpotBugs/FindSecBugs et la configuration du second avis.

Options de `install` (et de `uninstall`, qui ne retire que les entrées de JavaGuard) :

| Option | Signification |
|---|---|
| `--agent claude\|gemini\|codex\|all` | Quel(s) agent(s) configurer (obligatoire) |
| `--scope project\|user` | `project` (par défaut) écrit dans le projet ; `user` écrit dans votre répertoire personnel et s'applique à tous les projets |
| `--pre-edit auto\|always\|never` | Le hook facultatif d'instantané avant modification (voir plus bas). `auto` : uniquement pour les projets sans git |
| `--command <chemin>` | L'exécutable `javaguard` à écrire dans la ligne de commande du hook (par défaut : celui que vous exécutez) |
| `--dry-run` | Affiche la configuration résultante au lieu de l'écrire |

Pour désactiver JavaGuard temporairement sans le désinstaller : définissez `JAVAGUARD_DISABLED=1`, ou créez le fichier
`.javaguard/disabled` dans le projet.

## 3. Les hooks, agent par agent

JavaGuard utilise au plus **quatre hooks** par agent, un par *barrière* (le quatrième, `turn-start`, ne fait qu'enregistrer
un état). Chaque hook exécute la même commande, `javaguard hook --agent <agent> --gate <barrière>` ; l'agent lui transmet sur
**stdin** une description JSON de l'événement et lit la réponse de JavaGuard sur **stdout**/**stderr** et par le code de
sortie. Ce qui diffère d'un agent à l'autre, ce sont seulement les noms d'événements, les noms d'outils à comparer, les
unités du timeout et la façon d'exprimer un « non ».

| Barrière | Déclenchement | Ce que fait JavaGuard | Timeout écrit par `install` | Budget propre de JavaGuard |
|---|---|---|---|---|
| `pre-edit` (facultatif) | Avant que l'agent modifie un fichier | Prend un instantané du fichier tel qu'il est, pour que les barrières suivantes comparent avec lui. Ne parle jamais | 10 s | 2 s |
| `post-edit` | Juste après que l'agent a modifié un fichier | Les vérifications rapides sur ce fichier (section 4) | 30 s | `fastGate.budgetMillis`, 3 s |
| `end-of-turn` | Quand l'agent s'apprête à terminer son tour | Build, tests concernés, analyse approfondie, plus tout ce qui a changé en dehors des outils d'édition | 600 s | `fullGate.budgetSeconds`, 540 s |
| `turn-start` | Quand l'utilisateur envoie un prompt | Enregistre quels fichiers pertinents ont déjà des modifications non validées (voir plus bas). Ne parle jamais | 10 s | un `git status` |

JavaGuard applique son propre budget en dessous du timeout de l'agent, de sorte qu'il répond toujours avant d'être tué ; un
budget épuisé annule les vérifications en cours et rapporte ce qui a été fait, avec une note, mais ne bloque jamais de
lui-même.

Le hook **pre-edit** est facultatif parce que le suivi des changements peut généralement reconstituer l'état antérieur d'un
fichier : à partir de la modification elle-même, ou du `HEAD` de git. Il compte pour les projets *sans* git, c'est pourquoi
`--pre-edit auto` ne l'installe que là.

Le hook **turn-start** est silencieux et coûte quelques millisecondes : au début d'un tour, il enregistre quels fichiers
pertinents ont déjà des modifications non validées, de sorte qu'à la fin du tour ce qu'une commande shell a écrit (`sed`,
`cat >`, le `Set-Content` de PowerShell ; Codex modifie ainsi aussi souvent qu'avec `apply_patch`) se distingue du travail
qui était déjà là. Il n'imprime rien, donc il n'ajoute rien au prompt. Les exemples JSON ci-dessous l'omettent par souci de
brièveté ; `install` l'écrit pour tous les agents. Sans git, il n'y a rien à comparer et les modifications par shell ne sont
pas détectées.

### 3.1 Claude Code

| Barrière | Événement | Matcher |
|---|---|---|
| pre-edit | `PreToolUse` | `Edit\|Write\|MultiEdit` |
| post-edit | `PostToolUse` | `Edit\|Write\|MultiEdit` |
| end-of-turn | `Stop` | (aucun) |
| turn-start | `UserPromptSubmit` | (aucun) |

Fichier écrit : `.claude/settings.json` dans le projet (ou `~/.claude/settings.json` avec `--scope user`). Timeouts en
secondes. Voici ce qu'écrit `javaguard install --agent claude --pre-edit always` (à l'ordre des clés près) :

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Edit|Write|MultiEdit",
        "hooks": [
          {
            "type": "command",
            "command": "\"C:/tools/javaguard/javaguard.exe\" hook --agent claude --gate pre-edit",
            "timeout": 10,
            "statusMessage": "JavaGuard: taking a baseline of the file..."
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "Edit|Write|MultiEdit",
        "hooks": [
          {
            "type": "command",
            "command": "\"C:/tools/javaguard/javaguard.exe\" hook --agent claude --gate post-edit",
            "timeout": 30,
            "statusMessage": "JavaGuard: checking edited Java file..."
          }
        ]
      }
    ],
    "Stop": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "\"C:/tools/javaguard/javaguard.exe\" hook --agent claude --gate end-of-turn",
            "timeout": 600,
            "statusMessage": "JavaGuard: compiling and running affected tests..."
          }
        ]
      }
    ]
  }
}
```

Ce que JavaGuard lit de Claude Code : `session_id`, `transcript_path`, `cwd` et `hook_event_name` ; pour les événements de
modification, l'outil (`Write` : `file_path`, `content` ; `Edit` : `file_path`, `old_string`, `new_string` ; `MultiEdit` :
`file_path` et ses `edits`), et pour `Write` si le fichier a été créé (`tool_response.type`). La racine du projet vient aussi
de `CLAUDE_PROJECT_DIR`. `MultiEdit` reste dans le matcher pour les anciennes versions de Claude Code ; un nom d'outil
inconnu dans un matcher est inoffensif.

### 3.2 Gemini CLI

| Barrière | Événement | Matcher |
|---|---|---|
| pre-edit | `BeforeTool` | `write_file\|replace` |
| post-edit | `AfterTool` | `write_file\|replace` |
| end-of-turn | `AfterAgent` | (aucun) |
| turn-start | `BeforeAgent` | (aucun) |

Fichier écrit : `.gemini/settings.json` (ou `~/.gemini/settings.json`). **Les timeouts sont en millisecondes** dans Gemini
CLI, dont la valeur par défaut est de 60 s - trop courte pour la barrière end-of-turn - c'est pourquoi `install` écrit
toujours une valeur explicite. Chaque hook porte un nom (`javaguard-<barrière>`). **Sous Windows, Gemini CLI exécute les
commandes des hooks via PowerShell**, où un chemin de programme entre guillemets en début de commande n'est qu'une chaîne ;
`install` écrit donc d'abord l'opérateur d'appel (`& "C:/..."`) ; sous macOS et Linux, la commande n'a pas de `&`. L'exemple
ci-dessous est la forme Windows. Si vous avez installé une version antérieure sous Windows, `javaguard doctor` signale
l'ancienne forme et relancer `install` la réécrit. Gemini CLI ne charge en outre les hooks d'un projet que pour un dossier de
confiance : faites-lui confiance une fois en mode interactif, ou définissez `GEMINI_CLI_TRUST_WORKSPACE=true` pour les
exécutions sans surveillance.

```json
{
  "hooks": {
    "BeforeTool": [
      {
        "matcher": "write_file|replace",
        "hooks": [
          {
            "name": "javaguard-pre-edit",
            "type": "command",
            "command": "& \"C:/tools/javaguard/javaguard.exe\" hook --agent gemini --gate pre-edit",
            "timeout": 10000
          }
        ]
      }
    ],
    "AfterTool": [
      {
        "matcher": "write_file|replace",
        "hooks": [
          {
            "name": "javaguard-post-edit",
            "type": "command",
            "command": "& \"C:/tools/javaguard/javaguard.exe\" hook --agent gemini --gate post-edit",
            "timeout": 30000
          }
        ]
      }
    ],
    "AfterAgent": [
      {
        "hooks": [
          {
            "name": "javaguard-end-of-turn",
            "type": "command",
            "command": "& \"C:/tools/javaguard/javaguard.exe\" hook --agent gemini --gate end-of-turn",
            "timeout": 600000
          }
        ]
      }
    ]
  }
}
```

Ce que JavaGuard lit : les champs communs (`session_id`, `transcript_path`, `cwd`, `hook_event_name`) ; pour `write_file`,
`file_path` et `content` ; pour `replace`, `file_path`, `old_string` et `new_string` ; un `AfterTool` dont le
`tool_response` porte une `error` est ignoré (la modification n'a pas eu lieu). La racine du projet vient aussi de
`GEMINI_PROJECT_DIR`. Gemini CLI rejette tout stdout qui n'est pas du JSON ; JavaGuard répond donc toujours avec le code de
sortie 0 et un document JSON.

### 3.3 Codex

| Barrière | Événement | Matcher |
|---|---|---|
| pre-edit | `PreToolUse` | `^apply_patch$` |
| post-edit | `PostToolUse` | `^apply_patch$` |
| end-of-turn | `Stop` | (aucun) |
| turn-start | `UserPromptSubmit` | (aucun) |

Fichier écrit : `.codex/hooks.json` (ou `~/.codex/hooks.json`). Timeouts en secondes. Sous Windows, `install` écrit deux
commandes : un `command` portable (le nom du programme seul) et un `commandWindows` avec le chemin complet que vous avez
donné, précédé de l'opérateur d'appel `&` de PowerShell (Codex l'exécute via PowerShell sous Windows ; sans le `&`, le hook
échoue avec "hook exited with code 1") :

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "^apply_patch$",
        "hooks": [
          {
            "type": "command",
            "command": "javaguard hook --agent codex --gate pre-edit",
            "commandWindows": "& \"C:/tools/javaguard/javaguard.exe\" hook --agent codex --gate pre-edit",
            "timeout": 10,
            "statusMessage": "JavaGuard: taking a baseline..."
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "^apply_patch$",
        "hooks": [
          {
            "type": "command",
            "command": "javaguard hook --agent codex --gate post-edit",
            "commandWindows": "& \"C:/tools/javaguard/javaguard.exe\" hook --agent codex --gate post-edit",
            "timeout": 30,
            "statusMessage": "JavaGuard: checking patch..."
          }
        ]
      }
    ],
    "Stop": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "javaguard hook --agent codex --gate end-of-turn",
            "commandWindows": "& \"C:/tools/javaguard/javaguard.exe\" hook --agent codex --gate end-of-turn",
            "timeout": 600,
            "statusMessage": "JavaGuard: compiling and running affected tests..."
          }
        ]
      }
    ]
  }
}
```

Les hooks de Codex doivent être activés (`[features] hooks = true` dans la configuration de Codex, la valeur par défaut des
versions actuelles) ; `doctor` avertit lorsqu'ils sont désactivés. Codex modifie les fichiers avec l'outil `apply_patch`,
dont l'entrée est un patch et non un chemin de fichier : JavaGuard lit les en-têtes du patch (`*** Add File:`,
`*** Update File:` avec un `*** Move to:` facultatif, `*** Delete File:`) et résout les chemins par rapport à `cwd`. Codex
peut aussi modifier par des commandes shell (`sed`, `cat >`), qui n'atteignent jamais un hook d'édition : la barrière
end-of-turn les trouve en comparant l'arbre de travail avec l'état qu'a enregistré le hook `turn-start` au début du tour
(Codex écrit souvent ses fichiers ainsi, c'est donc plus important ici que pour les autres agents).

### 3.4 Récapitulatif

| | Claude Code | Gemini CLI | Codex |
|---|---|---|---|
| Fichier de configuration | `.claude/settings.json` | `.gemini/settings.json` | `.codex/hooks.json` |
| pre-edit (facultatif) | `PreToolUse` | `BeforeTool` | `PreToolUse` |
| post-edit | `PostToolUse` | `AfterTool` | `PostToolUse` |
| end-of-turn | `Stop` | `AfterAgent` | `Stop` |
| turn-start | `UserPromptSubmit` | `BeforeAgent` | `UserPromptSubmit` |
| Outils d'édition comparés | `Edit\|Write\|MultiEdit` | `write_file\|replace` | `^apply_patch$` |
| Unité du timeout | secondes | **millisecondes** | secondes |
| Dit « non » avec | code de sortie 2 + rapport sur stderr | `{"decision":"deny"}` | `{"decision":"block"}` |
| Variable de la racine du projet | `CLAUDE_PROJECT_DIR` | `GEMINI_PROJECT_DIR` | (utilise `cwd`) |

`--agent` peut être omis en exécutant `hook` à la main : JavaGuard reconnaît alors l'agent d'après le payload. `install` l'écrit
toujours explicitement.

### 3.5 Appeler directement le jar (quand `install` est inutilisable)

`javaguard install` ne fait qu'écrire les entrées de hooks ; les agents exécutent ce qu'ils y trouvent. Si l'installateur ou le lanceur
posent problème (poste restreint, proxy ou VPN exigeant une configuration précise, dossier impossible à ajouter au `PATH`), écrivez les
entrées à la main et appelez le jar via Java. Rien d'autre n'est nécessaire : ni exécutable, ni entrée dans le `PATH`, ni archive de démarrage.

La commande est la même pour tous les hooks ; seuls `--agent` (`claude`, `gemini`, `codex`) et `--gate` (`pre-edit`, `post-edit`,
`end-of-turn`) changent. Utilisez des barres `/` et des guillemets simples autour des chemins.

| Agent | Fichier | `command` |
|---|---|---|
| Gemini CLI (Windows) | `.gemini/settings.json` | `& 'C:/chemin/vers/java.exe' -jar 'C:/chemin/vers/javaguard-1.0.0-all.jar' hook --agent gemini --gate post-edit` |
| Codex (Windows) | `.codex/hooks.json`, dans `commandWindows` | `& 'C:/chemin/vers/java.exe' -jar 'C:/chemin/vers/javaguard-1.0.0-all.jar' hook --agent codex --gate post-edit` |
| Claude Code | `.claude/settings.json` | `java -jar "/chemin/vers/javaguard-1.0.0-all.jar" hook --agent claude --gate post-edit` |

Gemini CLI et Codex exécutent la commande via PowerShell sous Windows ; elle doit donc commencer par l'opérateur d'appel `&` (section 9).
Conservez les délais des entrées générées : 10 s pour `pre-edit`, 30 s pour `post-edit` et 600 s pour `end-of-turn` (en
millisecondes pour Gemini CLI). Exemple pour Gemini CLI :

```json
"AfterTool": [
  {
    "matcher": "write_file|replace",
    "hooks": [
      {
        "name": "javaguard-post-edit",
        "type": "command",
        "command": "& 'C:/tools/java/bin/java.exe' -jar 'C:/tools/javaguard-1.0.0/javaguard-1.0.0-all.jar' hook --agent gemini --gate post-edit",
        "timeout": 30000
      }
    ]
  }
]
```

Gardez `plugins/` à côté du jar pour que SpotBugs fonctionne. Sans l'archive de démarrage, chaque hook prend environ 0,4 s de plus ;
exécutez `javaguard optimize` et ajoutez `-XX:SharedArchiveFile=<dossier>/app.jsa -Xlog:disable` après `java` pour la retrouver. Les entrées écrites ainsi
sont protégées comme celles générées (JG-SUP-003) tant que `hook --agent <agent> --gate <phase>` reste dans la commande.

## 4. Ce que voit l'agent

Chaque barrière se termine par l'un de quatre verdicts :

- **PASS** - rien n'est dit.
- **ADVISE** - des constats bons à connaître ; l'agent poursuit.
- **BLOCK** - des constats bloquants, classés, chacun avec la ligne fautive, le problème et la correction. À la fin d'un tour,
  l'agent continue et les corrige.
- **HALT** - l'agent a été arrêté après avoir échoué encore et encore de la même façon (section 5).

Comment chaque verdict parvient à chaque agent :

| Verdict | Claude Code | Gemini CLI | Codex |
|---|---|---|---|
| PASS | exit 0, aucune sortie | `{}` | exit 0, aucune sortie |
| ADVISE après une modification | `additionalContext` ajouté au contexte du modèle | `additionalContext` ajouté au contexte du modèle | `additionalContext` ajouté au contexte du modèle |
| ADVISE en fin de tour | `systemMessage` : un résumé d'une ligne pour vous | idem | idem |
| BLOCK | exit 2, rapport sur stderr (un `Stop` avec exit 2 garde Claude au travail) | `{"decision":"deny","reason":"<rapport>"}` (en fin de tour, il déclenche un tour de nouvelle tentative) | `{"decision":"block","reason":"<rapport>"}` |
| HALT | `{"continue":false,"stopReason":"..."}` | idem | idem |

Le hook pre-edit ne parle jamais. Les suppressions que l'agent a ajoutées pendant la session (section 8) vous sont toujours
montrées dans un `systemMessage`, même en PASS.

**Quels résultats bloquent ?** Tout résultat grave n'arrête pas l'agent ; c'est la sévérité (section 10) qui décide, et elle dépend du gate :

| Gate | Bloque | Tout le reste |
|---|---|---|
| Après une édition (post-edit) | Résultats BLOCKER, source qui ne se parse pas (`JG-SYN-001`) et résultats d'intégrité des tests (`JG-TST-*`) de CRITICAL ou plus | ADVISE : le modèle est informé, l'agent continue |
| Fin de tour | Erreurs de compilation, une dépendance introuvable (`JG-DEP-003`), résultats de CRITICAL ou plus, résultats d'intégrité des tests de MAJOR ou plus et tests en échec | ADVISE : un résumé d'une ligne pour vous |

Ainsi, une règle CRITICAL comme `JG-SEC-002` (commande construite à partir d'une entrée) n'est qu'un conseil juste après l'édition. Un résultat que
SpotBugs signale en fin de tour porte le rang propre de SpotBugs, qui peut être inférieur à la sévérité du catalogue ; la ligne `Severity` du
rapport complet (`.javaguard/reports/last-report.md`) est celle qui a été appliquée. Pour qu'une règle bloque dans les deux gates, augmentez sa
sévérité dans `javaguard.yml` :

```yaml
rules:
  JG-SEC-002: { severity: BLOCKER }
  JG-PERF-002: { severity: BLOCKER }
```

Le rapport se termine par un paragraphe de consigne pour l'agent : corriger la cause racine et réécrire le code pour que le constat disparaisse
tout en gardant le comportement demandé ; ne pas toucher à la configuration de JavaGuard ni aux tests ; et, quand la seule façon de respecter une règle
contredit ce que vous avez demandé (ou que le constat semble erroné), **s'arrêter et vous l'expliquer en citant la règle, au lieu de réessayer**. Cette
dernière phrase est l'issue face à une règle bloquante que l'agent ne peut pas satisfaire ; s'il échoue malgré tout, l'escalade de la section 5 se termine par un HALT.

Toute exécution autre que PASS écrit aussi un rapport humain complet sous `.javaguard/reports/`, et le message à l'agent est
plafonné (`report.maxFindings`, `report.maxChars` ; les constats bloquants ne sont jamais tronqués). Les formats autres que le
texte pour l'agent - Markdown, JSON, SARIF - sont disponibles avec `javaguard check`.

## 5. Quand l'agent est bloqué

JavaGuard reconnaît un agent qui répète le même changement bloqué, qui oscille entre deux versions ou qui n'avance pas, et
escalade par étapes (seuils dans `loop:`) :

1. **Un conseil de changement d'approche** est ajouté au rapport, propre au type de constat (par exemple : pour une erreur de
   compilation, « lis la classe qui la déclare au lieu de deviner » ; pour une migration modifiée, « restaure-la et écris-en
   une nouvelle »).
2. **Un second avis** (facultatif, `consult.enabled: true`) : un autre modèle - le premier de `consult.order` qui n'est pas
   l'agent courant et qui est dans le `PATH` - est exécuté en arrière-plan avec le document de passation et on lui demande un
   plan. Sa réponse est remise à l'agent une seule fois, à son prochain appel de hook.
3. **HALT** : l'agent est arrêté, vous êtes notifié et `AI_HANDOFF_CONTEXT.md` est écrit à la racine du projet, en sept
   sections - résumé, environnement, le problème, le code source concerné et les diffs, les tentatives jusqu'ici, extraits du
   raisonnement de l'agent et ce qui a été écarté - avec les secrets et les chemins absolus masqués. Donnez-le à un autre
   modèle, ou réglez le problème vous-même. `javaguard state clear --session <id>` repart de zéro.

`javaguard handoff [--session <id>] [--consult claude|gemini|codex]` écrit le même document à la demande.

**Notifications** (`notify:`) : cloche du terminal (Claude Code uniquement), son, notification de bureau (*toast*) et, en
option, une notification push [ntfy](https://ntfy.sh) ; envoyées sur HALT et, si `notify.consultation` est activé, quand une
consultation commence et se termine.

## 6. Commandes

| Commande | Ce qu'elle fait |
|---|---|
| `install`, `uninstall` | Ajouter ou retirer les hooks de JavaGuard (section 2) |
| `hook --agent <a> --gate <g>` | Ce que les agents appellent ; lit l'événement sur stdin |
| `check [fichiers]` | Les vérifications post-edit à la main sur les fichiers ou répertoires donnés, ou sur les fichiers modifiés par rapport à `HEAD` ; `--format text\|md\|json\|sarif`, `--output <fichier>` ; exit 1 sur BLOCK |
| `check --full [fichiers]` | Le pipeline end-of-turn à la main (compilation, tests concernés, analyse approfondie), p. ex. en CI |
| `doctor` | Rapport sur l'environnement et l'installation |
| `warmup` | Construire le cache de classpath de chaque module qui a des sources (tout le reactor d'un build multimodule, une exécution de Maven ou de Gradle par module) |
| `optimize [--force]` | Construire l'archive de démarrage (`app.jsa`) à côté du jar de la distribution empaquetée ; `install` le fait aussi. Avec elle, les hooks démarrent environ 0,4 s plus vite |
| `handoff [--session id] [--consult a]` | Écrire la passation d'une session ; éventuellement interroger un second modèle et attendre sa réponse |
| `rules [--category c]` | Chaque règle avec sa configuration effective |
| `state clear --session id\|--all`, `baseline reset` | Oublier l'état de session ou les références (`baseline reset` accepte aussi la configuration actuelle de JavaGuard comme celle de la session) |

Options globales : `--project-dir <dir>`, `--config <fichier>`, `--verbose` (journalise au niveau FINE dans
`.javaguard/logs/javaguard.log`).

Codes de sortie des commandes manuelles : 0 succès, 1 constats bloquants (`check`), 2 erreur d'usage, 3 configuration
invalide, 4 problème d'environnement, 5 erreur interne. Le mode hook ne les utilise pas : ses codes de sortie relèvent du
protocole de chaque agent (section 3), et une erreur interne sort toujours avec 0.

## 7. Configuration

`javaguard.yml` à la racine du projet (peut être versionné), `~/.javaguard/config.yml` pour vos valeurs par défaut, variables
d'environnement `JAVAGUARD_<SECTION>_<CLÉ>` (p. ex. `JAVAGUARD_FASTGATE_BUDGETMILLIS=5000`) et options de ligne de commande,
par ordre de priorité croissante. Les clés inconnues sont signalées par `doctor` ; les valeurs invalides arrêtent les
commandes manuelles (exit 3) et reviennent aux valeurs par défaut dans les hooks. Chaque clé avec sa valeur par défaut :

```yaml
exclude: ["**/generated/**", "**/target/**", "**/build/**"]   # globs jamais analysés

build:
  tool: auto                 # auto | maven | gradle
  maven: { useDaemon: true, offlineFirst: true, extraArgs: [] }   # offlineFirst vaut aussi pour Gradle
  gradle: { extraArgs: [] }

fastGate:                    # post-edit
  enabled: true
  budgetMillis: 3000
  compileErrors: advise      # advise | block (la barrière end-of-turn bloque toujours dessus)
  inProcessCompile: true

fullGate:                    # end-of-turn
  enabled: true
  budgetSeconds: 540
  maxConsecutiveBlocks: 3

tests:
  scope: affected            # affected | module | all | none
  maxClasses: 50             # au-delà, les suites complètes des modules concernés sont exécutées
  includeIntegration: false
  unrelatedFailures: advise  # advise | block - échecs de tests sans rapport avec le changement

errorprone: { enabled: true }
pmd: { enabled: true }
spotbugs: { enabled: true }

rules:                       # réglages par règle
  JG-PERF-005: { enabled: false }
  JG-AI-001: { severity: INFO, exclude: ["**/cli/**"] }

suppressions:
  allowAgentAdded: true      # false : un javaguard:ignore ajouté par l'agent est lui-même un BLOCKER (JG-SUP-002)

deps:
  osv: { enabled: true, timeoutSeconds: 5 }   # seules les coordonnées sont envoyées à api.osv.dev

api:
  publicPackages: []         # p. ex. ["com.acme.api.**"] : JG-API-001 même sans dépendants dans le reactor

arch:
  rules: []                  # [{ from: "com.acme.domain..", mustNotDependOn: ["com.acme.web.."] }]

report:
  maxFindings: 15
  maxChars: 6000
  ascii: true
  adviseOnPostEdit: true
  sarif: false

loop:                        # détection d'agent bloqué
  repeatThreshold: 3
  oscillationWindow: 6
  noProgressWindow: 5
  maxBlockedRuns: 12
  maxSnapshots: 20

handoff:
  copyToProjectRoot: true    # AI_HANDOFF_CONTEXT.md
  maxFileLines: 400
  maxDiffLines: 300
  transcriptMessages: 6
  redactPatterns: []         # expressions régulières supplémentaires à masquer, en plus des secrets et des chemins absolus

consult:                     # consultation d'un second modèle (coûte des tokens ; opt-in)
  enabled: false
  order: [gemini, codex, claude]   # le premier qui n'est pas l'agent courant et qui est dans le PATH
  timeoutSeconds: 300
  claude: { model: "claude-opus-5-5" }
  commands: {}               # p. ex. gemini: 'gemini -p "<instruction>" --approval-mode plan'

notify:
  terminalBell: true         # Claude Code uniquement
  sound: true
  toast: true
  consultation: false        # notifier aussi quand une consultation commence et se termine
  ntfy: { enabled: false, url: "" }

state:
  retentionDays: 7
```

Une variable d'environnement l'emporte sur la même clé d'un fichier : `JAVAGUARD_FASTGATE_BUDGETMILLIS=60000` prime donc sur un
`fastGate.budgetMillis` de `javaguard.yml`. Sur une machine lente ou partagée (un runner de CI, un conteneur avec peu de CPU),
augmentez `fastGate.budgetMillis` : quand le budget est épuisé, la barrière répond par un résultat partiel et la note
"Fast-gate budget ... exhausted", ce qui est correct mais signifie que certains fichiers n'ont pas été analysés.

`JAVAGUARD_DISABLED=1`, ou un fichier `.javaguard/disabled`, désactive JavaGuard. Tout ce qu'il conserve se trouve sous
`.javaguard/` dans le projet, qui s'ignore lui-même dans git.

## 8. Faux positifs et suppressions (FAQ)

**Un constat est faux. Que dois-je faire (ou l'agent) ?** Ajoutez une suppression avec un motif, sur la ligne ou la ligne
précédente :

```java
// javaguard:ignore JG-SEC-001 -- the column name comes from a fixed enum, never from input
String sql = "SELECT " + column.sqlName() + " FROM orders";
```

ou sur une déclaration : `@SuppressWarnings("javaguard:JG-PERF-001") // built once at startup`. N'importe quel identifiant de
règle convient, y compris `PMD:<règle>`, `EP:<contrôle>` et `SB:<motif>`.

**L'agent peut-il faire taire JavaGuard ainsi ?** Il peut ajouter une suppression, mais vous êtes informé de chaque
suppression ajoutée par la session, à chaque exécution d'une barrière, et une suppression sans motif est elle-même un constat
(JG-SUP-001). Avec `suppressions.allowAgentAdded: false`, une suppression ajoutée par l'agent bloque (JG-SUP-002).

**L'agent peut-il désactiver JavaGuard en modifiant sa configuration ?** Non. Au premier hook d'une session, JavaGuard enregistre votre
`javaguard.yml`, `~/.javaguard/config.yml` et ses propres entrées de hooks dans `.claude/settings.json`, `.gemini/settings.json` et
`.codex/hooks.json`. Pendant le reste de la session, il lit la copie enregistrée de la configuration : une modification ne change donc
rien, et tout changement de ces fichiers est un BLOCKER (JG-SUP-003) tant que l'agent ne les a pas restaurés. Les autres réglages des
fichiers de l'agent (permissions, thème) ne sont pas comparés. Un changement que vous voulez en cours de session s'accepte avec
`javaguard baseline reset` ou en ouvrant une nouvelle session ; modifiez les fichiers avant d'ouvrir la session. Les modifications par
le shell sont détectées en fin de tour.

**Qui désactive une règle ?** Vous seul. Quand la seule issue d'un constat est de changer la configuration (une règle inadaptée,
une sortie console acceptable dans un prototype, une ancienne migration jamais appliquée), le rapport demande à l'agent de s'arrêter et
de vous poser la question au lieu de modifier `javaguard.yml`. Par exemple, pour la sortie console (JG-AI-001) dans un projet sans
logger, l'agent propose d'ajouter SLF4J ou demande si `System.out` convient à ce programme ; dans le second cas, c'est vous qui ajoutez
l'exception.

**Une règle ne convient pas du tout à mon projet.** `rules: { <id>: { enabled: false } }`, ou restreignez-la avec des globs
`exclude`, ou baissez sa `severity` (BLOCKER, CRITICAL, MAJOR, INFO). `javaguard rules` montre la configuration effective.

**Le code hérité est plein de constats.** Seul ce que la session a introduit est signalé : les constats sont comparés au
fichier tel qu'il était avant que la session ne le touche (le `HEAD` de git, ou l'instantané avant modification).

**Un échec de test n'a rien à voir avec mon changement.** Les échecs de tests sans rapport avec les classes modifiées sont
signalés comme "possibly pre-existing" et ne font qu'avertir, sauf si `tests.unrelatedFailures: block`.

**JavaGuard bloque la modification d'une migration qui n'a jamais été déployée.** Une migration Flyway ou Liquibase compte
comme appliquée quand elle existait avant la session et est validée dans git. Sur une branche de fonctionnalité dont les
migrations n'ont encore atteint aucune base partagée, désactivez la règle pour ce chemin
(`rules: { JG-DB-001: { enabled: false } }` tant que la branche est ouverte, ou un glob `exclude` pour le dossier). Un
commentaire de suppression est inutilisable : il changerait le checksum même de la migration.

**Un secret est signalé dans `application-dev.yml`.** Les secrets littéraux sont BLOCKER dans la configuration de production
et MAJOR dans les ressources de test et les profils de développement (`dev`, `local`, `test`, `docker`, `ci`, `sample`...).
Préférez des références `${ENV_VAR}` même là ; le constat ne répète jamais la valeur.

**Une clé de configuration est signalée comme jamais définie, mais l'environnement la fournit.** JG-CFG-002 est
consultatif : les clés peuvent venir de variables d'environnement ou d'un serveur de configuration qu'il ne voit pas. Donnez
au placeholder une valeur par défaut (`${clé:valeur}`) s'il est facultatif, ou désactivez la règle pour le projet.

**Une requête dérivée ou JPQL est signalée.** JG-SPR-015 et JG-SPR-016 ne signalent que ce que le modèle d'entités montre
comme manquant ; ce qu'elles ne peuvent pas résoudre (une entité d'un autre module, un type d'une bibliothèque) est laissé
tranquille. Si l'entité vit dans un autre module du build, le constat signifie que le nom y est inconnu aussi.

## 9. Dépannage

| Symptôme | Que faire |
|---|---|
| `doctor` : « Startup archive ... not built » ou « stale » | Exécutez `javaguard optimize --force`. Les hooks fonctionnent sans elle mais démarrent environ 0,4 s plus lentement ; il faut la reconstruire après avoir déplacé le dossier ou changé de runtime Java. |
| "In-process compilation skipped ... the classpath cache is missing" | Exécutez `javaguard warmup` (ou laissez une exécution end-of-turn la reconstruire). |
| "git refuses to read this repository ... dubious ownership" | Le dépôt appartient à un autre utilisateur (typique dans les conteneurs et les dossiers partagés) ; git ne répond donc pas et les modifications faites par le shell ne peuvent pas être détectées. Si vous lui faites confiance : `git config --global --add safe.directory <chemin>`. `javaguard doctor` affiche le même avertissement. |
| "No Maven executable or wrapper found" | Installez Maven/Gradle ou ajoutez le wrapper (`mvnw`, `gradlew`) ; `doctor` montre ce qui a été trouvé. |
| "Offline dependency resolution failed; retried online" | Normal au premier build d'une machine ; les suivants restent hors ligne. |
| "Maven could not resolve dependencies (no network, or a repository is unreachable)" | Une note d'infrastructure, jamais un blocage : le dépôt n'a pas donné de réponse « introuvable » exploitable (hors ligne, un proxy ou miroir, une erreur de transfert) ; JavaGuard ne peut donc pas affirmer que l'artefact n'existe pas. Corrigez le réseau ou la configuration du miroir et il signalera JG-DEP-003 pour une dépendance réellement hallucinée. |
| "Fast-gate budget (3000 ms) exhausted: N file(s) were not analyzed" | La machine a été trop lente pour le budget post-edit. Augmentez `fastGate.budgetMillis` (section 7). |
| Codex ou Gemini CLI sous Windows : "hook exited with code 1" | La commande du hook doit commencer par l'opérateur d'appel de PowerShell (`& "C:/..."`) car les deux agents exécutent les hooks via PowerShell. `javaguard doctor` détecte l'ancienne forme ; relancez `javaguard install`. |
| javac : "illegal character: '\ufeff'" | Le fichier commence par une marque d'ordre des octets UTF-8 (BOM), que javac rejette (le `Out-File`, le `Set-Content -Encoding UTF8` et le `>` de Windows PowerShell en ajoutent une). Enregistrez-le en UTF-8 sans BOM. Le rapport le dit lui-même. |
| L'agent s'arrête sur un HALT | Lisez `AI_HANDOFF_CONTEXT.md`, donnez-le à un autre modèle ou réglez le problème ; `javaguard state clear --session <id>` repart de zéro. |
| Un hook semble ne rien faire | `javaguard doctor` ; vérifiez `JAVAGUARD_DISABLED` et `.javaguard/disabled` ; exécutez avec `--verbose` et lisez `.javaguard/logs/javaguard.log`. |
| "JavaGuard internal error" dans l'agent | JavaGuard a échoué en ouvert ; le journal a les détails. Merci de le signaler. |
| La ligne SpotBugs de `doctor` avertit | Gardez `plugins/findsecbugs-plugin.jar` à côté du jar ou de l'exécutable. |
| L'antivirus signale `javaguard.exe` | L'exécutable n'est pas signé ; utilisez le zip portable. |

## 10. Règles

[rules_fr.md](rules_fr.md) ([English](rules.md), [Español](rules_es.md)) explique chaque règle en détail : ce qu'elle détecte, pourquoi elle compte en production et comment l'agent doit la corriger.

`javaguard rules` imprime chaque règle avec sa configuration effective. Gravité : B = BLOCKER, C = CRITICAL, M = MAJOR,
I = INFO. Barrière : F = post-edit et end-of-turn, E = end-of-turn seulement. En plus de celles-ci, la barrière post-edit
signale des constats de PMD (`PMD:<règle>`) et d'Error Prone (`EP:<contrôle>`) et des erreurs javac (`JAVAC`) ; la barrière
end-of-turn signale les échecs de tests (`TEST`) et les motifs SpotBugs (`SB:<motif>`, ou l'id JG avec lequel ils se
recoupent). Le catalogue complet, avec ce que détecte chaque règle, se trouve dans la documentation du projet
(`02-RULES-CATALOG.md` de sa spécification). Les titres sont laissés en anglais, tels que les imprime `javaguard rules`.

| Id | Title | Sev | Barrière |
|---|---|---|---|
| JG-SYN-001 | Source does not parse | B | F |
| JG-SEC-001 | SQL built by concatenation (B when a method parameter feeds the SQL text, M when the pieces have no visible origin) | B | F |
| JG-SEC-002 | Command built from variable input | C | F |
| JG-SEC-003 | Unsafe deserialization | C | F |
| JG-SEC-004 | XML parser open to XXE | C | F |
| JG-SEC-005 | Weak cryptography | C | F |
| JG-SEC-006 | Predictable randomness for secrets | M | F |
| JG-SEC-007 | TLS verification disabled | B | F |
| JG-SEC-008 | Hard-coded secret | B | F |
| JG-SEC-009 | Path from request input | M | F |
| JG-SEC-010 | Sensitive data logged | M | F |
| JG-SEC-011 | Web security weakened | M | F |
| JG-SEC-012 | Permissive CORS with credentials | C | F |
| JG-SEC-013 | LDAP/XPath/EL built by concatenation | M | F |
| JG-SEC-014 | Open redirect | M | F |
| JG-SEC-015 | Endpoint without authorization where its siblings have it | M | F |
| JG-PERF-001 | String concatenation in loop | M | F |
| JG-PERF-002 | Regex compiled in loop | M | F |
| JG-PERF-003 | Expensive object created repeatedly | M | F |
| JG-PERF-004 | Unbuffered stream I/O | M | F |
| JG-PERF-005 | Linear lookup in loop | I | F |
| JG-PERF-006 | Head removal on ArrayList in loop | I | F |
| JG-PERF-007 | Boxed accumulator in loop | I | F |
| JG-PERF-008 | Busy wait | M | F |
| JG-PERF-009 | Whole-file/stream materialized | I | F |
| JG-REL-001 | Exception swallowed | M (C for `Exception`/`Throwable`/`RuntimeException`) | F |
| JG-REL-002 | Catching Throwable/Error | M | F |
| JG-REL-003 | Resource not closed | C | F |
| JG-REL-004 | Cause dropped when rethrowing | M | F |
| JG-REL-005 | Unchecked Optional.get | M | F |
| JG-REL-006 | Null returned as Optional/collection | C | F |
| JG-REL-007 | String compared with == | C | F |
| JG-REL-008 | BigDecimal misuse | M | F |
| JG-REL-009 | Floating point for money | M | F |
| JG-REL-010 | Interrupt status lost | M | F |
| JG-REL-011 | Control flow in finally | C | F |
| JG-REL-012 | System.exit in non-main code | M | F |
| JG-REL-013 | equals without hashCode | M | F |
| JG-REL-014 | Mutable static state exposed | M | F |
| JG-REL-015 | toMap without merge function | I | F |
| JG-REL-016 | Unboxing a possibly absent map value | I | F |
| JG-REL-017 | First element of a query result | M | F |
| JG-REL-018 | Exception logged without the exception | M | F |
| JG-REL-019 | Log placeholders do not match | M | F |
| JG-CONC-001 | Thread-unsafe shared formatter | C | F |
| JG-CONC-002 | Mutable state in singleton bean | M | F |
| JG-CONC-003 | Double-checked locking without volatile | C | F |
| JG-CONC-004 | Bad lock object | M | F |
| JG-CONC-005 | Check-then-act on concurrent map | M | F |
| JG-CONC-006 | Unmanaged threads/executors | M | F |
| JG-CONC-007 | wait/notify misuse | M | F |
| JG-CONC-008 | Side effects in parallel stream | C | F |
| JG-CONC-009 | Non-atomic update of shared field | M | F |
| JG-CONC-010 | Lock not released on every path | C | F |
| JG-CONC-011 | ThreadLocal not removed | M | F |
| JG-SPR-001 | Proxy annotation has no effect | C | F |
| JG-SPR-002 | Self-invocation bypasses proxy | M | F |
| JG-SPR-003 | Query inside loop (N+1) | M | F |
| JG-SPR-004 | Eager to-many association | M | F |
| JG-SPR-005 | Load all then filter in memory | M | F |
| JG-SPR-006 | Exception swallowed in transaction | M | F |
| JG-SPR-007 | @Async with non-future return | M | F |
| JG-SPR-008 | Entity equality on generated id / @Data on entity | M | F |
| JG-SPR-009 | Blocking call in reactive pipeline | M | F |
| JG-SPR-010 | Entity exposed/bound directly | I | F |
| JG-SPR-011 | Checked exception without rollback | M | F |
| JG-SPR-012 | Write in a read-only transaction | M | F |
| JG-SPR-013 | javax API in a Jakarta project | C | F |
| JG-SPR-014 | Request body not validated | M | F |
| JG-SPR-015 | Derived query on an unknown property | B | F + E |
| JG-SPR-016 | JPQL on an unknown entity or attribute | C | F + E |
| JG-SPR-017 | Unpaged findAll() returned to the client | M | F |
| JG-DB-001 | Applied migration modified | B | F |
| JG-DB-002 | Migration renamed or deleted | B | F |
| JG-DB-003 | Duplicate migration version | C | F |
| JG-CFG-001 | Secret in configuration | B | F |
| JG-CFG-002 | Configuration key never defined | M | E |
| JG-CFG-003 | Hard-coded host | M | F |
| JG-TST-001 | Test method removed | C | F |
| JG-TST-002 | Test disabled | B | F |
| JG-TST-003 | Assertions removed | C | F |
| JG-TST-004 | Expected value changed | M | F |
| JG-TST-005 | Trivial assertion | C | F |
| JG-TST-006 | Test body emptied | C | F |
| JG-TST-007 | Assertion failure swallowed | C | F |
| JG-TST-008 | Test file deleted | C | F |
| JG-TST-009 | Tests skipped by build config | B | F |
| JG-TST-010 | Assumption added | M | F |
| JG-TST-011 | Timeout greatly increased | I | F |
| JG-TST-012 | Expected-exception test neutralized | M | F |
| JG-TST-013 | Production code detects tests | C | F |
| JG-TST-014 | Class under test mocked | M | F |
| JG-AI-001 | Console output in production code | M | F |
| JG-AI-002 | printStackTrace | M | F |
| JG-AI-003 | Unimplemented stub | C | F |
| JG-AI-004 | Commented-out code added | I | F |
| JG-AI-005 | Placeholder literal | M | F |
| JG-AI-006 | Broad warning suppression added | I | F |
| JG-AI-007 | Unused code added | I | F |
| JG-RES-001 | HTTP client without timeouts | C | F |
| JG-RES-002 | Unbounded wait | M | F |
| JG-RES-003 | Remote call inside a transaction | M | F |
| JG-RES-004 | Remote call in loop | M | F |
| JG-RES-005 | Unbounded thread pool or queue | M (INFO for fixed/single) | F |
| JG-RES-006 | Blocking work on the common pool | M | F |
| JG-DEP-001 | Dependency added | I | F |
| JG-DEP-002 | Known vulnerability | from CVSS | E |
| JG-DEP-003 | Dependency cannot be resolved (possibly hallucinated) | B | E |
| JG-DEP-004 | Unpinned / snapshot version | M | F |
| JG-DEP-005 | Dependency downgraded | M | F |
| JG-API-001 | Public API changed | M | E |
| JG-API-002 | HTTP endpoint removed or moved | M | E |
| JG-API-003 | Endpoint requires a new parameter | M | E |
| JG-API-004 | JSON field renamed or removed | M | E |
| JG-ARCH-001 | Forbidden dependency | M | F |
| JG-SUP-001 | Suppression without reason | M | F |
| JG-SUP-002 | Agent-added suppression (when disallowed) | B | F |
| JG-SUP-003 | Configuration ou hooks de JavaGuard modifiés pendant la session | B | F |

## 11. Licence

JavaGuardCLI est Copyright 2026 Alejandro Alberca Manzaneque, sous licence Apache License, Version 2.0 (`LICENSE`, `NOTICE`).
Les composants tiers inclus conservent leurs propres licences (`THIRD-PARTY.txt`).
