# Crash Tracer — концепция

Декомпозиция локальной части [исходного обсуждения](https://chatgpt.com/share/6ad08573-1610-83ed-8351-93940ae211a7). Поздние уточнения имеют приоритет: BetterCrashes подключается опционально, интеграция может обращаться к его внутренним методам через миксины.

Документы описывают планируемое поведение, а не уже реализованные возможности. Точные версии зависимостей, API и методы инъекций ещё предстоит проверить по исходникам. Серверные веб-сервисы, отправка данных и прочая бэкенд-логика в концепцию не включены. Выделенный сервер Minecraft — возможная среда работы самого мода, а не отдельный бэкенд.

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

Основной код пишем на Kotlin. Миксины — на Java; Java также допустима для технически необходимых адаптеров ранней загрузки. Целевое окружение исполнения — Java 8.
