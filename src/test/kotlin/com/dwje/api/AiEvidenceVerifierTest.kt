package com.dwje.api

import com.dwje.api.common.util.TimeWindow
import com.dwje.api.repository.DocChunkRef
import com.dwje.api.repository.DocEvidenceRepository
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.DashboardAiRepository
import com.dwje.api.service.AiEvidenceVerifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * sLLM 근거 대조 테스트
 *
 * ## 무엇을 막는가
 * "근거 없는 문장은 그리지 않는다" 로는 막히지 않는다. 모델은 그럴듯한 근거를
 * **지어낸다.** `{kind:"yield", key:"W110", value:"96.2%"}` 는 흉내내기 쉽고,
 * 화면은 근거가 붙었다고 보고 그대로 그린다. 지어낸 값을 그냥 내리는 것보다
 * **더 나쁘다** — 사람이 의심하지 않게 된다.
 *
 * 그래서 서버가 값을 **다시 계산해 대조한다.** 실제 W110 수율은 98.36% 이므로
 * 96.2% 를 주장하는 문장은 버려진다.
 *
 * `ref` URL 을 조회하는 방식은 쓰지 않는다 — 그 URL 도 모델이 쓴 문자열이라
 * 200 이 돌아오는 아무 엔드포인트나 적으면 통과한다.
 */
class AiEvidenceVerifierTest {

    /** 실제 값을 대신 돌려주는 조회 — 08-28 실측값을 쓴다. */
    private class FakeRepo : DashboardAiRepository(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(
        org.springframework.jdbc.datasource.SimpleDriverDataSource()
    )) {
        override fun findSummary(plantCd: String, window: TimeWindow): Map<String, Any?> =
            mapOf("todayQty" to 3_992_507L, "defectRate" to 1.69)

        // 실제 Repository 가 돌려주는 키를 그대로 맞춘다 —
        // 이름 키(process·label·eqptNm)를 빠뜨리면 정본 이름 검증이 헛돈다.
        override fun findProcessYield(plantCd: String, window: TimeWindow): List<Map<String, Any?>> =
            listOf(
                mapOf("processId" to "W110", "process" to "W110", "yield" to 98.36),
                mapOf("processId" to "W150", "process" to "W150", "yield" to 98.00)
            )

        override fun findDefectComposition(
            plantCd: String,
            window: TimeWindow,
            processId: String?
        ): List<Map<String, Any?>> =
            listOf(mapOf("code" to "D01", "label" to "찍힘", "value" to 37_030L))

        override fun findLineProduction(
            plantCd: String,
            window: TimeWindow,
            processId: String?
        ): List<Map<String, Any?>> =
            listOf(
                mapOf(
                    "eqptCd" to "BG-011", "eqptNm" to "BG-011호기",
                    "defectRate" to 1.64, "qty" to 3570L, "ngQty" to 58L
                )
            )
    }

