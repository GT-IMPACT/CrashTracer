# Референсы и открытые вопросы

Референсы перенесены из [обсуждения](https://chatgpt.com/share/6ad08573-1610-83ed-8351-93940ae211a7). Для реализации изучены VirtualWorld, исходники BetterCrashes 1.4.7-GTNH, Forge 10.13.4.1614 и интерфейсы UniMixins 0.3.1. Игровая совместимость ожидает общего тестового прохода.

| Проект | Что изучить |
| --- | --- |
| [BetterCrashes](https://github.com/GTNewHorizons/BetterCrashes) | Обработчики крашей, создание отчёта, восстановление и точки интеграции |
| [UniMixins](https://github.com/LegacyModdingMC/UniMixins) | Compat, Mixingasm, загрузка миксинов и диагностические данные |
| [CoreTweaks](https://github.com/makamys/CoreTweaks) | Ранний bootstrap, трансформеры, совместимость обработчиков |
| [MixinTrace](https://github.com/comp500/mixintrace) | Связь stack frames, target classes и Mixin configs |
| [Not Enough Crashes](https://github.com/natanfudge/Not-Enough-Crashes) | Атрибуция модов, обогащение ошибок, деобфускация |
| [OrnitheMC/mixin-trace](https://github.com/OrnitheMC/mixin-trace) | Дополнительные идеи Mixin-диагностики |

Проекты для других загрузчиков и версий — архитектурные референсы, а не готовые зависимости для Forge 1.7.10. Заимствование кода требует отдельной проверки лицензии.

## Зафиксировано в реализации

- Compile-среда: Forge 10.13.4.1614, UniMixins 0.3.1, BetterCrashes 1.4.7-GTNH.
- BetterCrashes Mod ID: `bettercrashes`; late-миксин подключается условно по этому ID.
- Точка интеграции: `vfyjxf.bettercrashes.utils.CrashUtils.outputReport(CrashReport)`.
- Ранний bootstrap не зависит от Kotlin/Forgelin; Kotlin-конвейер подключается на preInit.
- Общий перехват: `CrashReport`, `LoadController`, Mixin error handler, uncaught handler.
- Маппинги: локальные MCP `methods.csv`/`fields.csv` для 1.7.10, без сетевой загрузки.
- Лимиты и политика хранения вынесены в `config.json`; подробности — в README.

## До стабилизации формата

- Проверить схему v1 на реальных отчётах и старых полях.
- Проверить датасет MCP CSV и источники имён в фактическом модпаке.
- Оценить качество правил атрибуции и defaults на больших сборках.
- Проверить ранние/late-хуки на клиенте и выделенном сервере.
- Проверить opt-in ASM-адаптер с фактическими coremods и их проверками классов трансформеров.

Название мода — `Crash Tracer`, Mod ID — `crashtracer`, корневой package — `space.impact.crashtracer`. Имя Gradle-проекта — `crash-tracer`; Maven group — `space.impact`.
