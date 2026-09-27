package com.dwje.api.service

import com.dwje.api.common.util.AiBusinessPeriod
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** 허용한 질문 형태만 구조화한다. 인식하지 못한 문장은 문서 검색 경로에 둔다. */
object AiQuestionParser {
    data class DefectTop(val period: AiBusinessPeriod, val limit: Int)
    data class ProductList(val period: AiBusinessPeriod)
    data class DefectRateTop(val period: AiBusinessPeriod, val limit: Int)
    data class DocumentCount(val years: List<Int>, val byRegistration: Boolean, val defectReports: Boolean)

    /** 제품 목록·불량률 순위에 쓰는 명시 날짜. 인식 실패를 최신일로 대체하지 않는다. */
    fun explicitBusinessPeriod(question: String, today: LocalDate = LocalDate.now(ZoneId.of("Asia/Seoul"))): AiBusinessPeriod? {
        val compact = question.replace(Regex("\\s+"), "")
        val full = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[~～]|부터|에서)(?:(\\d{4})-)?(?:(\\d{1,2})-)?(\\d{1,2})(?:일까지|일|까지)?").find(compact)
        if (full != null) {
            val g = full.groupValues
            return runCatching {
                val from = LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt())
                val endMonth = g[5].toIntOrNull() ?: from.monthValue
                val endYear = g[4].toIntOrNull() ?: if (endMonth < from.monthValue) from.year + 1 else from.year
                validPeriod(from, LocalDate.of(endYear, endMonth, g[6].toInt()))
            }.getOrNull()
        }
        val twoMonths = Regex("(?<!\\d)(\\d{1,2})(?:월\\s*|-)(\\d{1,2})일?\\s*(?:[~～,]|부터|에서|와|과|및|\\s+)\\s*(\\d{1,2})(?:월\\s*|-)(\\d{1,2})일?")
            .find(question)
        if (twoMonths != null) {
            val g = twoMonths.groupValues
            return recentYearlessRange(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), today)
        }
        val oneMonth = Regex("(?<!\\d)(\\d{1,2})월\\s*(\\d{1,2})일?\\s*(?:[~～,]|부터|에서|와|과|및)\\s*(\\d{1,2})일")
            .find(question)
        if (oneMonth != null) {
            val g = oneMonth.groupValues
            return recentYearlessRange(g[1].toInt(), g[2].toInt(), g[1].toInt(), g[3].toInt(), today)
        }
        return null
    }

    private fun validPeriod(from: LocalDate, to: LocalDate): AiBusinessPeriod? =
        if (to.isBefore(from) || to.toEpochDay() - from.toEpochDay() > 92) null else AiBusinessPeriod(from, to)

    fun productList(question: String, today: LocalDate = LocalDate.now(ZoneId.of("Asia/Seoul"))): ProductList? {
        if (!question.contains("제품") || !Regex("목록|리스트|출력").containsMatchIn(question) || !question.contains("생산")) return null
        return explicitBusinessPeriod(question, today)?.let(::ProductList)
    }

    fun defectRateTop(question: String, today: LocalDate = LocalDate.now(ZoneId.of("Asia/Seoul"))): DefectRateTop? {
        val compact = question.lowercase().replace(Regex("\\s+"), "")
        if (!compact.contains("불량률") || !Regex("top|톱|상위").containsMatchIn(compact)) return null
        val limit = Regex("(?:top|톱|상위)(\\d{1,2})").find(compact)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (limit !in 1..20) return null
        return explicitBusinessPeriod(question, today)?.let { DefectRateTop(it, limit) }
    }

    fun defectTop(question: String, latest: LocalDate, today: LocalDate = LocalDate.now(ZoneId.of("Asia/Seoul"))): DefectTop? {
        val q = question.lowercase().replace(Regex("\\s+"), "")
        if (!q.contains("불량") || !Regex("top|톱|상위|대표|최근").containsMatchIn(q)) return null
        val requested = Regex("(?:top|톱|상위)(\\d{1,2})").find(q)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (q.contains("대표")) 1 else 2
        if (requested !in 1..10) return null

        val dates = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[~～](?:(\\d{4})-)?(?:(\\d{1,2})-)?(\\d{1,2}))?").find(q)
        if (dates != null) {
            val g = dates.groupValues
            val from = runCatching { LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt()) }.getOrNull() ?: return null
            val to = if (g[6].isNotEmpty()) runCatching {
                val month = g[5].toIntOrNull() ?: from.monthValue
                val day = g[6].toInt()
                val year = g[4].toIntOrNull() ?: if (month < from.monthValue) from.year + 1 else from.year
                LocalDate.of(year, month, day)
            }.getOrNull() ?: return null else from
            if (to.isBefore(from) || to.toEpochDay() - from.toEpochDay() > 92) return null
            return DefectTop(AiBusinessPeriod(from, to), requested)
        }
        // 월/일 두 개: "9-22 9-23 일", "9월 22일 ~ 9월 23일".
        val twoMonths = Regex("(?<!\\d)(\\d{1,2})(?:월\\s*|-)(\\d{1,2})일?\\s*(?:[~～,]|부터|에서|와|과|및|\\s+)\\s*(\\d{1,2})(?:월\\s*|-)(\\d{1,2})일?")
            .find(question)
        if (twoMonths != null) {
            val g = twoMonths.groupValues
            val period = recentYearlessRange(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), today) ?: return null
            return DefectTop(period, requested)
        }
        // 두 번째 업무일은 같은 달의 일자만 적을 수 있다.
        val oneMonth = Regex("(?<!\\d)(\\d{1,2})월\\s*(\\d{1,2})일?\\s*(?:[~～,]|부터|에서|와|과|및)\\s*(\\d{1,2})일")
            .find(question)
        if (oneMonth != null) {
            val g = oneMonth.groupValues
            val period = recentYearlessRange(g[1].toInt(), g[2].toInt(), g[1].toInt(), g[3].toInt(), today) ?: return null
            return DefectTop(period, requested)
        }
        val oneDay = Regex("(?<!\\d)(\\d{1,2})(?:월\\s*|-)(\\d{1,2})일?(?!\\d)").find(question)
        if (oneDay != null) {
            val g = oneDay.groupValues
            val period = recentYearlessRange(g[1].toInt(), g[2].toInt(), g[1].toInt(), g[2].toInt(), today) ?: return null
            return DefectTop(period, requested)
        }
        val monthMatch = Regex("(?:(\\d{4})년?)?(\\d{1,2})월(?:전체)?").find(q)
        if (monthMatch != null) {
            val year = monthMatch.groupValues[1].toIntOrNull()
                ?: if (monthMatch.groupValues[2].toInt() <= today.monthValue) today.year else today.year - 1
            val month = monthMatch.groupValues[2].toIntOrNull() ?: return null
            val ym = runCatching { YearMonth.of(year, month) }.getOrNull() ?: return null
            val to = ym.atEndOfMonth()
            return DefectTop(AiBusinessPeriod(ym.atDay(1), to), requested)
        }
        return DefectTop(AiBusinessPeriod(latest, latest), requested)
    }

    /** 연도 생략 시 오늘보다 미래인 업무일을 선택하지 않고 가장 최근 유효한 연도를 고른다. */
    private fun recentYearlessRange(fromMonth: Int, fromDay: Int, toMonth: Int, toDay: Int, today: LocalDate): AiBusinessPeriod? =
        (today.year downTo today.year - 2).mapNotNull { year ->
            runCatching {
                val from = LocalDate.of(year, fromMonth, fromDay)
                val endYear = if (toMonth < fromMonth) year + 1 else year
                val to = LocalDate.of(endYear, toMonth, toDay)
                if (to.isBefore(from) || to.isAfter(today) || to.toEpochDay() - from.toEpochDay() > 92) null
                else AiBusinessPeriod(from, to)
            }.getOrNull()
        }.maxByOrNull { it.to }

    fun documentCount(question: String, today: LocalDate): DocumentCount? {
        val q = question.replace(" ", "")
        if ((!q.contains("문서") && !q.contains("보고서")) ||
            (!q.contains("개수") && !q.contains("몇") && !q.contains("건") && !q.contains("개"))) return null
        val years = buildList {
            if (q.contains("작년")) add(today.year - 1)
            if (q.contains("올해")) add(today.year)
        }
        return years.takeIf { it.isNotEmpty() }?.let {
            DocumentCount(it, !q.contains("다루는기간") && !q.contains("기준일"), q.contains("불량보고서"))
        }
    }
}
