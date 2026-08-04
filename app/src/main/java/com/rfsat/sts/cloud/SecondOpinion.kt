package com.rfsat.sts.cloud

import com.rfsat.sts.log.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Asks the chosen AI service what it sees on the card, and asks it NOTHING
 * it cannot answer
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
    /** 4000, not 1500. A card with a dozen holes and a note on each ran the
     *  older budget out mid-object, and a truncated reply has no closing
     *  brace — which is what "the reply was not in the expected form" was
     *  actually reporting. */
    private const val MAX_TOKENS = 4000

    /** The model is made to answer THROUGH a tool rather than in prose.
     *
     *  Asking for "JSON and nothing else" is a request, not a guarantee: a
     *  model may add a sentence before it, wrap it in a fence, or — the case
     *  reported on Opus 5 — run out of tokens part way through and leave an
     *  object that never closes. A forced tool call cannot do any of those.
     *  The API assembles the arguments itself and hands back a structured
     *  object, so there is no prose to parse and nothing to go wrong in the
     *  parsing. The old text path is kept only as a fallback. */
    private const val TOOL_NAME = "report_shots"

    data class Spot(
        /** 0..1 across the image, left to right and top to bottom. */
        val xFrac: Double,
        val yFrac: Double,
        val note: String,
        /** Ring value the service gives this shot, or -1 when it was not
         *  asked. Only requested when it is scoring the card outright. */
        val ring: Int = -1
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

    private val SCORING_EXTRA = """
        This picture has been flattened onto the target's own scoring grid, so
        the printed rings are true circles about the centre of the image.

        Add a "ring" to each hole: the ring value it scores, counting the
        centre as 10 and the outermost printed ring as 1, or 0 for a shot
        outside them all. A shot counts for the highest ring its HOLE touches,
        not the ring its centre sits in.
    """.trimIndent()

    /**
     * [jpegBase64] is the card photograph. Blocking; call it off the main
     * thread. Never logs the key, and never logs the image.
     */
    /**
     * Asks whichever service is configured. Both are sent the same picture
     * and the same question and are held to the same schema, so everything
     * downstream is identical whichever answered.
     */
    fun ask(
        provider: AiProvider,
        apiKey: String,
        model: String,
        jpegBase64: String,
        scoreToo: Boolean = false
    ): Result {
        if (apiKey.isBlank()) return Result.Failed("No API key is set for ${provider.label}.")
        // An HTTP header may not contain a newline, and a key pasted from a
        // wrapped display does. The store strips whitespace now, but a
        // request must not be attempted on a key that somehow still has any:
        // the failure is an IllegalArgumentException raised before anything
        // is sent, which reads to the user as though the service refused.
        if (apiKey.any { it.isWhitespace() }) {
            return Result.Failed(
                "The stored ${provider.label} key contains a space or a line break, which cannot " +
                    "go in a request header. Set the key again, pasting it as a single line."
            )
        }
        return when (provider) {
            AiProvider.ANTHROPIC -> askAnthropic(apiKey, model, jpegBase64, scoreToo)
            AiProvider.OPENAI -> askOpenAi(apiKey, model, jpegBase64, scoreToo)
        }
    }

    /** The hole schema both services are held to. Written once so the two
     *  cannot drift into answering slightly different questions. */
    private fun holeProperties(scoreToo: Boolean): JSONObject {
        val props = JSONObject()
            .put("x", JSONObject().put("type", "number")
                .put("description", "0..1 across the image, left to right"))
            .put("y", JSONObject().put("type", "number")
                .put("description", "0..1 down the image, top to bottom"))
            .put("note", JSONObject().put("type", "string")
                .put("description", "where it is, in a few words"))
        if (scoreToo) {
            props.put("ring", JSONObject().put("type", "integer")
                .put("description",
                    "ring value scored, 10 at the centre, 0 for a shot outside them all"))
        }
        return props
    }

    private fun askAnthropic(apiKey: String, model: String, jpegBase64: String, scoreToo: Boolean): Result {
        val holeProps = holeProperties(scoreToo)
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("face", JSONObject().put("type", "string"))
                .put("usable", JSONObject().put("type", "boolean"))
                .put("comment", JSONObject().put("type", "string"))
                .put("holes", JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject()
                        .put("type", "object")
                        .put("properties", holeProps)
                        .put("required", JSONArray().put("x").put("y")))))
            .put("required", JSONArray().put("face").put("usable").put("holes"))

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            put("tools", JSONArray().put(JSONObject()
                .put("name", TOOL_NAME)
                .put("description", "Report every bullet or pellet hole visible on this target.")
                .put("input_schema", schema)))
            put("tool_choice", JSONObject().put("type", "tool").put("name", TOOL_NAME))
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
                        put("text", if (scoreToo) PROMPT + "\n\n" + SCORING_EXTRA else PROMPT)
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

    private const val OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"

    /**
     * OpenAI's Chat Completions, with the answer pinned by a JSON schema.
     *
     * The equivalent of Anthropic's forced tool call: response_format of
     * json_schema with strict set, which makes the reply conform to the
     * schema rather than merely be asked to. Its rules are stricter than
     * Anthropic's — EVERY property must be listed as required and every
     * object must forbid additional properties — so the schema is built
     * separately here rather than shared wholesale, even though the question
     * and the hole fields are the same.
     */
    private fun askOpenAi(apiKey: String, model: String, jpegBase64: String, scoreToo: Boolean): Result {
        val holeProps = holeProperties(scoreToo)
        val required = JSONArray().put("x").put("y").put("note")
        if (scoreToo) required.put("ring")
        val schema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put("properties", JSONObject()
                .put("face", JSONObject().put("type", "string"))
                .put("usable", JSONObject().put("type", "boolean"))
                .put("comment", JSONObject().put("type", "string"))
                .put("holes", JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject()
                        .put("type", "object")
                        .put("additionalProperties", false)
                        .put("properties", holeProps)
                        .put("required", required))))
            .put("required", JSONArray().put("face").put("usable").put("comment").put("holes"))

        val text = if (scoreToo) PROMPT + "\n\n" + SCORING_EXTRA else PROMPT
        val body = JSONObject().apply {
            put("model", model)
            put("max_completion_tokens", MAX_TOKENS)
            put("response_format", JSONObject()
                .put("type", "json_schema")
                .put("json_schema", JSONObject()
                    .put("name", TOOL_NAME)
                    .put("strict", true)
                    .put("schema", schema)))
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray()
                    .put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject()
                            .put("url", "data:image/jpeg;base64,$jpegBase64"))
                    })
                    .put(JSONObject().apply {
                        put("type", "text")
                        put("text", text)
                    }))
            }))
        }.toString()

        return runCatching {
            val conn = (URL(OPENAI_ENDPOINT).openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("content-type", "application/json")
                setRequestProperty("authorization", "Bearer $apiKey")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val reply = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            conn.disconnect()
            if (code !in 200..299) return Result.Failed(explainOpenAi(code, reply))
            parseOpenAi(reply)
        }.getOrElse {
            Logger.w("SecondOpinion", "OpenAI request failed: ${it.javaClass.simpleName}")
            Result.Failed("Could not reach the OpenAI API: ${it.message ?: it.javaClass.simpleName}. " +
                "Ranges often have no signal — this needs a connection.")
        }
    }

    private fun explainOpenAi(code: Int, body: String): String {
        val detail = runCatching {
            JSONObject(body).getJSONObject("error").getString("message")
        }.getOrDefault("")
        return when (code) {
            401 -> "The API key was rejected. It must be a key from platform.openai.com, not a " +
                "ChatGPT password — the two are different things."
            400 -> "The request was refused: $detail" +
                (if (detail.contains("max_completion_tokens") || detail.contains("max_tokens"))
                    " This model may not accept the token limit the app sends; try another model."
                 else if (detail.contains("json_schema") || detail.contains("response_format"))
                    " This model may not support schema-constrained replies; try GPT-4o."
                 else "")
            404 -> "That model was not found on this account: $detail"
            429 -> "Rate limited, or the account is out of credit. $detail"
            in 500..599 -> "The service is having trouble ($code). Try again shortly."
            else -> "The service returned $code. $detail"
        }
    }

    private fun parseOpenAi(response: String): Result {
        val root = JSONObject(response)
        val usage = root.optJSONObject("usage")
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: return Result.Failed("The reply held no answer at all.")
        val finish = choice.optString("finish_reason")
        val message = choice.optJSONObject("message")
        message?.optString("refusal")?.takeIf { it.isNotBlank() && it != "null" }?.let {
            return Result.Failed("The model declined to answer: $it")
        }
        val content = message?.optString("content").orEmpty()
        if (content.isBlank()) {
            return Result.Failed(
                if (finish == "length")
                    "The reply was cut off before it finished — the model ran out of room. " +
                        "Try a smaller image or a different model."
                else "The reply came back empty" +
                    (if (finish.isNotBlank()) " (stopped: $finish)." else ".")
            )
        }
        val obj = runCatching { JSONObject(content) }.getOrElse {
            return Result.Failed("The reply was not valid JSON.")
        }
        return build(obj,
            usage?.optInt("prompt_tokens") ?: 0,
            usage?.optInt("completion_tokens") ?: 0)
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
        val stop = root.optString("stop_reason")
        val content = root.optJSONArray("content")
        val blocks = (0 until (content?.length() ?: 0)).map { content!!.getJSONObject(it) }

        // The forced tool call: arguments assembled by the API, already an
        // object, nothing to parse out of prose.
        val obj: JSONObject = blocks.firstOrNull {
            it.optString("type") == "tool_use" && it.optString("name") == TOOL_NAME
        }?.optJSONObject("input")
            ?: run {
                // Fallback for a model or an account where the tool call did
                // not come back: pull the first {...} out of the text.
                val text = blocks.firstOrNull { it.optString("type") == "text" }
                    ?.optString("text").orEmpty()
                val a = text.indexOf('{'); val b = text.lastIndexOf('}')
                if (a < 0 || b <= a) {
                    return Result.Failed(
                        if (stop == "max_tokens")
                            "The reply was cut off before it finished — the model ran out of " +
                                "room. Try a smaller image or a different model."
                        else "The reply came back in a form this app could not read" +
                            (if (stop.isNotBlank()) " (stopped: $stop)." else ".")
                    )
                }
                runCatching { JSONObject(text.substring(a, b + 1)) }
                    .getOrElse { return Result.Failed("The reply was not valid JSON.") }
            }

        return build(obj,
            usage?.optInt("input_tokens") ?: 0,
            usage?.optInt("output_tokens") ?: 0)
    }

    /**
     * Turns the answered object into an Opinion, whichever service produced
     * it. Shared so that a coordinate out of range, or a missing field, is
     * treated identically either way — a schema guarantees the SHAPE of a
     * reply and nothing about whether the numbers in it make sense.
     */
    private fun build(obj: JSONObject, inTok: Int, outTok: Int): Result {
        val spots = ArrayList<Spot>()
        obj.optJSONArray("holes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val h = arr.optJSONObject(i) ?: continue
                val x = h.optDouble("x", -1.0); val y = h.optDouble("y", -1.0)
                if (x < 0 || y < 0 || x > 1 || y > 1) continue
                spots += Spot(x, y, h.optString("note"), h.optInt("ring", -1))
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
            inputTokens = inTok,
            outputTokens = outTok
        )
    }
}
