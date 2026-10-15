# Референсы и открытые вопросы

Референсы перенесены из [обсуждения](https://chatgpt.com/share/6ad08573-1610-83ed-8351-93940ae211a7). Их конкретные API и совместимость с целевой сборкой в рамках этой декомпозиции не проверялись.

| Проект | Что изучить |
| --- | --- |
| [BetterCrashes](https://github.com/GTNewHorizons/BetterCrashes) | Обработчики крашей, создание отчёта, восстановление и точки интеграции |
| [UniMixins](https://github.com/LegacyModdingMC/UniMixins) | Compat, Mixingasm, загрузка миксинов и диагностические данные |
| [CoreTweaks](https://github.com/makamys/CoreTweaks) | Ранний bootstrap, трансформеры, совместимость обработчиков |
| [MixinTrace](https://github.com/comp500/mixintrace) | Связь stack frames, target classes и Mixin configs |
| [Not Enough Crashes](https://github.com/natanfudge/Not-Enough-Crashes) | Атрибуция модов, обогащение ошибок, деобфускация |
| [OrnitheMC/mixin-trace](https://github.com/OrnitheMC/mixin-trace) | Дополнительные идеи Mixin-диагностики |

Проекты для других загрузчиков и версий — архитектурные референсы, а не готовые зависимости для Forge 1.7.10. Заимствование кода требует отдельной проверки лицензии.

## До написания интеграций

- Установить точные версии Forge, UniMixins-all и BetterCrashes в модпаке.
- Проверить настоящий идентификатор BetterCrashes, coremod-статус и порядок обнаружения.
- Выбрать методы/дескрипторы для Java-миксинов и механизм их условного подключения.
- Проверить доступность диагностических сведений Compat/Mixingasm в выбранной версии.
- Определить порядок загрузки Forgelin/Kotlin runtime относительно bootstrap.
- Выбрать минимально необходимый механизм раннего запуска.

## До стабилизации формата

- Утвердить JSON-схему, версионирование и правила чтения старых отчётов.
- Выбрать локальный набор маппингов 1.7.10.
- Определить правила атрибуции и объяснения уверенности.
- Задать лимиты памяти, событий, файлов и политику хранения.
- Уточнить обязательность поддержки выделенного сервера Minecraft и набор общих/клиентских хуков.

Название мода — `Crash Tracer`, Mod ID — `crashtracer`, корневой package — `space.impact.crashtracer`. Имя Gradle-проекта — `crash-tracer`; Maven group — `space.impact`.
