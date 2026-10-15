# Автономная проверка без клиента — 15 октября 2026

Продолжение [серверного прохода](13-server-verification.md). Среда та же:
Windows 11, Java 8u504, Forge 10.13.4.1614, UniMixins 0.3.1, Forgelin 2.1.3.
Gradle работает на JDK 25; JUnit, сервер и standalone CLI — на Java 8.

После этого прохода основные клиентские сценарии проверены с пользователем:
[report, восстановление, повторные crashes и watchdog](15-client-test-triggers.md).
Ниже сохранены результаты и границы именно автономного прохода.

## Результаты

| Проверка | Подтверждение |
| --- | --- |
| Реальный OOM | Heap сервера ограничен 256 МБ, память удерживается до настоящего Java heap space. Во время исчерпания сохранены emergency.txt и early.json; после освобождения тестовых массивов сервер продолжил работу. Следующий запуск восстановил report.json и анализ с признаком OOM |
| Подтверждённый deadlock | Два daemon-потока взаимно удерживали ReentrantLock. Watchdog обнаружил JVM deadlock, записал обоих участников, lock и lockOwner. Фикстура через 20 секунд прервала ожидания и штатно остановила сервер |
| Настоящий native crash | Отдельная серверная JVM аварийно завершена через Unsafe. JVM записала hs_err_pid83980.log; следующий здоровый запуск импортировал его, создал анализ, повторные запуски не дублировали импорт |
| Production mappings | runObfServer загружает основной reobf JAR, Forge universal и obfuscated Minecraft. Проверены запуск, CrashReport, tick crash и FML initialization crash; применяются три серверных миксина |
| Mixin InjectionError | Отсутствующая точка INVOKE вызвала настоящую критическую ошибку до preInit. Ранний перехват LaunchWrapper сохранил ClassNotFoundException → MixinTransformerError → InjectionError. ExitTrappedException связан с исходным инцидентом |
| Mixin APPLY failure | Отсутствующий целевой метод вызвал InvalidInjectionException. Сохранены APPLY_FAILED, имя миксина, config и target; Mixin handler / LaunchWrapper / uncaught связаны с одним инцидентом |
| Последующее восстановление | После ранних Mixin crashes здоровый запуск восстановил подробные отчёты и локальный анализ. Если контекст до preInit не был собран, владельцы модов остаются неизвестными |
| enabled=false | Сервер создал обычный Minecraft CrashReport и штатно остановился; новые сессии и инциденты трейсера не появились |
| Совместный прогон | 1200 тиков / около минуты с BetterCrashes, ASM tracing и bytecode export: шесть отдельных отчётов, штатная остановка, лимит 8 экспортов соблюдён |
| CLI | Java 8, Xmx128m, 512 отчётов → 2 fingerprint-группы; первый проход около 1,05 с. Проверены повторный запуск и --force с локальными MCP mappings. SHA-256 исходных report.json не изменились |
| История логов | В настоящем сервере захвачены WARN обычного логгера и ERROR Minecraft-логгера с additivity=false; fake Bearer/password отсутствуют в записанной истории |
| Регрессия | 30 JUnit-тестов на Java 8, без failures/errors/skips; полная сборка проходит |
| Артефакты | Основной JAR содержит bootstrap, runtime, миксины, manifest и refmap; тестовая фикстура отдельно, bytecode игровых классов Java 8 |

Минутный прогон не заменяет длительную проверку большого модпака.
OOM-проверка подтверждает аварийную запись в конкретном воспроизведённом случае,
но не гарантирует выполнение Java-кода при любом исчерпании ресурсов.

## Файловые и модельные проверки

JUnit проверяет UTF-8 byte quota, сохранность предыдущего файла при отказе записи,
удаление временного файла, восстановление после прерванной записи на границе квоты,
повреждённый full report при целом early capture, совместимость старой схемы v1
и отказ от перезаписи неизвестной будущей схемы.

Проверены retention с активной файловой блокировкой, защита текущей сессии,
сохранность посторонних каталогов, пропуск заблокированного pending report и повтор
после unlock. Реальный Windows junction не приводит к удалению внешних файлов
или записи native-отчёта во внешний каталог. Native import учитывает блокировку
другого writer, повторный импорт и ограничение размера hs_err.

