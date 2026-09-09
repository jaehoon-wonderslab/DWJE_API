package com.dwje.api.model.request

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

/**
 * 계정 등록·수정 요청 — POST/PUT /api/v1/system/users
 *
 * @param empNo      사번
 * @param name       이름
 * @param deptId     부서 ID
 * @param pos        직급 코드 (SYS_POSITION)
 * @param state      계정 상태 (사용/정지 또는 ACTIVE/SUSPENDED)
 * @param switchable 계정 전환 대상 여부
 * @param password   초기 비밀번호 (등록 시)
 */
data class UserSaveRequest(
    val empNo: String? = null,
    val name: String? = null,
    val deptId: Int? = null,
    val pos: String? = null,
    val state: String? = null,
    val switchable: Boolean? = null,
    val plantCd: String? = null,
    val password: String? = null,
    val remark: String? = null
)

/**
 * 계정 부서 이동 요청 — PUT /api/v1/system/users/{empNo}/dept
 */
data class UserDeptChangeRequest(
    val deptId: Int
)

/**
 * 부서 등록·수정 요청 — POST/PUT /api/v1/system/depts
 *
 * @param deptNm        부서명
 * @param abbr          부서 약칭 (최대 4자)
 * @param desc          부서 설명
 * @param initPermFrom  초기 권한을 복사해 올 부서 ID (등록 시)
 */
data class DeptSaveRequest(
    val deptNm: String? = null,
    val abbr: String? = null,
    val desc: String? = null,
    val plantCd: String? = null,
    val initPermFrom: Int? = null
)

/**
 * 메뉴 권한 단건 변경 요청 — PUT /api/v1/system/menu-perms
 */
data class MenuPermRequest(
    val deptId: Int,

    @field:NotBlank(message = "화면 ID를 입력해 주세요.")
    val screenId: String,

    val allowed: Boolean = true
)

/**
 * 메뉴 권한 그룹 일괄 변경 요청 — PUT /api/v1/system/menu-perms/group
 */
data class MenuPermGroupRequest(
    val deptId: Int,

    @field:NotBlank(message = "메뉴 그룹을 선택해 주세요.")
    val groupNm: String,

    val allowed: Boolean = true
)

/**
 * 부서 메뉴 권한 복사 요청 — POST /api/v1/system/menu-perms/copy
 */
data class MenuPermCopyRequest(
    val fromDeptId: Int,
    val toDeptId: Int
)

/**
 * 데이터 권한 변경 요청 — PUT /api/v1/system/data-perms
 */
data class DataPermRequest(
    val deptId: Int,

    @field:NotBlank(message = "데이터 항목을 선택해 주세요.")
    val fieldKey: String,

    val allowed: Boolean = true
)

/**
 * 다운로드 이력 기록 요청 — POST /api/v1/download-logs
 *
 * 화면이 클라이언트 측에서 파일을 만들어 내려받은 뒤 이력만 남길 때 보낸다.
 * 예전에는 `Map` 으로 받아 키 오타가 조용히 무시됐다(예: `rowCount` 를 보내면 0건으로 기록).
 * 타입 DTO 라 모르는 키는 400 으로 돌아가고 받는 키 목록이 안내된다.
 *
 * @param reportId 보고서 정의 ID (선택)
 * @param reportNm 내려받은 대상 이름 — 비우면 "보고서"
 * @param menuId   화면 ID (선택)
 * @param format   파일 형식 — xls | xlsx | csv | pdf (비우면 xls)
 * @param scope    조회 조건 요약 (선택)
 * @param rowCnt   내려받은 행 수 (0 이상)
 * @param blindCnt blind 처리된 셀 수 (0 이상)
 */
data class DownloadLogRecordRequest(
    val reportId: String? = null,
    val reportNm: String? = null,
    val menuId: String? = null,
    val format: String? = null,
    val scope: String? = null,
    @field:Min(0, message = "rowCnt 는 0 이상이어야 합니다.")
    val rowCnt: Int? = null,
    @field:Min(0, message = "blindCnt 는 0 이상이어야 합니다.")
    val blindCnt: Int? = null,
    /**
     * 생성 조건 스냅샷 — 대상일·기간·공정·양식 등 요청 파라미터 그대로.
     *
     * 문서를 저장하지 않으므로 같은 산출물을 다시 만들 수 있는 유일한 단서다.
     */
    val params: Map<String, Any?>? = null,
    /** 내려받은 파일 크기(byte). 인쇄(PDF) 처럼 파일이 없으면 생략한다. */
    @field:Min(0, message = "fileSize 는 0 이상이어야 합니다.")
    val fileSize: Long? = null
)