    /**
     * 문서 근거 조회를 대신한다.
     *
     * chunkId 로 상태를 갈라 다섯 갈래를 모두 시험한다 —
     * 1 정상 · 2 권한 없음 · 3 보존기한 경과 · 4 합성 개요 청크 · 그 밖 없음
     */
    private class FakeDocRepo : DocEvidenceRepository(
        org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(
            org.springframework.jdbc.datasource.SimpleDriverDataSource()
        )
    ) {
        override fun findChunkForVerify(chunkId: Long, userId: String): DocChunkRef? = when (chunkId) {
            1L -> ref(1L, allowed = true, expired = false)
            2L -> ref(2L, allowed = false, expired = false)
            3L -> ref(3L, allowed = true, expired = true)
            // 합성 개요 청크 — 인용 대조는 통과하지만 근거가 되지 못한다.
            4L -> ref(4L, allowed = true, expired = false, seq = 0)
            // heading 이 이미 쪽 표기인 청크 — 실제 데이터에 있다.
            5L -> ref(5L, allowed = true, expired = false)
            // 압축 내부 문서
            6L -> ref(6L, allowed = true, expired = false)
            else -> null
        }

        private fun ref(id: Long, allowed: Boolean, expired: Boolean, seq: Int = 3) = DocChunkRef(
            chunkId = id,
            chunkSeq = seq,
            text = "찍힘:Stamping 공정에서 Chip에 의해 발생\n설비 Cleaning주기 재차 교육 진행",
            title = "[LGIT CM] 25년 외관 품질 달성방안",
            docTypeCd = "MINUTES",
            docDate = "2025-04-03",
            docUid = "11111111-2222-3333-4444-555555555555",
            fileNm = "25년 외관 품질 달성방안_250403.pptx",
            sourcePath = if (id == 6L) {
                // 압축 안에서 추출된 문서 — 실제 46건이 이 형태다.
                "/Volumes/[C] Windows 11.hidden/덕우전자_NAS/FACA/6. DPBU/25y/분석.zip!/1차/NG1.xls"
            } else {
                "/Volumes/[C] Windows 11.hidden/덕우전자_NAS/FACA/1. LGIT CM/25y"
            },
            pageNo = 8,
            sectionPath = if (id == 5L) {
                // 실제 데이터는 section_path 가 이미 쪽으로 끝난다.
                "LGIT CM > 01.25y_품질정기미팅 > p8"
            } else {
                "LGIT CM > 01.25y_품질정기미팅"
            },
            heading = if (id == 5L) null else "재발 방지 대책",
            allowed = allowed,
            expired = expired
        )
    }

    private val verifier = AiEvidenceVerifier(FakeRepo(), FakeDocRepo(), AppProperties())
    /** 하루 구간 — 대조는 이 구간으로 한다. 모델 입력과 같은 구간이어야 한다. */
    private val date = TimeWindow.ofDay(LocalDate.of(2026, 8, 28))

    private fun mask(vararg fields: String) = MaskingSupport(
        UserPrincipal(
            userId = "10001", userName = "테스터", deptId = 1, deptName = "품질보증팀",
            deptAbbr = null, positionCd = "STAFF", plantCd = "PL01",
            superAdmin = false, dataPerms = fields.toSet()
        )
    )

    private fun line(text: String, vararg evidence: Map<String, Any?>) =
        mapOf("text" to text, "evidence" to evidence.toList())

    @Test
    @DisplayName("1. 지어낸 수치는 버린다 — 실제 98.36% 인데 96.2% 를 주장하면 탈락")
    fun dropsFabricatedValue() {
        val result = verifier.verifyLines(
            listOf(
                line("W110 수율이 96.2% 입니다", mapOf("kind" to "yield", "key" to "W110", "value" to "96.2%"))
            ),
            date, mask(DataField.QTY, DataField.YIELD), "10001")

        assertTrue(result.lines.isEmpty(), "실제 값과 다른 근거를 든 문장은 내려보내면 안 된다")
        assertEquals(1, result.droppedCnt)
        assertEquals("VALUE_MISMATCH", result.dropped.first().reason)
    }

    @Test
    @DisplayName("2. 맞는 수치는 통과한다 — 표기 반올림(98.4%)까지 허용")
    fun keepsCorrectValue() {
        val result = verifier.verifyLines(
            listOf(
                line("W110 수율 98.4%", mapOf("kind" to "yield", "key" to "W110", "value" to "98.4%")),
                line("총 생산 3,992,507EA", mapOf("kind" to "qty", "value" to "3,992,507 EA"))
            ),
            date, mask(DataField.QTY, DataField.YIELD), "10001")

        assertEquals(2, result.lines.size, "실제 값과 맞는 문장은 통과해야 한다")
        assertEquals(0, result.droppedCnt)
        assertTrue(result.lines.all { it["verified"] == true }, "통과한 문장에 verified 가 붙어야 한다")
    }

