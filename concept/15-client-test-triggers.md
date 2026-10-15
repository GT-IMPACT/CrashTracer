# Как вызывать клиентские тесты

Триггеры поставляются отдельным модом `crashtracer_clienttest`. Основной Crash Tracer
не регистрирует аварийных команд. Клиентские `report`, `crash`, восстановление,
повторный вход и второй краш в той же JVM проверены ручным запуском.

## Подтверждённый клиентский report — 15 октября 2026

Пользователь вызвал `/cttest report` в IDEA runClient с BetterCrashes 1.4.7-GTNH.
В 15:18:23 сохранён стандартный `crashtracer-client-1792066703860.txt` и один инцидент
`1792066703849-e5daf668-e80b-4f86-9dd6-caa3b576cb39`.
Источник — minecraft-crash-report, поток — Client thread, общая фаза — SERVER_STARTED
(одиночный мир с integrated server). В графе три узла: RuntimeException →
IllegalArgumentException и suppressed IllegalStateException. Сохранены 24 потока,
heap, клиентский/серверные миры и инвентарь модов. Фоновый анализ завершился через
две секунды; тестовая фикстура определена с LOW по владению классом. Дублей нет.

При проверке исправлены два побочных дефекта: старые hs_err больше не подмешиваются
ко всем Java-инцидентам (для них остаётся отдельный native import), служебный
SplashProgress report для печати характеристик компьютера исключён из capture.
Сохранённый первоначальный отчёт оставлен без изменения; исправления действуют
после перезапуска клиента. Отдельная ошибка OpenAL в этом запуске не относится
к команде report.

## Подтверждённый клиентский crash — 15 октября 2026

В 15:23:07 пользователь вызвал `/cttest crash` после перезапуска IDEA runClient.
Сохранён один инцидент `1792066987349-19fb5987-d624-4408-b7eb-2c8f0ebe5148`
и стандартный `crash-2026-10-15_15.23.07-client.txt`. В report.json сохранены
RuntimeException, cause IllegalArgumentException и suppressed IllegalStateException;
поток Client thread, 23 потока в снимке, heap и контекст integrated server.
Анализ завершён, тестовый мод определён с LOW по владению классом.

Лог подтверждает применение CrashUtilsMixin и вызов BetterCrashes.outputReport.
Источники minecraft-crash-report и bettercrashes-output связаны через sources.jsonl
с одним инцидентом; записан DUPLICATE/LINKED. Клиентский счётчик BetterCrashes — 1,
серверный — 0. Integrated server сохранил мир и штатно остановился.

Исправления предыдущего прохода подтвердились: служебного SplashProgress-инцидента
в этой сессии нет; старый hs_err не приложен к новому Java-отчёту.
Отдельный uncaught OpenAL при запуске не является дубликатом тестового краша.
Экран восстановления и успешный повторный вход в мир по одним логам не подтверждены.
Машинные проверки сохранены в build/client-crash-verification.json.

## Подтверждённое восстановление и второй crash

После первого краша пользователь вернулся в мир без перезапуска клиента:
повторный запуск integrated server и вход игрока записаны в 15:25:38.
В 15:25:40 повторён `/cttest crash`. Второй инцидент —
`1792067140680-d7695658-d963-406d-b583-3851568050fa`; sessionId совпадает с первым:
`session-4d2844d9-231b-4995-a04a-b67c39b97b77`.

BetterCrashes записал второй обычный client crash-report, счётчик Client Crashes
Since Restart вырос с 1 до 2, Integrated Server Crashes остался 0. Два разных
Throwable с одинаковым содержимым дали два отдельных инцидента; для каждого
bettercrashes-output связан с minecraft-crash-report без дополнительного capture.
Граф cause/suppressed сохранён в обоих случаях, анализы готовы, fingerprints
совпадают. Это подтверждает восстановление и повторный сбор после возврата в мир.
Машинные проверки — build/client-recovery-verification.json.

## Подтверждённый integrated server crash

В той же клиентской сессии после нового входа в мир пользователь вызвал
`/cttest servercrash` в 15:26:44. Сохранён отдельный инцидент
`1792067204412-6884d44e-0d35-4d1f-9a98-12a218992b0a`: поток Server thread,
фаза SERVER_STARTED, RuntimeException с cause и suppressed, снимок 29 потоков.
Анализ завершён, тестовая фикстура определена с LOW. Fingerprint отличается
от клиентского crash, поскольку отличается стек и точка отказа.

Minecraft сохранил `crash-2026-10-15_15.26.44-server.txt`, integrated server
сохранил мир и остановился. BetterCrashes на Client thread обработал тот же отчёт;
bettercrashes-output связан с исходным инцидентом без дублирования. Счётчики:
Client Crashes Since Restart=2, Integrated Server Crashes Since Restart=1.
Машинные проверки — build/integrated-server-crash-verification.json.

