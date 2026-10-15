# Crash Tracer 0.1.0-rc.1

Локальный релиз-кандидат для Minecraft 1.7.10 / Forge 10.13.4.1614 / Java 8.
Нужны Forgelin 2.1.3 и UniMixins-all; проверены UniMixins 0.3.1 и 0.3.2.
BetterCrashes 1.4.7-GTNH — отдельный опциональный мод.

## Установка

1. Закрыть клиент/сервер. Оставить в `mods` один основной JAR Crash Tracer.
2. Положить `crashtracer-0.1.0-rc.1.jar` в `mods`, заменив старый JAR.
3. Запустить игру. Тестовые fixture/dev/sources JAR устанавливать не нужно.

В релизе нет `/cttest` и игровых команд. Конфиг создаётся в
`<game-dir>/crash-tracer/config.json`; изменения требуют перезапуска.
Обычный режим: `experimentalTransformers=false`, `bytecodeExport=false`,
`hangThresholdSeconds=60`, `watchdogCooldownSeconds=300`.

## Где результат

Инциденты: `crash-tracer/reports/<id>/`. Начать с `analysis.txt`;
исходные исключения — `stacktrace.txt`, полный контекст — `report.json`.
`report.txt` создаётся до анализа и может ещё показывать неизвестного кандидата.
Обычные Minecraft crash reports сохраняются отдельно в `crash-reports/`.

Анализатор не устанавливается в `mods`; запускается отдельно на Java 8:

```shell
java -jar crashtracer-analyzer-0.1.0-rc.1-standalone.jar <game-dir>/crash-tracer/reports
```

Опции: `--force` пересчитывает анализ, `--mappings <dir>` задаёт каталог локальных
MCP `methods.csv` / `fields.csv`. Исходные capture-файлы не переписываются.

## Проверки и ограничения

- 37 тестов на Java 8, сборка и obfuscated сервер с AE2/GTNHLib/UniMixins 0.3.2.
- Предыдущий JAR прошёл 16 ручных кейсов IMPACT dev-42, включая клиентский crash,
  повторный вход, оба watchdog и ASM tracing. Кандидат дополнительно прошёл короткий
  клиентский запуск: вход, около трёх минут игры, сохранение и штатный выход.
  Полный crash/recovery проход кандидата повторно не выполнялся.
- После намеренного servercrash внутри GT tick блокировка может остаться у
  завершившегося потока в исходном GregTech. Отдельное исправление GT проверено:
  повторный вход после servercrash работает без перезапуска. Это исправление
  находится в GregTech, не в JAR трейсера. Tracer диагностирует ожидание,
  не освобождает чужие locks и не управляет восстановлением BetterCrashes.
- Часовой soak отложен; вариант модпака без BetterCrashes пропущен;
  подключение к отдельному серверу модпака не проверено.
- ASM tracing экспериментален и выключен. Начинается с FML preInit;
  name/Mixin transformers, диспетчеры цепочки и недоступный байткод не оборачиваются.
- Атрибуция показывает доказательства и уверенность, не гарантирует первопричину.

Это кандидат с известными ограничениями, не подтверждение полной релизной стабильности.
Подробности: `concept/17-release-review.md`, результаты —
`concept/test-data/impact-dev42-results.csv` в репозитории.