    @Test
    @DisplayName("3. 없는 대상을 근거로 들면 버린다 — 설비 마스터에 없는 PR-03")
    fun dropsUnknownTarget() {
        val result = verifier.verifyLines(
            listOf(
                line(
                    "프레스 3호기(PR-03) 불량률 4.25%",
                    mapOf("kind" to "anomaly", "key" to "PR-03", "value" to "4.25%")
                )
            ),
            date, mask(DataField.QTY, DataField.YIELD), "10001")

        assertEquals(1, result.droppedCnt, "없는 설비를 근거로 들면 버려야 한다")
        assertEquals("NOT_FOUND", result.dropped.first().reason)
    }

    @Test
    @DisplayName("4. 모르는 근거 종류는 버린다 — 통과시키면 검증이 무의미해진다")
    fun dropsUnknownKind() {
        val result = verifier.verifyLines(
            listOf(
                line(
                    "타발 압력 편차 ±14%",
                    mapOf("kind" to "press_tonnage", "value" to "118.4")
                )
            ),
            date, mask(DataField.QTY, DataField.YIELD), "10001")

        assertEquals(1, result.droppedCnt, "대조할 방법이 없는 근거는 버려야 한다")
        assertEquals("UNKNOWN_KIND", result.dropped.first().reason)
    }

    @Test
    @DisplayName("5. 근거가 없는 문장은 버린다")
    fun dropsLineWithoutEvidence() {
        val result = verifier.verifyLines(
            listOf(mapOf("text" to "SPM 5% 감속을 권고합니다", "evidence" to emptyList<Any>())),
            date, mask(DataField.QTY, DataField.YIELD), "10001")

        assertEquals(1, result.droppedCnt)
        assertEquals("NO_EVIDENCE", result.dropped.first().reason)
    }

    @Test
    @DisplayName("6. 가려진 항목의 근거는 버린다 — 통과시키면 마스킹이 뚫린다")
    fun dropsMaskedEvidence() {
        // 수율 권한이 없는 부서.
        val result = verifier.verifyLines(
            listOf(
                line("W110 수율 98.4%", mapOf("kind" to "yield", "key" to "W110", "value" to "98.4%"))
            ),
            date, mask(DataField.QTY), "10001")

        assertEquals(1, result.droppedCnt, "값이 맞아도 가려진 항목이면 내려보내면 안 된다")
        assertEquals("MASKED", result.dropped.first().reason)
    }

    @Test
    @DisplayName("7. 맞는 근거와 지어낸 근거가 섞인 문장은 전체를 버린다")
    fun dropsLineWithAnyBadEvidence() {
        val result = verifier.verifyLines(
            listOf(
                line(
                    "W110 수율 98.4% 이며 금형 온도 48.5℃ 가 원인입니다",
                    mapOf("kind" to "yield", "key" to "W110", "value" to "98.4%"),
                    mapOf("kind" to "die_temp", "value" to "48.5")
                )
            ),
            date, mask(DataField.QTY, DataField.YIELD), "10001")

        assertEquals(1, result.droppedCnt, "근거 하나라도 대조에 실패하면 그 문장을 믿을 수 없다")
        assertTrue(result.lines.isEmpty())
    }

    @Test
    @DisplayName("8. droppedCnt 로 몇 건을 버렸는지 알린다 — 조용히 버리지 않는다")
    fun reportsDroppedCount() {
        val result = verifier.verifyLines(
            listOf(
                line("맞는 문장", mapOf("kind" to "qty", "value" to "3992507")),
                line("지어낸 문장 1", mapOf("kind" to "yield", "key" to "W110", "value" to "50.0")),
                line("지어낸 문장 2", mapOf("kind" to "unknown", "value" to "1"))
            ),
            date, mask(DataField.QTY, DataField.YIELD), "10001")

        assertEquals(1, result.lines.size)
        assertEquals(2, result.droppedCnt, "화면이 '근거가 확인되지 않아 2건을 뺐습니다' 를 적을 수 있어야 한다")
    }

