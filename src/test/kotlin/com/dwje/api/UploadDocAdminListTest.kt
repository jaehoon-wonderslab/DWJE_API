package com.dwje.api

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.security.UserContext
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.service.DashboardUploadService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * 시스템관리 업로드 문서 목록의 서버 조건·쪽 나눔 — 실제 로컬 DB (11 기획서 UPD-01). 조회만 한다.
 *
 * 기대값은 테스트가 DB 에서 직접 센 값과 비교한다(로컬 픽스처 문서 수가 바뀌어도 깨지지 않게).
 */
@SpringBootTest(properties = ["app.ai.prewarm-enabled=false"])
@ActiveProfiles("local")
class UploadDocAdminListTest {

    @Autowired lateinit var service: DashboardUploadService
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeEach
    fun login() = UserContext.set(UserPrincipal("10004", "전산", 5, "전산팀", null, null, false, menuPerms = setOf("sys-upload-doc")))

    @AfterEach
    fun clear() = UserContext.clear()

    private fun count(where: String): Long = jdbc.queryForObject(
        """SELECT count(*) FROM ax.tb_dash_upload_doc d
           LEFT JOIN ax.tb_dash_upload_ver lv ON lv.doc_id = d.doc_id AND lv.ver = d.latest_ver
           WHERE d.del_flg = 'N' AND d.latest_ver > 0 $where""", MapSqlParameterSource(), Long::class.java)!!

    @Test
    @DisplayName("기간·파싱 상태 — 먼 미래는 0건, 상태는 그 상태만, from>to 는 400(field=from), 상태 코드 밖은 400")
    fun filters() {
        val (none, meta0) = service.listDocsForAdmin(from = "2030-01-01", to = "2030-01-02")
        assertEquals(0, none.size); assertEquals(0L, meta0.total)
        val all = count("")
        assertEquals(all, service.listDocsForAdmin(from = "2000-01-01").second.total, "한쪽만 오면 그쪽 조건만")
        val fail = count("AND lv.parse_state_cd = 'FAIL'")
        val (rows, meta) = service.listDocsForAdmin(parseState = "FAIL")
        assertEquals(fail, meta.total); assertTrue(rows.all { it["parseState"] == "FAIL" })

        val e = assertThrows(InvalidParameterException::class.java) { service.listDocsForAdmin(from = "2026-09-11", to = "2026-09-10") }
        assertEquals("from", e.field)
        val s = assertThrows(InvalidParameterException::class.java) { service.listDocsForAdmin(parseState = "경고") }
        assertEquals("parseState", s.field)
    }

    @Test
    @DisplayName("쪽 나눔 — size=1 이면 total 건수만큼 쪽, 기본 50, size=0 은 전체")
    fun paging() {
        val all = count("")
        val (one, meta) = service.listDocsForAdmin(page = 1, size = 1)
        assertEquals(minOf(1L, all).toInt(), one.size); assertEquals(all.toInt(), meta.totalPages)
        assertEquals(50, service.listDocsForAdmin().second.size)
        val (everything, metaAll) = service.listDocsForAdmin(size = 0)
        assertEquals(all.toInt(), everything.size); assertEquals(null, metaAll.truncated)
    }
}
