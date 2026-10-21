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
