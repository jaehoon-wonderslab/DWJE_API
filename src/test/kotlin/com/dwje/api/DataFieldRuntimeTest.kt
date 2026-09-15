package com.dwje.api

import com.dwje.api.common.exception.BusinessRuleException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.security.UserPrincipal
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.validation.CodeValidator
import com.dwje.api.model.request.DataFieldAttrRequest
import com.dwje.api.model.request.DataFieldSaveRequest
import com.dwje.api.repository.AuditLogRepository
import com.dwje.api.repository.DataFieldRepository
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.AuthorizationService
import com.dwje.api.service.DataFieldService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 데이터 접근 항목 운영(V33) — 서비스 규약.
 *
 * 1. 등록은 미적용(applyFlg='N'), key 형식·이름 검증은 400(field 지정)
 * 2. 응답 필드명 중복은 409 `E-RULE-001` "이미 <항목명>에 등록된 필드명입니다" — 사전 조회와 UNIQUE 위반(경쟁) 둘 다
 * 3. 기본 7개 항목과 참조가 남은 항목은 삭제 409, 없는 항목은 404
 * 4. 카탈로그: blindColumns 는 적용 중 항목만, 권한 없는 열은 값이 null, 새 항목의 이름 조각·필드명이 질의 차단 키워드가 된다
 */
class DataFieldRuntimeTest {

    /** 원천 없이 도는 저장소 — 항목·필드명 표를 메모리로 */
    private class MemRepo : DataFieldRepository(mock(NamedParameterJdbcTemplate::class.java)) {
        val fields = linkedMapOf<String, MutableMap<String, Any?>>()
        val attrs = linkedMapOf<String, String>()
        var refs = mutableMapOf<String, Long>()

        fun seed(key: String, name: String, apply: String, vararg attr: String) {
            fields[key] = mutableMapOf("key" to key, "name" to name, "desc" to null, "category" to null, "categoryNm" to null,
                "applyFlg" to apply, "useFlg" to "Y", "sortSeq" to fields.size + 1)
            attr.forEach { attrs[it] = key }
        }
        override fun findField(fieldKey: String) = fields[fieldKey]?.toMap()
        override fun findActiveKeys() = fields.keys.toList()
        override fun findAppliedFields() = fields.values.filter { it["applyFlg"] == "Y" }.map { f ->
            mapOf("key" to f["key"], "name" to f["name"], "category" to f["category"], "categoryNm" to f["categoryNm"],
                "attrs" to findFieldAttrs(f["key"] as String))
        }
        override fun findAppliedAttrMap() = attrs.filter { fields[it.value]?.get("applyFlg") == "Y" }
        override fun nextSortSeq() = fields.size + 1
        override fun insertField(fieldKey: String, name: String, desc: String?, category: String?, sortSeq: Int, actor: String): Int {
            seed(fieldKey, name, "N"); fields[fieldKey]!!["desc"] = desc; fields[fieldKey]!!["category"] = category; return 1
        }
        override fun updateField(fieldKey: String, name: String, desc: String?, category: String?, actor: String): Int {
            fields[fieldKey]!!.putAll(mapOf("name" to name, "desc" to desc, "category" to category)); return 1
        }
        override fun deleteField(fieldKey: String): Int { fields.remove(fieldKey); attrs.entries.removeIf { it.value == fieldKey }; return 1 }
        override fun updateApplyFlg(fieldKey: String, on: Boolean, actor: String): Int { fields[fieldKey]!!["applyFlg"] = if (on) "Y" else "N"; return 1 }
        override fun findAttrOwner(attrName: String) = attrs[attrName]?.let { mapOf("fieldKey" to it, "fieldNm" to fields[it]?.get("name")) }
        override fun insertAttr(fieldKey: String, attrName: String, remark: String?, actor: String): Int {
            if (attrName in attrs) throw DuplicateKeyException("uq_sys_data_field_attr_name")
            attrs[attrName] = fieldKey; return 1
        }
        override fun findFieldAttrs(fieldKey: String) = attrs.filterValues { it == fieldKey }.keys.sorted()
        override fun deleteAttr(fieldKey: String, attrName: String): Int = if (attrs[attrName] == fieldKey) { attrs.remove(attrName); 1 } else 0
        override fun countReferences(fieldKey: String): Map<String, Long> = mapOf(
            "alertCond" to (refs["alertCond"] ?: 0L), "metricStd" to (refs["metricStd"] ?: 0L), "reportFormField" to 0L,
            "docTag" to (refs["docTag"] ?: 0L), "deptPerm" to 3L, "attr" to findFieldAttrs(fieldKey).size.toLong()
        )
    }

