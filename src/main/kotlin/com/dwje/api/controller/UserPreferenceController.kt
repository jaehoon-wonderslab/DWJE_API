package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.model.request.FavoriteScreensRequest
import com.dwje.api.service.UserFavoriteService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 로그인 사용자 개인 설정 컨트롤러 — `/api/v1/users/me` 하위
 *
 * 본인 것만 읽고 쓴다. 관리자용 계정 API(`/api/v1/system/users`)와 달리 메뉴 권한을 요구하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/users/me")
@Tag(name = "01. 인증·공통")
class UserPreferenceController(
    private val userFavoriteService: UserFavoriteService
) {

    /** 내 즐겨찾기 화면 목록 (보고서 센터 사이드바 고정) */
    @Operation(
        summary = "내 즐겨찾기 화면 조회",
        description = "로그인 사용자가 별표한 화면 ID 를 sortOrder 오름차순으로 반환한다. 사용 중지된 메뉴는 제외한다."
    )
    @GetMapping("/favorites")
    fun favorites(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(userFavoriteService.getMine())

    /** 내 즐겨찾기 화면 전체 교체 */
    @Operation(
        summary = "내 즐겨찾기 화면 교체",
        description = "screenIds 배열 순서대로 목록 전체를 교체한다(멱등). 빈 배열은 전부 해제. " +
            "최대 20개, 메뉴에 없는 ID 는 400."
    )
    @PutMapping("/favorites")
    fun replaceFavorites(
        @Valid @RequestBody request: FavoriteScreensRequest
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(userFavoriteService.replaceMine(request), "즐겨찾기를 저장했습니다.")
}
