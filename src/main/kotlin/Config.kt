package org.example

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Config(val path: String) {
    companion object {
        private val file = File("./config.json")
        operator fun invoke(): Config {
            return Json.decodeFromString<Config>(file.readText())
        }
    }
}