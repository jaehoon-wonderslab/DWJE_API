package com.dwje.api.service

import com.dwje.api.config.AppProperties
import com.dwje.api.repository.AlertConfigRepository
import org.springframework.stereotype.Component

/** 수신 대상 판정용 그룹 행 */
data class TargetGroupRow(
    val groupId: Int,
    val groupNm: String,
    val useFlg: Boolean,
    val channels: List<String>
)

/** 수신 대상 판정용 멤버 행 — 제외 사유를 내야 하므로 거르지 않고 다 읽는다 */
data class TargetMemberRow(
    val groupId: Int,
    val userId: String,
    val userNm: String?,
    val deptNm: String?,
    val userState: String?,
    val userStateNm: String?,
    val email: String?,
    val mobileNo: String?,
    val messengerId: String?
)

/** 발송 대상 1건(사람 × 채널). 연락처(destAddr)는 대기열 적재에만 쓰고 응답에 싣지 않는다 */
data class AlertTarget(
    val groupId: Int,
    val userId: String,
    val userNm: String?,
    val deptNm: String?,
    val channel: String,
    val destAddr: String
)

/** 제외 1건. 그룹·조건 단위 사유는 empNo 가 null 이다 */
data class TargetSkip(
    val empNo: String?,
    val name: String?,
    val reason: String,
    val reasonNm: String,
    val groupId: Int?
)

data class TargetResult(val targets: List<AlertTarget>, val skipped: List<TargetSkip>)

/**
 * 알림 수신 대상 판정 (05 ALC-03·ALC-06, 06 RCP-03·RCP-04)
 *
 * Alert_Engine `RecipientRepository.findTargets` 와 같은 규칙에 계정 상태 조건을 더했다.
 * 테스트 발송·그룹 목록 `receivingCnt`·그룹 상세 `receivableCnt` 가 함께 쓴다.
 *
 * 대상(사람 × 채널) = 그룹 사용 중 ∧ 채널 ∈ (요청 채널 ∩ 그룹 채널)
 *   ∧ 계정 상태 ∈ [AlertProperties.receivableUserStates] ∧ 채널 연락처가 비어 있지 않음.
 * 수신자 부재·야간 수신은 2026-10-03 에 없앴다(V74) — 그룹 멤버는 시각과 관계없이 모두 받는다.
 * 유효 시간대(조건·그룹 window_cd)는 보지 않는다 — 테스트는 시간대와 무관하게 보낸다(05 Q-04 ①).
 * 같은 (사람, 채널)이 여러 그룹에서 나오면 첫 그룹만 대상에 넣는다(대기열 유일 키와 같다).
 */
