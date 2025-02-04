package org.example

import org.example.telegram.command

val blankCommand = command("`", "default")
val deleteMsgCommand = command("Закрыть", "deleteMsg", 1)

val joinCommand = command("", "join", 2)
val updateCommand = command("Обновить список игр", "update", 1)

val playerNumCommand = command("", "playerNum", 3)
val playerConfirmCommand = command("Ввести ▶️", "playerConfirm", 3)
val mainMenuCommand = command("🔙 Покинуть игру", "mainMenu", 1)

val detailsCommand = command("", "details", 2)
val renameCommand = command("✍️ Переименовать", "rename", 2)
val positionCommand = command("Указать номер", "posi", 3)
val handCommand = command("✋Поднять руку", "hand", 1)
val kickCommand = command("❌ Исключить", "kick", 2)

val resetNumsCommand = command("🚮 Сбросить номера игроков", "resetNums", 1)
val confirmResetCommand = command("Да", "confirmReset", 2)

val unkickCommand = command("Впустить", "unkick", 2)

val hostBackCommand = command("Назад", "back", 1)
val menuKickCommand = command("🚪 Список исключенных игроков", "menuKick", 1)

val changeHostCommand = command("🤝 Сменить ведущего", "changeHost", 1)
val newHostCommand = command("", "newHost", 2)
val stopRehostingCommand = command("◀️ Отмена", "stopRehosting", 1)

val acceptHostingCommand = command("Да", "acceptHosting", 3)
val declineHostingCommand = command("Нет", "declineHosting", 3)

val menuLobbyCommand = command("◀️ Меню игроков", "menuLobby", 1)
val menuRolesCommand = command("Меню ролей ▶️", "menuRoles", 1)
val menuPreviewCommand = command("Меню распределения ▶️", "menuPreview", 1)
val gameCommand = command("Начать игру 🎮", "game", 2)

val markBotCommand = command("🌚", "markBot", 2)
val proceedCommand = command("☀️ Начать день", "proceed", 1)

val posSetCommand = command("Ввести ▶️", "posSet", 3)

val nameCancelCommand = command("Отмена", "nameCancel", 1)

val dummyCommand = command("➕ Добавить игрока", "dummy", 1)
val roleCommand = command("", "role", 2)
val incrCommand = command("➕", "incr", 2)
val decrCommand = command("➖", "decr", 2)

val resetRolesCommand = command("🚮 Сбросить выбор ролей", "resetRoles", 2)
val previewCommand = command("🔀 Раздать роли", "preview", 2)
val updateRolesCommand = command("🔄 Перераздать", "updRoles", 2)
val gameModeCommand = command("", "mode", 2)

val dayDetailsCommand = command("", "dayDetails", 2)
val statusCommand = command("Статус: Ошибка", "status", 2)
val killCommand = command("💀", "kill", 2)
val reviveCommand = command("🏩", "rviv", 2)
val fallCommand = command("", "fall", 2)

val dayBackCommand = command("◀️ Назад", "dayBack", 1)

val settingsCommand = command("📋 Настройки", "settings", 1)
val timerCommand = command("⏳ Таймер", "timer")
val nightCommand = command("🌙 Начать ночь", "night", 1)

val selectCommand = command("", "select", 3)
val executeActionCommand = command("Подтвердить ▶️", "executeAction", 2)
val nextRoleCommand = command("Следующая роль ▶️", "nextRole", 1)
val skipRoleCommand = command("Пропустить ⏩", "skipRole", 1)

// todo add this coomand to all night menus
val cancelActionCommand = command("Отменить последнее действие", "cancelAction", 1)
val dayCommand = command("☀️ Начать день", "day", 1)

val hidePlayersCommand = command("🕶️ Скрыть игроков", "hidePlayers", 1)
val filterCommand = command("Фильтр: Ошибка", "fltr", 1)

val fallModeCommand = command("0️⃣ Режим фоллов", "fallMode", 2)
val detailedViewCommand = command("❤️‍🩹 Показ состояния игроков", "detailedMode", 2)
val doubleColumnNightCommand = command("👥 Два столбца ночью", "columnNight", 2)
val confirmNightSelectionCommand = command("☑️ Подтверждать действия", "confirmSelection", 2)
val timerSettingCommand = command("⏳ Таймер", "timerSetting", 2)
val hidePlayersSettingCommand = command("🕶️ Скрывать игроков днем", "hidePlayersSetting", 2)

val timerDeleteCommand = command("❌️", "timerDelete", 1)
val timerStateCommand = command("", "timerState", 1)
val timerResetCommand = command("🔄", "timerReset", 1)

val revealRoleCommand = command("👀 Показать роль", "reveal", 1)
val gameInfoCommand = command("ℹ️ Информация об игре", "gameInfo", 1)

val updateCheckCommand = command("", "updateCheck", 2)

val hostRequestCommand = command("Запросы на ведение", "hostRequests", 1)
val hostSettingsCommand = command("Список ведущих", "hostSettings", 1)
val adminSettingsCommand = command("Список администраторов", "adminSettings", 1)
val gamesSettingsCommand = command("Список игр", "gamesSettings", 1)
val advertCommand = command("Реклама", "advert", 0)

val timeLimitOnCommand = command("Off", "timeLimitOn", 2)
val timeLimitOffCommand = command("❌", "timeLimitOff", 2)
val gameLimitOnCommand = command("Off", "gameLimitOn", 2)
val gameLimitOffCommand = command("❌", "gameLimitOff", 2)
val shareCommand = command("Off", "share", 2)
val deleteHostCommand = command("❌ Удалить ведушего", "deleteHost", 2)
val promoteHostCommand = command("🧑‍🧒‍🧒 Сделать администратором", "promoteHost", 2)
val allowHostCommand = command("✅", "allowHost", 2)
val denyHostCommand = command("❌", "denyHost", 2)
val removeAdminCommand = command("❌", "removeAdmin", 2)
val adminBackCommand = command("Назад", "adminBack", 1)

val confirmPromoteCommand = command("Да", "confirmPromote", 3)

val terminateGameCommand = command("❌ Остановить игру", "terminateGame", 2)
val confirmTerminateCommand = command("Да", "confirmTerminate", 3)

val sendAdCommand = command("", "sendAd", 2)
val sendAdHistoryCommand = command("", "sendAdHistory", 2)
val adSelectCommand = command("Выбрать", "adSelect", 2)
val adClearCommand = command("Закрыть", "adClear", 1)

val acceptNameCommand = command("Да", "nameAccept", 3)
val cancelName = command("Нет", "nameDeny", 2)

val acceptStopCommand = command("Да", "stopAccept", 2)
val acceptLeaveCommand = command("Да", "leaveAccept", 2)
val acceptRehostCommand = command("Да", "rehostAccept", 1)

val closePopupCommand = command("Закрыть", "closePopup", 1)

val adCommand = command("/ad")
val adNewCommand = command("/newad")
val adminCommand = command("/admin")

val hostCommand = command("/host")
val rehostCommand = command("/rehost")
val startCommand = command("/start")
val menuCommand = command("/menu")
val changeNameCommand = command("/changename")
val editSettingsCommand = command("/settings")

val startGameCommand = command("Запустить игру")
val restartGameCommand = command("Перезапустить игру")
val stopGameCommand = command("Завершить игру")
val leaveGameCommand = command("Покинуть игру")

val adminPanelCommand = command("Меню администратора")