    @Test
    @DisplayName("9. 통과한 근거의 이름·값은 서버 값으로 덮어쓴다 — 모델 표기를 그대로 두면 이름이 지어내진다")
    fun canonicalizesLabelAndValue() {
        val result = verifier.verifyLines(
            listOf(
                line(
                    "W110 수율 98.4%",
                    mapOf(
                        "kind" to "yield", "key" to "W110", "value" to "98.4%",
                        // 모델이 지어낸 이름 — 실제 작업장 이름이 아니다.
                        "label" to "프레스 3호기 수율"
                    )
                )
            ),
            date, mask(DataField.QTY, DataField.YIELD), "10001")

        assertEquals(1, result.lines.size)

        @Suppress("UNCHECKED_CAST")
        val ev = (result.lines.first()["evidence"] as List<Map<String, Any?>>).first()

        assertEquals(
            "W110 수율", ev["label"],
            "이름은 마스터에서 온 값이어야 한다. 모델이 쓴 '프레스 3호기 수율' 이 남으면 " +
                "값은 맞는데 이름이 지어내진 채 화면에 그려진다"
        )
        assertEquals(
            98.36, ev["value"],
            "값도 서버가 구한 실제 값으로 덮어쓴다 — 검증한 값과 보여 준 값이 같아야 한다"
        )
        assertEquals("%", ev["unit"], "단위를 따로 알려 화면이 표기를 만든다")
    }

    @Test
    @DisplayName("10. 종류마다 정본 이름이 있다 — 이름을 못 내는 종류가 있으면 실패")
    fun everyKindHasCanonicalLabel() {
        val cases = listOf(
            Triple("qty", null, "총 생산 수량"),
            Triple("defect_rate", null, "전체 불량률"),
            Triple("yield", "W150", "W150 수율")
        )

        cases.forEach { (kind, key, expected) ->
            val ev = mutableMapOf<String, Any?>("kind" to kind, "value" to "0")
            if (key != null) ev["key"] = key
            // 값 대조는 통과시키기 위해 실제 값을 넣는다.
            val actual = when (kind) {
                "qty" -> "3992507"
                "defect_rate" -> "1.69"
                else -> "98.0"
            }
            ev["value"] = actual

            val result = verifier.verifyLines(
                listOf(mapOf("text" to "t", "evidence" to listOf(ev.toMap()))),
                date, mask(DataField.QTY, DataField.YIELD), "10001")

            assertEquals(1, result.lines.size, "$kind 이 통과해야 한다: ${result.dropped}")

            @Suppress("UNCHECKED_CAST")
            val got = (result.lines.first()["evidence"] as List<Map<String, Any?>>).first()
            assertEquals(expected, got["label"], "$kind 의 정본 이름")
        }
    }

    // ── kind=doc ────────────────────────────────────────────────────────────────
    //
    // 문서 근거는 수치가 아니라 **인용문**을 대조한다. 청크 실재만 보면 모델이
    // 아무 청크나 붙이고 그 안에 없는 말을 할 수 있다.

    private fun docLine(chunkId: Long, quote: String?) = mapOf(
        "text" to "조치 문장",
        "evidence" to listOf(
            buildMap<String, Any?> {
                put("kind", "doc")
                put("key", chunkId.toString())
                if (quote != null) put("quote", quote)
            }
        )
    )

    @Test
    @DisplayName("11. 인용문이 원문에 그대로 있으면 통과한다")
    fun docQuoteFound() {
        val result = verifier.verifyLines(
            listOf(docLine(1L, "설비 Cleaning주기 재차 교육 진행")),
            date, mask(DataField.QTY, DataField.YIELD), "10001")
        assertEquals(1, result.lines.size, "실제 원문에 있는 인용문은 통과해야 한다: ${result.dropped}")
    }

