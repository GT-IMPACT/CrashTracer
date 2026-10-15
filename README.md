# Crash Tracer

Сборка: Gradle 9.7.1 на JDK 25. Для Minecraft 1.7.10 также нужен установленный JDK 8.

```shell
git submodule update --init --recursive
./gradlew build
```

Параметры мода и публикации задаются в `impactMod` в `build.gradle.kts`.
Версия Forgelin переопределяется в каталоге `libs` в `settings.gradle.kts`.
Версия мода берётся из переменной окружения `VERSION` или Git.
Для публикации используются `IMPACT_MAVEN_USER` и `IMPACT_MAVEN_PASSWORD`.

Миксины включены через UniMixins и early/late-загрузчики по схеме
[VirtualWorld](https://github.com/GT-IMPACT/VirtualWorld/tree/main/src/main/java/space/gtimpact/virtual_world/mixinplugin).
Mixin-классы пишутся на Java. Списки пока пустые: early-классы регистрируются
в `TracerEarlyMixins`, late-классы — в `TracerLateMixins`; BetterCrashes-специфичные
миксины нужно добавлять условно по наличию `bettercrashes`.
Early/late JSON подключают загрузчики, в manifest указан только базовый JSON.

[GTNH BetterCrashes](https://github.com/GTNewHorizons/BetterCrashes/releases/tag/1.4.7-GTNH)
версии `1.4.7-GTNH` подключён как `compileOnly`, без встраивания и обязательной
зависимости во время запуска. Для проверки интеграции его нужно установить
в тестовую игру отдельно.
