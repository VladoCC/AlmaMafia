package org.example

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Config(
    val path: String,
    val author: Long,
    val botToken: String,
    val defaultScriptId: String = "default",
    val teamHistorySize: Int = 10,
    val correctionCap: Double = 2.0,
    val confidenceFactor: Double = 3.0,
    val scaleBias: Double = 1.5,
    val historyThreshold: Int = 5,
    val orderingMinWeight: Double = 0.1,
    val unknownAccuracyMax: Double = 0.5,
    val unknownAccuracyMin: Double = 0.1
) {
    companion object {
        private val file = File("./config.json")
        operator fun invoke(): Config {
            return Json.decodeFromString<Config>(file.readText())
        }
    }
}