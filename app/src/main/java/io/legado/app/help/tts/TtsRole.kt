package io.legado.app.help.tts

/**
 * 说书角色
 *
 * 一本书里「谁在说话」是这个功能的核心。没有它，一本小说从头到尾就一把嗓子，
 * 对白和旁白听起来一样——这是在线朗读最明显的「机器味」来源。
 */
data class TtsRole(
    val id: String,
    val name: String,
    /** 女 / 男 / 未知 */
    val gender: String = "",
    /** 主角 / 重要配角 / 配角 / 旁白 */
    val role: String = "配角",
    /** 音色标签，只说声音：清亮 / 沙哑 / 少年感 */
    val timbre: List<String> = emptyList(),
    /** 性格关键词，说人不说声音：沉稳 / 桀骜 */
    val personality: List<String> = emptyList(),
    /** 实测台词，用作音色种子，保证同名同声 */
    val sampleLine: String = "",
    /** 固定音色；空则按性别 + 稳定哈希分配 */
    val voice: String = ""
) {
    companion object {
        const val NARRATOR = "__narrator__"
    }
}

/**
 * MiMo 内置音色池。
 *
 * 来源：mimo.mi.com 官方文档的预置音色表。名字用于挑声音，不用于挑性别——
 * 性别一律走 [inferGender]，避免「低沉/磁性」这类音色词把女性角色判成男声。
 */
object MiMoVoices {

    data class Voice(val id: String, val gender: String, val style: String)

    val all: List<Voice> = listOf(
        Voice("冰糖", "女", "活泼少女"),
        Voice("茉莉", "女", "知性女声"),
        Voice("苏打", "男", "阳光少年"),
        Voice("白桦", "男", "成熟男声"),
        Voice("Mia", "女", "Lively girl"),
        Voice("Chloe", "女", "Sweet Dreamy"),
        Voice("Milo", "男", "Sunny boy"),
        Voice("Dean", "男", "Steady Gentle")
    )

    val narratorVoice = "白桦"

    fun byId(id: String?): Voice? = all.firstOrNull { it.id == id }

    /**
     * 稳定分配：同一个角色每次启动都拿到同一个音色。
     *
     * 不能用 String.hashCode 之外的随机数——重装一次就换声，用户会以为是 bug。
     */
    fun pickFor(role: TtsRole): String {
        if (role.voice.isNotBlank() && byId(role.voice) != null) return role.voice
        if (role.id == TtsRole.NARRATOR) return narratorVoice
        byId(role.name)?.let { return it.id }
        val gender = inferGender(role)
        val pool = when (gender) {
            "女" -> all.filter { it.gender == "女" }
            "男" -> all.filter { it.gender == "男" }
            else -> all
        }
        val seed = role.name + role.sampleLine.take(8)
        return pool[stableIndex(seed, pool.size)].id
    }

    fun stableIndex(text: String, modulo: Int): Int {
        if (modulo <= 0) return 0
        var h = -2128831035 // FNV offset basis
        for (ch in text) h = (h xor ch.code) * 16777619
        return ((h.toLong() and 0xFFFFFFFFL) % modulo).toInt()
    }

    /**
     * 只在有文本证据时判性别。
     *
     * 角色名本身不算证据（「茉莉」可能是男的），但「她/姑娘/小姐」这类词算。
     * 判不出来返回空，让调用方用全池子——猜错性别比不猜更刺耳。
     */
    fun inferGender(role: TtsRole): String {
        if (role.gender == "女" || role.gender == "男") return role.gender
        val evidence = buildString {
            append(role.sampleLine).append(' ')
            role.timbre.forEach { append(it).append(' ') }
            role.personality.forEach { append(it).append(' ') }
        }
        val female = listOf("她", "姑娘", "少女", "女人", "小姐", "夫人", "姐姐", "妹妹", "娘")
        val male = listOf("他", "少年", "男人", "先生", "公子", "哥哥", "弟弟", "爷")
        val f = female.count { evidence.contains(it) }
        val m = male.count { evidence.contains(it) }
        return when {
            f > m -> "女"
            m > f -> "男"
            else -> ""
        }
    }
}

/**
 * 逐句风格：角色底色 + 这一句的情绪。
 *
 * MiMo 的 `user` 消息是自由风格指令，所以「谁在说话」和「什么情绪」最终都落到
 * 这一段文字上。空字符串表示让音色自己发挥。
 */
object TtsStyle {

    fun forRole(role: TtsRole?, kind: String, emotion: String = ""): String {
        val base = when {
            role == null || role.id == TtsRole.NARRATOR ->
                "用讲故事的语气，语速稍慢，沉稳，有画面感"
            kind == "inner" -> "用内心独白的语气，轻声"
            kind == "dialogue" -> "用对白的语气，自然"
            else -> "语气自然"
        }
        val parts = mutableListOf(base)
        if (role != null && role.id != TtsRole.NARRATOR) {
            if (role.personality.isNotEmpty()) parts += "性格" + role.personality.joinToString("、")
            if (role.timbre.isNotEmpty()) parts += "声音" + role.timbre.joinToString("、")
        }
        if (emotion.isNotBlank() && emotion != "平静") parts += "情绪$emotion"
        return parts.joinToString("，")
    }
}
