package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.security.UserContext
import com.dwje.api.model.request.FavoriteScreensRequest
import com.dwje.api.repository.UserFavoriteRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 사용자 즐겨찾기 화면 서비스 (보고서 센터 사이드바 고정)
 *
 * 본인 것만 읽고 쓴다. 메뉴 권한 검사는 하지 않는다 — 별표는 "자주 가는 곳" 표시일 뿐이고,
 * 실제 화면 접근은 각 화면 API 가 따로 막는다. 웹도 메뉴 정의·권한으로 걸러 그린다.
 */
@Service
class UserFavoriteService(
    private val userFavoriteRepository: UserFavoriteRepository
) {

    companion object {
        /** 사이드바 상단에 고정할 수 있는 개수. 웹 요청은 "8개 정도" 였고 여유를 둔다. */
        const val MAX_FAVORITES = 20
    }

    /** 내 즐겨찾기 목록 */
    fun getMine(): Map<String, Any?> {
        val principal = UserContext.current()
        return toResponse(userFavoriteRepository.findByUser(principal.userId))
    }

    /**
     * 내 즐겨찾기를 통째로 교체한다.
     *
     * - 공백·중복은 정리한다(첫 등장만 남김). 같은 화면을 두 번 보내도 오류가 아니다.
     * - 개수 초과와 메뉴에 없는 ID 는 400 — FK 위반을 500 으로 흘리지 않는다.
     */
    @Transactional
    fun replaceMine(request: FavoriteScreensRequest): Map<String, Any?> {
        val principal = UserContext.current()

        val ids = (request.screenIds ?: emptyList())
            .mapNotNull { it.trim().takeIf { s -> s.isNotBlank() } }
            .distinct()

        if (ids.size > MAX_FAVORITES) {
            throw InvalidParameterException("즐겨찾기는 최대 ${MAX_FAVORITES}개까지 저장할 수 있습니다. [${ids.size}개]", "screenIds")
        }

        val unknown = ids - userFavoriteRepository.findExistingMenuIds(ids)
        if (unknown.isNotEmpty()) {
            throw InvalidParameterException("등록되지 않은 화면 ID 입니다. [${unknown.joinToString()}]", "screenIds")
        }

        userFavoriteRepository.replaceAll(principal.userId, ids)
        return toResponse(userFavoriteRepository.findByUser(principal.userId))
    }

    private fun toResponse(rows: List<Pair<String, Int>>): Map<String, Any?> =
        mapOf(
            "items" to rows.map { (menuId, seq) ->
                mapOf("screenId" to menuId, "sortOrder" to seq)
            }
        )
}
