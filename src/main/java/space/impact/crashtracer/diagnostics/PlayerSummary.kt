package space.impact.crashtracer.diagnostics

/** Short player-facing explanation; never equates stack ownership with a proven cause. */
data class PlayerSummary(val russian: List<String>, val english: List<String>, val language: String = "en_US") {
    companion object {
        fun create(report: IncidentReport, analysis: AnalysisResult?): PlayerSummary {
            val types = report.exceptions.nodes.map { it.type }
            val integrated = report.exceptions.nodes.any { node -> node.frames.any { it.contains("server.integrated.IntegratedServer.") } } ||
                report.context.environment.keys.any { it.startsWith("world.client:") }
            val serverRu = if (integrated) "Внутренний сервер" else "Сервер"
            val serverEn = if (integrated) "The internal server" else "The server"
            val packetRu = if (report.description == "Ticking memory connection") "$serverRu остановлен" else
                "Ошибка ${if (integrated) "внутреннего сервера" else "сервера"}"
            val packetEn = if (report.description == "Ticking memory connection") "$serverEn stopped" else "$serverEn encountered an error"
            val category = when {
                report.source == "watchdog" -> "Игра слишком долго не отвечает." to "The game stopped responding for too long."
                types.any { it.endsWith("OutOfMemoryError") } -> "Игре не хватило памяти Java." to "The game ran out of Java memory."
                types.any { it.endsWith("ClassNotFoundException") || it.endsWith("NoClassDefFoundError") } ->
                    "Не удалось загрузить нужный класс. Возможна несовместимость модов." to "A required class could not load. Mods may be incompatible."
                report.source == "jvm-native-log" -> "Сбой Java или нативной библиотеки." to "Java or a native library crashed."
                report.description.contains("manual", true) -> "Создан тестовый диагностический отчёт." to "A test diagnostic report was created."
                InteractionDetails.isRightClick(report) ->
                    "$packetRu при обработке ${if (report.interaction?.before?.sneaking == true) "Shift+ПКМ" else "правого клика"}." to
                        "$packetEn while processing ${if (report.interaction?.before?.sneaking == true) "Shift + right click" else "a right click"}."
                InteractionDetails.isPacketFailure(report) -> "$packetRu при обработке действия игрока." to "$packetEn while processing a player action."
                report.context.phase.contains("INITIAL") || report.context.phase == "BOOTSTRAP" || report.source == "launchwrapper-failure" ->
                    "Ошибка при запуске игры." to "An error occurred while starting the game."
                report.description.contains("server tick", true) -> "Ошибка внутреннего сервера: мир был остановлен." to "The internal server failed and the world stopped."
                else -> "В игре произошла ошибка." to "An error occurred in the game."
            }
            val suspects = analysis?.suspects.orEmpty().take(2)
            val names = suspects.map { suspect -> report.context.mods.firstOrNull { it.id == suspect.modId }?.name ?: suspect.modId }
                .joinToString(", ") { it.replace(Regex("[\r\n§]"), " ").take(80) }
            val interaction = report.interaction
            val held = interaction?.before?.heldItem ?: interaction?.atFailure?.heldItem ?: interaction?.packetItem
            val container = interaction?.atFailure?.container
            val detailsRu = if (interaction == null) "" else listOfNotNull(
                held?.let { "Предмет: ${it.registryName ?: it.itemClass.substringAfterLast('.')} (${it.metadata})." },
                container?.let { "Интерфейс: ${it.type.substringAfterLast('.')}." }).joinToString(" ", prefix = " ")
            val detailsEn = if (interaction == null) "" else listOfNotNull(
                held?.let { "Item: ${it.registryName ?: it.itemClass.substringAfterLast('.')} (${it.metadata})." },
                container?.let { "Container: ${it.type.substringAfterLast('.')}." }).joinToString(" ", prefix = " ")
            val related = when {
                analysis == null -> "Причина: анализ ещё выполняется." to "Cause: analysis is still running."
                suspects.isEmpty() -> "Точная причина не установлена." to "The exact cause is unknown."
                suspects.first().confidence == "HIGH" -> "Ошибка преобразования связана с: $names. Первопричина не доказана." to "A failing transformation is linked to: $names. Root cause is unproven."
                else -> "Возможная связь: $names. Это предположение, не доказанный виновник." to "Possibly related: $names. This is a clue, not a proven culprit."
            }
            return PlayerSummary(listOf(category.first, related.first + detailsRu, "Отправьте ZIP разработчику сборки и опишите, что делали перед ошибкой."),
                listOf(category.second, related.second + detailsEn, "Send the ZIP to your modpack developer and describe what you were doing."),
                report.context.environment["client.language"] ?: "en_US")
        }
    }
    fun text() = if (language.startsWith("ru", ignoreCase = true))
        "Crash Tracer — краткая сводка\n\n" + russian.joinToString("\n") + "\n"
        else "Crash Tracer — short summary\n\n" + english.joinToString("\n") + "\n"
}
