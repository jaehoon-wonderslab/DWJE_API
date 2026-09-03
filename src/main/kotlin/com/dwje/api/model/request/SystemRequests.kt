package com.dwje.api.model.request

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
