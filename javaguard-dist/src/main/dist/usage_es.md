# JavaGuardCLI - manual de usuario

> Traducción al español de [USAGE.md](USAGE.md), que es la versión de referencia. Los mensajes que imprime el programa, los
> ids de las reglas y sus títulos, las claves de configuración y los vereditos (`PASS`, `ADVISE`, `BLOCK`, `HALT`) se dejan en
> inglés, tal y como los verás en pantalla.

JavaGuardCLI es una puerta de calidad (*quality gate*) para agentes de IA que programan en Java. Se ejecuta como un **hook**
del agente - un comando que el agente llama por sí mismo alrededor de sus ediciones y al final de su turno - y le dice al
agente, en el protocolo del propio agente, qué falla en lo que acaba de escribir. Los agentes soportados son **Claude Code**,
**Gemini CLI** y **Codex**.

Comprueba en dos pasos:

- **Tras cada edición** (la *puerta post-edit*, alrededor de un segundo): análisis sintáctico, reglas de seguridad y
  fiabilidad, PMD, compilación en proceso con Error Prone, comprobaciones de integridad de los tests (el agente debilitando un
  test para que pase), trampas de Spring/JPA, reglas de resiliencia, secretos en ficheros de configuración y ediciones de
  migraciones de base de datos.
- **Al final del turno del agente** (la *puerta end-of-turn*): compilación con Maven o Gradle de los módulos cambiados y sus
  dependientes, los tests JUnit afectados, SpotBugs + FindSecBugs, comprobaciones de dependencias contra OSV, compatibilidad
  de la API pública y del contrato HTTP, claves de configuración, y todo lo que el agente cambió por el shell, que ningún
  hook de edición puede ver.

Y vigila al propio agente: cuando da vueltas en círculo, JavaGuard le sugiere un cambio de enfoque, opcionalmente consulta a
un segundo modelo y, por último, lo detiene con un documento de relevo (*handoff*) que otro modelo (o tú) puede retomar.

Cuatro garantías: solo informa de lo que introdujo la sesión (los hallazgos heredados no se achacan al agente); nunca
modifica tu código; nunca ejecuta un comando de git que cambie el estado; y **falla en abierto** (*fail-open*): si JavaGuard
se rompe, el agente sigue trabajando.

## Contenido

