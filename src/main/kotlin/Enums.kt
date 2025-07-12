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
    CHECK_ROLE("check_role", "Проверять роль ночью"),
    ONE_MSG_PLAYER_INFO("one_msg_player_info", "Роль и инфо об игре в одном сообщении")
}

enum class HostOptions(val shortName: String, val fullName: String, val current: HostSettings.() -> Boolean, val update: HostSettings.() -> Unit) {
    Fall(
        "0️⃣ Режим фоллов",
        "Добавляет счетчик каждому из игроков, которые стартует с 0 " +
                "и при нажатии увеличивает значение на единицу вплоть до 9, после чего возвращается в 0",
        { fallMode },
        {
            fallMode = !fallMode
        }),
    Detailed(
        "❤️‍🩹 Показ состояния игроков",
        "Отображение кнопок управления состоянием игрока в основном меню дня",
        { detailedView },
        {
            detailedView = !detailedView
        }),
    DoubleColNight(
        "👥 Два столбца ночью",
        "Показывать список игроков при выборе ночью в два ряда",
        { doubleColumnNight },
        {
            doubleColumnNight = !doubleColumnNight
        }
    ),
    ConfirmSelection(
        "☑️ Подтверждать действия",
        "Подтверждать корректность выбора отдельной кнопкой вместо автоматического выполнения ночного действия",
        { confirmNightSelection },
        {
            confirmNightSelection = !confirmNightSelection
        }
    ),
    Timer(
        "⏳ Таймер",
        "Отображать кнопку включения таймера в меню дня",
        { timer },
        {
            timer = !timer
        }
    ),
    HidePlayers(
        "🕶️ Скрывать игроков днем",
        "Отображать кнопку для сокрытия списка игроков в меню дня",
        { hideDayPlayers },
        {
            hideDayPlayers = !hideDayPlayers
        }
    )
}

enum class AccountState {
    Init, Menu, Host, Lobby, Presets, Admin
}

enum class GameState {
    Connect, Roles, Preview, Game, Dummy, Rename, Num, Type, Reveal, ChangeHost
}

enum class AdminState {
    NONE, HOST_TIME, HOST_GAMES
}

enum class LinkType {
    NONE, ROLE, INFO, ALIVE
}
