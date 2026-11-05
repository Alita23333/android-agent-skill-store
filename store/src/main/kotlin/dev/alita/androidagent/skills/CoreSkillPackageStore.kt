package dev.alita.androidagent.skills

import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/** Host package storage only. Discovery, activation and context remain owned by ZCode Core.
 * Nothing here projects a package into Core roots, launches scripts or grants capabilities.
 */
class CoreSkillPackageStore(
    private val root: File,
    private val dependencies: () -> SkillDependencyInventory = { SkillDependencyInventory() },
    private val inventoryFile: File? = null,
) {
    data class Pin(val id: String, val hash: String, val version: String, val source: String)

    init { root.mkdirs() }

    @Synchronized fun install(input: InputStream, source: String): SkillDescriptor {
        val stage = File(root, ".import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            extract(input, stage)
            val skillFiles = stage.walkTopDown().filter { it.isFile && it.name == "SKILL.md" }.toList()
            require(skillFiles.size == 1) { "压缩包必须只包含一个 SKILL.md" }
            val content = skillFiles.single().parentFile!!
            // Wrapping directories may contain this package only: no silently discarded siblings.
            require(stage.walkTopDown().filter { it.isFile }.all { it.toPath().startsWith(content.toPath()) }) {
                "压缩包包含技能目录外的文件"
            }
            val meta = metadata(File(content, "SKILL.md"))
            val id = meta["id"] ?: meta.getValue("name")
            require(id.matches(Regex("[a-z0-9][a-z0-9_-]{0,79}"))) { "技能 ID 必须为小写英文、数字、短横线或下划线" }
            val hash = hashTree(content)
            val home = home(id).apply { mkdirs() }
            val target = File(home, "versions/$hash")
            if (target.exists()) require(hashTree(target) == hash) { "已保存的技能版本校验失败" }
            else {
                requireNotNull(target.parentFile).mkdirs()
                val commit = File(home, ".commit-${UUID.randomUUID()}")
                try {
                    require(content.copyRecursively(commit, overwrite = false))
                    require(hashTree(commit) == hash) { "技能复制校验失败" }
                    require(commit.renameTo(target)) { "无法保存技能版本" }
                } finally { commit.deleteRecursively() }
            }
            val old = readIndex(home)
            val origins = old?.optJSONObject("origins") ?: JSONObject()
            // Re-importing identical bytes never rewrites the original recorded provenance.
            if (!origins.has(hash)) origins.put(hash, source.take(2000))
            writeIndex(home, JSONObject().put("schemaVersion", 1).put("id", id)
                .put("candidate", hash).put("removed", false).put("origins", origins)
                .put("enabled", old?.optBoolean("enabled") == true && old.optString("candidate") == hash))
            publishInventory()
            return descriptor(home, hash)
        } finally { stage.deleteRecursively() }
    }

    @Synchronized fun discover(): List<SkillDescriptor> = root.listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.startsWith('.') }
        .map { candidate ->
            val home = home(candidate.name)
            val index = requireNotNull(readIndex(home)) { "技能索引损坏：${home.name}" }
            if (index.getBoolean("removed")) null else descriptor(home, index.getString("candidate"))
        }.filterNotNull().sortedBy { it.name }

    /** Retirement hides future selection; immutable versions survive existing pins. */
    @Synchronized fun retire(id: String) {
        val home = home(id)
        val index = requireNotNull(readIndex(home)) { "技能不存在" }
        index.put("removed", true)
        index.put("enabled", false)
        writeIndex(home, index)
        publishInventory()
    }

    @Synchronized fun setEnabled(id: String, enabled: Boolean) {
        val home = home(id)
        val index = requireNotNull(readIndex(home)) { "技能不存在" }
        require(!index.getBoolean("removed")) { "技能已移除" }
        if (enabled) require(descriptor(home, index.getString("candidate")).missingDependencies.isEmpty()) { "技能依赖尚未满足" }
        index.put("enabled", enabled)
        writeIndex(home, index)
        publishInventory()
    }

    /** Host admission data only; no model context or second discovery/activation loop. */
    @Synchronized fun publishInventory() {
        val destination = inventoryFile ?: return
        val packages = JSONArray()
        root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }.sortedBy { it.name }.forEach { candidate ->
            require(packages.length() < 128) { "技能包过多" }
            val home = home(candidate.name)
            val index = requireNotNull(readIndex(home)) { "技能索引损坏" }
            val versions = JSONArray()
            File(home, "versions").listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.forEach { directory ->
                require(versions.length() < 128) { "技能历史版本过多" }
                val item = descriptor(home, directory.name)
                versions.put(JSONObject().put("hash", item.hash).put("name", item.name).put("version", item.version)
                    .put("source", item.source).put("directory", item.directory.canonicalPath)
                    .put("available", item.missingDependencies.isEmpty())
                    .put("missingDependencies", JSONArray(item.missingDependencies)))
            }
            packages.put(JSONObject().put("id", home.name).put("candidate", index.getString("candidate"))
                .put("enabled", index.optBoolean("enabled", false)).put("removed", index.getBoolean("removed"))
                .put("versions", versions))
        }
        val bytes = JSONObject().put("schemaVersion", 1).put("packages", packages).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1_048_576) { "技能索引过大" }
        val temporary = File(destination.parentFile, ".skills-${UUID.randomUUID()}")
        try {
            java.io.FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            java.nio.file.Files.move(temporary.toPath(), destination.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    /** Prepares a host version receipt; this is not a Core activation or model context. */
    @Synchronized fun pin(id: String): Pin {
        val item = discover().firstOrNull { it.id == id } ?: error("技能不存在")
        require(item.missingDependencies.isEmpty()) { "缺少依赖：${item.missingDependencies.joinToString()}" }
        return Pin(item.id, item.hash, item.version, item.source)
    }

    @Synchronized fun read(pin: Pin, relative: String, maxBytes: Int = 512_000): ByteArray {
        require(maxBytes in 1..512_000)
        val directory = version(home(pin.id), pin.hash)
        require(hashTree(directory) == pin.hash) { "技能版本内容已变化" }
        val file = inside(directory, relative)
        require(file.isFile && file.length() <= maxBytes) { "资源不存在或超出读取限制" }
        return file.readBytes()
    }

    private fun descriptor(home: File, hash: String): SkillDescriptor {
        val dir = version(home, hash)
        require(hashTree(dir) == hash) { "技能版本校验失败：${home.name}" }
        val meta = metadata(File(dir, "SKILL.md"))
        val missing = missingDependencies(dir)
        val index = requireNotNull(readIndex(home))
        return SkillDescriptor(home.name, meta.getValue("name"), meta.getValue("description"),
            meta["version"] ?: hash.take(12), hash, index.getJSONObject("origins").getString(hash),
            enabled = index.optBoolean("enabled", false),
            availability = if (missing.isNotEmpty()) SkillAvailability.MISSING_DEPENDENCY else
                if (index.optBoolean("enabled", false)) SkillAvailability.AVAILABLE else SkillAvailability.STORED,
            missingDependencies = missing, metadata = meta, directory = dir)
    }

    private fun missingDependencies(dir: File): List<String> {
        val supplement = File(dir, "android.yaml")
        if (!supplement.exists()) return emptyList()
        return runCatching {
            require(supplement.length() <= 32_768)
            val requirements = linkedMapOf<String, MutableList<String>>()
            var current: String? = null
            for (line in utf8(supplement.readBytes()).lineSequence()) {
                val text = line.trim()
                if (text.isEmpty() || text.startsWith('#')) continue
                if (text.startsWith("- ") && line.first().isWhitespace()) {
                    require(current != null)
                    requirements.getValue(current!!).add(scalar(text.removePrefix("- ")))
                } else {
                    require(!line.first().isWhitespace() && ':' in text) { "不支持的 Android 依赖格式" }
                    val key = text.substringBefore(':').trim()
                    require(key in setOf("runtimes", "tools", "platforms", "permissions")) { "未识别的依赖字段：$key" }
                    require(!requirements.containsKey(key)) { "重复依赖字段" }
                    current = key
                    val value = text.substringAfter(':').trim()
                    requirements[key] = when {
                        value.isEmpty() || value == "[]" -> mutableListOf()
                        value.startsWith('[') && value.endsWith(']') -> value.drop(1).dropLast(1).split(',').map(::scalar).toMutableList()
                        else -> mutableListOf(scalar(value))
                    }
                }
            }
            val available = dependencies()
            requirements.flatMap { (kind, names) -> names.distinct().mapNotNull { name ->
                val present = when (kind) {
                    "runtimes" -> name in available.runtimes
                    "tools" -> name in available.tools
                    "platforms" -> name in available.platforms
                    // A declaration is a request, never a permission grant.
                    "permissions" -> name in available.permissions
                    else -> false
                }
                if (present) null else "$kind:$name"
            } }
        }.getOrElse { listOf("android.yaml:${it.message ?: "无法解析依赖"}") }
    }

    private fun metadata(file: File): Map<String, String> {
        require(file.length() <= 100_000) { "SKILL.md 超出 100 KB" }
        val text = utf8(file.readBytes()).removePrefix("\uFEFF")
        val lines = text.lines()
        require(lines.firstOrNull() == "---") { "SKILL.md 必须包含元信息" }
        val end = lines.indices.drop(1).firstOrNull { lines[it].trim() == "---" }
            ?: error("SKILL.md 元信息未结束")
        val result = linkedMapOf<String, String>()
        var i = 1
        while (i < end) {
            val line = lines[i++]
            if (line.isBlank() || line.trimStart().startsWith('#') || line.first().isWhitespace()) continue
            require(':' in line) { "无效的技能元信息" }
            val key = line.substringBefore(':').trim()
            val value = line.substringAfter(':').trim()
            require(!result.containsKey(key)) { "重复技能元信息：$key" }
            if (value in listOf("|", "|-", ">", ">-")) {
                val block = mutableListOf<String>()
                while (i < end && (lines[i].isBlank() || lines[i].first().isWhitespace())) block.add(lines[i++].trim())
                result[key] = block.joinToString(if (value.startsWith('>')) " " else "\n").trim()
            } else result[key] = value.removeSurrounding("\"").removeSurrounding("'")
        }
        require(!result["name"].isNullOrBlank() && !result["description"].isNullOrBlank()) { "技能必须包含名称和描述" }
        require(result.getValue("description").length <= 1024) { "技能描述过长" }
        // Unknown frontmatter stays in immutable SKILL.md; Core performs its own parsing.
        return result
    }

    private fun scalar(text: String): String {
        val value = text.trim().removeSurrounding("\"").removeSurrounding("'")
        require(value.matches(Regex("[a-zA-Z0-9_.:/@+-]{1,160}"))) { "不支持的依赖值" }
        return value
    }

    private fun extract(input: InputStream, destination: File) {
        val seen = mutableSetOf<String>()
        val spelling = mutableMapOf<String, String>()
        var total = 0L
        var entries = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 500) { "技能包文件过多" }
                val relative = entry.name.removeSuffix("/")
                val target = inside(destination, relative)
                require(seen.add(relative.lowercase(java.util.Locale.ROOT))) { "技能包存在重复路径" }
                var prefix = ""
                for (segment in relative.split('/')) {
                    prefix = if (prefix.isEmpty()) segment else "$prefix/$segment"
                    val lower = prefix.lowercase(java.util.Locale.ROOT)
                    require(spelling[lower] == null || spelling[lower] == prefix) { "技能包路径大小写冲突" }
                    spelling[lower] = prefix
                }
                if (entry.isDirectory) require(target.mkdirs() || target.isDirectory)
                else {
                    require(!target.exists()) { "技能包路径冲突" }
                    requireNotNull(target.parentFile).mkdirs()
                    target.outputStream().use { out ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= 20L * 1024 * 1024) { "技能包超过 20 MB" }
                            out.write(buffer, 0, n)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun inside(base: File, relative: String): File {
        require(relative.isNotBlank() && '\\' !in relative && ':' !in relative && !relative.startsWith('/')) { "非法资源路径" }
        require(relative.split('/').none { it in listOf("", ".", "..") }) { "资源路径越界" }
        val target = File(base, relative)
        require(target.canonicalFile.toPath().startsWith(base.canonicalFile.toPath()) && target.canonicalFile != base.canonicalFile)
        // Stored/imported data is ordinary files only; never follow links planted afterwards.
        var cursor: File? = target
        while (cursor != null && cursor != base) {
            require(!java.nio.file.Files.isSymbolicLink(cursor.toPath())) { "资源包含符号链接" }
            cursor = cursor.parentFile
        }
        return target
    }

    private fun home(id: String): File {
        require(id.matches(Regex("[a-z0-9][a-z0-9_-]{0,79}")))
        return inside(root, id)
    }
    private fun version(home: File, hash: String): File {
        require(hash.matches(Regex("[a-f0-9]{64}"))) { "非法版本哈希" }
        return inside(home, "versions/$hash")
    }
    private fun readIndex(home: File): JSONObject? {
        val file = inside(home, "package.json")
        if (!file.exists()) return null
        return JSONObject(utf8(file.readBytes())).also {
            require(it.getInt("schemaVersion") == 1 && it.getString("id") == home.name)
        }
    }
    private fun writeIndex(home: File, value: JSONObject) {
        val file = inside(home, "package.json")
        val temporary = inside(home, ".index-${UUID.randomUUID()}")
        try {
            java.io.FileOutputStream(temporary).use { out ->
                out.write(value.toString().toByteArray(Charsets.UTF_8)); out.fd.sync()
            }
            java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            // An unsupported atomic replacement fails rather than deleting the prior index.
        } finally { temporary.delete() }
    }
    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun hashTree(directory: File): String {
        require(directory.isDirectory && !java.nio.file.Files.isSymbolicLink(directory.toPath()))
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("AndroidCoreSkill-content-v1\n".toByteArray())
        directory.walkTopDown().onEnter { require(!java.nio.file.Files.isSymbolicLink(it.toPath())); true }
            .filter { require(!java.nio.file.Files.isSymbolicLink(it.toPath())); it.isFile }
            .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }.forEach { file ->
                val name = file.relativeTo(directory).invariantSeparatorsPath.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(4).putInt(name.size).array()); digest.update(name)
                digest.update(ByteBuffer.allocate(8).putLong(file.length()).array())
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                }
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

data class SkillDependencyInventory(
    val runtimes: Set<String> = emptySet(),
    val tools: Set<String> = emptySet(),
    val platforms: Set<String> = setOf("android", "arm64-v8a"),
    val permissions: Set<String> = emptySet(),
)
