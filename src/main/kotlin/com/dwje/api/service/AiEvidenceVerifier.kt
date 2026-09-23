package com.dwje.api.service

import com.dwje.api.common.util.TimeWindow
import com.dwje.api.repository.DocChunkRef
import com.dwje.api.common.util.DataField
import com.dwje.api.common.util.MaskingSupport
import com.dwje.api.config.AppProperties
import com.dwje.api.repository.DashboardAiRepository
import com.dwje.api.repository.DocEvidenceRepository
import java.time.LocalDate
import kotlin.math.abs
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * sLLM 이 낸 문장의 **근거를 서버가 다시 계산해 대조한다**
 *
 * ## 왜 필요한가
 * "근거 없는 문장은 그리지 않는다" 로는 막히지 않는다. 모델은 그럴듯한 근거를
 * **지어낸다** — `{kind:"metric", label:"공정별 수율 W110", value:"96.2%",
 * ref:"/dashboard/process/product-yield?date=..."}` 같은 모양은 흉내내기 쉽다.
 * 값도 URL 도 그럴듯한데 실제로는 없는 값일 수 있다.
 *
 * 그러면 화면은 "근거가 붙었네" 하고 그대로 그린다. **지어낸 값을 그냥 내리는 것보다
 * 더 나쁘다** — 근거처럼 보이는 것이 붙어서 사람이 의심하지 않게 된다.
 *
 * ## 왜 ref 를 조회하지 않고 다시 계산하는가
 * `ref` 는 모델이 쓴 **문자열**이다. 그 URL 을 서버가 호출해 확인하는 방식은
 * 검증 자체를 흉내낼 수 있다 — 모델이 아무 URL 이나 적어도 200 이 돌아오는
 * 엔드포인트를 고르면 통과한다. 그래서 `ref` 를 믿지 않고, `kind` 와 파라미터로
 * **서버가 진짜 값을 직접 구해** 모델이 말한 `value` 와 대조한다.
 *
 * 확인할 수 있는 자리는 서버뿐이다 — DB 를 들고 있는 것도, 부서 권한을 아는 것도
 * 서버다. 모델에게 자기검열을 맡기면 뚫린다.
 *
 * ## 대조에 실패하면
 * 그 문장을 **버린다.** 몇 건을 버렸는지는 `droppedCnt` 로 알린다 —
 * 조용히 버리면 사용자가 모델이 무엇을 말했는지 알 수 없다.
 */
