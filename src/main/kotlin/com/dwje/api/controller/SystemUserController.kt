package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.model.request.DataPermRequest
import com.dwje.api.model.request.DeptSaveRequest
import com.dwje.api.model.request.MenuPermCopyRequest
import com.dwje.api.model.request.MenuPermGroupRequest
import com.dwje.api.model.request.MenuPermRequest
import com.dwje.api.model.request.SignupApprovalRequest
import com.dwje.api.model.request.UserStateChangeRequest
import com.dwje.api.model.request.UserDeptChangeRequest
import com.dwje.api.model.request.UserSaveRequest
import com.dwje.api.service.AuditLogService
import com.dwje.api.service.SystemUserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 계정 · 부서 · 권한 관리 컨트롤러 (SY-01, SY-02, SY-03)
 *
 * 접근 : 화면 권한 `sys-account` · `sys-menu` · `sys-data`(엔드포인트별, ax.tb_sys_dept_menu_perm) · 값 마스킹 : 없음
 * 모든 변경은 권한 변경 이력과 감사 로그에 자동 기록된다.
 */
@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "08. 시스템관리 - 계정·권한")
class SystemUserController(
    private val systemUserService: SystemUserService,
    private val auditLogService: AuditLogService
) {

    // =================================================================================
    // SY-01. 계정 관리
    // =================================================================================

    /** 계정 관리 요약 (No.127) */
    @Operation(summary = "계정 관리 요약", description = "사용/정지 계정 수, 부서 수, 전환 가능 계정 수를 반환한다.")
    @GetMapping("/accounts/summary")
    fun accountSummary(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(systemUserService.getAccountSummary())

    /** 계정 목록 조회 (No.128) */
    @Operation(summary = "계정 목록 조회", description = "계정을 조회한다. keyword 는 전 열 검색(사번·이름·부서명·직급·상태·마지막 접속). 행마다 extraMenuIds(계정별 추가 허용 화면)를 포함한다.")
    @GetMapping("/users")
    fun users(
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) deptId: Int?,
        @Parameter(description = "계정 상태 — ACTIVE|SUSPENDED|PENDING|LOCKED, 쉼표로 여러 개") @RequestParam(required = false) state: String?,
        @Parameter(description = "계정 전환 대상 여부") @RequestParam(required = false) switchable: Boolean?,
        @Parameter(description = "가입 경로 — GROUPWARE|SIGNUP|ADMIN") @RequestParam(required = false) joinSrc: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = systemUserService.getUsers(keyword, deptId, state, switchable, page, size, joinSrc)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** 승인 대기 계정 목록 (회원가입 신청 현황) */
    @Operation(summary = "승인 대기 계정 목록", description = "회원가입 신청 후 승인을 기다리는 계정을 조회한다.")
    @GetMapping("/users/pending")
    fun pendingUsers(
        @Parameter(description = "전 열 검색 — 사번·이름·부서·직급·상태·접속") @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = systemUserService.getPendingUsers(page, size, keyword)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** 회원가입 승인·반려 */
    @Operation(summary = "회원가입 승인·반려", description = "승인 대기 계정을 사용(ACTIVE) 또는 정지(SUSPENDED)로 전환한다.")
    @PostMapping("/users/{empNo}/approve")
    fun approveSignup(
        @PathVariable empNo: String,
        @Valid @RequestBody(required = false) request: SignupApprovalRequest?
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(
            systemUserService.approveSignup(empNo, request?.approve ?: true, request?.reason, request?.deptId),
            if (request?.approve != false) "가입을 승인했습니다." else "가입을 반려했습니다."
        )

    /** 계정 등록 (No.129) */
    @Operation(summary = "계정 등록", description = "새 계정을 등록한다. extraMenuIds 를 주면 계정별 추가 허용 화면을 같은 트랜잭션으로 저장한다.")
    @PostMapping("/users")
    fun createUser(@Valid @RequestBody request: UserSaveRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(systemUserService.createUser(request), "계정이 등록되었습니다.")

    /** 계정 수정 (No.130) */
    @Operation(summary = "계정 수정", description = "계정 정보를 수정한다. extraMenuIds — 미전달이면 그대로, [] 면 전부 회수, 목록이면 치환. 없는 화면 ID 가 있으면 400 이고 아무것도 바뀌지 않는다.")
    @PutMapping("/users/{empNo}")
    fun updateUser(
        @PathVariable empNo: String,
        @Valid @RequestBody request: UserSaveRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(systemUserService.updateUser(empNo, request), "계정이 수정되었습니다.")

    /** 계정 삭제 사전 확인 (01 ACC-11) */
    @Operation(summary = "계정 삭제 사전 확인", description = "막는 참조(서빙 프로필 활성화·문서 작성)와 함께 지워지는 참조(알림 수신자·추가 허용 화면·화면 사용 기록) 건수, 가입 경로. 권한 sys-account 조회.")
    @GetMapping("/users/{empNo}/delete-check")
    fun deleteCheck(@PathVariable empNo: String): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(systemUserService.getDeleteCheck(empNo))

    /** 계정 삭제 (No.131) */
    @Operation(summary = "계정 삭제", description = "계정을 삭제한다. 로그인 중인 본인 계정은 삭제할 수 없다.")
    @DeleteMapping("/users/{empNo}")
    fun deleteUser(@PathVariable empNo: String): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(systemUserService.deleteUser(empNo), "계정이 삭제되었습니다.")

    /** 계정 사용/정지 (No.132) */
    @Operation(summary = "계정 사용/정지", description = "계정 상태를 사용 또는 정지로 전환한다. 잠긴 계정을 사용으로 바꾸면 관리자 잠금 해제다.")
    @PatchMapping("/users/{empNo}/state")
    fun changeUserState(
        @PathVariable empNo: String,
        @Valid @RequestBody request: UserStateChangeRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(
            systemUserService.changeUserState(empNo, request.state, request.resetPassword ?: false, request.reason),
            "계정 상태가 변경되었습니다."
        )

    /** 계정 부서 이동 (No.133) */
    @Operation(summary = "계정 부서 이동", description = "계정을 다른 부서로 이동하고 새 부서 권한을 상속한다.")
    @PutMapping("/users/{empNo}/dept")
    fun changeUserDept(
        @PathVariable empNo: String,
        @Valid @RequestBody request: UserDeptChangeRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(systemUserService.changeUserDept(empNo, request.deptId), "부서가 이동되었습니다.")

    /** 계정·권한 변경 이력 (No.139) */
    @Operation(summary = "계정·권한 변경 이력", description = "계정·부서·권한 변경 이력을 조회한다.")
    @GetMapping("/perm-logs")
    fun permLogs(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @Parameter(description = "전 열 검색 — 대상·구분·내용·수행자(사번/이름/부서)") @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) target: String?,
        @Parameter(description = "변경 구분 — ACCOUNT|DEPT|MENU_PERM|DATA_PERM|USER_MENU_PERM|GW_DEPT_MAP, 쉼표로 여러 개")
        @RequestParam(required = false) actType: String?,
        @Parameter(description = "대상 사번(정확 일치)") @RequestParam(required = false) targetUserId: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = auditLogService.getPermLogs(from, to, target, actType, page, size, keyword, targetUserId)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    // =================================================================================
    // 부서 관리
    // =================================================================================

    /** 부서별 권한 비교 (No.134) */
    @Operation(summary = "부서별 권한 비교", description = "부서별 메뉴/데이터 권한 보유 수를 비교한다.")
    @GetMapping("/depts/perm-compare")
    fun deptPermCompare(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(systemUserService.getDeptPermStatus())

    /** 부서 목록 조회 (No.135) */
    @Operation(
        summary = "부서 목록 조회",
        description = "부서 목록과 소속 계정·권한 수를 반환한다. keyword 는 부서명·설명 검색. " +
            "page/size 를 주면 meta 와 함께 쪽으로, 없거나 size=0 이면 전량(items)으로 — 기존 선택지 호출과 호환된다."
    )
    @GetMapping("/depts")
    fun depts(
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = systemUserService.getDepts(keyword, page, size)
        return if (meta == null) ApiResponse.ok(mapOf("items" to rows)) else ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** 부서 등록 (No.136) */
    @Operation(summary = "부서 등록", description = "부서를 등록하고 지정 부서의 초기 권한을 복사한다.")
    @PostMapping("/depts")
    fun createDept(@Valid @RequestBody request: DeptSaveRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(systemUserService.createDept(request), "부서가 등록되었습니다.")

    /** 부서 수정 (No.137) */
    @Operation(summary = "부서 수정", description = "부서 정보를 수정한다.")
    @PutMapping("/depts/{deptId}")
    fun updateDept(
        @PathVariable deptId: Int,
        @Valid @RequestBody request: DeptSaveRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(systemUserService.updateDept(deptId, request), "부서가 수정되었습니다.")

    /** 부서 삭제 (No.138) */
    @Operation(summary = "부서 삭제", description = "부서를 삭제한다. 소속 계정이 있으면 삭제할 수 없다.")
    @DeleteMapping("/depts/{deptId}")
    fun deleteDept(@PathVariable deptId: Int): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(systemUserService.deleteDept(deptId), "부서가 삭제되었습니다.")

    // =================================================================================
    // SY-02. 메뉴 접근 권한
    // =================================================================================

    /** 메뉴 권한 매트릭스 조회 (No.140) */
    @Operation(summary = "메뉴 권한 매트릭스 조회", description = "부서 × 화면 메뉴 권한 매트릭스를 반환한다. 조회는 sys-menu 또는 sys-account 권한(계정별 추가 화면 선택지), 변경은 sys-menu 만.")
    @GetMapping("/menu-perms")
    fun menuPerms(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(systemUserService.getMenuPermMatrix())

    /** 메뉴 권한 단건 변경 (No.141) */
    @Operation(summary = "메뉴 권한 단건 변경", description = "부서의 특정 화면 접근 권한을 부여/회수한다.")
    @PutMapping("/menu-perms")
    fun changeMenuPerm(@Valid @RequestBody request: MenuPermRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(systemUserService.changeMenuPerm(request), "메뉴 권한이 변경되었습니다.")

    /** 메뉴 권한 그룹 일괄 변경 (No.142) */
    @Operation(summary = "메뉴 권한 그룹 일괄 변경", description = "메뉴 그룹 단위로 권한을 일괄 부여/회수한다.")
    @PutMapping("/menu-perms/group")
    fun changeMenuPermGroup(@Valid @RequestBody request: MenuPermGroupRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(systemUserService.changeMenuPermByGroup(request), "그룹 권한이 변경되었습니다.")

    /** 부서 메뉴 권한 복사 (No.143) */
    @Operation(summary = "부서 메뉴 권한 복사", description = "한 부서의 메뉴 권한을 다른 부서로 복사한다.")
    @PostMapping("/menu-perms/copy")
    fun copyMenuPerms(@Valid @RequestBody request: MenuPermCopyRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(systemUserService.copyMenuPerms(request), "권한이 복사되었습니다.")

    /** 부서별 적용 현황 (No.144) */
    @Operation(summary = "부서별 적용 현황", description = "부서별 메뉴/데이터 권한과 계정 수를 반환한다.")
    @GetMapping("/menu-perms/dept-status")
    fun menuPermDeptStatus(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(systemUserService.getDeptPermStatus())

    // =================================================================================
    // SY-03. 데이터 접근 권한
    // =================================================================================

    /** 데이터 항목 목록 (No.145) */
    @Operation(summary = "데이터 항목 목록", description = "데이터 접근 권한 항목 7종과 매핑 컬럼을 반환한다.")
    @GetMapping("/data-fields")
    fun dataFields(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(systemUserService.getDataFields())

    /** 적용 미리보기 (No.148). {fieldKey} 없는 고정 경로를 먼저 선언한다. */
    @Operation(summary = "데이터 권한 적용 미리보기", description = "특정 계정 기준 항목별 노출/비공개 여부를 미리 본다.")
    @GetMapping("/data-perms/preview")
    fun dataPermPreview(@RequestParam empNo: String): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(systemUserService.previewDataPerm(empNo))

    /** 계정별 적용 결과 (No.149) */
    @Operation(summary = "계정별 데이터 권한 적용 결과", description = "계정별 허용/비공개 데이터 항목을 반환한다.")
    @GetMapping("/data-perms/by-user")
    fun dataPermByUser(
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = systemUserService.getDataPermByUser(page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** 데이터 접근 감사 조회 (No.150) */
    @Operation(summary = "데이터 접근 감사 조회", description = "데이터 항목 열람·차단 이력을 조회한다.")
    @GetMapping("/data-perms/audit")
    fun dataPermAudit(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) empNo: String?,
        @RequestParam(required = false) fieldKey: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = auditLogService.getDataAccessAudit(from, to, empNo, fieldKey, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** 데이터 권한 매트릭스 조회 (No.146) */
    @Operation(summary = "데이터 권한 매트릭스 조회", description = "부서 × 데이터 항목 권한 매트릭스를 반환한다.")
    @GetMapping("/data-perms")
    fun dataPerms(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(systemUserService.getDataPermMatrix())

    /** 데이터 권한 변경 (No.147) */
    @Operation(summary = "데이터 권한 변경", description = "부서의 데이터 항목 열람 권한을 부여/회수한다.")
    @PutMapping("/data-perms")
    fun changeDataPerm(@Valid @RequestBody request: DataPermRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(systemUserService.changeDataPerm(request), "데이터 권한이 변경되었습니다.")
}

/**
 * 보안 감사 로그 컨트롤러 (SY-09)
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
@Tag(name = "08. 시스템관리 - 계정·권한")
class AuditLogController(
    private val auditLogService: AuditLogService,
    private val listExportService: com.dwje.api.service.ListExportService,
    private val auditRetentionService: com.dwje.api.service.AuditRetentionService
) {

    /** 보존 정책 조회 (09 AUD-11) */
    @Operation(
        summary = "감사 로그 보존 정책",
        description = "보존 연수, 아카이브 배치 사용 여부, 원천(감사·권한 변경·로그인)별 전체·보존 경과·아카이브 건수와 가장 오래된 시각, 마지막·다음 실행 시각. 권한 sys-audit."
    )
    @GetMapping("/retention-policy")
    fun retentionPolicy(): ApiResponse<Map<String, Any?>> = ApiResponse.ok(auditRetentionService.getRetentionPolicy())

    /** 감사 로그 전체 내려받기 — 서버 생성 xlsx (공통 CMN-07) */
    @Operation(summary = "감사 로그 전체 내려받기", description = "감사 로그 전체를 xlsx 로 만든다(scope=ALL 만, 기간·조건 무관, 상한 50,000건 — 넘으면 X-Export-Truncated·X-Export-Total 헤더).")
    @PostMapping("/export")
    fun export(
        @Valid @RequestBody(required = false) request: com.dwje.api.model.request.ListExportRequest?
    ): org.springframework.http.ResponseEntity<org.springframework.core.io.ByteArrayResource> =
        listExportService.auditLogs(request)

    /**
     * 감사 로그 조회 (No.190)
     *
     * 감사 로그·권한 변경 이력·로그인 이력을 하나의 타임라인으로 통합해 반환한다.
     */
    @Operation(summary = "감사 로그 조회", description = "마스킹·권한 변경·로그인·출력 이력을 통합 조회한다.")
    @GetMapping
    fun auditLogs(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @Parameter(description = "로그 유형 — MASK|RAW_VIEW|PERM_CHANGE|LOGIN|AUTO_GEN|EXPORT|CONFIG_CHANGE|ACCOUNT_SEC|ACCESS_DENIED|AUDIT_VIEW|UNMASK_REQ, 쉼표로 여러 개. 비우면 AUDIT_VIEW 를 뺀 전체")
        @RequestParam(required = false) type: String?,
        @Parameter(description = "부서명") @RequestParam(required = false) userGroup: String?,
        @RequestParam(required = false) empNo: String?,
        @Parameter(description = "사번·이름·부서·대상·내용·IP 검색어") @RequestParam(required = false) keyword: String?,
        @Parameter(description = "IP 또는 대역(10.0.0.0/8). IPv4 앞부분(10.1.)은 앞부분 일치") @RequestParam(required = false) ip: String?,
        @Parameter(description = "결과 — ALLOW|BLIND|REJECT|MASKED") @RequestParam(required = false) result: String?,
        @Parameter(description = "로그인 성공·로그아웃 행 제외") @RequestParam(required = false) excludeLoginSuccess: Boolean?,
        @Parameter(description = "이 시각까지의 행만(yyyy-MM-dd HH:mm:ss) — 첫 쪽 조회 시각을 넘기면 쪽이 밀리지 않는다") @RequestParam(required = false) asOf: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val asOfAt = asOf?.trim()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { java.time.LocalDateTime.parse(it.replace(' ', 'T')) }.getOrElse {
                throw com.dwje.api.common.exception.InvalidParameterException("asOf 는 yyyy-MM-dd HH:mm:ss 형식이어야 합니다.", "asOf")
            }
        }
        val (rows, meta) = auditLogService.getAuditLogs(
            from, to, type, userGroup, empNo, page, size,
            com.dwje.api.repository.AuditLogRepository.AuditFilter(keyword, ip, result, excludeLoginSuccess ?: false, asOfAt)
        )
        return ApiResponse.page(mapOf("items" to rows), meta)
    }
}
