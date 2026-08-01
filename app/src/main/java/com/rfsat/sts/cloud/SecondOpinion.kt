package com.rfsat.sts.cloud

import com.rfsat.sts.log.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Asks Claude what it sees on the card, and asks it NOTHING it cannot answer
 * well.
 *
 * WHAT THIS IS NOT. It is not a scorer. A vision model estimates positions to
 * a few per cent of the image, which on a 170 mm card is several millimetres;
 * ring pitch on a 10 m air pistol face is 8 mm and the gauge rule turns on
 * fractions of one. Measured against hand-scored truth, the app's own
 * geometry places a hole it has found to between 0.2 and 1.7 mm. So the score
 * stays where the measurement is, and nothing here ever becomes a score
 * directly.
 *
 * WHAT IT IS FOR. Our measured failure is not precision, it is RECALL — the
 * detector misses holes. "How many holes are there, and roughly where?" is
 * the question a vision model answers well and the local pipeline answers
 * badly. Its positions are only ever used to point the detector at somewhere
 * to look again, and anything it claims that cannot be confirmed by
 * measurement is offered to the shooter as a suggestion, never counted.
 *
 * Coordinates are requested as FRACTIONS OF THE IMAGE, not millimetres. The
 * model cannot know the scale, and asking for millimetres would invite it to
 * invent them; asking where something is in a picture is a question about the
 * picture.
 */
object SecondOpinion {

    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val VERSION = "2023-06-01"
    private const val TIMEOUT_MS = 45_000
    private const val MAX_TOKENS = 1500

    data class Spot(
        /** 0..1 across the image, left to right and top to bottom. */
        val xFrac: Double,
        val yFrac: Double,
        val note: String
    )

    data class Opinion(
        val faceName: String,
        val holeCount: Int,
        val spots: List<Spot>,
        val usable: Boolean,
        val comment: String
    )

    sealed class Result {
        data class Ok(val opinion: Opinion, val inputTokens: Int, val outputTokens: Int) : Result()
        data class Failed(val message: String) : Result()
    }

    private val PROMPT = """
        You are looking at a photograph of a paper shooting target. Answer only
        about what is visible in this picture.

        Report, as JSON and nothing else:
        {
          "face": "<the competition target face this looks like, or 'unknown'>",
          "usable": <true if the whole card is in frame and sharp enough to score, else false>,
          "holes": [ {"x": <0..1 left-to-right>, "y": <0..1 top-to-bottom>, "note": "<where it is, in words>"} ],
          "comment": "<one sentence on anything odd: creases, glare, overlapping holes, shots off the card>"
        }

        Count every bullet or pellet hole, including any outside the scoring
        rings and any that are only partly in frame. Do NOT count printed
        features: ring lines, numerals, maker's marks, logos, or the small
        crosses some test cards carry.

        Two holes that touch or overlap are TWO holes; say so in the note.

        Give positions as fractions of the image. Do not estimate distances in
        millimetres and do not score the shots — that is measured elsewhere.
    """.trimIndent()

    /**
     * [jpegBase64] is the card photograph. Blocking; call it off the main
     * thread. Never logs the key, and never logs the image.
     */
    fun ask(apiKey: String, model: String, jpegBase64: String): Result {
        if (apiKey.isBlank()) return Result.Failed("No API key is set.")
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray()
                    .put(JSONObject().apply {
                        put("type", "image")
                        put("source", JSONObject().apply {
                            put("type", "base64")
                            put("media_type", "image/jpeg")
                            put("data", jpegBase64)
                        })
                    })
                    .put(JSONObject().apply {
                        put("type", "text")
                        put("text", PROMPT)
                    }))
            }))
        }.toString()

        return runCatching {
            val conn = (URL(ENDPOINT).openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("content-type", "application/json")
                setRequestProperty("anthropic-version", VERSION)
                setRequestProperty("x-api-key", apiKey)
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            conn.disconnect()
            if (code !in 200..299) return Result.Failed(explain(code, text))
            parse(text)
        }.getOrElse {
            Logger.w("SecondOpinion", "request failed: ${it.javaClass.simpleName}")
            Result.Failed("Could not reach the Claude API: ${it.message ?: it.javaClass.simpleName}. " +
                "Ranges often have no signal — this needs a connection.")
        }
    }

    /** Turns an HTTP status into something a shooter can act on. */
    private fun explain(code: Int, body: String): String {
        val detail = runCatching {
            JSONObject(body).getJSONObject("error").getString("message")
        }.getOrDefault("")
        return when (code) {
            401 -> "The API key was rejected. Note that this must be a key from the Anthropic " +
                "Console, not your Claude.ai password — the two are different things."
            400 -> "The request was refused: $detail"
            429 -> "Rate limited. Wait a moment and try again."
            in 500..599 -> "The service is having trouble ($code). Try again shortly."
            402, 403 -> "The key is valid but the request was not allowed — usually no credit " +
                "on the account. $detail"
            else -> "The service returned $code. $detail"
        }
    }

    private fun parse(response: String): Result {
        val root = JSONObject(response)
        val usage = root.optJSONObject("usage")
        val text = root.optJSONArray("content")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .firstOrNull { it.optString("type") == "text" }?.optString("text")
        }.orEmpty()
        // The model is asked for bare JSON, but a stray fence or sentence
        // should not lose the whole answer.
        val a = text.indexOf('{'); val b = text.lastIndexOf('}')
        if (a < 0 || b <= a) return Result.Failed("The reply was not in the expected form.")
        val obj = runCatching { JSONObject(text.substring(a, b + 1)) }
            .getOrElse { return Result.Failed("The reply was not valid JSON.") }

        val spots = ArrayList<Spot>()
        obj.optJSONArray("holes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val h = arr.optJSONObject(i) ?: continue
                val x = h.optDouble("x", -1.0); val y = h.optDouble("y", -1.0)
                if (x < 0 || y < 0 || x > 1 || y > 1) continue
                spots += Spot(x, y, h.optString("note"))
            }
        }
        return Result.Ok(
            Opinion(
                faceName = obj.optString("face", "unknown"),
                holeCount = spots.size,
                spots = spots,
                usable = obj.optBoolean("usable", true),
                comment = obj.optString("comment")
            ),
            inputTokens = usage?.optInt("input_tokens") ?: 0,
            outputTokens = usage?.optInt("output_tokens") ?: 0
        )
    }
}
