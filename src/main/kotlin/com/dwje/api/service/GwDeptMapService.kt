package com.dwje.api.service

import com.dwje.api.common.util.AuditType
import com.dwje.api.common.exception.ConflictingValueException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.config.AppProperties
import com.dwje.api.model.request.GwDeptMapSaveRequest
import com.dwje.api.model.request.GwDeptReassignRequest
import com.dwje.api.repository.GwDeptMapRepository
import com.dwje.api.repository.SystemUserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 매핑 일괄 지정 한 번의 상한 (02 GWD-05) */
private const val BULK_MAX = 200

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
    private val appProperties: AppProperties,
    private val systemDeptGuard: SystemDeptGuard = SystemDeptGuard(appProperties)
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
        val withSource = repository.sourceExists()
        val inSource = repository.findMaps(unassigned?.get("deptId") as Int?, withSource)
            .filter { it["inSource"] == true }
        val unmapped = inSource.filter { it["state"] == "UNMAPPED" }
        val lastRun = repository.findGroupwareRun(onlyDone = false)
        val lastDone = repository.findGroupwareRun(onlyDone = true)

        return mapOf(
            "gwDeptCnt" to inSource.size,
            "mappedCnt" to inSource.count { it["state"] == "MAPPED" },
            "unmappedCnt" to unmapped.size,
            "excludedCnt" to inSource.count { it["state"] == "EXCLUDED" },
            "unmappedUserCnt" to unmapped.sumOf { it["activeCnt"] as Long },
            "unassignedUserCnt" to (unassigned?.let { repository.countUsersInDept(it["deptId"] as Int) } ?: 0L),
            // 그중 첫 로그인 비밀번호 변경이 남은 계정 — 정보로만(이동 조건 아님, R-05)
            "unassignedPwdInitCnt" to (unassigned?.let { repository.countPwdInitInDept(it["deptId"] as Int) } ?: 0L),
            // 쓰기 권한(R-06, 02 GWD-14) — false 면 화면은 지정·삭제·재배정 버튼을 비활성으로 그린다
            "canWrite" to authorizationService.canWrite(MenuId.SYS_GW_DEPT),
            "unassignedDept" to unassigned,
            "lastSyncAt" to lastRun?.get("startedAt"),
            "lastJoinMessage" to joinMessageOf(lastRun?.get("message") as String?),
            // 이상 상태 (GWD-03) — 미배정 부서를 이름으로 못 찾거나 원천 표가 없으면 목록이 비는 이유를 화면이 알린다.
            // engineDeptName 은 API 설정값이다(엔진 설정은 API 가 읽을 수 없다 — 두 값은 같아야 한다).
            "health" to mapOf(
                "unassignedDeptFound" to (unassigned != null),
                "sourceExists" to withSource,
                "sourceRowCnt" to (if (withSource) repository.countSourceRows() else 0L),
                "engineDeptName" to appProperties.unassignedDeptName
            ),
            "lastSync" to lastRun?.let {
                mapOf("startedAt" to it["startedAt"], "stateCd" to it["stateCd"], "joinSummary" to joinMessageOf(it["message"] as String?))
            },
            "lastJoin" to lastDone?.let { mapOf("startedAt" to it["startedAt"], "summary" to joinMessageOf(it["message"] as String?)) }
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
        val principal = authorizationService.requireWrite(MenuId.SYS_GW_DEPT)
        val gwDeptNm = request.gwDeptNm   // 엔진이 글자 그대로 비교한다 — 다듬지 않는다
        request.fromGwDeptNm?.takeIf { it.isNotEmpty() }?.let { return inheritMap(principal.userId, it, gwDeptNm) }
        val joinYn = request.joinYn ?: "Y"
        val deptId = if (joinYn == "N") null else request.deptId
        val remark = request.remark?.takeIf { it.isNotBlank() }

        deptId?.let { requireMappableDept(it) }

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

    /**
     * 그룹웨어 부서명 변경 이어받기 (02 GWD-11) — 옛 이름 행의 부서·가입 여부·메모를 새 이름으로 옮기고 옛 행을 지운다.
     * 원본이 아직 그룹웨어에 있어도, 새 이름이 원천에 아직 없어도 허용한다(권장안). 한 트랜잭션이다.
     */
    private fun inheritMap(actor: String, from: String, to: String): Map<String, Any?> {
        if (from == to) throw InvalidParameterException("이어받을 이름과 새 이름이 같습니다.", "fromGwDeptNm")
        val src = repository.findMap(from) ?: throw ResourceNotFoundException("이어받을 매핑을 찾을 수 없습니다. [$from]")
        if (repository.findMap(to) != null) {
            throw ConflictingValueException("'$to' 에 이미 매핑이 있습니다. 먼저 삭제하거나 직접 지정하십시오.", "gwDeptNm")
        }
        val deptId = src["deptId"] as Int?
        deptId?.let { requireMappableDept(it) }
        try {
            repository.insertMap(to, deptId, src["joinYn"] as String, src["remark"] as String?, actor)
        } catch (e: org.springframework.dao.DuplicateKeyException) {
            throw ConflictingValueException("'$to' 에 이미 매핑이 있습니다. 먼저 삭제하거나 직접 지정하십시오.", "gwDeptNm")
        }
        repository.deleteMap(from)
        recordChange(to, "이름 변경 이어받기 $from → $to — ${describe(src["state"] as String?, src["deptNm"] as String?)}", deptId)
        log.info("그룹웨어 부서 매핑 이어받기 : {} → {} by={}", from, to, actor)
        return mapOf("gwDeptNm" to to, "state" to src["state"], "inheritedFrom" to from)
    }

    @Transactional
    fun deleteMap(gwDeptNm: String?): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_GW_DEPT)
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

    /**
     * 매핑 일괄 지정 (02 GWD-05) — 여러 그룹웨어 부서를 같은 부서·가입 여부로 한 트랜잭션에.
     * 검사를 모두 끝낸 뒤 쓰므로 하나라도 틀리면 아무것도 저장하지 않는다.
     * 이력은 부서마다 권한 변경 이력 1줄 + 감사 로그 1줄(일괄 N건).
     */
    @Transactional
    fun saveMapsBulk(request: com.dwje.api.model.request.GwDeptMapBulkSaveRequest): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_GW_DEPT)
        // 엔진이 글자 그대로 비교한다 — 다듬지 않고 빈 문자열만 뺀다
        val names = request.gwDeptNms.orEmpty().filter { it.isNotEmpty() }.distinct()
        if (names.isEmpty()) throw InvalidParameterException("그룹웨어 부서를 1개 이상 고르십시오.", "gwDeptNms")
        if (names.size > BULK_MAX) throw InvalidParameterException("한 번에 ${BULK_MAX}개까지 처리할 수 있습니다.", "gwDeptNms")
        names.firstOrNull { it.length > 100 }?.let {
            throw InvalidParameterException("그룹웨어 부서명은 100자 이내여야 합니다.", "gwDeptNms")
        }
        val joinYn = request.joinYn ?: "Y"
        val deptId = if (joinYn == "N") null else request.deptId
        deptId?.let { requireMappableDept(it) }
        val keepRemark = request.keepRemark ?: true
        val remark = request.remark?.takeIf { it.isNotBlank() }

        val before = repository.findMapsByNames(names)
        repository.upsertMaps(names, deptId, joinYn, remark, keepRemark, principal.userId)
        val after = repository.findMapsByNames(names)

        names.forEach { nm ->
            val b = before[nm]
            val a = after.getValue(nm)
            auditLogService.recordPermChange(
                actCd = "GW_DEPT_MAP", targetKindCd = "GW_DEPT", targetNm = nm,
                detail = ("매핑 저장 — ${describe(b?.get("state") as String?, b?.get("deptNm") as String?)}" +
                    " → ${describe(a["state"] as String, a["deptNm"] as String?)} (일괄)").take(500),
                targetDeptId = deptId
            )
        }
        auditLogService.record(
            logType = AuditType.PERM_CHANGE, menuId = MenuId.SYS_GW_DEPT,
            targetDesc = "그룹웨어 부서 매핑 일괄 지정 [${names.size}건]", remark = names.joinToString(", ").take(500)
        )
        log.info("그룹웨어 부서 매핑 일괄 저장 : {}건 deptId={} joinYn={} by={}", names.size, deptId, joinYn, principal.userId)
        return mapOf("savedCnt" to names.size, "items" to names.map { mapOf("gwDeptNm" to it, "state" to after.getValue(it)["state"]) })
    }

    /** 매핑할 수 있는 부서인지 — 없는 부서·미배정·통합관리자는 400 (단건·일괄 공용) */
    private fun requireMappableDept(deptId: Int) {
        val dept = systemUserRepository.findDept(deptId)
            ?: throw InvalidParameterException("존재하지 않는 부서입니다. [deptId=$deptId]", "deptId")
        if (dept["deptNm"] == appProperties.unassignedDeptName) {
            throw InvalidParameterException("미배정 부서는 고를 수 없습니다. 부서를 비워 두면 미배정으로 가입됩니다.", "deptId")
        }
        if (dept["superAdmin"] == true) {
            throw InvalidParameterException("통합관리자 부서는 매핑할 수 없습니다. 자동 가입 계정이 전체 권한을 받게 됩니다.", "deptId")
        }
    }

    private fun recordChange(gwDeptNm: String, detail: String, deptId: Int?) {
        val permAuditId = auditLogService.record(
            logType = AuditType.PERM_CHANGE,
            menuId = MenuId.SYS_GW_DEPT,
            targetDesc = "그룹웨어 부서 매핑 [$gwDeptNm]",
            remark = detail.take(500)
        )
        auditLogService.recordPermChange(
            actCd = "GW_DEPT_MAP",
            targetKindCd = "GW_DEPT",
            targetNm = gwDeptNm,
            detail = detail.take(500),
            targetDeptId = deptId,
            auditId = permAuditId
        )
    }

    // =================================================================================
    // 5) 미배정 계정 · 6) 매핑대로 재배정
    // =================================================================================

    /** @param keyword 사번·이름·그룹웨어 부서명 */
    @Transactional(readOnly = true)
    fun getUnassignedUsers(keyword: String?, page: Int?, size: Int?, state: String? = null): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.SYS_GW_DEPT)
        // 계정 상태 — 쉼표로 여러 개 (ACTIVE·LOCKED·SUSPENDED, 02 GWD-12). 모르는 값은 400
        val states = state?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { systemUserRepository.normalizeUserState(it) }
        val paging = PageRequestParam.ofAllowAll(page, size)
        val deptId = unassignedDept()?.get("deptId") as Int?
            ?: return emptyList<Map<String, Any?>>() to PageMeta.all(0)
        val withSource = repository.sourceExists()

        val total = repository.countUnassignedUsers(deptId, withSource, keyword, states)
        val rows = repository.findUnassignedUsers(deptId, withSource, keyword, null, paging.limitOrNull, paging.offset, states = states)
        return rows to (if (paging.isAll) PageMeta.all(total) else PageMeta.of(paging.page, paging.size, total))
    }

    /**
     * 미배정 계정을 지금 매핑의 제안 부서로 옮긴다. 한 트랜잭션 (02 GWD-01).
     *
     * - 대상 = 미배정 부서 계정 ∩ `empNos`(주면) ∩ `gwDeptNms`(주면). 셋 다 없고 `all=true` 도 아니면 400 —
     *   예전에는 빈 본문이 「전체」 였는데, 화면이 고른 범위와 서버가 옮긴 범위가 달라질 수 있었다.
     * - 상태: 기본은 사용(ACTIVE)·잠김(LOCKED) 계정. `includeSuspended=true` 면 정지 계정도. 승인 대기는 늘 제외.
     *   초기 비밀번호 변경 여부는 보지 않는다(R-05 — 옮긴 뒤 첫 로그인에서 바꾼다).
     * - 옮기지 못한 사번은 `skipped[{empNo, reason}]` — NOT_FOUND · NOT_UNASSIGNED · SUSPENDED · NO_SUGGESTION · SUPER_ADMIN_SUGGESTED
     * - 옮기는 방식·이력은 계정 부서 이동(PUT /system/users/{empNo}/dept)과 같다. 요청 단위 감사 1줄을 더 남긴다.
     */
    @Transactional
    fun reassign(request: GwDeptReassignRequest?): Map<String, Any?> {
        val principal = authorizationService.requireWrite(MenuId.SYS_GW_DEPT)
        val empNos = request?.empNos?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.takeIf { it.isNotEmpty() }
        // 그룹웨어 부서명은 엔진이 글자 그대로 비교한다 — 빈 값만 빼고 다듬지 않는다
        val gwDeptNms = request?.gwDeptNms?.filter { it.isNotEmpty() }?.distinct()?.takeIf { it.isNotEmpty() }
        if (empNos == null && gwDeptNms == null && request?.all != true) {
            throw InvalidParameterException("옮길 계정을 지정하거나 전체 재배정을 명시하십시오.", "empNos")
        }
        val includeSuspended = request?.includeSuspended == true

        val skipped = ArrayList<Map<String, Any?>>()
        fun skip(empNo: String, reason: String) { skipped += mapOf("empNo" to empNo, "reason" to reason) }

        val unassignedId = unassignedDept()?.get("deptId") as Int?
        if (unassignedId == null) {
            // 미배정 부서가 없으면(이름 불일치) 옮길 대상이 없다 — 원인은 요약의 health 가 알린다(GWD-03)
            empNos?.forEach { skip(it, if (systemUserRepository.existsUser(it)) "NOT_UNASSIGNED" else "NOT_FOUND") }
            return reassignResult(emptyList(), skipped)
        }

        val candidates = repository.findUnassignedUsers(unassignedId, repository.sourceExists(), null, empNos, null, 0, gwDeptNms)
        // 사번을 직접 고른 경우 — 목록에 없는 사번의 사유를 가린다(그룹웨어 부서 필터 밖이면 대상이 아니라 사유도 없다)
        if (empNos != null && gwDeptNms == null) {
            val found = candidates.map { it["empNo"] as String }.toSet()
            empNos.filterNot { it in found }.forEach {
                skip(it, if (systemUserRepository.existsUser(it)) "NOT_UNASSIGNED" else "NOT_FOUND")
            }
        }

        // 부서별 상속 권한 수는 부서마다 한 번만 센다
        val counts = HashMap<Int, Pair<Int, Int>>()
        val depts = HashMap<Int, Map<String, Any?>?>()

        val moved = ArrayList<Map<String, Any?>>()
        candidates.forEach { c ->
            val empNo = c["empNo"] as String
            val state = c["state"] as String?
            val movable = state == "ACTIVE" || state == "LOCKED" || (includeSuspended && state == "SUSPENDED")
            if (!movable) return@forEach skip(empNo, "SUSPENDED")

            val targetId = c["suggestDeptId"] as Int?
            val dept = targetId?.let { id -> depts.getOrPut(id) { systemUserRepository.findDept(id) } }
            if (targetId == null || dept == null || targetId == unassignedId) return@forEach skip(empNo, "NO_SUGGESTION")
            // 통합관리자 부서로는 재배정하지 않는다 — 건너뛰기다(CMN-01, 사람이 계정 관리에서 판단)
            if (systemDeptGuard.isSuperAdminDept(dept)) return@forEach skip(empNo, "SUPER_ADMIN_SUGGESTED")

            // 부서 이동은 계정 관리와 같은 한 곳(01 ACC-06)으로 — 이력 문장·대상 표기 「이름(사번)」 이 같다
            systemUserService.moveDept(
                principal, empNo, "${c["name"]}($empNo)", unassignedId, dept, MenuId.SYS_GW_DEPT,
                counts.getOrPut(targetId) { systemUserService.appliedCounts(dept) }
            )
            moved += mapOf("empNo" to empNo, "deptId" to dept["deptId"], "deptNm" to dept["deptNm"], "gwDeptNm" to c["gwDeptNm"])
        }

        // 요청 단위 감사 1줄 — 어떤 범위로 몇 명을 옮겼는지 (계정별 이동 줄과 별개)
        val scope = listOfNotNull(
            if (request?.all == true && empNos == null && gwDeptNms == null) "전체" else null,
            empNos?.let { "사번 ${it.size}명" },
            gwDeptNms?.let { "그룹웨어 부서 ${it.joinToString(", ")}" },
            if (includeSuspended) "정지 포함" else null
        ).joinToString(" · ").take(200)
        auditLogService.record(
            logType = AuditType.PERM_CHANGE,
            menuId = MenuId.SYS_GW_DEPT,
            targetDesc = "미배정 계정 재배정 [$scope]",
            remark = "옮김 ${moved.size} · 건너뜀 ${skipped.size}"
        )
        log.info("미배정 계정 재배정 : scope={} moved={} skipped={} by={}", scope, moved.size, skipped.size, principal.userId)
        return reassignResult(moved, skipped)
    }

    private fun reassignResult(moved: List<Map<String, Any?>>, skipped: List<Map<String, Any?>>): Map<String, Any?> = mapOf(
        "movedCnt" to moved.size,
        "skippedCnt" to skipped.size,
        "items" to moved,
        "byDept" to moved.groupBy { it["deptId"] to it["deptNm"] }
            .entries.sortedByDescending { it.value.size }
            .map { mapOf("deptId" to it.key.first, "deptNm" to it.key.second, "cnt" to it.value.size) },
        "skipped" to skipped
    )
}
