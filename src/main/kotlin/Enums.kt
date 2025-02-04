package org.example

enum class Mode(val type: String, val desc: String) {
    OPEN(
        "Открытая",
        "Роль игрока вскрывается после смерти. Запрещено называть роли вовремя игры, а также слишком явно на них намекать."
    ),
    CLOSED(
        "Закрытая",
        "После смерти игрок выхоит из игры не называя роли. Вовремя игры можно называть роли и блефовать."
    )
}

enum class DayView(val desc: String, val filter: (Person) -> Boolean) {
    ALL("Все игроки", { true }),
    ALIVE("Живые игроки", { it.alive })
}

enum class CheckOption(val key: String, val display: String) {
    NAMES("names", "Показывать список игроков в команде"),
    COVER("cover", "Использовать `coverName`"),
    HOST_KNOWN("host_known", "Только известные ведущие"),
    HOST_REQUEST("host_request", "Сохранять запросы на ведение"),
    REVEAL_MENU("reveal_menu", "Проверка ознакомления с ролями"),
    GAME_MESSAGES("game_messages", "Удалять игровые сообщения"),
    KEEP_DETAILS("keep_details", "Оставаться в меню деталей после нажатия кнопки"),
    CHECK_ROLE("check_role", "Проверять роль ночью")
}


enum class AccountState {
    Init, Menu, Host, Lobby, Presets, Admin
}

enum class GameState {
    Connect, Roles, Preview, Game, Dummy, Rename, Num, Reveal, ChangeHost
}

enum class AdminState {
    NONE, HOST_TIME, HOST_GAMES
}