    private class MemAudit : AuditLogService(mock(AuditLogRepository::class.java), mock(AuthorizationService::class.java)) {
        val perm = mutableListOf<Pair<String, String>>()
        override fun recordPermChange(actCd: String, targetKindCd: String, targetNm: String, detail: String, targetDeptId: Int?, targetUserId: String?) {
            perm += actCd to detail
        }
        override fun record(logType: String, menuId: String?, fieldKey: String?, targetDesc: String?, resultCd: String, maskedCnt: Int, remark: String?) {}
    }

    private fun principal(dataPerms: Set<String>, superAdmin: Boolean = false) = UserPrincipal(
        userId = "T1", userName = "t", deptId = 9, deptName = "d", deptAbbr = null, positionCd = null, plantCd = null,
        superAdmin = superAdmin, menuPerms = setOf(MenuId.SYS_DATA), dataPerms = dataPerms
    )

    private val admin = principal(emptySet(), superAdmin = true)
    private val repo = MemRepo().apply {
        seed("qty", "생산·출하 수량", "Y", "okQty", "ngQty")
        seed("price", "단가·금액", "Y", "unitPrice", "amount")
        seed("recipe", "배합 비율", "N", "mixRatio")
    }
    private val audit = MemAudit()
    private val auth = mock(AuthorizationService::class.java).also { `when`(it.requireMenu(MenuId.SYS_DATA)).thenReturn(admin) }
    private val service = DataFieldService(repo, auth, audit, mock(CodeValidator::class.java))

    @Test
    @DisplayName("등록 — 미적용(N)으로 시작하고, key 형식·이름은 400(field), 같은 key 는 409")
    fun create() {
        val created = service.create(DataFieldSaveRequest(fieldKey = "lead_time", name = "리드타임", desc = " ", category = "PLAN"))
        assertEquals("N", created["applyFlg"]); assertEquals("PLAN", created["category"]); assertNull(created["desc"], "공백 설명은 null")
        assertEquals("fieldKey", assertThrows(InvalidParameterException::class.java) { service.create(DataFieldSaveRequest("LeadTime", "x")) }.field)
        assertEquals("fieldKey", assertThrows(InvalidParameterException::class.java) { service.create(DataFieldSaveRequest("a", "x")) }.field, "2자 미만")
        assertEquals("name", assertThrows(InvalidParameterException::class.java) { service.create(DataFieldSaveRequest("okkey", "  ")) }.field)
        assertThrows(BusinessRuleException::class.java) { service.create(DataFieldSaveRequest("qty", "중복")) }
        assertTrue(audit.perm.any { it.first == "DATA_PERM" && it.second.contains("lead_time") && it.second.contains("미적용") })
    }

