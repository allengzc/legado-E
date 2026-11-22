package io.legado.app.help.tts

import io.legado.app.utils.GSON

/**
 * 角色表：一本书一份，存在 [io.legado.app.data.entities.Book.variable] 里。
 *
 * 为什么存在书上而不是全局：音色是**这本书**的选角结果。换个书架同一角色
 * 应该有不同嗓音，全局表会把两本书的角色混在一起。
 */
object RoleStore {

    private const val KEY = "_tts_roles"

    /**
     * @return 已存角色；没有则返回只有旁白的默认表
     */
    fun load(bookVariable: String?): MutableList<TtsRole> {
        val default = mutableListOf(TtsRole(TtsRole.NARRATOR, "旁白", role = "旁白"))
        val json = bookVariable?.let { runCatching { GSON.fromJson(it, Map::class.java) as? Map<*, *> }.getOrNull() }
            ?: return default
        val raw = json[KEY] as? String ?: return default
        val stored = runCatching {
            GSON.fromJson(raw, Array<TtsRole>::class.java)?.toList()
        }.getOrNull().orEmpty()
        if (stored.none { it.id == TtsRole.NARRATOR }) {
            default.addAll(stored)
            return default
        }
        return stored.toMutableList()
    }

    /** 写回书变量（保留原有其他变量）。 */
    fun save(bookVariable: String?, roles: List<TtsRole>): String {
        val map: MutableMap<String, Any> = runCatching {
            GSON.fromJson(bookVariable, Map::class.java)?.entries
                ?.associate { it.key.toString() to (it.value ?: "") }
                ?.toMutableMap()
        }.getOrNull() ?: mutableMapOf()
        map[KEY] = GSON.toJson(roles)
        return GSON.toJson(map)
    }
}
