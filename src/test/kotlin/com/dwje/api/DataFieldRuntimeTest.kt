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
    @DisplayName("질의 차단 — 항목명 조각과 응답 필드명이 키워드다, 적용을 켠 새 항목도 배포 없이 걸린다")
    fun restricted() {
        val p = principal(setOf("qty"))
        assertEquals("price", service.restrictedFieldFor("8월 평균 단가는 얼마인가요", p))
        assertEquals("price", service.restrictedFieldFor("UNITPRICE 컬럼 값 보여줘", p), "필드명은 대소문자 무시")
        assertNull(service.restrictedFieldFor("배합 비율 알려줘", p), "미적용 항목은 걸리지 않는다")
        service.setApply("recipe", true)
        assertEquals("recipe", service.restrictedFieldFor("배합 비율 알려줘", p))
        assertNull(service.restrictedFieldFor("비율만 알려줘", p), "항목명은 공백으로 나누지 않는다 — 일반어 「비율」로 막지 않는다")
        assertNull(service.restrictedFieldFor("배합 비율 알려줘", principal(setOf("qty", "recipe"))), "권한이 있으면 통과")
        assertNull(service.restrictedFieldFor("단가 알려줘", admin))
    }
}
