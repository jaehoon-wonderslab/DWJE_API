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
    val extraMenuIds: List<String>? = null,
    /**
     * 추가 허용 사유 — 화면 ID → 사유(200자 이내, 01 ACC-10). 부여 중인 화면의 사유를 저장하고, 빈 문자열이면 사유를 지운다.
     * 부여하지 않는 화면의 사유는 무시한다.
     */
    val extraMenuReasons: Map<String, String?>? = null
)

/**
 * 계정 상태 변경 요청 — PATCH /api/v1/system/users/{empNo}/state
 *
 * @param state         바꿀 상태 (ACTIVE · SUSPENDED). LOCKED 는 지정할 수 없다(잠금은 로그인 실패로만 생긴다)
 * @param reason        변경 사유 (선택)
 * @param resetPassword 잠긴 계정을 풀 때 비밀번호도 초기 규칙값으로 되돌릴지 (선택, 기본 false)
 */
data class UserStateChangeRequest(
    val state: String? = null,
    val reason: String? = null,
    val resetPassword: Boolean? = null
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

    val allowed: Boolean = true,

    /** 바꿀 칸 — READ(조회, 기본) | WRITE(쓰기). 03 MNP-16 */
    val perm: String = "READ"
)

/**
 * 메뉴 권한 그룹 일괄 변경 요청 — PUT /api/v1/system/menu-perms/group
 */
data class MenuPermGroupRequest(
    val deptId: Int,

    /** 메뉴 그룹 ID(assistant·dashboard·…·system). 옛 본문의 [groupNm](그룹 이름)도 받는다 — 둘 중 하나 필수 */
    val groupId: String? = null,
    val groupNm: String? = null,

    val allowed: Boolean = true,

    /** READ | WRITE (03 MNP-16) */
    val perm: String = "READ",

    /** 동작 화면(예: 업로드 리포트 업로드)도 함께 바꿀지. 기본 false — 그룹 일괄로 동작 권한이 딸려 가지 않게 */
    val includeActions: Boolean = false
)

/**
 * 부서 메뉴 권한 복사 요청 — POST /api/v1/system/menu-perms/copy
 */