## Подтверждённая задержка клиентского тика

После перезапуска с hangThresholdSeconds=10 и watchdogCooldownSeconds=30 пользователь
вызвал `/cttest hang` в 15:29:58. В 15:30:12 watchdog сохранил инцидент
`1792067412831-5cb0d2fc-5305-40fc-9d57-70b99c08b554` с source=watchdog и событием
STALL для Client thread. В снимке Client thread находится в TIMED_WAITING,
стек содержит Thread.sleep и ClientSmokeMod.hang. Это превышение порога тика,
а не JVM deadlock. Анализ завершён. После 16-секундной задержки клиент продолжил
работу; в 15:30:18 integrated server обработал обычную паузу и сохранение мира.
Машинные проверки — build/client-hang-verification.json.

## Подтверждённая задержка integrated server

В той же сессии пользователь вызвал `/cttest serverhang` в 15:32:09.
В 15:32:22 создан инцидент `1792067542980-9e48ddb6-45cb-4124-98c3-a3a3017e8c62`:
source=watchdog, WATCHDOG/STALL для Server thread. В снимке Server thread находится
в TIMED_WAITING со стеком Thread.sleep → ClientSmokeMod.hang → serverTick;
deadlocked=false. Анализ завершён, тестовая фикстура определена по стеку.

В 15:32:25 сервер продолжил тики и сообщил задержку 16052 мс; в 15:32:27 выполнил
обычную паузу и сохранение мира. Клиентский и серверный hang дали отдельные
инциденты в одной сессии. Машинные проверки — build/integrated-server-hang-verification.json.
После прохода параметры watchdog в config.json восстановлены из сохранённой копии:
hangThresholdSeconds=60, watchdogCooldownSeconds=300. Работающая JVM сохраняет
тестовые значения до следующего запуска.

## Обычный лаунчер

Собрать:

```powershell
.\gradlew.bat reobfClientSmokeJar -PclientSmoke=true
```

Положить `build/libs/crashtracer-<version>-client-smoke-fixture-reobf.jar` в `mods`
рядом с основным Crash Tracer, Forgelin и UniMixins. Для проверки восстановления
добавить GTNH BetterCrashes. Перезапустить игру и открыть отдельный тестовый мир.
Фикстура предназначена для клиента; на выделенный сервер её устанавливать не нужно.

## Запуск через Gradle

Обычная Gradle-задача `runClient`, в том числе из IDEA, автоматически подключает
тестовую фикстуру и BetterCrashes:

```powershell
.\gradlew.bat runClient
```

Без BetterCrashes:

```powershell
.\gradlew.bat runClient -PclientSmokeBetterCrashes=false
```

Для чистого запуска без тестовых команд: `runClient -PclientSmoke=false`.
При обычной сборке `build` фикстура не подключается. Для отдельной сборки её JAR
по-прежнему требуется `-PclientSmoke=true`.

## Команды в игровом чате

Читы не требуются: `/cttest` — клиентская команда, она не отправляется внешнему серверу.

| Команда | Действие |
| --- | --- |
| `/cttest report` | Создать и сохранить CrashReport без намеренного завершения игры |
| `/cttest crash` | Выбросить RuntimeException из следующего клиентского тика |
| `/cttest bettercrashes` | Создать отчёт и явно вызвать BetterCrashes CrashUtils.outputReport; требуется BetterCrashes |
| `/cttest servercrash` | Выбросить ошибку из тика integrated server; только одиночный мир |
| `/cttest hang` | Задержать клиентский тик на 16 секунд |
| `/cttest serverhang` | Задержать тик integrated server на 16 секунд; только одиночный мир |

Краш содержит cause и suppressed с маркерами `CRASHTRACER_CLIENT_TEST`.
Исключение выбрасывается из tick callback, а не из обработчика команды,
который мог бы поглотить его как ошибку синтаксиса.

Для hang-проверок до запуска игры установить в `crash-tracer/config.json`
`hangThresholdSeconds=10`, `watchdogCooldownSeconds=30`. При стандартном пороге
60 секунд задержка 16 секунд ожидаемо не создаёт watchdog-инцидент.
После проверки восстановить прежние значения. Пауза временная, watchdog сам
не останавливает потоки и не закрывает игру.

Начать с `report`, затем с BetterCrashes выполнить `crash`, вернуться в меню,
открыть другой тестовый мир и повторить `crash`. Проверить отдельный новый инцидент
для второго краша и отсутствие дубликатов для одного Throwable.
`bettercrashes` проверяет outputReport, но сам по себе не проверяет crash screen
и восстановление игрового состояния — для этого нужен `crash`.

Сохранить `crash-tracer/reports/<id>/`, соответствующую сессию из
`crash-tracer/sessions/`, `logs/fml-client-latest.log` и обычный Minecraft crash-report.
После тестирования удалить JAR фикстуры из `mods`.
