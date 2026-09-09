package com.dwje.api

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 제품·공정별 일목표 계약 테스트
 *
 * 이 테스트가 고정하는 두 가지
 *
 * 1. **우선순위** — 저장값(작성자 입력) > 마스터 > null.
 *    순서가 뒤집히면 작성자가 그날만 달리 잡은 목표가 마스터에 덮여 사라진다.
 *    화면에는 그럴듯한 숫자가 그대로 보이므로 아무도 모른다.
 *
 * 2. **적용일 구간** — 목표는 적용일부터 다음 적용일 전까지 유효하다.
 *    종료일을 두지 않으므로 조회는 "그 날짜 이하의 적용일 중 가장 늦은 것" 한 건이어야 한다.
 *    `DISTINCT ON` 이나 `ORDER BY apply_from DESC` 가 빠지면 과거 목표가 섞여 들어온다.
 */
class DayTargetContractTest {

    private val service =
        File("src/main/kotlin/com/dwje/api/service/DailyReportService.kt").readText()
    private val repository =
        File("src/main/kotlin/com/dwje/api/repository/DayTargetRepository.kt").readText()

    @Test
    @DisplayName("1. 목표 우선순위는 저장값 > 마스터 다 — 뒤집히면 작성자 입력이 덮인다")
    fun manualBeatsMaster() {
        val sheet = Regex("""fun getSheet\(.*?\n    \}""", RegexOption.DOT_MATCHES_ALL)
            .find(service)?.value ?: error("getSheet 를 찾지 못했다")

        // 저장값을 먼저 보고, 없을 때만(?:) 마스터를 본다.
        assertTrue(
            Regex("""own\?\.get\("targetQty"\)\s*as\?\s*Long\s*\)?\s*\n?\s*\?:\s*master\[""")
                .containsMatchIn(sheet),
            "저장값이 먼저이고 마스터가 뒤여야 한다. 순서가 뒤집히면 작성자가 그날만 " +
                "달리 잡은 목표가 마스터 값에 덮여 조용히 사라진다"
        )
        assertTrue(
            sheet.contains("\"targetQtyOrigin\""),
            "목표가 어디서 온 값인지 화면이 알아야 한다 — 마스터 값은 작성자가 덮어쓸 수 있는 밑값이다"
        )
        assertTrue(
            sheet.contains("\"MANUAL\"") && sheet.contains("\"MASTER\""),
            "출처는 MANUAL / MASTER 두 값으로 구분해야 한다"
        )
    }

    @Test
    @DisplayName("2. 주간목표는 목표 x 주간 일수다 — 저장값이든 마스터든 같은 규칙")
    fun weekTargetUsesWeekDays() {
        val sheet = Regex("""fun getSheet\(.*?\n    \}""", RegexOption.DOT_MATCHES_ALL)
            .find(service)?.value ?: error("getSheet 를 찾지 못했다")

        assertTrue(
            Regex("""weekTargetQty"\s*to\s*if \(qtyAllowed\) targetQty\?\.let \{ it \* weekDays \}""")
                .containsMatchIn(sheet),
            "주간목표는 목표 x weekDays 다. 목표가 없으면 낼 수 없으므로 null 이어야 한다 — " +
                "0 을 내면 달성률이 무한이 된다"
        )
    }

    @Test
    @DisplayName("3. 적용일 조회는 그 날짜 이하의 최신 한 건이다 — 과거 목표가 섞이면 안 된다")
    fun effectiveLookupPicksLatest() {
        val fn = Regex("""fun findEffectiveTargets\(.*?\n    \}""", RegexOption.DOT_MATCHES_ALL)
            .find(repository)?.value ?: error("findEffectiveTargets 를 찾지 못했다")

        assertTrue(
            fn.contains("DISTINCT ON (product, wc_cd)"),
            "제품·공정마다 한 건만 골라야 한다. 없으면 이력 전체가 섞여 들어온다"
        )
        assertTrue(
            fn.contains("apply_from <= :date"),
            "미래 적용일의 목표를 당겨 쓰면 안 된다"
        )
        assertTrue(
            Regex("""ORDER BY product, wc_cd, apply_from DESC""").containsMatchIn(fn),
            "DISTINCT ON 이 고르는 한 건은 ORDER BY 가 정한다. apply_from DESC 가 빠지면 " +
                "어느 적용일이 뽑히는지 정해지지 않아 과거 목표가 나올 수 있다"
        )
    }

    @Test
    @DisplayName("4. 같은 제품·공정·적용일 중복을 막는다 — 어느 값이 이기는지 알 수 없어진다")
    fun rejectsDuplicateKey() {
        val svc = File("src/main/kotlin/com/dwje/api/service/DayTargetService.kt").readText()

        assertTrue(
            svc.contains("existsSameKey"),
            "등록·수정에서 같은 제품·공정·적용일이 있는지 확인해야 한다"
        )
        // 수정 시에는 자기 자신을 중복으로 보면 안 된다.
        assertTrue(
            Regex("""existsSameKey\([^)]*targetId\)""").containsMatchIn(svc),
            "수정할 때는 자기 자신을 중복 검사에서 빼야 한다 — 안 그러면 자기 적용일로 " +
                "수정하는 것이 막힌다"
        )
        assertTrue(
            !Regex("""fun update\([^{]*\{[\s\S]{0,900}?request\.product""").containsMatchIn(svc),
            "수정에서 제품·공정을 바꿀 수 없어야 한다 — 바꾸면 다른 제품의 목표를 " +
                "덮어쓰는 것이라 삭제 후 등록과 뜻이 달라진다"
        )
    }

    @Test
    @DisplayName("5. 목표 마스터에 UNIQUE 와 CHECK 가 걸려 있다")
    fun migrationHasConstraints() {
        val ddl = File("src/main/resources/db/V21__prod_day_target.sql").readText()

        assertTrue(
            Regex("""UNIQUE \(plant_cd, product, wc_cd, apply_from\)""").containsMatchIn(ddl),
            "같은 제품·공정·적용일이 두 번 들어오는 것을 DB 에서도 막아야 한다 — " +
                "서비스 검증만 있으면 다른 경로로 들어오는 것을 못 막는다"
        )
        assertTrue(
            ddl.contains("CHECK (target_qty >= 0)"),
            "목표가 음수가 되는 것을 DB 에서도 막아야 한다"
        )
        assertTrue(
            ddl.contains("apply_from DESC"),
            "조회는 항상 적용일 내림차순이므로 인덱스도 그 모양이어야 한다"
        )
    }
}
