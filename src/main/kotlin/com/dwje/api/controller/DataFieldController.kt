package com.dwje.api.controller

import com.dwje.api.common.response.ApiResponse
import com.dwje.api.model.request.DataFieldApplyRequest
import com.dwje.api.model.request.DataFieldAttrRequest
import com.dwje.api.model.request.DataFieldSaveRequest
import com.dwje.api.service.DataFieldService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 데이터 접근 항목 운영 컨트롤러 (SY-03, V33)
 *
 * 항목 목록 `GET /system/data-fields` 는 [SystemUserController] 에 그대로 있고(attrs · applyFlg · category 로 확장),
 * 여기는 등록·수정·삭제 · 응답 필드명 · 적용 스위치다. 접근 부서 : 전산팀 · 통합관리자(`sys-data`).
 */
@RestController
@RequestMapping("/api/v1/system/data-fields")
@Tag(name = "08. 시스템관리 - 계정·권한")
class DataFieldController(
    private val dataFieldService: DataFieldService
) {

    @Operation(summary = "데이터 항목 등록", description = "항목을 미적용(applyFlg='N') 상태로 등록한다. 부서 권한을 채운 뒤 apply 로 켠다.")
    @PostMapping
    fun create(@Valid @RequestBody request: DataFieldSaveRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dataFieldService.create(request), "데이터 항목이 등록되었습니다. 부서 권한을 확인한 뒤 적용을 켜 주세요.")

    @Operation(summary = "데이터 항목 수정", description = "항목명 · 설명 · 분류를 수정한다. key 는 바꿀 수 없다.")
    @PutMapping("/{fieldKey}")
    fun update(@PathVariable fieldKey: String, @Valid @RequestBody request: DataFieldSaveRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dataFieldService.update(fieldKey, request), "데이터 항목이 수정되었습니다.")

    @Operation(
        summary = "데이터 항목 삭제",
        description = "부서 권한·응답 필드명이 함께 지워진다. 기본 7개 항목과 알림 조건·지표 기준·보고서 양식·문서 태그가 참조하는 항목은 409."
    )
    @DeleteMapping("/{fieldKey}")
    fun delete(@PathVariable fieldKey: String): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dataFieldService.delete(fieldKey), "데이터 항목이 삭제되었습니다.")

    @Operation(summary = "응답 필드명 등록", description = "API 응답 JSON 필드명을 항목에 붙인다. 다른 항목에 이미 붙은 필드명이면 409.")
    @PostMapping("/{fieldKey}/attrs")
    fun addAttr(@PathVariable fieldKey: String, @Valid @RequestBody request: DataFieldAttrRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dataFieldService.addAttr(fieldKey, request), "응답 필드명이 등록되었습니다.")

    @Operation(summary = "응답 필드명 해제")
    @DeleteMapping("/{fieldKey}/attrs/{attrName}")
    fun removeAttr(@PathVariable fieldKey: String, @PathVariable attrName: String): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dataFieldService.removeAttr(fieldKey, attrName), "응답 필드명이 해제되었습니다.")

    @Operation(summary = "데이터 항목 적용 스위치", description = "on=true 면 마스킹 적용, false 면 미적용. 화면 반영은 재로그인 때.")
    @PatchMapping("/{fieldKey}/apply")
    fun apply(@PathVariable fieldKey: String, @Valid @RequestBody request: DataFieldApplyRequest): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(dataFieldService.setApply(fieldKey, request.on), if (request.on) "항목이 적용되었습니다. 재로그인 때 반영됩니다." else "항목 적용이 해제되었습니다.")
}
