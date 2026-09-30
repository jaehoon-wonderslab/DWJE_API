package com.dwje.api.model.request

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

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
    /**
     * 등록: 초기 비밀번호(비우면 `사번!Dwje1234`). 수정: **관리자 비밀번호 변경** — 비우거나 미전달이면 그대로,
     * 값이 있으면 통합관리자 또는 부서 기본 sys-account 권한자만 바꿀 수 있다(그 외 403). 정책 검사 후 해시로만 저장하고 이력에 값은 남기지 않는다.
     */
    val password: String? = null,
    val remark: String? = null,
    /**
     * 계정별 추가 허용 화면(menu_id) — 부서 권한에 **더해** 이 계정에만 열어 주는 화면 (V30 `ax.tb_sys_user_menu_grant`).
     * `null`(미전달) 이면 그대로 두고, `[]` 면 전부 회수, 목록이면 그 목록으로 **치환**한다. 계정 정보와 한 트랜잭션으로 저장된다.
     */
    val extraMenuIds: List<String>? = null
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

/**
 * 그룹웨어 부서 매핑 저장(upsert) 요청 — PUT /api/v1/system/gw-dept-maps (2026-09-30 WEB 요청)
 *
 * 웹은 빈 값(`null`·`''`)을 요청에서 빼므로 **전체 덮어쓰기**다. 빠진 [deptId] 는 미배정, 빠진 [remark] 는 비움.
 *
 * @param gwDeptNm 그룹웨어 부서명 — 엔진이 글자 그대로 비교하므로 앞뒤 공백을 다듬지 않는다
 * @param deptId   가입시킬 AX 부서. 없으면 미배정. [joinYn] 이 `N` 이면 무시하고 비운다
 * @param joinYn   자동 가입 대상 여부 `Y`|`N`. 없으면 `Y`
 * @param remark   매핑 근거·메모
 */
data class GwDeptMapSaveRequest(
    @field:NotBlank(message = "그룹웨어 부서명을 입력해 주세요.")
    @field:Size(max = 100, message = "그룹웨어 부서명은 100자 이내여야 합니다.")
    val gwDeptNm: String,
    val deptId: Int? = null,
    @field:Pattern(regexp = "^[YN]$", message = "가입 여부는 Y 또는 N 이어야 합니다.")
    val joinYn: String? = null,
    @field:Size(max = 200, message = "메모는 200자 이내여야 합니다.")
    val remark: String? = null
)

/**
 * 미배정 계정 재배정 요청 — POST /api/v1/system/gw-dept-maps/reassign
 *
 * @param empNos 옮길 사번. 웹은 빈 배열을 요청에서 빼므로 **없으면 제안 부서가 있는 미배정 계정 전체**
 */
data class GwDeptReassignRequest(
    val empNos: List<String>? = null
)
