package org.example

import com.google.common.cache.CacheBuilder
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.File
import java.nio.file.Files.*
import java.nio.file.Paths
import java.time.Duration
import kotlin.reflect.KClass

class Database(path: String) {
    val driver = Driver(path)

    inline fun <reified T, K: Any> collection(name: String, noinline keyFun: (T) -> K) = Collection(name, driver, keyFun)
}

class Collection<T: Any, K: Any>(private val name: String, private val driver: Driver, private val type : KClass<out T>, private val keyFun: (T) -> K) {
    private val gson = Gson()

    companion object {
        inline operator fun <reified T: Any, K: Any> invoke(name: String, database: Driver, noinline keyFun: (T) -> K) = Collection(name, database, T::class, keyFun)
    }

    fun get(id: K): T? {
        return driver.read(key(id))?.let { fromString(it) }
    }

    fun update(id: K, updateFun: T.() -> Unit) {
        get(id)?.apply(updateFun)?.let {
            save(id, it)
        }
    }

    fun updateMany(filter: T.() -> Boolean, updateFun: T.() -> Unit) {
        find(filter).forEach {
            it.updateFun()
            save(it)
        }
    }

    fun find() = find { true }

    fun find(filter: T.() -> Boolean): List<T> {
        return driver.readAll(name).mapNotNull { fromString(it) }.filter(filter)
    }

    fun find(ids: Set<K>): List<T> = ids.mapNotNull { driver.read(key(it)) }.mapNotNull { fromString(it) }

    fun save(value: T) = save(keyFun(value), value)

    private fun save(id: K, value: T) = driver.write(key(id), gson.toJson(value)).let { id }

    fun delete(id: K) = driver.delete(key(id))

    fun deleteMany(filter: T.() -> Boolean) = find(filter).forEach { delete(keyFun(it)) }

    private fun key(id: K) = "$name/$id"

    private fun fromString(str: String) = try {
        gson.fromJson(str, type.java)
    } catch (e: JsonSyntaxException) {
        e.printStackTrace()
        null
    }
}

class Driver(private val root: String) {
    private val cache = CacheBuilder.newBuilder()
        .expireAfterAccess(Duration.ofMinutes(10))
        .maximumSize(1000).build<String, String>()

    fun read(key: String): String? {
        return try {
            cache.get(key) {
                File("${root}/$key.json").readText()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun readAll(collection: String): List<String> {
        return File("${root}/$collection").listFiles()?.map { it.readText() }?: emptyList()
    }

    fun write(key: String, value: String) {
        try {
            val path = Paths.get(root, "$key.json")
            if (!exists(path.parent)) {
                createDirectories(path.parent)
            }
            if (!exists(path)) {
                createFile(path)
            }
            path.toFile().writeText(value)
            cache.put(key, value)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun delete(key: String) {
        try {
            val path = Paths.get(root, "$key.json")
            if (exists(path)) {
                delete(path)
            }
            cache.invalidate(key)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}