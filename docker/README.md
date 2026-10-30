# Сборка desktop-установщиков в Docker

Собирает Linux-установщики (`.deb` / `.rpm`) локально, независимо от GitHub CI, в том же
окружении, в котором их собирает [release workflow](../.github/workflows/desktop-release.yml).
Windows и macOS собрать в Linux-контейнере невозможно: jpackage не умеет кросс-компиляцию —
это те таргеты, ради которых в CI существуют отдельные runner'ы.

## Что в образе и почему

| Компонент | Почему |
|---|---|
| `ubuntu:22.04` | Лаунчер, который генерирует jpackage, линкуется против glibc сборщика: собранное на новой glibc не встанет на старые дистрибутивы. CI сознательно собирает Linux на ubuntu-22.04 — образ повторяет это (glibc 2.35). |
| Amazon Corretto 21 | `gradle/gradle-daemon-jvm.properties` пинует JVM демона на `vendor=AMAZON`, `version=21`, и этот criteria важнее `JAVA_HOME`. jpackage Compose Desktop берётся из JVM демона (см. комментарий в `tools/build-macos-intel.sh`). Другой vendor заставил бы Gradle молча скачать ~200 MB JDK при первом холодном билде, для каждой архитектуры отдельно. |
| `rpm`, `fakeroot` | Те же два пакета, которые ставит release workflow: `rpmbuild` для `.rpm`, `fakeroot` для `.deb`. `dpkg-deb` уже есть в ubuntu. |

Android SDK в образе нет — он не нужен для desktop-сборки (CI делает то же самое). Нативный
libgit2 тоже не нужен: desktop использует JGit и bundled sqlite (нативный libgit2 — только iOS).

## Предусловия

- Docker + compose plugin (проверено на Docker 26 / Compose v2.27).
- **arm64 на x86_64-хосте** — разово установить эмуляцию QEMU (после перезагрузки — повторить):
  ```sh
  docker run --privileged --rm tonistiigi/binfmt --install arm64
  ```
- ~7–10 GB RAM на билд (`gradle.properties`: Gradle `-Xmx4096M` + kotlin daemon `-Xmx3072M`),
  и ~2–4 GB диска на кэш-том каждой архитектуры.

## Быстрый старт

```sh
tools/docker-build.sh               # x64:  dist/GitWriter-<version>-linux-x86_64.{deb,rpm}
tools/docker-build.sh arm64         # arm64: dist/GitWriter-<version>-linux-arm64.{deb,rpm}
tools/docker-build.sh all           # обе архитектуры, последовательно
```

Или напрямую через compose (тогда задайте uid/gid сами, чтобы файлы остались вашими):

```sh
GITWRITER_UID="$(id -u)" GITWRITER_GID="$(id -g)" docker compose run --rm build-x64
```

На SELinux-системах (Fedora) экспортируйте ещё `COMPOSE_MOUNT_OPTS=:Z` — иначе контейнеру
запретят писать в bind-маунты.

## Что ещё можно запускать

Любая gradle-команда проходит насквозь — то же окружение, что у сборки:

```sh
tools/docker-build.sh -- :shared:jvmTest        # тесты
tools/docker-build.sh -- :desktopApp:packageDeb # только .deb
tools/docker-build.sh x64 shell                 # интерактивный bash внутри окружения
tools/docker-build.sh -- ./gradlew --stop       # убить демон (если что-то зависло)
```

Обёртка без аргументов = release-сборка. Файлы в `dist/` называются по схеме release workflow:
`GitWriter-<version>-linux-x86_64.<ext>` / `GitWriter-<version>-linux-arm64.<ext>`, где версия
берётся из `app.version.number` в `gradle.properties` — единого источника версии.

## Как это устроено

- Репозиторий **bind-маунтится** в контейнер (`/workspace`), образ содержит только тулчейн:
  ничего из репо не попадает в образ, артефакты сразу оказываются на хосте.
- Сборка идёт от uid/gid хостового пользователя (`user:` в compose) — файлы в репо и `dist/`
  остаются вашими, root-owned мусора не остаётся.
- Кэш Gradle (`wrapper`, зависимости, configuration cache) живёт в **named-томах** —
  по одному на архитектуру (`gradle-cache-x86_64` / `gradle-cache-arm64`): демон, provisioned-JDK
  и нативы skiko арх-специфичны. Второй и последующие билды значительно быстрее первого.
- `--project-cache-dir /cache/project` (см. `docker/entrypoint.sh`) уводит configuration cache
  из репо в том — контейнерные и хостовые сборки не делят это состояние.
- Две архитектуры нельзя запускать параллельно: оба сервиса делят одно дерево репо.

## Troubleshooting

- **«no such file or directory» при запуске arm64** — не установлена QEMU-эмуляция, см. предусловия.
- **Permission denied в `/workspace` или `/out`** — SELinux: `COMPOSE_MOUNT_OPTS=:Z`; либо
  `dist/` создан docker'ом от root — удалите и дайте создать обёртке.
- **Что-то зависло в daemon-состоянии** — `tools/docker-build.sh -- ./gradlew --stop`, а если
  совсем плохо — сбросить кэш архитектуры:
  ```sh
  docker volume rm git-writer-desktop-build_gradle-cache-x86_64   # и/или ..._gradle-cache-arm64
  ```
- **В логе первого билда мелькает «Downloading» JDK** — criteria демона не сматчил локальный
  Corretto (например, вы заменили базовый образ). Работает, но каждый холодный старт тянет ~200 MB.
- **`apt.corretto.aws` недоступен** — можно заменить базу на `eclipse-temurin:21-jdk-jammy`
  (мультиарх, тоже jammy): сборка заработает, но первый билд скачает Corretto в кэш-том, см. выше.
- **arm64 под QEMU падает по памяти** — хосту нужно больше RAM; можно попробовать
  `tools/docker-build.sh arm64 -- -Dorg.gradle.jvmargs=-Xmx2g`.
