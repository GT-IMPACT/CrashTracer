# Template Mod for ModPack

Сборка: Gradle 9.7.1 на JDK 25. Для Minecraft 1.7.10 также нужен установленный JDK 8.

```shell
git submodule update --init --recursive
./gradlew build
```

Параметры мода и публикации задаются в `impactMod` в `build.gradle.kts`.
Версия Forgelin переопределяется в каталоге `libs` в `settings.gradle.kts`.
Версия мода берётся из переменной окружения `VERSION` или Git.
Для публикации используются `IMPACT_MAVEN_USER` и `IMPACT_MAVEN_PASSWORD`.