@Component
class AlertTargetResolver(
    private val alertConfigRepository: AlertConfigRepository,
    private val appProperties: AppProperties
) {

    /** 수신 가능한 계정 상태 */
    val receivableUserStates: List<String> get() = appProperties.alert.receivableUserStates

    fun resolve(groupIds: List<Int>, channels: List<String>): TargetResult {
        val wanted = channels.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) {
            return TargetResult(emptyList(), listOf(TargetSkip(null, null, NO_CHANNEL, "발송 채널이 지정되지 않았습니다", null)))
        }
        val ids = groupIds.distinct()
        if (ids.isEmpty()) return TargetResult(emptyList(), emptyList())

        val groups = alertConfigRepository.findTargetGroups(ids).associateBy { it.groupId }
        val members = alertConfigRepository.findTargetMembers(ids).groupBy { it.groupId }
        return evaluate(ids.mapNotNull { groups[it] }, members, wanted)
    }

    /** 판정 본체 — DB 없이 시험할 수 있게 나눴다 */
    fun evaluate(
        groups: List<TargetGroupRow>,
        membersByGroup: Map<Int, List<TargetMemberRow>>,
        channels: List<String>
    ): TargetResult {
        val targets = mutableListOf<AlertTarget>()
        val skipped = LinkedHashSet<TargetSkip>()
        val seen = HashSet<Pair<String, String>>()

        for (g in groups) {
            if (!g.useFlg) {
                skipped.add(TargetSkip(null, null, GROUP_INACTIVE, "사용 중지된 수신 그룹", g.groupId))
                continue
            }
            val effective = channels.filter { it in g.channels }
            channels.filterNot { it in g.channels }.forEach {
                skipped.add(TargetSkip(null, null, CHANNEL_MISMATCH, "그룹이 받지 않는 채널($it)", g.groupId))
            }
            if (effective.isEmpty()) continue

            for (m in membersByGroup[g.groupId].orEmpty()) {
                val personSkip = personSkip(m)
                if (personSkip != null) {
                    skipped.add(personSkip)
                    continue
                }
                for (ch in effective) {
                    val dest = destOf(ch, m)
                    if (dest.isNullOrBlank()) {
                        skipped.add(TargetSkip(m.userId, m.userNm, NO_CONTACT, "${channelNm(ch)} 연락처가 비어 있습니다", null))
                        continue
                    }
                    if (seen.add(m.userId to ch)) {
                        targets.add(AlertTarget(g.groupId, m.userId, m.userNm, m.deptNm, ch, dest))
                    }
                }
            }
        }
        return TargetResult(targets, skipped.toList())
    }

    /** 채널과 무관한 사람 단위 수신 가능 여부 — 메일 기준(채널을 모를 때) */
    fun isReceivable(m: TargetMemberRow): Boolean =
        m.userState in receivableUserStates && !m.email.isNullOrBlank()

    /**
     * 그룹 채널 기준 수신 가능 여부 (06 RCP-09) — 계정 상태 · 그 채널 중 하나라도 연락처가 있음.
     * 시스템 팝업은 사번으로 받으므로 늘 연락처가 있다.
     */
    fun isReceivable(m: TargetMemberRow, channels: List<String>): Boolean =
        m.userState in receivableUserStates && channels.any { !destOf(it, m).isNullOrBlank() }

    /**
     * 도달 가능성 (05 ALC-06) — 조건 채널 ∩ 그룹 채널로 받을 수 있는 사람 수. 시간대는 보지 않는다(시각에 따라 숫자가 바뀌지 않게).
     *
     * @return receivingCnt(중복 제외 사람 수), byGroup[{groupId, receivingCnt, channelMatch}]
     */
    fun reach(groupIds: List<Int>, channels: List<String>): Map<String, Any?> {
        val wanted = channels.map { it.trim().uppercase() }.distinct()
        val ids = groupIds.distinct()
        val groups = alertConfigRepository.findTargetGroups(ids).associateBy { it.groupId }
        val members = alertConfigRepository.findTargetMembers(ids).groupBy { it.groupId }
        val result = evaluate(ids.mapNotNull { groups[it] }, members, wanted)
        return mapOf(
            "receivingCnt" to result.targets.map { it.userId }.distinct().size,
            "byGroup" to ids.mapNotNull { groups[it] }.map { g ->
                mapOf(
                    "groupId" to g.groupId,
                    "receivingCnt" to result.targets.filter { it.groupId == g.groupId }.map { it.userId }.distinct().size,
                    "channelMatch" to wanted.filter { it in g.channels }
                )
            }
        )
    }

    /** 사람 단위 제외 사유 — 계정 상태뿐이다(부재·야간 미수신 사유는 2026-10-03 제거) */
    private fun personSkip(m: TargetMemberRow): TargetSkip? =
        if (m.userState !in receivableUserStates) {
            TargetSkip(m.userId, m.userNm, ACCOUNT_INACTIVE, "계정 ${m.userStateNm ?: m.userState ?: "상태 없음"}", null)
        } else null

    companion object {
        const val GROUP_INACTIVE = "GROUP_INACTIVE"
        const val CHANNEL_MISMATCH = "CHANNEL_MISMATCH"
        const val NO_CHANNEL = "NO_CHANNEL"
        const val ACCOUNT_INACTIVE = "ACCOUNT_INACTIVE"
        const val NO_CONTACT = "NO_CONTACT"

        /** 채널별 발송 주소 — 엔진 RecipientRepository 와 같다(POPUP 은 사번) */
        fun destOf(channel: String, m: TargetMemberRow): String? = when (channel) {
            "MAIL" -> m.email
            "SMS" -> m.mobileNo
            "MSG" -> m.messengerId
            "POPUP" -> m.userId
            else -> null
        }

        fun channelNm(channel: String): String = when (channel) {
            "MAIL" -> "메일"
            "SMS" -> "SMS"
            "MSG" -> "메신저"
            "POPUP" -> "시스템 팝업"
            else -> channel
        }
    }
}
