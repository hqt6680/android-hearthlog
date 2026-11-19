package com.stroexd.hsdecktracker.core.prediction

import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.deck.DeckDefinition
import com.stroexd.hsdecktracker.core.deck.SideboardCard
import java.net.URLDecoder
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NeteaseDirectPredictorTest {
    private lateinit var server: MockWebServer
    private lateinit var predictor: NeteaseDirectPredictor
    private val rc4Key = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
    private lateinit var profile: NeteaseGuestProfile

    // 30-card test deck (sum == 30) plus optional ETC sideboard
    private val dbfToCard = mapOf(
        1 to "CARD_ALPHA",
        2 to "CARD_BETA",
        3 to "CARD_SIDE_1",
    )
    private val baseDeck = DeckDefinition(
        heroes = listOf(7),
        format = GameFormat.STANDARD,
        cards = mapOf(1 to 20, 2 to 10),
    )

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        predictor = NeteaseDirectPredictor(
            client = OkHttpClient(),
            totalBudgetMillis = 5_000L,
            retryDelayMillis = 1L,
            delayMillis = {},
            uuidGenerator = { "20261119_unit-${uuidSeq++}" },
        )
        profile = NeteaseGuestProfile(
            endpoint = server.url("/hs/predict/standard_substep").toString(),
            sid = "11111111-2222-3333-4444-555555555555",
            cookie = "ls_session_id=session-cookie-value",
            userAgent = "HSAng/4.0.4.486",
            clientType = "win",
            version = "4.0.4.486",
            rc4Key = rc4Key,
        )
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private var uuidSeq = 1

    @Test
    fun `predicts mulligan replacements, excludes coin, handles keep_all and explicit replace-all`() = runTest {
        val logText = buildMulliganLog()
        val preview = assertNotNull(PredictStateLogReader.readActive(logText.reader(), PowerLogPredictionKind.MULLIGAN))

        val sideboardDeck = baseDeck.copy(sideboards = listOf(SideboardCard(dbfId = 3, count = 1, ownerDbfId = 1)))
        val request = DirectPredictionRequest(
            preview = preview,
            logBytes = logText.toByteArray(),
            deck = sideboardDeck,
            cardIdByDbfId = dbfToCard::get,
            cardNameById = { id -> "Name($id)" },
        )

        // 1. Replace two cards, keep two, Coin must not be emitted
        enqueueEncryptedJson("""{"status":true,"ret":0,"result":{"status":200,"rec":[{"chosen":[54,44]}],"winrate_data":{"status":200,"winrate":0.5425}}}""")
        val replaced = predictor.predict(request, profile)
        assertEquals(54.25, replaced.winRatePercent!!, 1e-6)
        assertEquals(listOf("replace", "replace"), replaced.recommendations?.map { it.action })
        assertEquals(
            listOf(61, 43),
            replaced.recommendations?.map { it.card?.getValue("ENTITY_ID")?.jsonPrimitive?.int },
        )
        assertEquals("Name(MULL_2)", replaced.recommendations?.first()?.card?.getValue("name")?.jsonPrimitive?.content)

        // Inspect decrypted wire request payload
        val recorded = server.takeRequest()
        val form = parseFormBody(recorded.body.readUtf8())
        assertEquals("rec,winrate", form["tasks"])
        assertEquals("11111111-2222-3333-4444-555555555555", recorded.requestUrl?.queryParameter("sid"))
        val envelope = Json.parseToJsonElement(
            NeteaseDirectCodec.zlibDecompress(NeteaseDirectCodec.rc4(NeteaseDirectCodec.decodeBase64Url(form.getValue("data")), rc4Key)).decodeToString(),
        ).jsonObject
        assertEquals("netreloaded", envelope.getValue("type").jsonPrimitive.content)
        assertEquals("LocalPlayer#1234", envelope.getValue("btag").jsonPrimitive.content)
        val extra = envelope.getValue("data").jsonObject.getValue("extra_infos").jsonObject
        assertEquals("CARD_ALPHA:20,CARD_BETA:10|CARD_ALPHA+CARD_SIDE_1:1", extra.getValue("cardgroup").jsonPrimitive.content)
        val state = envelope.getValue("data").jsonObject.getValue("turns").jsonArray.single().jsonObject
            .getValue("processes").jsonArray.single().jsonObject.getValue("state").jsonObject
        assertEquals(2, state.getValue("choices_id").jsonPrimitive.int)
        assertEquals("MULLIGAN", state.getValue("choices").jsonObject.getValue("choice_type").jsonPrimitive.content)
        assertTrue("played_cards" in state)

        // 2. Keep all four opening cards
        enqueueEncryptedJson("""{"status":true,"ret":0,"result":{"status":200,"rec":[{"chosen":[54,61,43,44]}],"winrate_data":{"status":200,"winrate":0.60}}}""")
        val keepAll = predictor.predict(request, profile)
        assertEquals(listOf("keep_all"), keepAll.recommendations?.map { it.action })

        // 3. Explicit empty chosen list [{"chosen": []}] replaces all 4 non-coin opening cards
        enqueueEncryptedJson("""{"status":true,"ret":0,"result":{"status":200,"rec":[{"chosen":[]}],"winrate_data":{"status":200,"winrate":0.51}}}""")
        val replaceAll = predictor.predict(request, profile)
        assertEquals(listOf("replace", "replace", "replace", "replace"), replaceAll.recommendations?.map { it.action })
    }

    @Test
    fun `predicts general discover choice and fetches missing winrate via follow-up`() = runTest {
        val logText = buildChoiceLog()
        val preview = assertNotNull(PredictStateLogReader.readActive(logText.reader(), PowerLogPredictionKind.CHOICE))
        val request = DirectPredictionRequest(preview, logText.toByteArray(), baseDeck, dbfToCard::get)

        enqueueEncryptedJson("""{"status":true,"ret":0,"result":{"status":200,"rec":[{"chosen":[72]}],"winrate_data":{"status":0,"winrate":null}}}""")
        enqueueEncryptedJson("""{"status":true,"ret":0,"result":{"status":200,"winrate_data":{"status":200,"winrate":0.612}}}""")

        val result = predictor.predict(request, profile)
        assertNull(result.errorCode)
        assertEquals(61.2, result.winRatePercent!!, 1e-6)
        val choose = result.recommendations?.single()
        assertEquals("choose", choose?.action)
        assertEquals(72, choose?.target?.getValue("ENTITY_ID")?.jsonPrimitive?.int)

        val firstForm = parseFormBody(server.takeRequest().body.readUtf8())
        val secondForm = parseFormBody(server.takeRequest().body.readUtf8())
        assertEquals("rec,winrate", firstForm["tasks"])
        assertEquals("winrate", secondForm["tasks"])
    }

    @Test
    fun `predicts action sequence and remaps targetless minion self-target to play_minion`() = runTest {
        val logText = buildActionLog()
        val preview = assertNotNull(PredictStateLogReader.readActive(logText.reader(), PowerLogPredictionKind.ACTION))
        val request = DirectPredictionRequest(preview, logText.toByteArray(), baseDeck, dbfToCard::get)

        enqueueEncryptedJson(
            """
            {"status":true,"ret":0,"result":{"status":200,"winrate_data":{"status":200,"winrate":0.48},"rec":[
              {"option_id":1,"option_entity_id":31,"target_entity_id":31,"sub_option_id":-1,"sub_option_entity_id":0,"magnetic_target_entity_id":0,"position":1},
              {"option_id":2,"option_entity_id":32,"target_entity_id":32,"sub_option_id":-1,"sub_option_entity_id":0,"magnetic_target_entity_id":0},
              {"option_id":3,"option_entity_id":33,"target_entity_id":999,"sub_option_id":0,"sub_option_entity_id":80,"position":2},
              {"option_id":4,"option_entity_id":41,"target_entity_id":21},
              {"option_id":5,"option_entity_id":42,"target_entity_id":21,"position":0},
              {"option_id":6,"option_entity_id":10,"target_entity_id":21},
              {"option_id":7,"option_entity_id":12,"target_entity_id":20},
              {"option_id":0,"option_entity_id":0}
            ]}}
            """.trimIndent(),
        )

        val result = predictor.predict(request, profile)
        assertEquals(48.0, result.winRatePercent!!, 1e-6)
        val recs = assertNotNull(result.recommendations)
        assertEquals(
            listOf("play_minion", "trade", "play_special", "minion_attack", "location_power", "hero_attack", "hero_skill", "end_turn"),
            recs.map { it.action },
        )
        // Untargeted minion quirk cleared target & kept position
        assertNull(recs[0].target)
        assertNull(recs[0].opponentTarget)
        assertEquals(1, recs[0].position)
        // Actual trade kept as trade with null target
        assertNull(recs[1].target)
        // Sub-option present
        assertEquals(80, recs[2].subOption?.getValue("ENTITY_ID")?.jsonPrimitive?.int)
        // Attacks map enemy hero to opponentTargetHero & own hero to targetHero
        assertEquals(21, recs[3].opponentTargetHero?.getValue("ENTITY_ID")?.jsonPrimitive?.int)
        assertEquals(0, recs[4].position)
        assertEquals(20, recs[6].opponentTarget?.getValue("ENTITY_ID")?.jsonPrimitive?.int)
    }

    @Test
    fun `predicts opponent turn winrate and handles auth failures plus not-ready retries`() = runTest {
        val logText = buildOpponentLog()
        val preview = assertNotNull(PredictStateLogReader.readActive(logText.reader(), PowerLogPredictionKind.OPPONENT))
        val request = DirectPredictionRequest(preview, logText.toByteArray(), baseDeck, dbfToCard::get)

        // Not-ready response retries and succeeds with new uuid
        enqueueEncryptedJson("""{"status":false,"ret":1,"msg":"TypeError: 'NoneType' object"}""")
        enqueueEncryptedJson("""{"status":true,"ret":0,"result":{"status":200,"winrate_data":{"status":200,"winrate":0.715}}}""")
        val retried = predictor.predict(request, profile)
        assertEquals(71.5, retried.winRatePercent!!, 1e-6)
        assertEquals("winrate", parseFormBody(server.takeRequest().body.readUtf8())["tasks"])
        assertEquals("winrate", parseFormBody(server.takeRequest().body.readUtf8())["tasks"])

        // 401 triggers predictor_auth_required
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        val authFail = predictor.predict(request, profile)
        assertEquals("predictor_auth_required", authFail.errorCode)

        // Encrypted auth-error message triggers predictor_auth_required
        enqueueEncryptedJson("""{"status":false,"ret":403,"msg":"sid expired"}""")
        val msgAuthFail = predictor.predict(request, profile)
        assertEquals("predictor_auth_required", msgAuthFail.errorCode)
    }

    private fun enqueueEncryptedJson(json: String) {
        val cipher = NeteaseDirectCodec.encodeBase64UrlWithPadding(
            NeteaseDirectCodec.rc4(json.toByteArray(Charsets.UTF_8), rc4Key),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(cipher))
    }

    private fun parseFormBody(body: String): Map<String, String> =
        body.split('&').filter { it.isNotBlank() }.associate { part ->
            val idx = part.indexOf('=')
            val k = URLDecoder.decode(part.substring(0, idx), "UTF-8")
            val v = URLDecoder.decode(part.substring(idx + 1), "UTF-8")
            k to v
        }

    private fun openingHeader(): String = buildString {
        appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() - CREATE_GAME")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() -     GameEntity EntityID=1")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() -         tag=CARDTYPE value=GAME")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() -         tag=TURN value=1")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() -     Player EntityID=2 PlayerID=1 GameAccountId=[hi=123 lo=123]")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() -     Player EntityID=3 PlayerID=2 GameAccountId=[hi=456 lo=456]")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintGame() - GameType=GT_RANKED")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintGame() - FormatType=FT_STANDARD")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintGame() - PlayerID=1, PlayerName=LocalPlayer#1234")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintGame() - PlayerID=2, PlayerName=Opponent#9999")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() - TAG_CHANGE Entity=GameEntity tag=STEP value=BEGIN_MULLIGAN")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=10 CardID=HERO_01")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() -     tag=CONTROLLER value=1")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() -     tag=CARDTYPE value=HERO")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() -     tag=ZONE value=PLAY")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() -     tag=HEALTH value=30")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=12 CardID=HP_01")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() -     tag=CONTROLLER value=1")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() -     tag=CARDTYPE value=HERO_POWER")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() -     tag=ZONE value=PLAY")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=21 CardID=HERO_02")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() -     tag=CONTROLLER value=2")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() -     tag=CARDTYPE value=HERO")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() -     tag=ZONE value=PLAY")
        appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() -     tag=HEALTH value=30")
    }

    private fun buildMulliganLog(): String = buildString {
        append(openingHeader())
        appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() - TAG_CHANGE Entity=3 tag=FIRST_PLAYER value=1")
        // Opponent (going first) draws 3 cards from DECK to HAND
        listOf(91, 92, 93).forEachIndexed { idx, id ->
            appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=$id CardID=")
            appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() -     tag=CONTROLLER value=2")
            appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() -     tag=ZONE value=DECK")
            appendLine("D 01:00:00.2000000 GameState.DebugPrintPower() - TAG_CHANGE Entity=$id tag=ZONE value=HAND")
            appendLine("D 01:00:00.2000000 GameState.DebugPrintPower() - TAG_CHANGE Entity=$id tag=ZONE_POSITION value=${idx + 1}")
        }
        // Local player (going second) draws 4 cards from DECK -> HAND + Coin
        val localOpening = listOf(54 to "MULL_1", 61 to "MULL_2", 43 to "MULL_3", 44 to "MULL_4")
        localOpening.forEach { (id, _) ->
            appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=$id CardID=")
            appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() -     tag=CONTROLLER value=1")
            appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() -     tag=CARDTYPE value=MINION")
            appendLine("D 01:00:00.1000000 GameState.DebugPrintPower() -     tag=ZONE value=DECK")
        }
        localOpening.forEachIndexed { idx, (id, card) ->
            appendLine("D 01:00:00.2000000 GameState.DebugPrintPower() - SHOW_ENTITY - Updating Entity=[entityName=Card id=$id zone=DECK player=1] CardID=$card")
            appendLine("D 01:00:00.2000000 GameState.DebugPrintPower() -     tag=CONTROLLER value=1")
            appendLine("D 01:00:00.2000000 GameState.DebugPrintPower() -     tag=CARDTYPE value=MINION")
            appendLine("D 01:00:00.2000000 GameState.DebugPrintPower() -     tag=ZONE value=HAND")
            appendLine("D 01:00:00.2000000 GameState.DebugPrintPower() -     tag=ZONE_POSITION value=${idx + 1}")
        }
        appendLine("D 01:00:00.2000000 GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=68 CardID=GAME_005")
        appendLine("D 01:00:00.2000000 GameState.DebugPrintPower() -     tag=CONTROLLER value=1")
        appendLine("D 01:00:00.2000000 GameState.DebugPrintPower() -     tag=CARDTYPE value=SPELL")
        appendLine("D 01:00:00.2000000 GameState.DebugPrintPower() -     tag=CREATOR value=1")
        appendLine("D 01:00:00.2000000 GameState.DebugPrintPower() -     tag=ZONE value=HAND")
        appendLine("D 01:00:00.2000000 GameState.DebugPrintPower() -     tag=ZONE_POSITION value=5")

        appendLine("D 01:00:01.1000000 GameState.DebugPrintEntityChoices() - id=1 Player=Opponent#9999 TaskList= ChoiceType=MULLIGAN CountMin=0 CountMax=3")
        appendLine("D 01:00:01.1000000 GameState.DebugPrintEntityChoices() -   Source=GameEntity")
        appendLine("D 01:00:01.1000000 GameState.DebugPrintEntityChoices() -   Entities[0]=[entityName=UNKNOWN id=91 zone=HAND player=2]")
        appendLine("D 01:00:01.1000000 GameState.DebugPrintEntityChoices() -   Entities[1]=[entityName=UNKNOWN id=92 zone=HAND player=2]")
        appendLine("D 01:00:01.1000000 GameState.DebugPrintEntityChoices() -   Entities[2]=[entityName=UNKNOWN id=93 zone=HAND player=2]")
        appendLine("D 01:00:01.2000000 GameState.DebugPrintEntityChoices() - id=2 Player=LocalPlayer#1234 TaskList= ChoiceType=MULLIGAN CountMin=0 CountMax=5")
        appendLine("D 01:00:01.2000000 GameState.DebugPrintEntityChoices() -   Source=GameEntity")
        appendLine("D 01:00:01.2000000 GameState.DebugPrintEntityChoices() -   Entities[0]=[entityName=MULL_1 id=54 zone=HAND cardId=MULL_1 player=1]")
        appendLine("D 01:00:01.2000000 GameState.DebugPrintEntityChoices() -   Entities[1]=[entityName=MULL_2 id=61 zone=HAND cardId=MULL_2 player=1]")
        appendLine("D 01:00:01.2000000 GameState.DebugPrintEntityChoices() -   Entities[2]=[entityName=MULL_3 id=43 zone=HAND cardId=MULL_3 player=1]")
        appendLine("D 01:00:01.2000000 GameState.DebugPrintEntityChoices() -   Entities[3]=[entityName=MULL_4 id=44 zone=HAND cardId=MULL_4 player=1]")
        appendLine("D 01:00:01.2000000 GameState.DebugPrintEntityChoices() -   Entities[4]=[entityName=COIN id=68 zone=HAND cardId=GAME_005 player=1]")
    }

    private fun buildChoiceLog(): String = buildString {
        append(openingHeader())
        appendLine("D 01:00:01.1000000 GameState.DebugPrintEntityChoices() - id=2 Player=LocalPlayer#1234 TaskList=7 ChoiceType=MULLIGAN CountMin=0 CountMax=3")
        appendLine("D 01:00:01.2000000 GameState.SendChoices() - id=2 ChoiceType=MULLIGAN")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() - TAG_CHANGE Entity=GameEntity tag=STEP value=MAIN_ACTION")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() - TAG_CHANGE Entity=2 tag=CURRENT_PLAYER value=1")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=71 CardID=CHOICE_A")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() -     tag=CONTROLLER value=1")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() -     tag=CARDTYPE value=SPELL")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() -     tag=ZONE value=SETASIDE")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=72 CardID=CHOICE_B")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() -     tag=CONTROLLER value=1")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() -     tag=CARDTYPE value=SPELL")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() -     tag=ZONE value=SETASIDE")
        appendLine("D 01:00:02.1000000 GameState.DebugPrintEntityChoices() - id=6 Player=LocalPlayer#1234 TaskList=7 ChoiceType=GENERAL CountMin=1 CountMax=1")
        appendLine("D 01:00:02.1000000 GameState.DebugPrintEntityChoices() -   Source=10")
        appendLine("D 01:00:02.1000000 GameState.DebugPrintEntityChoices() -   Entities[0]=[entityName=CHOICE_A id=71 cardId=CHOICE_A player=1]")
        appendLine("D 01:00:02.1000000 GameState.DebugPrintEntityChoices() -   Entities[1]=[entityName=CHOICE_B id=72 cardId=CHOICE_B player=1]")
    }

    private fun buildActionLog(): String = buildString {
        append(openingHeader())
        appendLine("D 01:00:01.1000000 GameState.DebugPrintEntityChoices() - id=2 Player=LocalPlayer#1234 TaskList=7 ChoiceType=MULLIGAN CountMin=0 CountMax=3")
        appendLine("D 01:00:01.2000000 GameState.SendChoices() - id=2 ChoiceType=MULLIGAN")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() - TAG_CHANGE Entity=GameEntity tag=STEP value=MAIN_ACTION")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() - TAG_CHANGE Entity=2 tag=CURRENT_PLAYER value=1")
        listOf(
            Triple(31, "CARD_ALPHA", "HAND" to "MINION"),
            Triple(32, "CARD_BETA", "HAND" to "SPELL"),
            Triple(33, "CARD_SIDE_1", "HAND" to "SPELL"),
        ).forEach { (id, card, zt) ->
            appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=$id CardID=$card")
            appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() -     tag=CONTROLLER value=1")
            appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() -     tag=ZONE value=${zt.first}")
            appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() -     tag=CARDTYPE value=${zt.second}")
        }
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=41 CardID=BOARD_M1")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() -     tag=CONTROLLER value=1")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() -     tag=ZONE value=PLAY")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() -     tag=CARDTYPE value=MINION")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=42 CardID=LOC_1")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() -     tag=CONTROLLER value=1")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() -     tag=ZONE value=PLAY")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() -     tag=CARDTYPE value=LOCATION")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=20 CardID=OPP_M1")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() -     tag=CONTROLLER value=2")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() -     tag=ZONE value=PLAY")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() -     tag=CARDTYPE value=MINION")
        appendLine("D 01:00:02.1000000 GameState.DebugPrintOptions() - id=52")
        appendLine("D 01:00:02.1000000 GameState.DebugPrintOptions() -   option 0 type=END_TURN mainEntity= error=INVALID errorParam=")
        appendLine("D 01:00:02.1000000 GameState.DebugPrintOptions() -   option 1 type=POWER mainEntity=31 error=NONE errorParam=")
        appendLine("D 01:00:02.1000000 GameState.DebugPrintOptions() -   option 2 type=POWER mainEntity=32 error=NONE errorParam=")
        appendLine("D 01:00:02.1000000 GameState.DebugPrintOptions() -     target 0 entity=32 error=NONE errorParam=")
    }

    private fun buildOpponentLog(): String = buildString {
        append(openingHeader())
        appendLine("D 01:00:01.1000000 GameState.DebugPrintEntityChoices() - id=2 Player=LocalPlayer#1234 TaskList=7 ChoiceType=MULLIGAN CountMin=0 CountMax=3")
        appendLine("D 01:00:01.2000000 GameState.SendChoices() - id=2 ChoiceType=MULLIGAN")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() - TAG_CHANGE Entity=GameEntity tag=STEP value=MAIN_ACTION")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() - TAG_CHANGE Entity=GameEntity tag=TURN value=2")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() - TAG_CHANGE Entity=2 tag=CURRENT_PLAYER value=0")
        appendLine("D 01:00:01.3000000 GameState.DebugPrintPower() - TAG_CHANGE Entity=3 tag=CURRENT_PLAYER value=1")
    }
}
