package org.example.lua

import org.example.Config
import org.example.Person
import org.example.logger
import org.example.scriptDir
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

class Script(name: String, scriptDir: Path) {
    private val lua: Globals = Globals()

    init {
        lua.load(JseBaseLib())
        lua.load(PackageLib())
        LoadState.install(lua)
        LuaC.install(lua)
        lua.get("dofile").call(LuaValue.valueOf("${scriptDir.toFile().absolutePath}/${name}.lua"))
    }

    private val log = logger<Script>()

    fun <R> action(players: List<Person?>, util: LuaInterface, func: (Return) -> R): R? {
        return callForReturn("action", CoerceJavaToLua.coerce(players.toTypedArray()), util)?.let(func)
    }

    fun <R> passive(action: Action, util: LuaInterface, func: (Return) -> R): R? {
        return callForReturn("passive", CoerceJavaToLua.coerce(action), util)?.let(func)
    }

    fun type(players: List<Person>): String {
        return call("type", CoerceJavaToLua.coerce(players.toTypedArray())).toString()
    }

    fun team(players: List<Person>): String {
        return call("team", CoerceJavaToLua.coerce(players.toTypedArray())).toString()
    }

    fun status(players: List<Person>, util: LuaInterface): GameState? {
        return callForState(
            "status",
            CoerceJavaToLua.coerce(players.toTypedArray()),
            util
        )
    }

    private fun callForReturn(func: String, arg: LuaValue, util: LuaInterface): Return? {
        val result = CoerceLuaToJava.coerce(
            call(func, arg, util),
            Return::class.java
        )
        if (result !is Return) {
            log.error("Unexpected result type for lua call: ${result::class.qualifiedName}")
            return null
        }
        return result
    }

    private fun callForState(func: String, arg: LuaValue, util: LuaInterface): GameState? {
        val result = CoerceLuaToJava.coerce(
            call(func, arg, util),
            GameState::class.java
        )
        if (result !is GameState) {
            log.error("Unexpected result type for lua call: ${result::class.qualifiedName}")
            return null
        }
        return result
    }

    private fun call(func: String, arg: LuaValue, util: LuaInterface? = null): LuaValue? {
        if (util != null) {
            lua.set("UTIL", CoerceJavaToLua.coerce(util))
        }
        return lua.get(func).call(arg)
    }
}

internal fun prepareScripts() {
    val dir = Path.of(Config().path, "scripts")
    if (!exists(dir)) {
        createDirectories(dir)
    }
    if (!exists(scriptDir)) {
        createDirectories(scriptDir)
    }
    scriptDir.toFile().listFiles()?.forEach { it.delete() }
    dir.toFile()
        .listFiles { file -> file.name.endsWith(".lua") }
        ?.forEach {
            val script = it.readText().replace("$", "UTIL:")
            Paths.get(scriptDir.toFile().absolutePath, it.name).toFile().writeText(script)
        }
}