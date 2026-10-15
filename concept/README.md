# Crash Tracer — концепция

Декомпозиция локальной части [исходного обсуждения](https://chatgpt.com/share/6ad08573-1610-83ed-8351-93940ae211a7). Поздние уточнения имеют приоритет: BetterCrashes подключается опционально, интеграция может обращаться к его внутренним методам через миксины.

Документы 01–10 сохраняют замысел и исходный план. Актуальная реализация,
проверенные версии и ограничения описаны в 11–17 и корневом README.
Серверные веб-сервисы, отправка данных и прочая бэкенд-логика в концепцию не включены.
Выделенный сервер Minecraft — среда работы самого мода, а не отдельный бэкенд.

## Навигация

| Документ | Содержание |
| --- | --- |
| [01-goals.md](01-goals.md) | Назначение, среда и приоритеты |
| [02-architecture.md](02-architecture.md) | Компоненты, поток данных, Kotlin и Java |
| [03-bootstrap-and-capture.md](03-bootstrap-and-capture.md) | Ранний запуск и обнаружение сбоев |
| [04-runtime-context.md](04-runtime-context.md) | Окружение, моды, события, потоки |
| [05-integrations.md](05-integrations.md) | BetterCrashes и UniMixins |
| [06-mixin-and-transformer-forensics.md](06-mixin-and-transformer-forensics.md) | Атрибуция миксинов и ASM |
| [07-analysis-and-mappings.md](07-analysis-and-mappings.md) | Причины, доказательства, деобфускация, CLI |
| [08-local-reports.md](08-local-reports.md) | Файлы, форматы, аварийная запись |
| [09-roadmap-and-validation.md](09-roadmap-and-validation.md) | Этапы, критерии готовности, тесты |
| [10-references-and-open-questions.md](10-references-and-open-questions.md) | Референсы и вопросы перед реализацией |
| [11-implementation-status.md](11-implementation-status.md) | Реализованные подсистемы и границы |
| [12-validation-scopes.md](12-validation-scopes.md) | План общего тестового прохода |
| [13-server-verification.md](13-server-verification.md) | Результаты проверки выделенного сервера |
| [14-headless-verification.md](14-headless-verification.md) | OOM, deadlock, native, reobf, Mixin failures, файловые проверки и CLI |
| [15-client-test-triggers.md](15-client-test-triggers.md) | Отдельная клиентская фикстура и команды ручных тестов |
| [16-impact-modpack-test-cases.md](16-impact-modpack-test-cases.md) | Тест-кейсы для реального IMPACT dev-42 в PrismLauncher |
| [17-release-review.md](17-release-review.md) | Ревью реализации, исправления и ограничения релиз-кандидата |

Основной код пишем на Kotlin. Миксины — на Java; Java также допустима для технически необходимых адаптеров ранней загрузки. Целевое окружение исполнения — Java 8.
