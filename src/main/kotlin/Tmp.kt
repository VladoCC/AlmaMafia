package org.example

import org.bson.types.ObjectId
import org.example.Result
import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaValue
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.jse.CoerceJavaToLua
import org.luaj.vm2.lib.jse.CoerceLuaToJava
import org.luaj.vm2.lib.jse.JseBaseLib

fun main() {
    val lua = Globals()
    lua.undumper
    lua.load(JseBaseLib())
    lua.load(PackageLib())
    LoadState.install(lua)
    LuaC.install(lua)
    lua.set("CONST", CoerceJavaToLua.coerce(Const(
        listOf(1, 2),
        listOf(
            Person(3, "n", Role(ObjectId(), 3, "role", "desc", false, "t"), "city"),
            Person(3, "n", Role(ObjectId(), 3, "role", "desc", false, "t"), "city")
        ),
        Town(
            -1, emptyList(), emptyList(), emptyMap(), emptyMap()
        )
    )))
    lua.get("dofile").call(LuaValue.valueOf("./scripts/traitor.lua"))
    val func = lua.get("team")
    val arg = arrayOf(Person(1, "name", Role(ObjectId(), 1, "Маньяк", "desc", false, "mafia"), "mafia", false))
    val arg2 = HealResult(listOf(1, 2), emptyList())
    val ret = func.call(CoerceJavaToLua.coerce(arg))
    val res = CoerceLuaToJava.coerce(ret, Result::class.java)
    val type = res is KillResult
}