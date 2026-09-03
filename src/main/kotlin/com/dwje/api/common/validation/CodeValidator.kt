package com.dwje.api.common.validation

import com.dwje.api.common.exception.InvalidParameterException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

/**
 * 공통코드(`ax.tb_sys_code`) 값 검증기
 *
 * 요청 값이 코드 그룹 안에 있는지 확인한다.
 *
 * 키 이름 검증(`FAIL_ON_UNKNOWN_PROPERTIES`)으로는 이 자리를 막을 수 없다.
 * 키가 맞아도 값이 코드 집합 밖이면 그대로 저장되고, 화면은 표시명을 찾지 못해
 * 코드를 그대로 노출하거나 빈칸을 그린다. 실제로 그런 행이 있었다 —
 * `tb_met_metric_std.window_cd = 'DAY'` (MET_WINDOW 에 없는 값, V12 에서 교정).
 *
 * 표시명을 값으로 보내는 실수도 여기서 걸린다. (예: 양식 유형에 `품질 이슈` 대신 `QUALITY`)
 */
@Component
class CodeValidator(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    /**
     * 코드가 그룹에 없으면 400 으로 끊는다.
     *
     * @param groupCd 코드 그룹 (예: MET_WINDOW)
     * @param code    검증할 값. null·공백이면 통과 (선택 항목)
     * @param field   응답 `error.field` 에 실을 요청 필드명
     * @param label   오류 메시지에 쓸 항목 이름 (예: "집계 구간")
     */
    fun require(groupCd: String, code: String?, field: String, label: String) {
        val value = code?.trim()?.takeIf { it.isNotBlank() } ?: return
        if (exists(groupCd, value)) return

        val allowed = codesOf(groupCd)
        throw InvalidParameterException(
            "${label} 값이 올바르지 않습니다. [$value] 허용 값은 ${allowed.joinToString(" · ")} 입니다.",
            field
        )
    }

    /** 코드 존재 여부 */
    fun exists(groupCd: String, code: String): Boolean =
        jdbcTemplate.queryForObject(
            """
            SELECT exists(
                SELECT 1 FROM ax.tb_sys_code
                WHERE group_cd = :groupCd AND code = :code AND use_flg = 'Y'
            )
            """.trimIndent(),
            MapSqlParameterSource().addValue("groupCd", groupCd).addValue("code", code),
            Boolean::class.java
        ) ?: false

    /** 그룹의 코드 목록 — 오류 메시지에 허용 값을 적기 위함 */
    fun codesOf(groupCd: String): List<String> =
        jdbcTemplate.query(
            "SELECT code FROM ax.tb_sys_code WHERE group_cd = :groupCd AND use_flg = 'Y' ORDER BY sort_seq",
            MapSqlParameterSource("groupCd", groupCd)
        ) { rs, _ -> rs.getString("code") }
}