    @Test
    @DisplayName("응답 필드명 — 다른 항목에 붙은 이름은 409 '이미 <항목명>에 등록된 필드명입니다', 경쟁으로 UNIQUE 에 걸려도 같은 409, 형식은 400")
    fun attrDuplicate() {
        val e = assertThrows(BusinessRuleException::class.java) { service.addAttr("qty", DataFieldAttrRequest("unitPrice")) }
        assertEquals("이미 단가·금액에 등록된 필드명입니다. [unitPrice]", e.message)
        assertEquals("E-RULE-001", e.errorCode.code); assertEquals(409, e.errorCode.status.value())

        val same = assertThrows(BusinessRuleException::class.java) { service.addAttr("qty", DataFieldAttrRequest("okQty")) }
        assertTrue(same.message!!.startsWith("이미 이 항목에"))

        // 사전 조회를 통과한 뒤 UNIQUE 에 걸리는 경쟁 — 저장소가 DuplicateKeyException 을 던져도 409 로 나간다
        val racy = object : DataFieldRepository(mock(NamedParameterJdbcTemplate::class.java)) {
            override fun findField(fieldKey: String) = repo.findField(fieldKey)
            override fun findAttrOwner(attrName: String) = if (attrName == "amount") null else repo.findAttrOwner(attrName)
            override fun insertAttr(fieldKey: String, attrName: String, remark: String?, actor: String) = repo.insertAttr(fieldKey, attrName, remark, actor)
        }
        val r = assertThrows(BusinessRuleException::class.java) { DataFieldService(racy, auth, audit, mock(CodeValidator::class.java)).addAttr("qty", DataFieldAttrRequest("amount")) }
        assertTrue(r.message!!.contains("등록된 필드명입니다"), r.message)

        assertEquals("attrName", assertThrows(InvalidParameterException::class.java) { service.addAttr("qty", DataFieldAttrRequest("bad-name")) }.field)
        assertEquals("attrName", assertThrows(InvalidParameterException::class.java) { service.addAttr("qty", DataFieldAttrRequest("")) }.field)

        val added = service.addAttr("qty", DataFieldAttrRequest(" inputQty ", remark = "실적 집계"))
        assertEquals(listOf("inputQty", "ngQty", "okQty"), added["attrs"])
        assertThrows(ResourceNotFoundException::class.java) { service.removeAttr("qty", "unitPrice") }
        assertEquals(listOf("ngQty", "okQty"), service.removeAttr("qty", "inputQty")["attrs"])
    }

    @Test
    @DisplayName("삭제 — 기본 항목 409, 참조가 남은 항목 409(건수), 없는 항목 404, 그 밖에는 부서 권한·필드명과 함께 삭제")
    fun delete() {
        assertTrue(assertThrows(BusinessRuleException::class.java) { service.delete("qty") }.message!!.contains("기본 항목"))
        repo.refs["alertCond"] = 2; repo.refs["docTag"] = 5
        val e = assertThrows(BusinessRuleException::class.java) { service.delete("recipe") }
        assertTrue(e.message!!.contains("알림 조건 2건") && e.message!!.contains("문서 태그 5건"), e.message)
        repo.refs.clear()
        assertThrows(ResourceNotFoundException::class.java) { service.delete("nope") }
        val r = service.delete("recipe")
        assertEquals(3L, r["deletedDeptPerms"]); assertEquals(1L, r["deletedAttrs"])
        assertFalse("mixRatio" in repo.attrs); assertFalse("recipe" in repo.fields)
    }

    @Test
    @DisplayName("적용 스위치 — 켜면 카탈로그에 들어오고 끄면 빠진다, 같은 값은 changed=false")
    fun apply() {
        assertNull(service.fieldOf("mixRatio"), "미적용 항목의 필드명은 카탈로그에 없다")
        assertEquals(true, service.setApply("recipe", true)["changed"])
        assertEquals("recipe", service.fieldOf("mixRatio"))
        assertEquals(false, service.setApply("recipe", true)["changed"])
        service.setApply("recipe", false)
        assertNull(service.fieldOf("mixRatio"))
    }

    @Test
    @DisplayName("표 블록 — blindColumns 는 열마다 항목 key(없으면 null), 권한 없는 열만 값이 null 이 되고 가린 칸 수를 돌려준다")
    fun blindColumns() {
        val columns = listOf("model", "okQty", "unitPrice", "mixRatio")
        assertEquals(listOf(null, "qty", "price", null), service.blindColumnsFor(columns))
        val rows = listOf(
            mutableMapOf<String, Any?>("model" to "A", "okQty" to 10, "unitPrice" to 1200, "mixRatio" to 0.3),
            mutableMapOf<String, Any?>("model" to "B", "okQty" to 20, "unitPrice" to null, "mixRatio" to 0.4)
        )
        val masked = service.maskRows(rows, columns, service.blindColumnsFor(columns), principal(setOf("qty")))
        assertEquals(1, masked, "null 이던 칸은 세지 않는다")
        assertEquals(10, rows[0]["okQty"]); assertNull(rows[0]["unitPrice"]); assertEquals(0.3, rows[0]["mixRatio"], "미적용 항목은 가리지 않는다")
        assertEquals(setOf("price"), service.blindKeysFor(principal(setOf("qty"))))
        assertTrue(service.blindKeysFor(admin).isEmpty())
    }

