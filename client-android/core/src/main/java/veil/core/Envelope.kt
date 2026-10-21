package veil.core

// Конверт содержимого сообщения (то, что внутри ratchet-шифра).
// Сервер и наблюдатель видят только непрозрачный паддингованный payload.

import org.json.JSONObject

object MsgType {
    const val TEXT = "text"
    const val IMAGE = "image"
    const val FILE = "file"
    const val VOICE = "voice"
    const val VIDEO_NOTE = "video_note" // кружок
    const val POLL = "poll"
    const val STATUS = "status" // адресная история, автосгорание 24 ч
    const val REACTION = "reaction"
    const val FORWARD = "forward"
    const val CALL_OFFER = "call_offer"
    const val CALL_ANSWER = "call_answer"
    const val CALL_ICE = "call_ice"
    const val CALL_END = "call_end"
}

/** Группа: {gid, members[]} — отправка идёт каждому участнику отдельным E2E-конвертом. */
object Group {
    fun id(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(12)

    fun meta(gid: String, name: String, members: List<String>): String = JSONObject()
        .put("type", "group_meta").put("gid", gid).put("name", name)
        .put("members", org.json.JSONArray(members)).toString()

    fun msg(gid: String, body: String): String = JSONObject()
        .put("type", MsgType.TEXT).put("gid", gid).put("body", body).toString()

    fun media(gid: String, type: String, name: String, bytes: ByteArray): String =
        JSONObject(Envelope.media(type, name, bytes)).put("gid", gid).toString()
}

/** Звонок: сигналинг идёт через реле тем же зашифрованным конвертом. */
object CallSignal {
    fun offer(sdp: String): String = JSONObject()
        .put("type", MsgType.CALL_OFFER).put("sdp", sdp).toString()

    fun answer(sdp: String): String = JSONObject()
        .put("type", MsgType.CALL_ANSWER).put("sdp", sdp).toString()

    fun ice(candidate: String, mid: String): String = JSONObject()
        .put("type", MsgType.CALL_ICE).put("cand", candidate).put("mid", mid).toString()

    fun end(reason: String): String = JSONObject()
        .put("type", MsgType.CALL_END).put("reason", reason).toString()
}

/** Статус — адресная история, автосгорание 24 ч (серверный TTL конвертов). */
object Status {
    fun set(text: String): String = JSONObject()
        .put("type", MsgType.STATUS).put("body", text)
        .put("exp", System.currentTimeMillis() / 1000 + 86_400).toString()
}

object Envelope {

    fun text(text: String): String = JSONObject()
        .put("type", MsgType.TEXT).put("body", text).toString()

    fun media(type: String, name: String, bytes: ByteArray): String {
        // Медиа/файлы: чанков не требуется до ~1 МБ (сетка паддинга §4),
        // сверх — разбивается на parts клиентом.
        val partCount = (bytes.size + MAX_PART - 1) / MAX_PART
        if (partCount <= 1) {
            return JSONObject()
                .put("type", type).put("name", name)
                .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                .toString()
        }
        return JSONObject()
            .put("type", type).put("name", name).put("parts", partCount)
            .put("part", 0) // первую часть отправляет вызывающий, далее 1..n-1
            .put("data", android.util.Base64.encodeToString(
                bytes.copyOfRange(0, MAX_PART), android.util.Base64.NO_WRAP))
            .toString()
    }

    fun part(type: String, name: String, parts: Int, index: Int, bytes: ByteArray): String =
        JSONObject()
            .put("type", type).put("name", name).put("parts", parts).put("part", index)
            .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            .toString()

    fun poll(question: String, options: List<String>): String = JSONObject()
        .put("type", MsgType.POLL).put("q", question).put("opts", options).toString()

    const val MAX_PART = 900_000 // до паддинг-ячейки 1 МБ
}
