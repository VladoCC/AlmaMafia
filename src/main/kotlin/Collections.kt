package org.example

import org.example.db.Database

val database = Database(Config().path + "/data")
val accounts = database.collection("accounts", Account::chatId)
val games = database.collection("games", Game::id)
val gameHistory = database.collection("gameHistory", GameSummary::id)
val connections = database.collection("connections", Connection::id)
val pendings = database.collection("pending", Pending::host)
val roles = database.collection("roles", Role::id)
val setups = database.collection("setups", Setup::id)
val pairings = database.collection("pairings", Pairing::id)
val orders = database.collection("orders", TypeOrder::id)
val types = database.collection("types", Type::id)
val ads = database.collection("ads", Message::id)
val bombs = database.collection("bombs", TimedMessage::id)
val checks = database.collection("checks", Check::name)
val kicks = database.collection("kicks", Kick::id)
val modes = database.collection("modes", GameMode::gameId)
val hostInfos = database.collection("hostInfos", HostInfo::chatId)
val hostRequests = database.collection("hostRequests", UserId::chatId)
val admins = database.collection("admins", UserId::chatId)
val adminContexts = database.collection("adminMenus", AdminContext::chatId)
val timers = database.collection("timers", Timer::chatId)
val internal = database.collection("internal", String::toString)
val adPopups = database.collection("adPopups", AdPopup::chatId)
val adTargets = database.collection("adTargets", AdTarget::id)
val messageLinks = database.collection("messageLinks", MessageLink::id)
val rehosts = database.collection("rehosts", Rehost::gameId)
val hostSettings = database.collection("hostSettings", HostSettings::hostId)
val aliveUpdates = database.collection("aliveUpdates", GameUpdate::id)