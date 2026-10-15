# Crash Tracer

Локальная диагностика Minecraft 1.7.10 / Forge. Основной код — Kotlin, миксины и
независимый ранний bootstrap — Java 8. Mod ID: `crashtracer`.

## Сборка

Gradle 9.7.1 запускается на JDK 25. Для Minecraft-задач нужен установленный JDK 8.

```shell
git submodule update --init --recursive
./gradlew assemble
```

Собираются игровой reobf JAR и `crashtracer-analyzer-<version>-standalone.jar`.
`assemble` не запускает тесты. Параметры сборки — в `impactMod`, версия Forgelin —
в `settings.gradle.kts`. Публикация использует `IMPACT_MAVEN_USER` и
`IMPACT_MAVEN_PASSWORD`; версия берётся из `VERSION` или Git.

Для локального кандидата: задать `VERSION=0.1.0-rc.1` и выполнить `gradlew test build`.
Инструкция установки и ограничения — [RELEASE.md](RELEASE.md);
результат ревью — [concept/17-release-review.md](concept/17-release-review.md).

Среда: UniMixins 0.3.1, Forgelin 2.1.3. Опциональная compileOnly-зависимость —
[GTNH BetterCrashes 1.4.7-GTNH](https://github.com/GTNewHorizons/BetterCrashes/releases/tag/1.4.7-GTNH).
BetterCrashes не включается в JAR трейсера и устанавливается в модпак отдельно.

## Перехват и контекст

Ранний Java-bootstrap создаёт сессию, резервирует память для аварийной записи и
подключает uncaught exception handler с передачей предыдущему обработчику.
Java-миксины отслеживают создание `CrashReport`, ошибки/переходы FML и игровые тики.
Условный клиентский late-миксин BetterCrashes наблюдает `CrashUtils.outputReport`.
На выделенном сервере этот адаптер отключён: outputReport использует клиентский
CrashReport.getFile; общие Minecraft/Forge-хуки продолжают работать.
Обработчик ошибок Mixin сохраняет PREPARE/APPLY failures, не меняя `ErrorAction`.

Загрузчики early/late построены по схеме
[VirtualWorld](https://github.com/GT-IMPACT/VirtualWorld/tree/main/src/main/java/space/gtimpact/virtual_world/mixinplugin).
Они подключают соответствующие JSON; в manifest указан базовый JSON.

Повторные уведомления связываются по идентичности Throwable и его ограниченного
графа cause/suppressed. Отдельные исключения с одинаковым текстом не объединяются.
Дополнительные источники того же инцидента записываются в `sources.jsonl`.

Контекст включает версии/пути модов, размер и mtime JAR, coremods с источниками,
tweakers, трансформеры с источниками, Java/OS/Forge, последнее наблюдаемое состояние
FML, активные измерения, heap statistics и ограниченный thread dump. Из JVM arguments
сохраняются только Xms/Xmx. История содержит фазы, миры, ошибки и WARN/ERROR/FATAL
Log4j; известные формы токенов/паролей в истории логов маскируются.

## Файлы

```text
<game-dir>/crash-tracer/
  config.json
  native-imports.json
  mappings/1.7.10/
    methods.csv
    fields.csv
  sessions/session-<uuid>/
    session.json
    context.json
    events.jsonl
    closed.json
    bytecode/                  # только opt-in режим
  reports/<timestamp>-<uuid>/
    emergency.txt
    early.json
    report.txt
    report.json
    stacktrace.txt
    mods.json
    transformers.json
    mixins.json
    runtime-mixins.json
    threads.txt
    memory.json
    sources.jsonl
    analysis.json
    analysis.txt
    deobfuscated-stacktrace.txt
```

`emergency.txt` и ограниченный граф `early.json` пишутся до Kotlin-конвейера.
При OOM полная диагностика пропускается. При отказе подробного writer остаётся
минимальная запись и, если возможно, `collector-failure.txt`. Данные ранних инцидентов
превращаются в подробные отчёты при последующем анализе.

`report.json` и события имеют `schemaVersion: 1`. Граф исключений хранит численные
ссылки: `root` — верхнее исключение, `rootCause` — конец cause-chain либо `null`
при цикле/обрыве. Массивы событий внутри forensics имеют порядок
`timestamp, kind, target, detail, status`; строки `events.jsonl` используют именованные
поля. Частично записанные JSON не подменяют готовые файлы: writer использует
временный файл и атомарную замену, если файловая система её поддерживает.

## Анализ

JAR/маппинги обрабатываются фоновым worker после сохранения, при следующем запуске
или отдельным CLI. Исходный отчёт сохраняется; выводы идут в `analysis.*`.
Анализ читает ZIP entries, не загружая подозреваемые классы.

Низкая уверенность означает принадлежность класса из стектрейса JAR мода.
Средняя — зарегистрированную FML-ошибку либо сбой трансформера.
Высокая — прямую связь Mixin failure с классом/config конкретного JAR.
Это уверенность в атрибуции наблюдаемого сбоя, а не доказательство первопричины.
Правила дополнительно выделяют OOM, class loading errors, VerifyError, deadlock
и возможные взаимодействия нескольких трансформеров.

Локальные MCP CSV размещаются в `mappings/1.7.10/`; загрузки из сети нет.
Без CSV исходные SRG-имена сохраняются. Источники маппингов перечисляются в анализе.
SHA-256 fingerprint нормализованного стектрейса позволяет группировать инциденты.
При изменении размера/mtime JAR он не используется для подтверждения старой
атрибуции. Одинаковые размер/mtime не гарантируют криптографическую идентичность.

```shell
java -jar crashtracer-analyzer-<version>-standalone.jar <game-dir>/crash-tracer/reports
java -jar crashtracer-analyzer-<version>-standalone.jar <incident-dir> --mappings <csv-dir> --force
```

CLI содержит Kotlin/Gson и работает на Java 8 без Minecraft/Forge. `--force`
перезаписывает только результаты анализа, не исходный capture. Индекс групп —
`analysis-index.json`. Известные старые поля схемы v1 дополняются при чтении;
неизвестная версия схемы отклоняется.

## Конфигурация

Файл создаётся при первом preInit. Изменения применяются после перезапуска.
Неизвестные поля игнорируются; численные значения ограничиваются безопасными
диапазонами. Некорректный файл сохраняется, конвейер использует defaults.

| Параметр | По умолчанию | Назначение |
| --- | --- | --- |
| `enabled` | true | Включение записи |
| `maxIncidentsPerSession` | 32 | Новые инциденты за запуск |
| `maxExceptionNodes` | 64 | Узлы cause/suppressed |
| `maxFramesPerException` | 256 | Кадры на исключение |
| `eventCapacity` | 512 | Кольцевой буфер плюс резерв 128 критичных событий |
| `maxEventsOnDisk` | 10000 | Строки истории за сессию |
| `maxReportBytes` | 16777216 | Общая квота основных файлов инцидента |
| `maxReports` / `maxSessions` | 128 / 16 | Хранимые каталоги |
| `cleanup` | true | Очистка старых записей |
| `threadDump` / `maxThreads` | true / 128 | Снимок потоков |
| `logCapture` | true | WARN/ERROR/FATAL в истории |
| `watchdog` | true | Наблюдение за игровыми тиками и deadlock |
| `hangThresholdSeconds` | 60 | Порог активного тика |
| `watchdogCooldownSeconds` | 300 | Пауза между watchdog-инцидентами |
| `analyzeOnStartup` / `maxPendingReports` | true / 32 | Очередь анализа при запуске |
| `experimentalTransformers` | false | Обёртки текущих ASM-трансформеров |
| `bytecodeExport` | false | Экспорт изменённых классов при включённых обёртках |
| `maxBytecodeFiles` / `maxBytecodeBytes` | 64 / 4194304 | Число экспортов и размер одного класса |

Очистка ограничена каталогами трейсера с признаками его артефактов. Активные сессии
и инциденты защищаются файловыми блокировками; внешние ссылки не обходятся при
удалении. При блокировках квоты числа каталогов могут временно превышаться.

## Режимы и ограничения

По умолчанию Mixin-связи берутся из доступного реестра и error handler.
`REGISTERED` и `ASSOCIATED` не означают успешное применение. Opt-in ASM tracing
добавляет SHA-256 до/после, ошибки и MixinMerged-метаданные изменённых методов.
Экспорт относится к сессии; в инциденте есть идентификатор этой сессии.

Обёртки экспериментальны: другие coremods могут проверять конкретные классы
трансформеров. Трассируются только трансформеры, зарегистрированные к моменту
подключения адаптера в FML preInit. Ранние трансформации не трассируются:
Mixingasm сначала должен увидеть оригинальные классы. Name transformers,
Mixin proxies, диспетчеры цепочки (включая AE2 ApiRepairer) и реализации
с недоступным байткодом сохраняются без обёртки. ADAPTER/SKIPPED означает
ограничение наблюдения; их собственные преобразования напрямую не записываются.

Watchdog не останавливает потоки и не закрывает игру. Он отличает подтверждённый
JVM deadlock от превышения времени активного тика. Внешний debugger или долгий
GC могут вызвать ложное подозрение на зависание.
Для ожидаемых блокировок сохраняются ID владельца и признак его наличия в JVM
на момент снимка. Завершившийся владелец — отдельная подсказка анализа,
а не доказательство циклического deadlock. Старые отчёты без этих полей читаются.
Чужие блокировки автоматически не освобождаются.

Нативные `hs_err_pid*.log` из каталога игры импортируются при здоровом запуске.
Это данные прошлого процесса; текущая сборка могла измениться. Произвольное
принудительное завершение JVM не гарантирует ни Java-отчёта, ни hs_err.
Создание CrashReport также не всегда означает завершение игры.

Проверены запуск выделенного сервера, отчёты, tick/FML crashes, дедупликация,
ASM export cap, watchdog и CLI на Java 8. [Протокол серверной проверки](concept/13-server-verification.md).
Дополнительно проверены OOM, deadlock, native crash/import, production reobf,
реальные Mixin failures, файловые отказы и CLI на 512 отчётах; 37 тестов проходят на Java 8.
[Автономный протокол](concept/14-headless-verification.md).
Клиентские report/crash, восстановление и второй краш без перезапуска, integrated
server crash и watchdog обоих игровых потоков проверены ручным проходом.
Длительная проверка целевого большого модпака остаётся открытой.
[Полная матрица](concept/12-validation-scopes.md).

Для ручных клиентских проверок предусмотрен отдельный тестовый JAR с командами
`/cttest`: [сборка, установка и сценарии](concept/15-client-test-triggers.md).
В основной артефакт эти команды не входят.

Для IMPACT dev-42 подготовлены [20 тест-кейсов](concept/16-impact-modpack-test-cases.md)
с учётом установленных модов и обновления BetterCrashes.
Ручной результат после отдельного исправления GregTech: 17 PASS, три кейса
не выполнены. Исходный FAIL после servercrash из-за GT TICK_LOCK закрыт повтором
на исправленном GT; история сохранена в протоколе. Обычные загрузки и клиентское восстановление
прошли. Кандидат 0.1.0-rc.1 дополнительно прошёл автономные проверки и короткий
клиентский запуск с входом/сохранением/выходом. Полный crash/recovery проход
кандидата повторно не выполнен.