Отдельные JVM проверяют отключённый bootstrap, недоступный путь хранилища,
конкурентный capture из 12 потоков и квоты инцидентов/кольца/истории на диске.
Также проверены циклические, глубокие и широкие exception graphs, character budget,
CSV с кавычками, неизвестные mappings, устойчивость fingerprints к строкам/сообщениям,
общий JAR нескольких модов и отказ от атрибуции изменённого JAR.
REGISTERED не повышает уверенность до HIGH; наблюдённый APPLY_FAILED повышает.

ASM-тесты подтверждают сохранение идентичности результата, обнаружение изменения
массива на месте, хеши до/после, null input/output и повторный выброс исходного
VerifyError. Mixin handler сохраняет исходный ErrorAction для PREPARE/APPLY.
Маскирование проверено на совместной строке Authorization: Bearer и именованных секретах.

Физическое заполнение диска не выполнялось: отказы проверяются квотой записи,
непригодным destination и блокировками.

## Исправлено по результатам

- OOM reserve освобождается до выделений при обходе графа и дедупликации.
- Повреждённый report.json больше не блокирует восстановление из early.json;
  неизвестная будущая версия схемы сохраняется без перезаписи.
- Остаток заменяемого .partial не расходует бюджет его повторной записи.
- Raw stream закрывается независимо от writer: Java 8 при ошибке flush мог
  оставить дескриптор открытым, мешая удалению временного файла на Windows.
- Native import использует межпроцессную и incident-блокировки, проверяет ссылки/junction.
- Bearer маскируется до assignment matching, который раньше оставлял токен
  после замены слова Bearer в Authorization.
- История подключается также к non-additive logger configs: Minecraft WARN/ERROR
  раньше обходили root appender и не попадали в события.
- Ранний Java-адаптер сохраняет fatal LaunchWrapper до замены ошибки на trapped exit;
  используется родительский Log4j context LaunchWrapper.
- Отключённый runtime пропускает сбор контекста и запуск фонового анализа.
- Reobf-фикстура получила отдельное имя JAR и больше не пересекается с основным артефактом.
- JUnit по умолчанию запускается на целевой Java 8.

## Повторение

```shell
./gradlew build
./gradlew runServer -PserverSmokeScenario=oom
./gradlew runServer -PserverSmokeScenario=deadlock
./gradlew runServer -PserverSmokeScenario=native
./gradlew runServer -PserverSmokeScenario=mixin
./gradlew runServer -PserverSmokeScenario=mixin-apply
./gradlew runServer -PserverSmokeScenario=early-fml
./gradlew runServer -PserverSmokeScenario=start
./gradlew runServer -PserverSmokeScenario=logs
./gradlew runObfServer -PserverSmokeScenario=report
./gradlew runObfServer -PserverSmokeScenario=crash
./gradlew runObfServer -PserverSmokeScenario=fml
./gradlew runServer -PserverSmokeScenario=soak -PserverSmokeBetterCrashes=true
```

Для совместного прогона временно включались experimentalTransformers/bytecodeExport,
maxBytecodeFiles=8. После проверки config восстановлен. Deadlock не требует
уменьшения hangThresholdSeconds. Native и оба Mixin-сценария намеренно завершают
Gradle-задачу ошибкой; критерием служат исходная ошибка и сохранённые артефакты.
Напротив, Forge может вернуть код 0 после tick/FML crash — одного BUILD SUCCESSFUL недостаточно.

runObfServer использует отдельный run/obfuscated с локальными server.properties и EULA.
Native crash выполняется только в отдельной тестовой JVM; minidump и диалог JVM отключены.
Никаких изменений системных настроек Windows не требуется.

Локальные доказательства: build/autonomous-final.log, build/autonomous-verification.json,
build/server-smoke-*.log, build/cli-stress-results.json и build/test-results/test/.
Серверные отчёты находятся в run/crash-tracer/ и run/obfuscated/crash-tracer/.

## Что остаётся за пределами прохода

Клиентские экраны и восстановление BetterCrashes, integrated server, реальные конфликты
конкретного большого модпака, длительные утечки/производительность, аппаратные/драйверные
native crashes, физический disk-full и иные ОС/JVM. Синтетические ошибки не доказывают
совместимость со всеми сторонними coremods.
