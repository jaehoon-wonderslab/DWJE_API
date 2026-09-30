package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.model.request.GwDeptMapSaveRequest
import com.dwje.api.model.request.GwDeptReassignRequest
import com.dwje.api.service.GwDeptMapService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 그룹웨어 부서 매핑 컨트롤러 (SY-17, 2026-09-30)
 *
 * 접근 : 화면 권한 `sys-gw-dept` · 값 마스킹 : 없음
 * 매핑 저장·삭제는 권한 변경 이력(GW_DEPT_MAP)과 감사 로그에, 재배정은 계정 부서 이동(ACCOUNT)으로 기록된다.
 * 한 명씩 옮기기는 기존 `PUT /api/v1/system/users/{empNo}/dept` 를 쓴다(이 화면 권한으로는 미배정 계정만).
 */
@RestController
@RequestMapping("/api/v1/system/gw-dept-maps")
@Tag(name = "08. 시스템관리 - 그룹웨어 부서 매핑")
class GwDeptMapController(
    private val gwDeptMapService: GwDeptMapService
) {

    /** SY-17-F01 요약 */
    @Operation(
        summary = "그룹웨어 부서 매핑 요약",
        description = "재직자가 있는 그룹웨어 부서의 매핑 현황, 미배정 계정 수, 미배정 부서, 가장 최근 그룹웨어 동기화의 AX 가입 결과."
    )
    @GetMapping("/summary")
    fun summary(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(gwDeptMapService.getSummary())

    /** SY-17-F02 목록 */
    @Operation(
        summary = "그룹웨어 부서 매핑 목록",
        description = "그룹웨어 부서명(재직자 기준) ∪ 매핑표 행. 매핑 행이 없는 부서도 한 줄(hasRow=false), " +
            "그룹웨어에서 사라진 부서의 매핑 행은 inSource=false. size=0 이면 전량."
    )
    @GetMapping
    fun maps(
        @Parameter(description = "그룹웨어 부서명·AX 부서명·메모 부분 일치") @RequestParam(required = false) keyword: String?,
        @Parameter(description = "MAPPED | UNMAPPED | EXCLUDED") @RequestParam(required = false) state: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = gwDeptMapService.getMaps(keyword, state, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** SY-17-F03 저장 */
    @Operation(
        summary = "그룹웨어 부서 매핑 저장",
        description = "전체 덮어쓰기(upsert). deptId 없음 = 미배정, remark 없음 = 비움, joinYn=N 이면 deptId 를 비운다. " +
            "미배정·통합관리자 부서는 400. 이미 가입된 계정의 부서는 바뀌지 않는다."
    )
    @PutMapping
    fun save(@Valid @RequestBody request: GwDeptMapSaveRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(gwDeptMapService.saveMap(request), GwDeptMapService.SAVE_MESSAGE)

    /** SY-17-F04 삭제 */
    @Operation(summary = "그룹웨어 부서 매핑 삭제", description = "매핑 행을 지운다. 없으면 404. 그 부서 사람은 다음 가입부터 미배정.")
    @DeleteMapping
    fun delete(@RequestParam(required = false) gwDeptNm: String?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(gwDeptMapService.deleteMap(gwDeptNm), GwDeptMapService.DELETE_MESSAGE)

    /** SY-17-F05 미배정 계정 */
    @Operation(
        summary = "미배정 계정 목록",
        description = "미배정 부서에 속한 계정 전체와 지금 매핑 기준 제안 부서(suggestDept*). size=0 이면 전량."
    )
    @GetMapping("/unassigned-users")
    fun unassignedUsers(
        @Parameter(description = "사번·이름·그룹웨어 부서명") @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): ApiResponse<Map<String, Any?>> {
        val (rows, meta) = gwDeptMapService.getUnassignedUsers(keyword, page, size)
        return ApiResponse.page(mapOf("items" to rows), meta)
    }

    /** SY-17-F06 매핑대로 재배정 */
    @Operation(
        summary = "미배정 계정 재배정",
        description = "미배정 계정을 제안 부서로 옮긴다(한 트랜잭션). empNos 가 없으면 제안 부서가 있는 미배정 계정 전체. " +
            "미배정이 아니거나 제안 부서가 없는 사번은 skippedCnt."
    )
    @PostMapping("/reassign")
    fun reassign(@Valid @RequestBody(required = false) request: GwDeptReassignRequest?): ApiResponse<Map<String, Any?>> {
        val result = gwDeptMapService.reassign(request?.empNos)
        val moved = result["movedCnt"] as Int
        return ApiResponse.ok(result, if (moved == 0) "옮길 미배정 계정이 없습니다." else "미배정 계정 ${moved}명을 옮겼습니다.")
    }
}