1. [Instalación](#1-instalación)
2. [Configurar un proyecto](#2-configurar-un-proyecto)
3. [Los hooks, agente por agente](#3-los-hooks-agente-por-agente)
4. [Lo que ve el agente](#4-lo-que-ve-el-agente)
5. [Cuando el agente se atasca: escalado, relevo, segunda opinión, notificaciones](#5-cuando-el-agente-se-atasca)
6. [Comandos](#6-comandos)
7. [Configuración](#7-configuración)
8. [Falsos positivos y supresiones (FAQ)](#8-falsos-positivos-y-supresiones-faq)
9. [Resolución de problemas](#9-resolución-de-problemas)
10. [Reglas](#10-reglas)
11. [Licencia](#11-licencia)

## 1. Instalación

**Windows** - `javaguard-<version>-windows-x64.zip`: descomprímelo donde quieras y mantén los ficheros juntos
(`javaguard.exe`, `runtime\`, `javaguard-<version>-all.jar`, `plugins\`). No hace falta instalar Java.
Instálalo en una ruta corta: Windows no puede iniciar un programa cuya ruta completa tenga 260 caracteres o más, así que
mantén la carpeta por debajo de unos 200 caracteres. Los espacios y los caracteres con tilde en la ruta no dan problema.

**macOS, Linux o Windows sin el exe** - `javaguard-<version>-portable.zip`: necesita Java 21 o superior (`JAVA_HOME` o `java`
en el `PATH`). Usa `javaguard` (macOS/Linux) o `javaguard.cmd` (Windows). El script `javaguard` debe ser ejecutable
(`chmod +x javaguard` si tu herramienta de descompresión perdió el permiso). Linux está probado; macOS aún no se ha ejecutado
a mano, y un zip descargado con el navegador puede llevar el atributo de cuarentena (`xattr -dr com.apple.quarantine <carpeta>`).

Pon la carpeta en el `PATH`, o pasa la ruta completa a `--command` al instalar los hooks. Comprueba con
`javaguard --version`.

Bajo el locale POSIX (`LANG=C`, habitual en contenedores y cron) la JVM no puede manejar una ruta con acentos; el script
`javaguard` cambia a un locale `C.UTF-8` para su propio proceso cuando el sistema lo tiene.

El proyecto que se comprueba necesita Maven o Gradle (un ejecutable o el wrapper) para la puerta end-of-turn; la puerta
post-edit no necesita más que las fuentes.

## 2. Configurar un proyecto

Desde la raíz del proyecto:

```
javaguard install --agent claude --command C:\tools\javaguard\javaguard.exe     # Windows
javaguard install --agent claude --command /opt/javaguard/javaguard             # Linux, macOS
javaguard warmup
javaguard doctor
```

1. **`install`** mezcla los hooks de JavaGuard en el fichero de configuración del agente (tras una copia de seguridad; tus
   propios hooks y ajustes se conservan, y volver a ejecutarlo no cambia nada) y crea un `javaguard.yml` comentado con todas
   las claves en su valor por defecto.
   También construye el archivo de arranque (`app.jsa`, junto al jar) que hace que cada hook arranque unos 0,4 s más rápido. El
   archivo recuerda dónde está la distribución, así que tras mover la carpeta ejecuta `javaguard optimize --force` (o `install`
   de nuevo); un archivo ausente u obsoleto cuesta velocidad, nunca corrección, y `doctor` lo indica.
2. **`warmup`** construye la caché de classpath de cada módulo que tiene fuentes (todo el reactor de una compilación
   multimódulo, una ejecución de Maven o Gradle por módulo) para que los ficheros editados se compilen en proceso. Sin ella la
   puerta post-edit omite la compilación y lo dice; la puerta end-of-turn reconstruye la caché por sí misma.
3. **`doctor`** comprueba el entorno de ejecución de Java, la configuración, el proyecto, la herramienta de compilación, la
   caché de classpath, git, los hooks de cada agente, SpotBugs/FindSecBugs y la configuración de la segunda opinión.

Opciones de `install` (y de `uninstall`, que elimina solo las entradas de JavaGuard):

| Opción | Significado |
|---|---|
| `--agent claude\|gemini\|codex\|all` | Qué agente(s) configurar (obligatorio) |
| `--scope project\|user` | `project` (por defecto) escribe dentro del proyecto; `user` escribe en tu directorio personal y vale para todos los proyectos |
| `--pre-edit auto\|always\|never` | El hook opcional de instantánea previa a la edición (ver más abajo). `auto`: solo en proyectos sin git |
| `--command <ruta>` | El ejecutable `javaguard` que se escribe en la línea de comandos del hook (por defecto: el que estás ejecutando) |
| `--dry-run` | Muestra la configuración resultante en lugar de escribirla |

Para apagar JavaGuard temporalmente sin desinstalarlo: define `JAVAGUARD_DISABLED=1`, o crea el fichero
`.javaguard/disabled` en el proyecto.

## 3. Los hooks, agente por agente

JavaGuard usa como máximo **cuatro hooks** por agente, uno por *puerta* (la cuarta, `turn-start`, solo registra estado).
Todos los hooks ejecutan el mismo comando, `javaguard hook --agent <agente> --gate <puerta>`; el agente le pasa por **stdin**
una descripción JSON del evento y lee la respuesta de JavaGuard por **stdout**/**stderr** y el código de salida. Lo que
cambia entre agentes es solo el nombre de los eventos, los nombres de las herramientas que se comparan, las unidades del
timeout y la forma de expresar un «no».

| Puerta | Cuándo se dispara | Qué hace JavaGuard | Timeout que escribe `install` | Presupuesto propio de JavaGuard |
|---|---|---|---|---|
| `pre-edit` (opcional) | Antes de que el agente edite un fichero | Toma una instantánea del fichero tal como está, para que las puertas posteriores comparen con ella. Nunca habla | 10 s | 2 s |
| `post-edit` | Justo después de que el agente editó un fichero | Las comprobaciones rápidas sobre ese fichero (sección 4) | 30 s | `fastGate.budgetMillis`, 3 s |
| `end-of-turn` | Cuando el agente está a punto de terminar su turno | Compilación, tests afectados, análisis profundo, y todo lo cambiado fuera de las herramientas de edición | 600 s | `fullGate.budgetSeconds`, 540 s |
| `turn-start` | Cuando el usuario envía un prompt | Registra qué ficheros relevantes ya tienen cambios sin confirmar (ver más abajo). Nunca habla | 10 s | un `git status` |

JavaGuard aplica su propio presupuesto por debajo del timeout del agente, de modo que siempre responde antes de que lo maten;
un presupuesto que se agota cancela las comprobaciones en curso e informa de lo hecho, con una nota, pero nunca bloquea por sí
mismo.

El hook **pre-edit** es opcional porque el rastreador de cambios suele poder reconstruir cómo era un fichero antes: a partir de
la propia edición o del `HEAD` de git. Importa en proyectos *sin* git, y por eso `--pre-edit auto` lo instala solo ahí.

El hook **turn-start** es silencioso y cuesta unos milisegundos: cuando empieza un turno registra qué ficheros relevantes ya
tienen cambios sin confirmar, de modo que al final del turno lo que escribió un comando de shell (`sed`, `cat >`, el
`Set-Content` de PowerShell; Codex edita así tantas veces como con `apply_patch`) se distingue del trabajo que ya estaba
ahí. No imprime nada, así que no añade nada al prompt. Los ejemplos JSON siguientes lo omiten por brevedad; `install` lo
escribe para todos los agentes. Sin git no hay con qué comparar y las ediciones por shell no se detectan.

### 3.1 Claude Code

| Puerta | Evento | Matcher |
|---|---|---|
| pre-edit | `PreToolUse` | `Edit\|Write\|MultiEdit` |
| post-edit | `PostToolUse` | `Edit\|Write\|MultiEdit` |
| end-of-turn | `Stop` | (ninguno) |
| turn-start | `UserPromptSubmit` | (ninguno) |

Fichero escrito: `.claude/settings.json` en el proyecto (o `~/.claude/settings.json` con `--scope user`). Timeouts en
segundos. Esto es lo que escribe `javaguard install --agent claude --pre-edit always` (salvo el orden de las claves):

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

Lo que JavaGuard lee de Claude Code: `session_id`, `transcript_path`, `cwd` y `hook_event_name`; para los eventos de edición,
la herramienta (`Write`: `file_path`, `content`; `Edit`: `file_path`, `old_string`, `new_string`; `MultiEdit`: `file_path` y
sus `edits`), y para `Write` si el fichero fue creado (`tool_response.type`). La raíz del proyecto también viene de
`CLAUDE_PROJECT_DIR`. `MultiEdit` se queda en el matcher para versiones antiguas de Claude Code; un nombre de herramienta
desconocido en un matcher es inofensivo.

### 3.2 Gemini CLI

| Puerta | Evento | Matcher |
|---|---|---|
| pre-edit | `BeforeTool` | `write_file\|replace` |
| post-edit | `AfterTool` | `write_file\|replace` |
| end-of-turn | `AfterAgent` | (ninguno) |
| turn-start | `BeforeAgent` | (ninguno) |

Fichero escrito: `.gemini/settings.json` (o `~/.gemini/settings.json`). **Los timeouts van en milisegundos** en Gemini CLI,
cuyo valor por defecto es 60 s - demasiado corto para la puerta end-of-turn - así que `install` siempre escribe un valor
explícito. Cada hook tiene nombre (`javaguard-<puerta>`). **En Windows, Gemini CLI ejecuta los comandos de los hooks a través
de PowerShell**, donde una ruta de programa entre comillas al principio de un comando es solo una cadena, así que `install`
escribe primero el operador de llamada (`& "C:/..."`); en macOS y Linux el comando no lleva `&`. El ejemplo siguiente es la
forma de Windows. Si instalaste una compilación anterior en Windows, `javaguard doctor` avisa de la forma antigua y volver a
ejecutar `install` la reescribe. Gemini CLI además carga los hooks de un proyecto solo en una carpeta de confianza: confía en
ella una vez en modo interactivo, o define `GEMINI_CLI_TRUST_WORKSPACE=true` en ejecuciones desatendidas.

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

Lo que JavaGuard lee: los campos comunes (`session_id`, `transcript_path`, `cwd`, `hook_event_name`); para `write_file`,
`file_path` y `content`; para `replace`, `file_path`, `old_string` y `new_string`; un `AfterTool` cuyo `tool_response`
lleva un `error` se ignora (la edición no ocurrió). La raíz del proyecto también viene de `GEMINI_PROJECT_DIR`. Gemini CLI
rechaza cualquier stdout que no sea JSON, así que JavaGuard siempre responde con código de salida 0 y un documento JSON.

### 3.3 Codex

| Puerta | Evento | Matcher |
|---|---|---|
| pre-edit | `PreToolUse` | `^apply_patch$` |
| post-edit | `PostToolUse` | `^apply_patch$` |
| end-of-turn | `Stop` | (ninguno) |
| turn-start | `UserPromptSubmit` | (ninguno) |

Fichero escrito: `.codex/hooks.json` (o `~/.codex/hooks.json`). Timeouts en segundos. En Windows `install` escribe dos
comandos: un `command` portable (el nombre del programa a secas) y un `commandWindows` con la ruta completa que diste,
precedido del operador de llamada `&` de PowerShell (Codex lo ejecuta a través de PowerShell en Windows; sin el `&` el hook
falla con "hook exited with code 1"):

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

Los hooks de Codex deben estar habilitados (`[features] hooks = true` en la configuración de Codex, el valor por defecto en
las versiones actuales); `doctor` avisa cuando están apagados. Codex edita ficheros con la herramienta `apply_patch`, cuya
entrada es un parche, no una ruta de fichero: JavaGuard lee las cabeceras del parche (`*** Add File:`, `*** Update File:` con
un `*** Move to:` opcional, `*** Delete File:`) y resuelve las rutas contra `cwd`. Codex también puede editar mediante
comandos de shell (`sed`, `cat >`), que nunca llegan a un hook de edición: la puerta end-of-turn los encuentra comparando el
árbol de trabajo con el estado que registró el hook `turn-start` cuando empezó el turno (Codex escribe a menudo sus ficheros
así, de modo que aquí importa más que con los otros agentes).

### 3.4 Resumen

| | Claude Code | Gemini CLI | Codex |
|---|---|---|---|
| Fichero de configuración | `.claude/settings.json` | `.gemini/settings.json` | `.codex/hooks.json` |
| pre-edit (opcional) | `PreToolUse` | `BeforeTool` | `PreToolUse` |
| post-edit | `PostToolUse` | `AfterTool` | `PostToolUse` |
| end-of-turn | `Stop` | `AfterAgent` | `Stop` |
| turn-start | `UserPromptSubmit` | `BeforeAgent` | `UserPromptSubmit` |
| Herramientas de edición que se comparan | `Edit\|Write\|MultiEdit` | `write_file\|replace` | `^apply_patch$` |
| Unidad del timeout | segundos | **milisegundos** | segundos |
| Dice «no» con | código de salida 2 + informe por stderr | `{"decision":"deny"}` | `{"decision":"block"}` |
| Variable de la raíz del proyecto | `CLAUDE_PROJECT_DIR` | `GEMINI_PROJECT_DIR` | (usa `cwd`) |

`--agent` puede omitirse al ejecutar `hook` a mano: JavaGuard reconoce entonces el agente por el payload. `install` siempre
lo escribe de forma explícita.

### 3.5 Llamar al jar directamente (cuando no se puede usar `install`)

`javaguard install` solo escribe las entradas de hooks; los agentes ejecutan lo que encuentren ahí. Si el instalador o el lanzador dan
problemas (un equipo restringido, un proxy o una VPN que exigen una configuración concreta, una carpeta que no puedes añadir al `PATH`),
escribe las entradas a mano y llama al jar a través de Java. No hace falta nada más: ni ejecutable, ni entrada en el `PATH`, ni archivo de arranque.

El comando es el mismo para todos los hooks; solo cambian `--agent` (`claude`, `gemini`, `codex`) y `--gate` (`pre-edit`, `post-edit`,
`end-of-turn`). Usa barras `/` y comillas simples alrededor de las rutas.

| Agente | Fichero | `command` |
|---|---|---|
| Gemini CLI (Windows) | `.gemini/settings.json` | `& 'C:/ruta/a/java.exe' -jar 'C:/ruta/a/javaguard-1.0.0-all.jar' hook --agent gemini --gate post-edit` |
| Codex (Windows) | `.codex/hooks.json`, en `commandWindows` | `& 'C:/ruta/a/java.exe' -jar 'C:/ruta/a/javaguard-1.0.0-all.jar' hook --agent codex --gate post-edit` |
| Claude Code | `.claude/settings.json` | `java -jar "/ruta/a/javaguard-1.0.0-all.jar" hook --agent claude --gate post-edit` |

Gemini CLI y Codex ejecutan el comando a través de PowerShell en Windows, así que debe empezar por el operador de llamada `&` (sección 9).
Conserva los tiempos de espera de las entradas generadas: 10 s para `pre-edit`, 30 s para `post-edit` y 600 s para `end-of-turn` (en
milisegundos en Gemini CLI). Ejemplo para Gemini CLI:

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

Mantén `plugins/` junto al jar para que funcione SpotBugs. Sin el archivo de arranque cada hook tarda unos 0,4 s más; ejecuta
`javaguard optimize` y añade `-XX:SharedArchiveFile=<carpeta>/app.jsa -Xlog:disable` después de `java` para recuperarlo. Las entradas escritas así
quedan protegidas igual que las generadas (JG-SUP-003) mientras `hook --agent <agente> --gate <fase>` siga en el comando.

## 4. Lo que ve el agente

Cada puerta termina en uno de cuatro vereditos:

- **PASS** - no se dice nada.
- **ADVISE** - hallazgos que conviene conocer; el agente sigue adelante.
- **BLOCK** - hallazgos bloqueantes, ordenados, cada uno con la línea ofensiva, el problema y la solución. Al final de un
  turno hace que el agente continúe y los corrija.
- **HALT** - el agente fue detenido tras fallar una y otra vez del mismo modo (sección 5).

Cómo llega cada veredicto a cada agente:

| Veredicto | Claude Code | Gemini CLI | Codex |
|---|---|---|---|
| PASS | exit 0, sin salida | `{}` | exit 0, sin salida |
| ADVISE tras una edición | `additionalContext` añadido al contexto del modelo | `additionalContext` añadido al contexto del modelo | `additionalContext` añadido al contexto del modelo |
| ADVISE al final del turno | `systemMessage`: un resumen de una línea para ti | igual | igual |
| BLOCK | exit 2, informe por stderr (un `Stop` con exit 2 mantiene a Claude trabajando) | `{"decision":"deny","reason":"<informe>"}` (al final del turno provoca un turno de reintento) | `{"decision":"block","reason":"<informe>"}` |
| HALT | `{"continue":false,"stopReason":"..."}` | igual | igual |

El hook pre-edit nunca habla. Las supresiones que el agente añadió durante la sesión (sección 8) siempre se te muestran en un
`systemMessage`, incluso en PASS.

**¿Qué hallazgos bloquean?** No todo hallazgo grave detiene al agente; decide la severidad (sección 10) y depende del gate:

| Gate | Bloquea | Todo lo demás |
|---|---|---|
| Tras una edición (post-edit) | Hallazgos BLOCKER, código que no se parsea (`JG-SYN-001`) y hallazgos de integridad de tests (`JG-TST-*`) de CRITICAL o superior | ADVISE: se informa al modelo y el agente sigue |
| Fin de turno | Errores de compilación, una dependencia que no se resuelve (`JG-DEP-003`), hallazgos de CRITICAL o superior, hallazgos de integridad de tests de MAJOR o superior y tests que fallan | ADVISE: un resumen de una línea para ti |

Así, una regla CRITICAL como `JG-SEC-002` (comando construido con una entrada) es sólo un consejo justo después de la edición. Un hallazgo que
SpotBugs informa al final del turno lleva el rango propio de SpotBugs, que puede ser menor que la severidad del catálogo; la línea `Severity`
del informe completo (`.javaguard/reports/last-report.md`) es la que se aplicó. Para que una regla bloquee en ambos gates, sube su severidad
en `javaguard.yml`:

```yaml
rules:
  JG-SEC-002: { severity: BLOCKER }
  JG-PERF-002: { severity: BLOCKER }
```

El informe termina con un párrafo de guía para el agente: arreglar la causa raíz y reescribir el código para que el hallazgo desaparezca
manteniendo el comportamiento pedido; no tocar la configuración de JavaGuard ni los tests; y, cuando la única forma de cumplir una regla
contradice lo que pediste (o el hallazgo parece erróneo), **parar y explicártelo citando la regla, en vez de reintentar**. Esa última frase
es la salida ante una regla bloqueante que el agente no puede satisfacer; si aun así sigue fallando, la escalada de la sección 5 acaba en un HALT.

Toda ejecución que no sea PASS escribe además un informe humano completo en `.javaguard/reports/`, y el mensaje al agente
tiene un tope (`report.maxFindings`, `report.maxChars`; los hallazgos bloqueantes nunca se recortan). Los formatos distintos
del texto para el agente - Markdown, JSON, SARIF - están disponibles en `javaguard check`.

## 5. Cuando el agente se atasca

JavaGuard reconoce a un agente que repite el mismo cambio bloqueado, que oscila entre dos versiones o que no avanza, y escala
en pasos (umbrales en `loop:`):

1. **Una pista de cambio de enfoque** se añade al informe, específica del tipo de hallazgo (por ejemplo: para un error de
   compilación, «lee la clase que lo declara en lugar de adivinar»; para una migración editada, «restáurala y escribe una
   nueva»).
2. **Una segunda opinión** (opcional, `consult.enabled: true`): otro modelo - el primero de `consult.order` que no sea el
   agente actual y esté en el `PATH` - se ejecuta en segundo plano con el documento de relevo y se le pide un plan. Su
   respuesta se entrega al agente una sola vez, en su siguiente llamada de hook.
3. **HALT**: el agente se detiene, se te notifica y se escribe `AI_HANDOFF_CONTEXT.md` en la raíz del proyecto, en siete
   secciones - resumen, entorno, el problema, el código fuente relevante y los diffs, los intentos hasta ahora, extractos del
   razonamiento del agente y lo que se ha descartado - con los secretos y las rutas absolutas ocultos. Dáselo a otro modelo,
   o arregla el problema tú. `javaguard state clear --session <id>` empieza de nuevo.

`javaguard handoff [--session <id>] [--consult claude|gemini|codex]` escribe el mismo documento bajo demanda.

**Notificaciones** (`notify:`): campana del terminal (solo Claude Code), sonido, aviso de escritorio (*toast*) y, opcionalmente,
una notificación push de [ntfy](https://ntfy.sh); se envían en HALT y, si `notify.consultation` está activo, cuando una
consulta empieza y termina.

## 6. Comandos

| Comando | Qué hace |
|---|---|
| `install`, `uninstall` | Añadir o quitar los hooks de JavaGuard (sección 2) |
| `hook --agent <a> --gate <g>` | Lo que llaman los agentes; lee el evento por stdin |
| `check [ficheros]` | Las comprobaciones post-edit a mano sobre los ficheros o directorios dados, o sobre los ficheros cambiados respecto a `HEAD`; `--format text\|md\|json\|sarif`, `--output <fichero>`; exit 1 en BLOCK |
| `check --full [ficheros]` | El pipeline end-of-turn a mano (compilación, tests afectados, análisis profundo), p. ej. en CI |
| `doctor` | Informe del entorno y de la instalación |
| `warmup` | Construir la caché de classpath de cada módulo que tiene fuentes (todo el reactor de una compilación multimódulo, una ejecución de Maven o Gradle por módulo) |
| `optimize [--force]` | Construir el archivo de arranque (`app.jsa`) junto al jar de la distribución empaquetada; `install` también lo hace. Con él los hooks arrancan unos 0,4 s más rápido |
| `handoff [--session id] [--consult a]` | Escribir el relevo de una sesión; opcionalmente preguntar a un segundo modelo y esperar su respuesta |
| `rules [--category c]` | Cada regla con su configuración efectiva |
| `state clear --session id\|--all`, `baseline reset` | Olvidar el estado de sesión o las líneas base (`baseline reset` también acepta la configuración actual de JavaGuard como la de la sesión) |

Opciones globales: `--project-dir <dir>`, `--config <fichero>`, `--verbose` (registra a nivel FINE en
`.javaguard/logs/javaguard.log`).

Códigos de salida de los comandos manuales: 0 éxito, 1 hallazgos bloqueantes (`check`), 2 error de uso, 3 configuración
inválida, 4 problema de entorno, 5 error interno. El modo hook no los usa: sus códigos de salida pertenecen al protocolo de
cada agente (sección 3), y un error interno siempre sale con 0.

## 7. Configuración

`javaguard.yml` en la raíz del proyecto (se puede versionar), `~/.javaguard/config.yml` para tus valores por defecto,
variables de entorno `JAVAGUARD_<SECCIÓN>_<CLAVE>` (p. ej. `JAVAGUARD_FASTGATE_BUDGETMILLIS=5000`) y opciones de línea de
comandos, en orden creciente de precedencia. Las claves desconocidas las informa `doctor`; los valores inválidos detienen los
comandos manuales (exit 3) y vuelven a los valores por defecto en los hooks. Cada clave con su valor por defecto:

```yaml
exclude: ["**/generated/**", "**/target/**", "**/build/**"]   # globs que nunca se analizan

build:
  tool: auto                 # auto | maven | gradle
  maven: { useDaemon: true, offlineFirst: true, extraArgs: [] }   # offlineFirst también vale para Gradle
  gradle: { extraArgs: [] }

fastGate:                    # post-edit
  enabled: true
  budgetMillis: 3000
  compileErrors: advise      # advise | block (la puerta end-of-turn siempre bloquea por ellos)
  inProcessCompile: true

fullGate:                    # end-of-turn
  enabled: true
  budgetSeconds: 540
  maxConsecutiveBlocks: 3

tests:
  scope: affected            # affected | module | all | none
  maxClasses: 50             # por encima, se ejecutan las suites completas de los módulos afectados
  includeIntegration: false
  unrelatedFailures: advise  # advise | block - fallos en tests con los que el cambio no se relaciona

errorprone: { enabled: true }
pmd: { enabled: true }
spotbugs: { enabled: true }

rules:                       # ajustes por regla
  JG-PERF-005: { enabled: false }
  JG-AI-001: { severity: INFO, exclude: ["**/cli/**"] }

suppressions:
  allowAgentAdded: true      # false: un javaguard:ignore que añada el agente es en sí un BLOCKER (JG-SUP-002)

deps:
  osv: { enabled: true, timeoutSeconds: 5 }   # solo se envían coordenadas a api.osv.dev

api:
  publicPackages: []         # p. ej. ["com.acme.api.**"]: JG-API-001 incluso sin dependientes en el reactor

arch:
  rules: []                  # [{ from: "com.acme.domain..", mustNotDependOn: ["com.acme.web.."] }]

report:
  maxFindings: 15
  maxChars: 6000
  ascii: true
  adviseOnPostEdit: true
  sarif: false

loop:                        # detección de agente atascado
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
  redactPatterns: []         # expresiones regulares extra a ocultar, además de secretos y rutas absolutas

consult:                     # consulta a un segundo modelo (cuesta tokens; opt-in)
  enabled: false
  order: [gemini, codex, claude]   # el primero que no sea el agente actual y esté en el PATH
  timeoutSeconds: 300
  claude: { model: "claude-opus-5-5" }
  commands: {}               # p. ej. gemini: 'gemini -p "<instruction>" --approval-mode plan'

notify:
  terminalBell: true         # solo Claude Code
  sound: true
  toast: true
  consultation: false        # notificar también cuando una consulta empieza y termina
  ntfy: { enabled: false, url: "" }

state:
  retentionDays: 7
```

Una variable de entorno sustituye a la misma clave de un fichero, de modo que `JAVAGUARD_FASTGATE_BUDGETMILLIS=60000` gana a
un `fastGate.budgetMillis` de `javaguard.yml`. En una máquina lenta o compartida (un runner de CI, un contenedor con poca CPU)
sube `fastGate.budgetMillis`: cuando el presupuesto se agota, la puerta responde con un resultado parcial y la nota
"Fast-gate budget ... exhausted", lo cual es correcto pero significa que algunos ficheros no se analizaron.

`JAVAGUARD_DISABLED=1`, o un fichero `.javaguard/disabled`, apaga JavaGuard. Todo lo que guarda está bajo `.javaguard/` en el
proyecto, que se ignora a sí mismo en git.

## 8. Falsos positivos y supresiones (FAQ)

**Un hallazgo es erróneo. ¿Qué hago yo (o el agente)?** Añade una supresión con un motivo, en la línea o en la anterior:

```java
// javaguard:ignore JG-SEC-001 -- the column name comes from a fixed enum, never from input
String sql = "SELECT " + column.sqlName() + " FROM orders";
```

o sobre una declaración: `@SuppressWarnings("javaguard:JG-PERF-001") // built once at startup`. Sirve cualquier id de regla,
incluidos `PMD:<regla>`, `EP:<comprobación>` y `SB:<patrón>`.

**¿Puede el agente silenciar a JavaGuard así?** Puede añadir una supresión, pero se te informa de cada supresión que añade la
sesión, cada vez que se ejecuta una puerta, y una sin motivo es en sí un hallazgo (JG-SUP-001). Con
`suppressions.allowAgentAdded: false` una supresión añadida por el agente bloquea (JG-SUP-002).

**¿Puede el agente desactivar JavaGuard editando su configuración?** No. En el primer hook de una sesión JavaGuard registra tu
`javaguard.yml`, `~/.javaguard/config.yml` y sus propias entradas de hooks en `.claude/settings.json`, `.gemini/settings.json` y
`.codex/hooks.json`. Durante el resto de la sesión lee la copia registrada de la configuración, así que una edición no cambia nada, y
cualquier cambio en esos ficheros es un BLOCKER (JG-SUP-003) hasta que el agente los restaura. Los demás ajustes de los ficheros del
agente (permisos, tema) no se comparan. Un cambio tuyo que sí quieras a mitad de sesión se acepta con `javaguard baseline reset` o
iniciando una sesión nueva; edita los ficheros antes de abrir la sesión. Las ediciones por shell se detectan al final del turno.

**¿Quién desactiva una regla?** Solo tú. Cuando la única salida de un hallazgo es cambiar la configuración (una regla que no
encaja, salida por consola aceptable en un prototipo, una migración antigua que nunca se aplicó), el informe le dice al agente que pare y
te pregunte en vez de editar `javaguard.yml`. Por ejemplo, para la salida por consola (JG-AI-001) en un proyecto sin logger, el agente
ofrece añadir SLF4J o pregunta si `System.out` vale para este programa; en el segundo caso eres tú quien añade la excepción.

**Una regla no encaja en absoluto con mi proyecto.** `rules: { <id>: { enabled: false } }`, o restríngela con globs
`exclude`, o baja su `severity` (BLOCKER, CRITICAL, MAJOR, INFO). `javaguard rules` muestra la configuración efectiva.

**El código heredado está lleno de hallazgos.** Solo se informa de lo que introdujo la sesión: los hallazgos se comparan con
el fichero tal como estaba antes de que la sesión lo tocara (el `HEAD` de git, o la instantánea previa a la edición).

**Un fallo de test no tiene nada que ver con mi cambio.** Los fallos en tests no relacionados con las clases cambiadas se
informan como "possibly pre-existing" y solo avisan, salvo que `tests.unrelatedFailures: block`.

**JavaGuard bloquea la edición de una migración que nunca se desplegó.** Una migración de Flyway o Liquibase cuenta como
aplicada cuando existía antes de la sesión y está confirmada en git. En una rama de funcionalidad cuyas migraciones aún no han
llegado a ninguna base de datos compartida, desactiva la regla para esa ruta (`rules: { JG-DB-001: { enabled: false } }`
mientras la rama esté abierta, o un glob `exclude` para la carpeta). No se puede usar un comentario de supresión: cambiaría
el propio checksum de la migración.

**Se informa de un secreto en `application-dev.yml`.** Los secretos literales son BLOCKER en la configuración de producción y
MAJOR en los recursos de test y en los perfiles de desarrollo (`dev`, `local`, `test`, `docker`, `ci`, `sample`...). Es
preferible usar referencias `${ENV_VAR}` incluso ahí; el hallazgo nunca repite el valor.

**Se informa de una clave de configuración nunca definida, pero el entorno la proporciona.** JG-CFG-002 es solo consultivo:
las claves pueden venir de variables de entorno o de un servidor de configuración que no puede ver. Dale al placeholder un
valor por defecto (`${clave:valor}`) si es opcional, o desactiva la regla para el proyecto.

**Se informa de una consulta derivada o JPQL.** JG-SPR-015 y JG-SPR-016 solo informan de lo que el modelo de entidades
muestra que falta; lo que no pueden resolver (una entidad de otro módulo, un tipo de una biblioteca) se deja en paz. Si la
entidad vive en otro módulo de la compilación, el hallazgo significa que el nombre también es desconocido allí.

## 9. Resolución de problemas

| Síntoma | Qué hacer |
|---|---|
| `doctor`: "Startup archive ... not built" u "obsoleto" | Ejecuta `javaguard optimize --force`. Los hooks funcionan sin él pero arrancan unos 0,4 s más lentos; hay que reconstruirlo tras mover la carpeta o cambiar el runtime de Java. |
| "In-process compilation skipped ... the classpath cache is missing" | Ejecuta `javaguard warmup` (o deja que una ejecución end-of-turn la reconstruya). |
| "git refuses to read this repository ... dubious ownership" | El repositorio pertenece a otro usuario (típico en contenedores y carpetas compartidas), así que git no responde y las ediciones hechas por shell no se pueden detectar. Si confías en él: `git config --global --add safe.directory <ruta>`. `javaguard doctor` muestra el mismo aviso. |
| "No Maven executable or wrapper found" | Instala Maven/Gradle o añade el wrapper (`mvnw`, `gradlew`); `doctor` muestra lo que se encontró. |
| "Offline dependency resolution failed; retried online" | Normal en la primera compilación de una máquina; las siguientes se quedan sin conexión. |
| "Maven could not resolve dependencies (no network, or a repository is unreachable)" | Una nota de infraestructura, nunca un bloqueo: el repositorio no dio una respuesta de «no encontrado» utilizable (sin conexión, un proxy o espejo, un error de transferencia), así que JavaGuard no puede afirmar que el artefacto no exista. Arregla la red o la configuración del espejo y informará de JG-DEP-003 ante una dependencia realmente alucinada. |
| "Fast-gate budget (3000 ms) exhausted: N file(s) were not analyzed" | La máquina fue demasiado lenta para el presupuesto post-edit. Sube `fastGate.budgetMillis` (sección 7). |
| Codex o Gemini CLI en Windows: "hook exited with code 1" | El comando del hook debe empezar con el operador de llamada de PowerShell (`& "C:/..."`) porque ambos agentes ejecutan los hooks a través de PowerShell. `javaguard doctor` detecta la forma antigua; ejecuta `javaguard install` de nuevo. |
| javac: "illegal character: '\ufeff'" | El fichero empieza con una marca de orden de bytes UTF-8 (BOM), que javac rechaza (el `Out-File`, el `Set-Content -Encoding UTF8` y el `>` de Windows PowerShell la añaden). Guárdalo como UTF-8 sin BOM. El informe ya lo dice. |
| El agente se detiene con un HALT | Lee `AI_HANDOFF_CONTEXT.md`, dáselo a otro modelo o arregla el problema; `javaguard state clear --session <id>` empieza de nuevo. |
| Un hook parece no hacer nada | `javaguard doctor`; comprueba `JAVAGUARD_DISABLED` y `.javaguard/disabled`; ejecuta con `--verbose` y lee `.javaguard/logs/javaguard.log`. |
| "JavaGuard internal error" en el agente | JavaGuard falló en abierto; el log tiene los detalles. Por favor, infórmalo. |
| La fila de SpotBugs de `doctor` avisa | Mantén `plugins/findsecbugs-plugin.jar` junto al jar o al ejecutable. |
| El antivirus marca `javaguard.exe` | El ejecutable no está firmado; usa el zip portable. |

## 10. Reglas

`javaguard rules` imprime cada regla con su configuración efectiva. Severidad: B = BLOCKER, C = CRITICAL, M = MAJOR,
I = INFO. Puerta: F = post-edit y end-of-turn, E = solo end-of-turn. Además de estas, la puerta post-edit informa de hallazgos
de PMD (`PMD:<regla>`) y de Error Prone (`EP:<comprobación>`) y de errores de javac (`JAVAC`); la puerta end-of-turn informa
de fallos de test (`TEST`) y de patrones de SpotBugs (`SB:<patrón>`, o el id JG con el que se solapan). El catálogo completo,
con lo que detecta cada regla, está en la documentación del proyecto (`02-RULES-CATALOG.md` de su especificación). Los
títulos se dejan en inglés, tal y como los imprime `javaguard rules`.

| Id | Title | Sev | Puerta |
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
| JG-SUP-003 | Configuración o hooks de JavaGuard cambiados durante la sesión | B | F |

## 11. Licencia

JavaGuardCLI es Copyright 2026 Alejandro Alberca Manzaneque, con licencia Apache License, Version 2.0 (`LICENSE`, `NOTICE`).
Los componentes de terceros incluidos conservan sus propias licencias (`THIRD-PARTY.txt`).