    @Test
    @DisplayName("12. 줄바꿈만 다른 인용문도 통과한다 — 뜻은 판정하지 않는다")
    fun docQuoteNormalized() {
        val result = verifier.verifyLines(
            // 원문은 줄바꿈으로 끊겨 있다. 공백만 정규화해 비교한다.
            listOf(docLine(1L, "Chip에 의해 발생 설비 Cleaning주기")),
            date, mask(DataField.QTY, DataField.YIELD), "10001")
        assertEquals(1, result.lines.size, "공백·줄바꿈 차이는 통과해야 한다: ${result.dropped}")
    }

    @Test
    @DisplayName("13. 원문에 없는 말을 인용하면 버린다 — 청크 실재만으로는 부족하다")
    fun docQuoteFabricated() {
        val result = verifier.verifyLines(
            listOf(docLine(1L, "타발 압력을 118.4 Ton 으로 낮출 것")),
            date, mask(DataField.QTY, DataField.YIELD), "10001")
        assertEquals(1, result.droppedCnt)
        assertEquals(
            "QUOTE_MISMATCH", result.dropped.first().reason,
            "청크는 실재하지만 그 안에 없는 말이면 버려야 한다"
        )
    }

    @Test
    @DisplayName("14. 인용문이 없으면 버린다 — quote 를 required 에서 빼면 모델이 안 낸다")
    fun docWithoutQuote() {
        val result = verifier.verifyLines(
            listOf(docLine(1L, null)),
            date, mask(DataField.QTY, DataField.YIELD), "10001")
        assertEquals(1, result.droppedCnt)
        assertEquals("NO_EVIDENCE", result.dropped.first().reason)
    }

    @Test
    @DisplayName("15. 볼 수 없는 문서는 버린다 — 통과시키면 문서 내용이 문장으로 새어 나간다")
    fun docNotAllowed() {
        val result = verifier.verifyLines(
            listOf(docLine(2L, "설비 Cleaning주기 재차 교육 진행")),
            date, mask(DataField.QTY, DataField.YIELD), "10001")
        assertEquals(1, result.droppedCnt)
        assertEquals(
            "MASKED", result.dropped.first().reason,
            "인용문이 진짜여도 그 문서를 못 보는 사용자에게는 근거로 붙일 수 없다"
        )
    }

    @Test
    @DisplayName("16. 보존기한이 지난 문서는 버린다 — fn_allowed_doc 이 보지 않는 항목")
    fun docExpired() {
        val result = verifier.verifyLines(
            listOf(docLine(3L, "설비 Cleaning주기 재차 교육 진행")),
            date, mask(DataField.QTY, DataField.YIELD), "10001")
        assertEquals(1, result.droppedCnt)
        assertEquals("EXPIRED", result.dropped.first().reason)
    }

    @Test
    @DisplayName("17. 없는 청크는 버린다")
    fun docNotFound() {
        val result = verifier.verifyLines(
            listOf(docLine(99L, "아무 말")),
            date, mask(DataField.QTY, DataField.YIELD), "10001")
        assertEquals(1, result.droppedCnt)
        assertEquals("NOT_FOUND", result.dropped.first().reason)
    }

    @Test
    @DisplayName("18. 통과한 문서 근거의 이름·참조는 마스터 값으로 덮어쓴다")
    fun docCanonicalized() {
        val result = verifier.verifyLines(
            listOf(docLine(1L, "설비 Cleaning주기 재차 교육 진행")),
            date, mask(DataField.QTY, DataField.YIELD), "10001")

        @Suppress("UNCHECKED_CAST")
        val ev = (result.lines.first()["evidence"] as List<Map<String, Any?>>).first()

        assertEquals(
            "[LGIT CM] 25년 외관 품질 달성방안 · MINUTES · 2025-04-03", ev["label"],
            "화면은 label 로 '무슨 문서인지' 를 읽는다 — 모델이 지어낼 수 없게 마스터 값으로 덮어쓴다"
        )
        assertEquals(
            "11111111-2222-3333-4444-555555555555", ev["ref"],
            "외부로 내보내는 참조는 내부 id 가 아니라 doc_uid 다"
        )
    }

