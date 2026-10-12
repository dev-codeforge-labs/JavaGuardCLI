Real output of Gradle 8.14.3 (`gradle -q --console=plain ...`) on the `gradle-simple` fixture, captured 2026-10-06:

- `compile-error`: `--offline testClasses` after replacing the return value with an undefined variable. `@ROOT@` stands for the project directory.
- `dependency-missing`: online `testClasses` with `com.javaguard.fake:nothing:9.9.9` added.
- `dependency-offline`: the same, `--offline`.
- `selection-failure`: `:nosuch:testClasses`.

Gradle prints a compile error twice: once as javac does, once indented inside "What went wrong".