@Service
class AiEvidenceVerifier(
    private val dashboardAiRepository: DashboardAiRepository,
    private val docEvidenceRepository: DocEvidenceRepository,
    private val appProperties: AppProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * 수치 대조 허용 오차 (%)
         *
         * 모델이 96.23 을 96.2 로 줄여 쓰는 것은 통과시킨다. 그 정도 반올림까지
         * 막으면 맞는 문장도 전부 버려진다. 다만 허용치를 넓히면 검증이 헐거워지므로
         * 표기 반올림 수준으로만 둔다.
         */
        private const val TOLERANCE_PCT = 0.5

        /** 확인할 수 없는 근거 종류 — 대조 대상이 아니므로 문장을 버린다. */
        private const val REASON_UNKNOWN_KIND = "UNKNOWN_KIND"
        private const val REASON_NOT_FOUND = "NOT_FOUND"
        private const val REASON_VALUE_MISMATCH = "VALUE_MISMATCH"
        private const val REASON_MASKED = "MASKED"

        /** 문서 근거 종류 — 수치가 아니라 인용문을 대조한다. */
        private const val KIND_DOC = "doc"

        /**
         * 압축파일 내부 경로 구분자.
         *
         * 적재 파이프라인이 압축 안에서 뽑은 문서를 `…zip!/내부경로` 로 적는다(46건).
         * 그대로 열면 없는 경로다 — 열기는 압축파일까지만 해야 한다.
         */
        private const val ARCHIVE_SEPARATOR = "!/"

        /** 인용문 정규화용 — 공백·줄바꿈만 줄인다. */
        private val WHITESPACE = Regex("\\s+")

        /**
         * 중복 판정용 — 공백과 문장부호를 지운다.
         *
         * 같은 대책이 문서마다 띄어쓰기·마침표가 달라 문자열로는 다르게 보인다.
         * **대조가 아니라 중복 판정에만** 쓴다. 대조를 이 정도로 느슨하게 하면
         * 모델이 고쳐 쓴 인용문도 통과한다.
         */
        private val COMPARE_NOISE = Regex("""[\s.,·:;()\[\]"'→>·]+""")
        private const val REASON_NO_EVIDENCE = "NO_EVIDENCE"

        /** 보존기한이 지난 문서 — fn_allowed_doc 이 보지 않는 항목이라 여기서 막는다. */
        private const val REASON_EXPIRED = "EXPIRED"

        /** 인용문이 원문에 없음 — 청크는 실재하는데 그 안에 없는 말을 한 경우 */
        private const val REASON_QUOTE_MISMATCH = "QUOTE_MISMATCH"

        /**
         * 합성 개요 청크를 근거로 든 경우.
         *
         * `chunk_seq = 0` 은 파이프라인이 만든 문서 개요다(문서명·고객사·경로).
         * 인용 대조는 통과하지만 사람이 쓴 원인·대책이 아니라 근거가 되지 못한다.
         */
        private const val REASON_SYNTHETIC_CHUNK = "SYNTHETIC_CHUNK"
    }

    /**
     * 문장 목록을 검증해 **대조를 통과한 것만** 돌려준다.
     *
     * @param lines 모델이 낸 문장 — 각 항목은 `text` 와 `evidence` 배열을 갖는다
     * @param date   대상 일자 — 근거를 다시 계산할 기준
     * @param mask   부서별 데이터 항목 권한
     * @param userId 문서 권한 판정 대상. 스레드 로컬에서 꺼내지 않고 받는다 —
     *               숨은 의존성이면 이 관문을 테스트할 수 없다
     * @param blindAttrs 조회자가 못 보는 응답 필드명([DataFieldService.blindAttrNames]) — 근거 이름표(label)에
     *               공정·설비 이름을 넣을 때 여기 걸리면 이름 대신 코드를 쓴다
     * @return 통과한 문장(`verified = true`)과 버린 건수
     */
    fun verifyLines(
        lines: List<Map<String, Any?>>,
        window: TimeWindow,
        mask: MaskingSupport,
        userId: String,
        blindAttrs: Set<String> = emptySet()
    ): VerifyResult {
        val kept = mutableListOf<Map<String, Any?>>()
        val dropped = mutableListOf<Dropped>()

        lines.forEach { line ->
            @Suppress("UNCHECKED_CAST")
            val evidence = line["evidence"] as? List<Map<String, Any?>> ?: emptyList()

            // 근거가 아예 없는 문장은 대조할 것이 없다 — 버린다.
            if (evidence.isEmpty()) {
                dropped += Dropped(line["text"] as? String, REASON_NO_EVIDENCE, null)
                return@forEach
            }

            // 근거 하나라도 대조에 실패하면 그 문장을 버린다.
            // 맞는 근거와 지어낸 근거가 섞인 문장은 전체를 믿을 수 없다.
            val failure = evidence.asSequence()
                .map { verifyOne(it, window, mask, userId) }
                .firstOrNull { it != null }

            if (failure != null) {
                dropped += Dropped(line["text"] as? String, failure.reason, failure.detail)
            } else {
                // 통과한 근거는 **서버가 구한 값과 이름으로 덮어쓴다.**
                // 모델이 쓴 label·value 를 그대로 두면, 값은 맞아도 이름이 지어내진 채
                // 화면에 그려진다. 화면은 label 로 "무슨 근거인지" 를 읽으므로 그것도
                // 검증 대상이어야 한다. 대조를 통과했다는 것은 서버가 진짜 값을 갖고
                // 있다는 뜻이니, 굳이 모델의 표기를 쓸 이유가 없다.
                // 같은 근거가 여러 번 실릴 수 있다 — 모델이 같은 인용을 여러 청크에서
                // 가져오면 화면에 같은 칩이 네 개 그려진다. 통과한 뒤 한 번 정리한다.
                // 같은 대책이 여러 청크에 걸쳐 있으면 **공백·문장부호만 다른 같은 인용문**이
                // 두세 번 온다("제품 측면 부위 검사에 취약점이 있음." vs "제품측면부위검사에취약점이있음.").
                // 문자열이 달라 단순 비교로는 안 걸리므로, 비교할 때만 공백·문장부호를 지운다.
                // 화면에 내려보내는 값은 원문 그대로 둔다.
                val canonical = evidence.map { canonicalize(it, window, mask, userId, blindAttrs) }
                    .distinctBy {
                        listOf(
                            it["kind"], it["label"], it["value"],
                            (it["quote"] as? String)?.let { q -> q.replace(COMPARE_NOISE, "") }
                        )
                    }

                kept += line + mapOf("evidence" to canonical, "verified" to true)
            }
        }

        if (dropped.isNotEmpty()) {
            log.warn(
                "AI 근거 대조 실패로 문장 {}건 제외 : {}",
                dropped.size,
                dropped.joinToString { "${it.reason}(${it.detail ?: "-"})" }
            )
        }

        return VerifyResult(lines = kept, droppedCnt = dropped.size, dropped = dropped)
    }

    /**
     * 근거 한 건을 대조한다.
     *
     * @return 통과하면 `null`, 실패하면 사유
     */
    private fun verifyOne(
        item: Map<String, Any?>,
        window: TimeWindow,
        mask: MaskingSupport,
        userId: String
    ): Failure? {
        val kind = (item["kind"] as? String)?.trim()?.lowercase()
            ?: return Failure(REASON_UNKNOWN_KIND, "kind 없음")
        val key = (item["key"] as? String)?.trim()

        // 문서 근거는 수치가 아니라 인용문을 대조한다. 다른 종류와 절차가 다르다.
        if (kind == KIND_DOC) return verifyDoc(key, item["quote"] as? String, userId)

        val claimed = numberOf(item["value"])
            ?: return Failure(REASON_VALUE_MISMATCH, "value 를 수치로 읽을 수 없음")

        val resolved = resolve(kind, key, window)
            ?: return Failure(REASON_UNKNOWN_KIND, kind)

        // 가려진 항목의 근거는 대조해도 화면에 그릴 수 없고, 그리면 마스킹이 뚫린다.
        if (!mask.allowed(resolved.fieldKey)) return Failure(REASON_MASKED, resolved.fieldKey)
        val actual = resolved.value ?: return Failure(REASON_NOT_FOUND, "$kind/${key ?: "-"}")

        val gap = abs(actual - claimed)
        val allowed = maxOf(abs(actual) * TOLERANCE_PCT / 100.0, TOLERANCE_PCT)
        return if (gap <= allowed) {
            null
        } else {
            Failure(REASON_VALUE_MISMATCH, "$kind/${key ?: "-"} 주장=$claimed 실제=$actual")
        }
    }

    /**
     * 문서 근거를 대조한다. — 합의된 순서 (2026-09-05)
     *
     * 1. 청크가 실재하고 `is_current` · `del_flg='N'` 인가 → 아니면 NOT_FOUND
     * 2. 그 문서를 **이 사용자가 볼 수 있는가**(`fn_allowed_doc`) → 아니면 MASKED
     *    청크가 실재하고 인용문이 진짜여도, 못 보는 문서가 근거로 붙으면
     *    문서 내용이 그 문장을 통해 새어 나간다. 수치 마스킹과 같은 종류의 구멍이다.
     * 3. 보존기한이 지났는가(`retention_until`) → 지났으면 EXPIRED
     * 4. 합성 개요 청크(`chunk_seq = 0`)인가 → 맞으면 SYNTHETIC_CHUNK
     * 5. 인용문이 원문에 **그대로** 있는가 → 없으면 QUOTE_MISMATCH
     *
     * 5번이 핵심이다. 청크 실재만 보면 모델이 아무 청크나 붙이고 그 안에 없는 말을
     * 할 수 있다. 의미 비교는 하지 않는다 — 서버가 뜻을 판정하기 시작하면
     * 그 판정 자체를 또 믿어야 한다. 공백·줄바꿈만 정규화해 문자열 포함만 본다.
     */
    private fun verifyDoc(key: String?, quote: String?, userId: String): Failure? {
        val chunkId = key?.toLongOrNull()
            ?: return Failure(REASON_NOT_FOUND, "chunkId 를 읽을 수 없음: $key")
        val text = quote?.trim()?.takeIf { it.isNotBlank() }
            ?: return Failure(REASON_NO_EVIDENCE, "quote 없음 (chunkId=$chunkId)")

        val ref = docEvidenceRepository.findChunkForVerify(chunkId, userId)
            ?: return Failure(REASON_NOT_FOUND, "chunkId=$chunkId")

        if (!ref.allowed) return Failure(REASON_MASKED, "chunkId=$chunkId")
        if (ref.expired) return Failure(REASON_EXPIRED, "chunkId=$chunkId")
        // 개요 청크는 문서를 가리킬 뿐 조치를 담지 않는다.
        if (ref.chunkSeq == 0) return Failure(REASON_SYNTHETIC_CHUNK, "chunkId=$chunkId")

        return if (normalize(ref.text).contains(normalize(text))) {
            null
        } else {
            Failure(REASON_QUOTE_MISMATCH, "chunkId=$chunkId 인용문이 원문에 없음")
        }
    }

    /**
     * 문서 안에서 어디인지 한 줄로 만든다.
     *
     * `section_path` 가 **이미 쪽으로 끝난다** — "… > p2" · "… > p2 (1/2)" 형태다.
     * 쪽 번호를 그냥 덧붙이면 "… > p2 > p2" 가 된다. 같은 쪽을 두 번 적지 않는다.
     */
    private fun locationOf(ref: DocChunkRef): String? {
        val section = ref.sectionPath?.takeIf { it.isNotBlank() }
        val heading = ref.heading?.takeIf { it.isNotBlank() }
        val page = ref.pageNo?.let { "p$it" }

        // 마지막 구간이 이미 이 쪽을 가리키면 덧붙이지 않는다.
        val lastSegment = section?.substringAfterLast(">")?.trim()
        val pageAlreadyThere = page != null &&
            (lastSegment?.startsWith(page) == true || heading?.startsWith(page) == true)

        return listOfNotNull(
            section,
            heading,
            page?.takeIf { !pageAlreadyThere }
        ).joinToString(" > ").takeIf { it.isNotBlank() }
    }

    /**
     * 적재 PC 의 마운트 지점을 떼고 상대경로를 만든다.
     *
     * 접두를 못 찾으면 원본을 그대로 돌려준다 — 지어내지 않는다.
     */
    private fun relativePath(path: String?): String? {
        if (path == null) return null
        val marker = appProperties.ai.docRootMarker
        val idx = path.indexOf(marker)
        return if (idx < 0) path else path.substring(idx + marker.length)
    }

    /** 압축파일 경로 — `!/` 앞부분. 압축이 아니면 null */
    private fun archiveOf(path: String?): String? =
        path?.takeIf { it.contains(ARCHIVE_SEPARATOR) }?.substringBefore(ARCHIVE_SEPARATOR)

    /** 압축파일 안의 경로 — `!/` 뒷부분. 압축이 아니면 null */
    private fun innerOf(path: String?): String? =
        path?.takeIf { it.contains(ARCHIVE_SEPARATOR) }?.substringAfter(ARCHIVE_SEPARATOR)

    /** 공백·줄바꿈만 정규화한다. 뜻은 건드리지 않는다. */
    private fun normalize(text: String): String = text.replace(WHITESPACE, " ").trim()

    /**
     * 근거 한 건을 **서버가 구한 값·이름으로** 다시 쓴다.
     *
     * `verifyOne` 이 통과시킨 근거에만 부른다. 대조에 실패한 근거는 여기 오지 않는다.
     * 대조에 쓴 조회를 한 번 더 하지만, 검증과 표기를 같은 값에서 뽑기 위해 그대로 둔다 —
     * 두 값이 갈리면 "검증한 값" 과 "보여 준 값" 이 달라진다.
     */
    private fun canonicalize(
        item: Map<String, Any?>,
        window: TimeWindow,
        mask: MaskingSupport,
        userId: String,
        blindAttrs: Set<String>
    ): Map<String, Any?> {
        val kind = (item["kind"] as? String)?.trim()?.lowercase() ?: return item
        val key = (item["key"] as? String)?.trim()

        // 문서 근거의 이름도 모델이 지어낼 수 있다. 화면은 label 로 "무슨 문서인지" 를
        // 읽으므로 제목·종류·일자를 마스터에서 가져와 덮어쓰고, 참조는 doc_uid 로 낸다.
        if (kind == KIND_DOC) {
            val ref = key?.toLongOrNull()
                ?.let { docEvidenceRepository.findChunkForVerify(it, userId) }
                ?: return item
            return item + mapOf(
                "label" to listOfNotNull(ref.title, ref.docTypeCd, ref.docDate).joinToString(" · "),
                // 외부로 내보내는 참조는 내부 id 가 아니라 doc_uid 다.
                "ref" to ref.docUid,
                // 사용자는 "참고할 만한 문서" 를 실제로 찾아본다. 경로·파일명·쪽이 있어야
                // 원본을 열 수 있다. label 만으로는 폴더명 비슷한 문자열일 뿐이다.
                "fileName" to ref.fileNm,
                "path" to ref.sourcePath,
                // 적재 PC 의 마운트 지점을 뗀 상대경로 — 다른 PC 에서도 뜻이 통한다.
                "relativePath" to relativePath(ref.sourcePath),
                // 압축파일 안에서 추출된 문서(46건)는 "…zip!/내부경로" 형태다.
                // 그대로 열면 없는 경로이므로 압축파일과 내부 경로를 갈라 준다.
                "archivePath" to archiveOf(ref.sourcePath),
                "innerPath" to innerOf(ref.sourcePath),
                "page" to ref.pageNo,
                // 문서 안에서 어디인지 — 고객사 > 이슈 > 소제목 > 쪽
                "location" to locationOf(ref),
                "value" to null,
                "unit" to null
            )
        }

        val resolved = resolve(kind, key, window, blindAttrs) ?: return item
        // 분자·분모는 수량이다. 비율(수율 권한)은 볼 수 있어도 수량 권한이 없으면 내지 않는다.
        val qtyAllowed = mask.allowed(DataField.QTY)

        return item + mapOf(
            // 모델이 쓴 이름을 버리고 마스터의 이름을 쓴다.
            "label" to (resolved.label ?: item["label"]),
            // 모델이 쓴 표기를 버리고 서버가 구한 값을 쓴다. 단위는 따로 알린다.
            "value" to resolved.value,
            "unit" to resolved.unit,
            // 비율이면 분자·분모를 함께 낸다 — 크기를 모르면 100% 를 판단할 수 없다.
            "numerator" to resolved.numerator?.takeIf { qtyAllowed },
            "denominator" to resolved.denominator?.takeIf { qtyAllowed }
        )
    }

    /**
     * `kind` 와 `key` 로 **서버가 진짜 값과 이름을 구한다.**
     *
     * 대조할 수 없는 종류는 `null` 을 돌려주고, 그러면 문장이 버려진다.
     * 대상을 찾지 못한 경우는 [Resolved.value] 가 `null` 이다 — 종류는 알지만
     * 그 키가 없는 것이므로 사유가 다르다.
     */
    private fun resolve(kind: String, key: String?, window: TimeWindow, blindAttrs: Set<String> = emptySet()): Resolved? {
        val plantCd = appProperties.defaultPlantCd
        // 이름이 가려진 항목이면 코드로 대신한다 — 이름표는 화면에 그대로 그려진다.
        fun nameOf(attr: String, name: Any?, code: Any?): Any? = if (attr in blindAttrs) code else (name ?: code)

        return when (kind) {
            "qty", "production" -> Resolved(
                fieldKey = DataField.QTY,
                value = numberOf(dashboardAiRepository.findSummary(plantCd, window)["todayQty"]),
                label = "총 생산 수량",
                unit = "EA"
            )

            "defect_rate", "defectrate" -> Resolved(
                fieldKey = DataField.YIELD,
                value = numberOf(dashboardAiRepository.findSummary(plantCd, window)["defectRate"]),
                label = "전체 불량률",
                unit = "%"
            )

            "yield", "metric" -> dashboardAiRepository.findProcessYield(plantCd, window)
                .firstOrNull { key != null && it["processId"] == key }
                .let { row ->
                    Resolved(
                        fieldKey = DataField.YIELD,
                        value = row?.let { numberOf(it["yield"]) },
                        label = row?.let { nameOf("processNm", it["process"], it["processId"]) }?.let { "$it 수율" },
                        unit = "%"
                    )
                }

            "defect" -> dashboardAiRepository.findDefectComposition(plantCd, window, null)
                .firstOrNull { key != null && it["code"] == key }
                .let { row ->
                    Resolved(
                        fieldKey = DataField.YIELD,
                        value = row?.let { numberOf(it["value"]) },
                        label = row?.get("label")?.let { "$it 불량 수량" },
                        unit = "EA"
                    )
                }

            "anomaly", "equipment" -> dashboardAiRepository.findLineProduction(plantCd, window, null)
                .firstOrNull { key != null && it["eqptCd"] == key }
                .let { row ->
                    Resolved(
                        // 설비 불량률은 비율이라 수율 항목이다. 예전에 QTY 로 적혀 있어 수율 권한이 없는
                        // 부서에도 불량률 근거가 통과했다(2026-09-23 WEB 확인, cause-prescription).
                        fieldKey = DataField.YIELD,
                        value = row?.let { numberOf(it["defectRate"]) },
                        label = row?.let { nameOf("eqptNm", it["eqptNm"], it["eqptCd"]) }?.let { "$it 불량률" },
                        unit = "%",
                        // 불량률만 내려보내면 100% 가 1/1 인지 3,570/3,570 인지 알 수 없다.
                        // 분자·분모를 함께 실어 화면이 "100.0% (3,570/3,570)" 로 그릴 수 있게 한다.
                        // 모수가 작은 값은 app.anomaly-min-qty 로 후보에서 이미 걸러지지만,
                        // 통과한 값도 사람이 크기를 보고 판단할 수 있어야 한다.
                        // 분자·분모는 수량 권한까지 있어야 내려간다([canonicalize]).
                        numerator = row?.let { numberOf(it["ngQty"]) },
                        denominator = row?.let { numberOf(it["qty"]) }
                    )
                }

            // 모르는 종류는 대조할 방법이 없다. 통과시키면 검증이 무의미해진다.
            else -> null
        }
    }

    /** 서버가 구한 근거 — 값·이름·단위와 걸리는 데이터 항목 권한 */
    private data class Resolved(
        val fieldKey: String,
        val value: Double?,
        val label: String?,
        val unit: String?,
        /** 비율일 때 분자 — 없으면 null */
        val numerator: Double? = null,
        /** 비율일 때 분모 — 없으면 null. 100% 가 1/1 인지 3,570/3,570 인지 구분한다 */
        val denominator: Double? = null
    )

    /** 문자열에 단위가 붙어 와도 수치만 읽는다. ("96.2%" · "21,963,275 EA") */
    private fun numberOf(value: Any?): Double? = when (value) {
        null -> null
        is Number -> value.toDouble()
        is String -> Regex("""-?[\d,]+(\.\d+)?""").find(value)
            ?.value?.replace(",", "")?.toDoubleOrNull()
        else -> null
    }

    /** 검증 결과 */
    data class VerifyResult(
        val lines: List<Map<String, Any?>>,
        val droppedCnt: Int,
        val dropped: List<Dropped>
    )

    /** 버린 문장 한 건 — 사유를 남겨 조용히 사라지지 않게 한다. */
    data class Dropped(val text: String?, val reason: String, val detail: String?)

    private data class Failure(val reason: String, val detail: String?)
}