    @Test
    @DisplayName("19. 합성 개요 청크는 근거가 되지 못한다 — 인용 대조는 통과하지만 실체가 없다")
    fun rejectsSyntheticChunk() {
        val result = verifier.verifyLines(
            // 인용문 자체는 원문에 있다. 그래도 개요 청크면 버린다.
            listOf(docLine(4L, "설비 Cleaning주기 재차 교육 진행")),
            date, mask(DataField.QTY, DataField.YIELD), "10001"
        )

        assertEquals(1, result.droppedCnt)
        assertEquals(
            "SYNTHETIC_CHUNK", result.dropped.first().reason,
            "chunk_seq=0 은 파이프라인이 만든 문서 개요(문서명·고객사·경로)라 " +
                "사람이 쓴 원인·대책이 아니다. 1,476건 전부 그렇다"
        )
    }

    @Test
    @DisplayName("20. 불량률 근거에는 분자·분모를 함께 낸다 — 100% 가 1/1 인지 3,570/3,570 인지 구분해야 한다")
    fun anomalyCarriesDenominator() {
        val result = verifier.verifyLines(
            listOf(
                line(
                    "BG-011호기 불량률이 높습니다",
                    mapOf("kind" to "anomaly", "key" to "BG-011", "value" to "1.64")
                )
            ),
            date, mask(DataField.QTY, DataField.YIELD), "10001"
        )

        assertEquals(1, result.lines.size, "값이 맞으면 통과해야 한다: ${result.dropped}")

        @Suppress("UNCHECKED_CAST")
        val ev = (result.lines.first()["evidence"] as List<Map<String, Any?>>).first()

        assertEquals(58.0, ev["numerator"], "불량 수량이 분자다")
        assertEquals(
            3570.0, ev["denominator"],
            "생산 수량이 분모다. 없으면 화면이 100% 의 크기를 판단할 수 없다"
        )
    }

    @Test
    @DisplayName("21. 같은 근거가 여러 번 실리면 한 번만 낸다")
    fun dedupesRepeatedEvidence() {
        val result = verifier.verifyLines(
            listOf(
                mapOf(
                    "text" to "조치",
                    "evidence" to listOf(
                        mapOf("kind" to "doc", "key" to "1", "quote" to "설비 Cleaning주기 재차 교육 진행"),
                        // 모델이 같은 인용을 두 번 담는 일이 실제로 있다.
                        mapOf("kind" to "doc", "key" to "1", "quote" to "설비 Cleaning주기 재차 교육 진행"),
                        // 줄바꿈만 다른 것도 같은 근거다.
                        mapOf("kind" to "doc", "key" to "1", "quote" to "설비 Cleaning주기  재차 교육 진행")
                    )
                )
            ),
            date, mask(DataField.QTY, DataField.YIELD), "10001"
        )

        assertEquals(1, result.lines.size, "인용이 원문에 있으므로 통과해야 한다: ${result.dropped}")

        @Suppress("UNCHECKED_CAST")
        val ev = result.lines.first()["evidence"] as List<Map<String, Any?>>
        assertEquals(1, ev.size, "같은 근거는 한 번만 그려야 한다")
    }

    @Test
    @DisplayName("22. 표기를 고쳐 쓴 인용문은 중복 제거가 아니라 대조에서 탈락한다")
    fun rewordedQuoteIsRejectedNotDeduped() {
        // 띄어쓰기를 없앤 인용문은 원문에 없는 문자열이다.
        // 중복 판정은 느슨해도, **대조는 그대로 엄격해야 한다.**
        val result = verifier.verifyLines(
            listOf(docLine(1L, "설비Cleaning주기 재차 교육 진행")),
            date, mask(DataField.QTY, DataField.YIELD), "10001"
        )

        assertEquals(1, result.droppedCnt)
        assertEquals(
            "QUOTE_MISMATCH", result.dropped.first().reason,
            "대조를 중복 판정만큼 느슨하게 하면 모델이 고쳐 쓴 인용문도 통과한다"
        )
    }

