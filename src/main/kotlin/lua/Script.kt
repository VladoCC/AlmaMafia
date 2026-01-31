package org.example.lua

import org.example.Game
import org.example.Person
import org.example.logger
import org.example.tempDir
import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaValue
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.jse.CoerceJavaToLua
import org.luaj.vm2.lib.jse.CoerceLuaToJava
import org.luaj.vm2.lib.jse.JseBaseLib
import java.nio.file.Files.createDirectories
import java.nio.file.Files.exists
import java.nio.file.Path
import java.nio.file.Paths

private val log = logger<Script>()

class Script(scriptPath: Path) : MemoryStore {
    private val lua: Globals = Globals()
    private val storage: MutableMap<Int, Any> = mutableMapOf()
    private var storageIndex = 1

    init {
        lua.load(JseBaseLib())
        lua.load(PackageLib())
        LoadState.install(lua)
        LuaC.install(lua)
        lua.get("dofile").call(LuaValue.valueOf(scriptPath.toFile().absolutePath))
    }

    fun <R> action(actors: List<Person>, players: List<Person>, callback: (List<Action>) -> R): R? {
        return callForActions("action", CoerceJavaToLua.coerce(players.toTypedArray()), actors, players).let(callback)
    }

    fun <R> passive(
        action: Action,
        actors: List<Person>,
        players: List<Person>,
        dependencies: Set<Action>,
        func: (List<Action>) -> R
    ): R? {
        return callForActions("passive", CoerceJavaToLua.coerce(action), actors, players, dependencies).let(func)
    }

    fun choice(
        players: List<Person>,
        actors: List<Person>,
        func: String = "choice"
    ): List<Choice> {
        return callForChoices(func, players, actors)
    }

    fun type(players: List<Person>): List<String> {
        return mutableListOf<String>().also {
            call(
                "type",
                CoerceJavaToLua.coerce(players.toTypedArray()),
                CoerceJavaToLua.coerce(TypeInterface(it))
            )
        }
    }

    fun team(players: List<Person>): String? {
        val res = call("team", CoerceJavaToLua.coerce(players.toTypedArray()))
        if (res == null) {
            return null
        }
        return res.toString()
    }

    fun dawn() {
        call("dawn", LuaValue.NIL)
    }

    fun dusk() {
        call("dusk", LuaValue.NIL)
    }

    fun status(players: List<Person>): GameState? {
        return callForState(
            "status",
            CoerceJavaToLua.coerce(players.toTypedArray()),
            StatusInterface()
        )
    }

    private fun callForActions(
        func: String,
        arg: LuaValue,
        actors: List<Person>,
        players: List<Person>,
        dependencies: Set<Action> = emptySet()
    ): List<Action> {
        return mutableListOf<Action>().also {
            call(
                func,
                arg,
                CoerceJavaToLua.coerce(
                    ActionInterface(
                        this,
                        actors,
                        players,
                        it,
                        dependencies
                    )
                )
            )
        }
    }

    private fun callForChoices(
        func: String,
        players: List<Person>,
        actors: List<Person>
    ): List<Choice> {
        return mutableListOf<Choice>().also {
            call(
                func,
                LuaValue.NIL,
                CoerceJavaToLua.coerce(
                    ChoiceInterface(this, players, actors, it)
                )
            )
        }
    }

    private fun callForState(func: String, arg: LuaValue, util: StatusInterface): GameState? {
        val result = CoerceLuaToJava.coerce(
            call(func, arg, CoerceJavaToLua.coerce(util)),
            GameState::class.java
        )
        if (result !is GameState) {
            log.error("Unexpected result type for lua call: ${result::class.qualifiedName}")
            return null
        }
        return result
    }

    private fun call(func: String, arg: LuaValue, util: LuaValue = LuaValue.NIL): LuaValue? {
        lua.set("UTIL", CoerceJavaToLua.coerce(util))
        val function = lua.get(func)
        if (function.isnil()) {
            return null
        }
        return function.call(arg)
    }

    override fun store(value: Any): Int {
        storage[storageIndex] = value
        return storageIndex++
    }

    override fun stored(key: Int) = storage[key]
}


// todo relative paths for safety (deal with path traversal attacks like ./scripts/../../etc/passwd)
internal fun prepareScripts(game: Game, scriptDir: String): Map<String, Path> {
    try {
        val dir = Path.of(scriptDir)
        if (!exists(dir)) {
            createDirectories(dir)
        }
        val gameDir = tempDir.resolve(game.id.toString())
        if (!exists(gameDir)) {
            createDirectories(gameDir)
        }
        return dir.toFile()
            .listFiles { file -> file.name.endsWith(".lua") }?.associate { file ->
                val script = file.readText().replace("$", "UTIL:")
                val path = Paths.get(gameDir.toFile().absolutePath, file.name).also {
                    it.toFile().writeText(script)
                }
                file.name.removeSuffix(".lua") to path
            } ?: emptyMap()
    } catch (e: Exception) {
        log.error("Failed to prepare script files", e)
    }
    return emptyMap()
}

internal fun deleteScripts(game: Game) {
    try {
        val gameDir = tempDir.resolve(game.id.toString())
        if (exists(gameDir)) {
            gameDir.toFile().listFiles()?.forEach { it.delete() }
            gameDir.toFile().delete()
        }
    } catch (e: Exception) {
        log.error("Failed to delete script files", e)
    }
}