    @Test
    @DisplayName("문장 마스킹 — 항목 키워드 뒤의 값만 「비공개」, 문장 구조·태그·권한 있는 값은 그대로, 표에서 가린 값도 가린다")
    fun maskText() {
        val p = principal(setOf("qty"))
        assertEquals("8월 평균 단가는 비공개입니다" to 1, service.maskText("8월 평균 단가는 12,400원입니다", p))
        assertEquals("<p>단가·금액: 비공개 (전월 비공개)</p>" to 2, service.maskText("<p>단가·금액: 1,200 (전월 1,150.5)</p>", p))
        assertEquals("unitPrice 는 비공개 입니다" to 1, service.maskText("unitPrice 는 980 입니다", p), "응답 필드명 키워드")
        assertEquals("양품 수량은 3,000개입니다" to 0, service.maskText("양품 수량은 3,000개입니다", p), "권한 있는 항목은 그대로")
        assertEquals("단가는 <b>1200</b>" to 0, service.maskText("단가는 <b>1200</b>", p), "태그를 넘어 값을 찾지 않는다")
        assertEquals("고객사 삼성전기 비중 40%" to 0, service.maskText("고객사 삼성전기 비중 40%", p), "미적용·무관 항목은 그대로")
        assertEquals("최고 단가 제품은 비공개, 최저는 비공개" to 2, service.maskText("최고 단가 제품은 MDL-77, 최저는 MDL-09", p, extraValues = listOf("MDL-77", "MDL-09")), "표에서 가린 값")
        assertEquals("단가 기준 모델 MDL-77 입니다" to 0, service.maskText("단가 기준 모델 MDL-77 입니다", p), "모델 코드 속 숫자는 값이 아니다")
        assertEquals("단가 12,400원" to 0, service.maskText("단가 12,400원", admin), "통합관리자는 가리지 않는다")
        assertEquals(null to 0, service.maskText(null, p))
    }

    @Test
    @DisplayName("근거 문서 — 권한 없는 항목이 태그된 문서는 발췌만 가리고 제목·쪽은 남긴다, 태그 없는 문서는 값만 가린다, 검색에서 빼지 않는다")
    fun maskHit() {
        val p = principal(setOf("qty"))
        val tagged = mutableMapOf<String, Any?>("title" to "8월 원가 보고", "snippet" to "A제품 단가 12,400원, 고객 X사", "page" to 3, "fieldTags" to listOf("price", "qty"))
        assertEquals(1, service.maskHit(tagged, p))
        assertTrue((tagged["snippet"] as String).contains("비공개 항목(단가·금액)"), tagged["snippet"].toString())
        assertEquals(listOf("price"), tagged["blindTags"]); assertEquals(true, tagged["blinded"]); assertEquals(3, tagged["page"])
        val plain = mutableMapOf<String, Any?>("title" to "아침회의", "snippet" to "평균 단가 1,000원, 생산 500개", "fieldTags" to emptyList<String>())
        assertEquals(1, service.maskHit(plain, p))
        assertEquals("평균 단가 비공개, 생산 500개", plain["snippet"]); assertEquals(true, plain["blinded"])
        val ok = mutableMapOf<String, Any?>("title" to "t", "snippet" to "생산 500개", "fieldTags" to listOf("qty"))
        assertEquals(0, service.maskHit(ok, p)); assertEquals(false, ok["blinded"])
        assertEquals(0, service.maskHit(tagged.toMutableMap().also { it["snippet"] = "x 1,000원" }, admin), "통합관리자는 그대로")
    }

    @Test
    @DisplayName("키워드 — 항목명 전체·`·` 조각·필드명, 공백으로는 나누지 않고 긴 것부터, 적용을 켠 새 항목도 배포 없이 포함")
    fun keywords() {
        val p = principal(setOf("qty"))
        val kws = service.keywordsOfBlindFields(p)
        assertTrue(kws.containsAll(listOf("단가·금액", "단가", "금액", "unitPrice", "amount")), kws.toString())
        assertFalse(kws.contains("배합 비율"), "미적용 항목은 없다")
        service.setApply("recipe", true)
        val after = service.keywordsOfBlindFields(p)
        assertTrue(after.contains("배합 비율") && after.contains("mixRatio") && !after.contains("비율"), after.toString())
        assertTrue(service.keywordsOfBlindFields(admin).isEmpty())
    }
}
