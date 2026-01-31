package org.example

fun migrate(func: MigrationContext.() -> Unit) {
    mutableListOf<Migration>().also {
        MigrationContext(it, func)
    }.forEach {
        val migration = migrations.get(it.name)
        if (migration == null) {
            log.info("Applying migration '${it.name}'...")
            try {
                it.definition()
                log.info("Migration '${it.name}' applied successfully.")
            } catch (e: Exception) {
                log.error("Migration '${it.name}' failed: ${e.message}")
                throw Error("Migration '${it.name}' failed", e)
            }
            migrations.save(MigrationRecord(it.name))
        }
    }
}

class MigrationContext(private val definitions: MutableList<Migration>, func: MigrationContext.() -> Unit) {
    private val nameSet = mutableSetOf<String>()

    init {
        func()
    }

    fun migration(name: String, definition: () -> Unit) {
        if (name in nameSet) {
            throw IllegalArgumentException("Migration with name '$name' is already defined in this context.")
        }
        nameSet.add(name)
        definitions.add(Migration(name, definition))
    }
}

data class Migration(val name: String, val definition: () -> Unit)