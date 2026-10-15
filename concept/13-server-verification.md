# Проверка выделенного сервера — 15 октября 2026

Среда: Windows 11, Temurin Java 8u504, Forge 10.13.4.1614,
UniMixins 0.3.1, Forgelin 2.1.3; отдельные прогоны с GTNH BetterCrashes 1.4.7-GTNH.
Сервер слушал только `127.0.0.1:25567`, использовался отдельный плоский тестовый мир.
EULA принята с разрешения пользователя для локальной среды проверки.

## Подтверждено

| Сценарий | Результат |
| --- | --- |
| Запуск без BetterCrashes | `Done`, 120 тиков, штатное сохранение мира и остановка; без ложного инцидента |
| Применение миксинов | В логах подтверждены CrashReportMixin, LoadControllerMixin и ServerTickMixin; ClientTickMixin не загружался |
| Явный CrashReport | Сохранены стандартный файл Minecraft и подробный отчёт трейсера |
| Реальный crash серверного тика | Сохранены vanilla crash-report и capture с cause/suppressed; сервер завершился |
| Ошибка FML initialization | Источник `fml-lifecycle`, 4 узла графа, suppressed сохранён |
| Дедупликация FML → CrashReport | Один инцидент, дополнительный источник записан в sources.jsonl |
| BetterCrashes установлен | Запуск, отчёт и реальный tick crash проходят через общие серверные хуки |
| ASM tracing + export | Сервер запускается/останавливается, CHANGED-хеши записаны; после исправления соблюдён лимит ровно 8 экспортов |
| Watchdog | При задержке тика на 16 секунд и пороге 10 секунд создан STALL-инцидент; затем сервер продолжил работу и штатно остановился |
| Диагностический контекст | В отчёте есть Server thread, heap, версии, mods/JAR; сохраняются источники coremods и ранний список tweakers |
| Атрибуция | Тестовая фикстура определяется как подозреваемый мод; инфраструктурные FML/Forge/MCP не выдаются за мод-виновник |
| Standalone CLI | На Java 8 без Minecraft обработал 12 сохранённых инцидентов в 5 групп с methods.csv/fields.csv MCP stable 12 |
| Регрессия | `gradlew build`: 5 тестов, 0 failures/errors |
| Изоляция | Тестовая фикстура отсутствует в release JAR; временный config восстановлен, серверных JVM не осталось |

Количество инцидентов CLI включает предварительные диагностические прогоны до
исправлений, а не только финальные успешные сценарии.

## Исправления по результатам

- Исключён служебный `ThisIsFake` / `Not real`, который создаёт MinecraftForge.initialize.
- Исправлено затирание известных путей coremods значением `unknown`.
- Ранние tweakers сохраняются до очистки Launch.blackboard.
- Отключена автоматическая отдельная Kotlin stdlib в игровом runtime: её предоставляет Forgelin.
  Это устраняет ошибку старого Forge при сканировании Java 9 module-info внешнего Kotlin JAR.
- BetterCrashes CrashUtils-адаптер ограничен клиентом. На сервере `CrashReport.getFile()`
  удалён через SideOnly, поэтому клиентский outputReport там не поддерживается.
- Ранний ASM-адаптер читает maxBytecodeFiles/maxBytecodeBytes из config, а не использует defaults до preInit.
- Инфраструктурные ModContainer MCP/FML/Forge исключены из списка модов-подозреваемых.

## Повторение

При установленном JDK 8 и Gradle JVM на JDK 25:

```shell
./gradlew runServer -PserverSmokeScenario=start
./gradlew runServer -PserverSmokeScenario=report
./gradlew runServer -PserverSmokeScenario=crash
./gradlew runServer -PserverSmokeScenario=fml
./gradlew runServer -PserverSmokeScenario=report -PserverSmokeBetterCrashes=true
./gradlew runServer -PserverSmokeScenario=crash -PserverSmokeBetterCrashes=true
```

Для ASM временно включить experimentalTransformers/bytecodeExport и установить
maxBytecodeFiles=8 в config. Для watchdog: hangThresholdSeconds=10,
watchdogCooldownSeconds=30, затем `-PserverSmokeScenario=hang`.
Фикстура автоматически останавливает здоровый сервер после 120 тиков;
crash/fml специально завершают запуск ошибкой игры.

Фикстура находится в `src/serverSmoke`, подключается только при наличии свойства
serverSmokeScenario и не входит в обычную сборку/публикацию.

Локальные артефакты: `build/server-smoke-*.log`, `build/server-verification.json`,
`build/server-verification-build.log`; игровые файлы — в `run/crash-tracer/`.
Watchdog-инцидент: `1792055451772-a9b197b0-b89b-435e-9e0e-7b6c8bb27692`.

## Не проверено этим проходом

OOM, deadlock, native, production reobf и файловые проверки впоследствии выполнены:
[автономное продолжение](14-headless-verification.md). Ниже зафиксированы границы первого прохода.

Клиентский экран/восстановление BetterCrashes, его клиентский outputReport,
OOM, подтверждённый deadlock, native crash, production reobf-запуск,
долгий прогон большого модпака и полная матрица filesystem/retention.
Сторонняя Forge Version Check выдаёт ошибку чтения сетевого ответа;
это не мешало запуску, и сетевой updater не изменялся.
