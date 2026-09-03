package com.dwje.api.model.response

/**
 * 로그인 응답 — accessToken / refreshToken 및 사용자 기본 정보
 *
 * @param accessToken  API 호출용 접근 토큰
 * @param refreshToken 접근 토큰 재발급용 갱신 토큰
 * @param expiresIn    접근 토큰 유효시간(초)
 * @param user         사용자 기본 정보
 */
data class LoginResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val user: LoginUser
)

/**
 * 로그인 사용자 기본 정보
 *
 * @param empNo 사번
 * @param name  사용자명
 * @param dept  부서명
 * @param pos   직급 코드
 * @param deptId 부서 ID
 * @param superAdmin 통합관리자 여부
 */
data class LoginUser(
    val empNo: String,
    val name: String,
    val dept: String,
    val pos: String?,
    val deptId: Int,
    val superAdmin: Boolean
)

/**
 * 토큰 갱신 응답
 *
 * @param accessToken 재발급된 접근 토큰
 * @param expiresIn   유효시간(초)
 */
data class RefreshTokenResponse(
    val accessToken: String,
    val expiresIn: Long
)

/**
 * 내 정보·권한 조회 응답 — GET /api/v1/auth/me
 *
 * 메뉴 권한과 데이터 권한을 한 번에 반환하며, 프론트 전 화면의 권한 판정 기준이 된다.
 *
 * @param user             사용자 기본 정보
 * @param dept             소속 부서 정보
 * @param menuPerms        접근 가능한 화면 ID 목록
 * @param dataPerms        허용된 데이터 항목 key 목록 (7종 중)
 * @param blindFields      비공개(마스킹) 처리되는 데이터 항목 key 목록
 * @param servingModelVer  현재 서비스 중인 AI 모델 버전
 * @param impersonated     계정 전환 상태 여부
 */
data class MyInfoResponse(
    val user: LoginUser,
    val dept: DeptInfo,
    val menuPerms: List<String>,
    val dataPerms: List<String>,
    val blindFields: List<String>,
    val servingModelVer: String?,
    val impersonated: Boolean
)

/**
 * 부서 정보
 *
 * @param deptId     부서 ID
 * @param deptNm     부서명
 * @param deptAbbr   부서 약칭
 * @param superAdmin 통합관리자 부서 여부
 * @param plantCd    사업장 코드
 */
data class DeptInfo(
    val deptId: Int,
    val deptNm: String,
    val deptAbbr: String?,
    val superAdmin: Boolean,
    val plantCd: String?
)

/**
 * 메뉴 트리 조회 응답 — GET /api/v1/menus
 *
 * @param groups 메뉴 그룹 목록 (접근 가능한 항목만 포함)
 */
data class MenuTreeResponse(
    val groups: List<MenuGroup>
)

/**
 * 메뉴 그룹
 *
 * @param group 그룹명
 * @param solo  단독 메뉴 여부 (하위 항목 없이 단일 링크로 노출)
 * @param items 하위 화면 목록
 */
data class MenuGroup(
    val groupId: String,
    val group: String,
    val solo: Boolean,
    val items: List<MenuItem>
)

/**
 * 메뉴 항목
 *
 * @param id   화면 ID
 * @param name 화면명
 * @param path 라우팅 경로
 * @param tag  개발 구분 태그 (NEW/MOD/REQ)
 */
data class MenuItem(
    val id: String,
    val name: String,
    val path: String?,
    val tag: String?
)