    @Test
    @DisplayName("23. 문서 근거에 경로·파일명·쪽을 함께 낸다 — 사용자가 원본을 찾아본다")
    fun docCarriesFileLocation() {
        val result = verifier.verifyLines(
            listOf(docLine(1L, "설비 Cleaning주기 재차 교육 진행")),
            date, mask(DataField.QTY, DataField.YIELD), "10001"
        )

        @Suppress("UNCHECKED_CAST")
        val ev = (result.lines.first()["evidence"] as List<Map<String, Any?>>).first()

        assertEquals("25년 외관 품질 달성방안_250403.pptx", ev["fileName"])
        assertEquals("/Volumes/[C] Windows 11.hidden/덕우전자_NAS/FACA/1. LGIT CM/25y", ev["path"])
        assertEquals(
            "1. LGIT CM/25y", ev["relativePath"],
            "적재 PC 의 마운트 지점을 떼야 다른 PC 에서도 뜻이 통한다"
        )
        assertEquals(8, ev["page"])
        assertEquals(
            "LGIT CM > 01.25y_품질정기미팅 > 재발 방지 대책 > p8", ev["location"],
            "문서 안에서 어디인지 한 줄로 읽히게 낸다"
        )
    }

    @Test
    @DisplayName("24. section_path 가 이미 쪽으로 끝나면 쪽을 두 번 적지 않는다")
    fun locationDoesNotRepeatPage() {
        val result = verifier.verifyLines(
            listOf(docLine(5L, "설비 Cleaning주기 재차 교육 진행")),
            date, mask(DataField.QTY, DataField.YIELD), "10001"
        )

        @Suppress("UNCHECKED_CAST")
        val ev = (result.lines.first()["evidence"] as List<Map<String, Any?>>).first()

        assertEquals(
            "LGIT CM > 01.25y_품질정기미팅 > p8", ev["location"],
            "section_path 가 이미 p8 로 끝나는데 쪽까지 붙이면 '… > p8 > p8' 이 된다"
        )
    }

    @Test
    @DisplayName("25. 압축 안에서 추출된 문서는 압축파일과 내부 경로를 갈라 낸다")
    fun splitsArchivePath() {
        val result = verifier.verifyLines(
            listOf(docLine(6L, "설비 Cleaning주기 재차 교육 진행")),
            date, mask(DataField.QTY, DataField.YIELD), "10001"
        )

        @Suppress("UNCHECKED_CAST")
        val ev = (result.lines.first()["evidence"] as List<Map<String, Any?>>).first()

        assertEquals(
            "/Volumes/[C] Windows 11.hidden/덕우전자_NAS/FACA/6. DPBU/25y/분석.zip",
            ev["archivePath"],
            "열 수 있는 것은 압축파일까지다"
        )
        assertEquals("1차/NG1.xls", ev["innerPath"], "내부 경로는 따로 보여 준다")
        assertEquals("6. DPBU/25y/분석.zip!/1차/NG1.xls", ev["relativePath"])
    }

    @Test
    @DisplayName("26. 압축이 아니면 압축 관련 값은 비운다 — 없는 것을 만들지 않는다")
    fun noArchiveFieldsForPlainFile() {
        val result = verifier.verifyLines(
            listOf(docLine(1L, "설비 Cleaning주기 재차 교육 진행")),
            date, mask(DataField.QTY, DataField.YIELD), "10001"
        )

        @Suppress("UNCHECKED_CAST")
        val ev = (result.lines.first()["evidence"] as List<Map<String, Any?>>).first()

        assertNull(ev["archivePath"])
        assertNull(ev["innerPath"])
    }
}
