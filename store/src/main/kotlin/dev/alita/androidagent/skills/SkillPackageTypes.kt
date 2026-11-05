package dev.alita.androidagent.skills

import java.io.File

enum class SkillAvailability { AVAILABLE, MISSING_DEPENDENCY, DISABLED, INVALID, STORED }

data class SkillDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val hash: String,
    val source: String,
    val enabled: Boolean,
    val availability: SkillAvailability,
    val missingDependencies: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val directory: File,
)

data class SkillSnapshot(val descriptor: SkillDescriptor, val body: String)
