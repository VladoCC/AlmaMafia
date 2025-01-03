package org.example.lua

import org.example.Person
import org.example.logger
import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaValue
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.jse.CoerceJavaToLua
import org.luaj.vm2.lib.jse.CoerceLuaToJava
import org.luaj.vm2.lib.jse.JseBaseLib
import java.nio.file.Path

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

    fun action(players: List<Person?>, util: LuaInterface, func: (Return) -> Unit) {
        callForReturn("action", CoerceJavaToLua.coerce(players.toTypedArray()), util)?.let(func)
    }

    fun passive(result: Result, util: LuaInterface, func: (Return) -> Unit) {
        callForReturn("passive", CoerceJavaToLua.coerce(result), util)?.let(func)
    }

    fun type(players: List<Person>): String {
        return call("type", CoerceJavaToLua.coerce(players.toTypedArray())).toString()
    }

    fun team(players: List<Person>): String {
        return call("team", CoerceJavaToLua.coerce(players.toTypedArray())).toString()
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

    private fun call(func: String, arg: LuaValue, util: LuaInterface? = null): LuaValue? {
        if (lua == null) return null

        if (util != null) {
            lua.set("UTIL", CoerceJavaToLua.coerce(util))
        }
        return lua.get(func).call(arg)
    }
}