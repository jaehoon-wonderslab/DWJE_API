package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.config.AppProperties
import com.dwje.api.model.request.GwDeptMapSaveRequest
import com.dwje.api.repository.GwDeptMapRepository
import com.dwje.api.repository.SystemUserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 시스템관리 › 그룹웨어 부서 매핑 (SY-17, 2026-09-30 WEB 요청)
 *
 * 접근 : 화면 권한 `sys-gw-dept` · 값 마스킹 : 없음
 *
 * MES 이관 엔진은 그룹웨어 인사정보를 받을 때 AX 에 없는 사번을 가입시키고, 부서는
 * `ax.tb_sys_dept_gw_map` 으로 정한다. 매핑이 없으면 미배정 부서([AppProperties.unassignedDeptName])다.
 * **매핑은 가입 순간에만 쓴다** — 여기서 매핑을 바꿔도 이미 가입된 계정의 부서는 그대로이고,
 * 미배정 계정은 [reassign] 이나 계정 부서 이동 API 로 따로 옮긴다.
 */
@Service
class GwDeptMapService(
    private val repository: GwDeptMapRepository,
    private val systemUserRepository: SystemUserRepository,
    private val systemUserService: SystemUserService,
    private val authorizationService: AuthorizationService,
    private val auditLogService: AuditLogService,
    private val appProperties: AppProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val SAVE_MESSAGE = "매핑을 저장했습니다. 다음 동기화부터 새로 가입하는 사람에게 적용되며, 이미 가입된 계정의 부서는 바뀌지 않습니다."
        const val DELETE_MESSAGE = "매핑을 삭제했습니다. 이 부서 사람은 다음 가입부터 미배정 부서로 들어가며, 이미 가입된 계정의 부서는 바뀌지 않습니다."

        /** 엔진이 tb_sync_run.message 에 "인사정보 요약 · AX 가입 요약" 으로 남긴다 — AX 가입 요약만 뗀다 */
        fun joinMessageOf(message: String?): String? {
            if (message == null) return null
            val at = message.indexOf("AX 가입")
            return if (at < 0) null else message.substring(at).trim()
        }

        /** 이력용 한 줄 — 상태와 부서 */
        fun describe(state: String?, deptNm: String?): String = when (state) {
            "EXCLUDED" -> "가입 제외"
            "MAPPED" -> deptNm ?: "(없는 부서)"
            "UNMAPPED" -> "미배정"
            else -> "없음"
        }
    }

    /** 미배정 부서 — 이름으로 찾는다. 없으면(V45 미적용) null */
    private fun unassignedDept(): Map<String, Any?>? = repository.findDeptByName(appProperties.unassignedDeptName)

    // =================================================================================
    // 1) 요약
    // =================================================================================

    @Transactional(readOnly = true)
    fun getSummary(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_GW_DEPT)
        val unassigned = unassignedDept()
        val inSource = repository.findMaps(unassigned?.get("deptId") as Int?, repository.sourceExists())
            .filter { it["inSource"] == true }
        val unmapped = inSource.filter { it["state"] == "UNMAPPED" }
        val lastRun = repository.findLastGroupwareRun()

        return mapOf(
            "gwDeptCnt" to inSource.size,
            "mappedCnt" to inSource.count { it["state"] == "MAPPED" },
            "unmappedCnt" to unmapped.size,
            "excludedCnt" to inSource.count { it["state"] == "EXCLUDED" },
            "unmappedUserCnt" to unmapped.sumOf { it["activeCnt"] as Long },
            "unassignedUserCnt" to (unassigned?.let { repository.countUsersInDept(it["deptId"] as Int) } ?: 0L),
            "unassignedDept" to unassigned,
            "lastSyncAt" to lastRun?.first,
            "lastJoinMessage" to joinMessageOf(lastRun?.second)
        )
    }

    // =================================================================================
    // 2) 목록
    // =================================================================================

    /**
     * @param keyword 그룹웨어 부서명·AX 부서명·메모 부분 일치
     * @param state   MAPPED | UNMAPPED | EXCLUDED
     */
    @Transactional(readOnly = true)
    fun getMaps(keyword: String?, state: String?, page: Int?, size: Int?): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_GW_DEPT)
        val stateFilter = state?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
        if (stateFilter != null && stateFilter !in setOf("MAPPED", "UNMAPPED", "EXCLUDED")) {
            throw InvalidParameterException("state 는 MAPPED · UNMAPPED · EXCLUDED 중 하나여야 합니다.", "state")
        }
        val k = keyword?.trim()?.takeIf { it.isNotEmpty() }
        val paging = PageRequestParam.ofAllowAll(page, size)

        val rows = repository.findMaps(unassignedDept()?.get("deptId") as Int?, repository.sourceExists())
            .filter { stateFilter == null || it["state"] == stateFilter }
            .filter { r ->
                k == null || listOf(r["gwDeptNm"], r["deptNm"], r["remark"]).any { (it as String?)?.contains(k) == true }
            }

        val total = rows.size.toLong()
        val pageRows = if (paging.isAll) rows else rows.drop(paging.offset).take(paging.limit)
        return pageRows to (if (paging.isAll) PageMeta.all(total) else PageMeta.of(paging.page, paging.size, total))
    }

    // =================================================================================
    // 3) 저장 (upsert) · 4) 삭제
    // =================================================================================

    @Transactional
    fun saveMap(request: GwDeptMapSaveRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_GW_DEPT)
        val gwDeptNm = request.gwDeptNm   // 엔진이 글자 그대로 비교한다 — 다듬지 않는다
        val joinYn = request.joinYn ?: "Y"
        val deptId = if (joinYn == "N") null else request.deptId
        val remark = request.remark?.takeIf { it.isNotBlank() }

        if (deptId != null) {
            val dept = systemUserRepository.findDept(deptId)
                ?: throw InvalidParameterException("존재하지 않는 부서입니다. [deptId=$deptId]", "deptId")
            if (dept["deptNm"] == appProperties.unassignedDeptName) {
                throw InvalidParameterException("미배정 부서는 고를 수 없습니다. 부서를 비워 두면 미배정으로 가입됩니다.", "deptId")
            }
            if (dept["superAdmin"] == true) {
                throw InvalidParameterException("통합관리자 부서는 매핑할 수 없습니다. 자동 가입 계정이 전체 권한을 받게 됩니다.", "deptId")
            }
        }

        val before = repository.findMap(gwDeptNm)
        repository.upsertMap(gwDeptNm, deptId, joinYn, remark, principal.userId)
        val after = repository.findMap(gwDeptNm)!!

        val detail = "매핑 저장 — ${describe(before?.get("state") as String?, before?.get("deptNm") as String?)}" +
            " → ${describe(after["state"] as String, after["deptNm"] as String?)}" +
            (remark?.let { " (메모: ${it.take(100)})" } ?: "")
        recordChange(gwDeptNm, detail, deptId)
        log.info("그룹웨어 부서 매핑 저장 : gwDeptNm={} deptId={} joinYn={} by={}", gwDeptNm, deptId, joinYn, principal.userId)

        return mapOf("gwDeptNm" to gwDeptNm, "state" to after["state"])
    }

    @Transactional
    fun deleteMap(gwDeptNm: String?): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_GW_DEPT)
        if (gwDeptNm.isNullOrEmpty()) throw InvalidParameterException("그룹웨어 부서명을 입력해 주세요.", "gwDeptNm")

        val before = repository.findMap(gwDeptNm)
            ?: throw ResourceNotFoundException("매핑을 찾을 수 없습니다. [$gwDeptNm]")
        repository.deleteMap(gwDeptNm)

        recordChange(gwDeptNm,
            "매핑 삭제 — ${describe(before["state"] as String, before["deptNm"] as String?)} → 미배정(매핑 없음)",
            before["deptId"] as Int?)
        log.info("그룹웨어 부서 매핑 삭제 : gwDeptNm={} by={}", gwDeptNm, principal.userId)

        return mapOf("gwDeptNm" to gwDeptNm, "state" to "UNMAPPED")
    }

    private fun recordChange(gwDeptNm: String, detail: String, deptId: Int?) {
        auditLogService.recordPermChange(
            actCd = "GW_DEPT_MAP",
            targetKindCd = "GW_DEPT",
            targetNm = gwDeptNm,
            detail = detail.take(500),
            targetDeptId = deptId
        )
        auditLogService.record(
            logType = "PERM_CHANGE",
            menuId = MenuId.SYS_GW_DEPT,
            targetDesc = "그룹웨어 부서 매핑 [$gwDeptNm]",
            remark = detail.take(500)
        )
    }

    // =================================================================================
    // 5) 미배정 계정 · 6) 매핑대로 재배정
    // =================================================================================

    /** @param keyword 사번·이름·그룹웨어 부서명 */
    @Transactional(readOnly = true)
    fun getUnassignedUsers(keyword: String?, page: Int?, size: Int?): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_GW_DEPT)
        val paging = PageRequestParam.ofAllowAll(page, size)
        val deptId = unassignedDept()?.get("deptId") as Int?
            ?: return emptyList<Map<String, Any?>>() to PageMeta.all(0)
        val withSource = repository.sourceExists()

        val total = repository.countUnassignedUsers(deptId, withSource, keyword)
        val rows = repository.findUnassignedUsers(deptId, withSource, keyword, null, paging.limitOrNull, paging.offset)
        return rows to (if (paging.isAll) PageMeta.all(total) else PageMeta.of(paging.page, paging.size, total))
    }

    /**
     * 미배정 계정을 지금 매핑의 제안 부서로 옮긴다. 한 트랜잭션.
     *
     * - [empNos] 가 없으면 제안 부서가 있는 미배정 계정 전체
     * - 미배정 부서가 아닌 사번, 제안 부서가 없는 사번, 제안이 통합관리자 부서인 사번은 건너뛴다([skippedCnt])
     * - 옮기는 방식·이력은 계정 부서 이동(PUT /system/users/{empNo}/dept)과 같다
     */
    @Transactional
    fun reassign(empNos: List<String>?): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_GW_DEPT)
        val requested = empNos?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.takeIf { it.isNotEmpty() }
        val unassignedId = unassignedDept()?.get("deptId") as Int?
            ?: return mapOf("movedCnt" to 0, "skippedCnt" to (requested?.size ?: 0), "items" to emptyList<Any>())

        val candidates = repository.findUnassignedUsers(unassignedId, repository.sourceExists(), null, requested, null, 0)
            .filter { it["suggestDeptId"] != null }

        // 부서별 상속 권한 수는 한 번만 센다 (계정마다 매트릭스를 다시 읽지 않는다)
        val menuCnt by lazy { systemUserRepository.findMenuPermMatrix().groupingBy { it["deptId"] as Int }.eachCount() }
        val dataCnt by lazy { systemUserRepository.findDataPermMatrix().groupingBy { it["deptId"] as Int }.eachCount() }
        val depts = HashMap<Int, Map<String, Any?>?>()

        val moved = ArrayList<Map<String, Any?>>()
        candidates.forEach { c ->
            val empNo = c["empNo"] as String
            val targetId = c["suggestDeptId"] as Int
            val dept = depts.getOrPut(targetId) { systemUserRepository.findDept(targetId) } ?: return@forEach
            if (dept["superAdmin"] == true || targetId == unassignedId) return@forEach

            systemUserRepository.updateUserDept(empNo, targetId, principal.userId)
            systemUserService.recordDeptMove(empNo, dept, menuCnt[targetId] ?: 0, dataCnt[targetId] ?: 0, MenuId.SYS_GW_DEPT)
            moved += mapOf("empNo" to empNo, "deptNm" to dept["deptNm"])
        }

        val skipped = (requested?.size ?: candidates.size) - moved.size
        log.info("미배정 계정 재배정 : moved={} skipped={} by={}", moved.size, skipped, principal.userId)
        return mapOf("movedCnt" to moved.size, "skippedCnt" to skipped, "items" to moved)
    }
}