data class MenuPermCopyRequest(
    val fromDeptId: Int,
    val toDeptId: Int,
    /** true 면 저장하지 않고 미리보기만(조회 권한으로 가능). 03 MNP-01 */
    val dryRun: Boolean = false,
    /** 미리보기가 돌려준 해시. 실행 때 다르면 409(그 사이 권한이 바뀜) */
    val expectedHash: String? = null
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
 * @param reportId    보고서 정의 ID (선택, RPT_*). 옛 클라이언트가 넣던 화면 ID 는 menuId 로 옮긴다
 * @param reportNm    내려받은 대상 이름 — 비우면 "보고서" (200자)
 * @param menuId      화면 ID — 그 화면의 조회 권한이 있어야 한다 (DLG-05)
 * @param format      파일 형식 코드 — XLS · XLSX · CSV · PDF · PNG · JSONL (비우면 XLS, 소문자·옛 표시명도 받는다)
 * @param scope       사람이 읽는 범위 문구 (선택, 100자) — VIEW/ALL 코드가 아니다
 * @param scopeCd     내려받은 범위 코드 — VIEW(조회 목록) | ALL(전체) (DLG-15, 공통 10.6)
 * @param condSummary 조회 조건 요약 (500자)
 * @param rowCnt      내려받은 행 수 (0 이상)
 * @param blindCnt    비공개로 채운 셀 수 (0 이상)
 */
data class DownloadLogRecordRequest(
    val reportId: String? = null,
    @field:Size(max = 200, message = "보고서명은 200자 이내여야 합니다.")
    val reportNm: String? = null,
    val menuId: String? = null,
    val format: String? = null,
    @field:Size(max = 100, message = "범위 설명은 100자 이내여야 합니다.")
    val scope: String? = null,
    val scopeCd: String? = null,
    @field:Size(max = 500, message = "조회 조건은 500자 이내여야 합니다.")
    val condSummary: String? = null,
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
    val remark: String? = null,
    /**
     * 이어받을 옛 그룹웨어 부서명 (02 GWD-11) — 주면 그 행의 부서·가입 여부·메모를 새 이름으로 옮기고 옛 행을 지운다.
     * 이때 본문의 deptId·joinYn·remark 는 쓰지 않는다(DB 의 옛 행 값이 기준).
     */
    @field:Size(max = 100, message = "이어받을 그룹웨어 부서명은 100자 이내여야 합니다.")
    val fromGwDeptNm: String? = null
)

/**
 * 미배정 계정 재배정 요청 — POST /api/v1/system/gw-dept-maps/reassign (02 GWD-01)
 *
 * `empNos` · `gwDeptNms` · `all=true` 중 하나는 있어야 한다. 아무것도 없으면 400 — 예전처럼 빈 본문을 「전체」 로 보지 않는다.
 * 셋이 함께 오면 교집합이다(`all` 은 「목록 없이 전체 허용」 의 뜻만 있다).
 *
 * @param empNos           옮길 사번 (최대 1,000)
 * @param gwDeptNms        이 그룹웨어 부서 소속 미배정 계정만 (최대 200, 글자 그대로 비교)
 * @param all              목록 없이 제안 부서가 있는 미배정 계정 전체
 * @param includeSuspended 정지(SUSPENDED) 계정도 옮길지. 기본은 사용·잠김 계정만
 */
data class GwDeptReassignRequest(
    @field:Size(max = 1000, message = "한 번에 1,000개까지 처리할 수 있습니다.")
    val empNos: List<String>? = null,
    @field:Size(max = 200, message = "한 번에 200개까지 처리할 수 있습니다.")
    val gwDeptNms: List<String>? = null,
    val all: Boolean? = null,
    val includeSuspended: Boolean? = null
)

/**
 * 서버 생성 전체 내려받기 공통 요청 (공통 CMN-07) — 감사 로그 · 다운로드 이력 · 데이터 연동 · 질의 이력
 *
 * @param scope       ALL(전체, 기본) | VIEW. 서버 생성은 「전체」 용이다(조회 목록은 브라우저가 만든다)
 * @param menuId      내려받는 화면 ID — 다운로드 이력에 남는다
 * @param condSummary 조회 조건 요약(사람이 읽는 문구) — 다운로드 이력에 남는다
 * @param format      xlsx 만
 * @param from        기간 시작(없으면 화면 기본 기간)
 * @param to          기간 끝
 * @param target      데이터 연동만 — JOBS | RUNS | DRIFTS
 * @param view        질의 이력만 — QUERY(질의 단위, 기본) | SESSION(세션 단위)
 * @param keyword     검색어(화면이 쓰는 경우)
 */
data class ListExportRequest(
    val scope: String? = null,
    val scopeCd: String? = null,
    val menuId: String? = null,
    @field:Size(max = 500, message = "조회 조건은 500자 이내여야 합니다.")
    val condSummary: String? = null,
    val format: String? = null,
    val from: String? = null,
    val to: String? = null,
    val target: String? = null,
    val view: String? = null,
    val keyword: String? = null
)

/**
 * 그룹웨어 부서 매핑 일괄 지정 — PUT /api/v1/system/gw-dept-maps/bulk (02 GWD-05)
 *
 * @param gwDeptNms  그룹웨어 부서명 1~200개 — 엔진이 글자 그대로 비교하므로 다듬지 않는다
 * @param deptId     가입시킬 AX 부서. 없으면 미배정. [joinYn] 이 N 이면 무시
 * @param joinYn     자동 가입 대상 여부 Y|N (기본 Y)
 * @param keepRemark 기존 메모를 둘지(기본 true). false 면 [remark] 로 바꾼다(없으면 비움)
 */
data class GwDeptMapBulkSaveRequest(
    val gwDeptNms: List<String>? = null,
    val deptId: Int? = null,
    @field:Pattern(regexp = "^[YN]$", message = "가입 여부는 Y 또는 N 이어야 합니다.")
    val joinYn: String? = null,
    val keepRemark: Boolean? = null,
    @field:Size(max = 200, message = "메모는 200자 이내여야 합니다.")
    val remark: String? = null
)

/**
 * 업로드 문서 숨김 요청 — DELETE /api/v1/system/uploads/{docId} (R-19)
 *
 * @param reason 숨기는 사유 (필수, 200자 이내) — 감사 비고와 목록 deleteReason 에 남는다
 */
data class UploadDocHideRequest(
    @field:Size(max = 200, message = "숨기는 사유는 200자 이내여야 합니다.")
    val reason: String? = null
)
