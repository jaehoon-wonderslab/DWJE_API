package com.dwje.api.common.util

/**
 * 데이터 접근 권한 항목 (7종) — ax.tb_sys_data_field.field_key
 *
 * API 목록 명세 「공통 규약 / 4. 데이터 접근 권한 항목」과 1:1 대응한다.
 */
object DataField {
    /** 생산·출하 수량 — 투입·양품·불량·출하 수량, 실적 집계 */
    const val QTY = "qty"

    /** 수율·불량률 — 제품별 수율, 공정 불량률, 달성률, LRR(%) */
    const val YIELD = "yield"

    /** 단가·금액 — 품목 단가, 가공비, 폐기 금액, 원가 */
    const val PRICE = "price"

    /** 고객사·거래처 — 고객사명, 거래처, 계약 조건 */
    const val CUSTOMER = "customer"

    /** 출하 계획 — 연간·월별 출하 계획 수량 */
    const val PLAN = "plan"

    /** 금형·설비 상세 — 금형 이력, 설비 파라미터, 공정 조건 */
    const val MOLD = "mold"

    /** 작업자 정보 — 사번, 작업자명, 근태·배치 */
    const val WORKER = "worker"

    /** 전체 항목 목록 (정렬 순서 = 명세 표기 순서) */
    val ALL = listOf(QTY, YIELD, PRICE, CUSTOMER, PLAN, MOLD, WORKER)
}

/**
 * 시스템 메뉴(화면) ID 상수 — ax.tb_sys_menu.menu_id
 *
 * 컨트롤러의 메뉴 접근 권한 판정에 사용한다.
 */
object MenuId {
    const val AI_CHAT = "ai-chat"
    const val DASH_AI = "dash-ai"
    const val DASH_PROC = "dash-proc"
    const val PROD_MONITOR = "prod-monitor"
    const val PROD_RESULT = "prod-result"
    const val PROD_DAILY = "prod-daily"
    const val DAILY_HISTORY = "daily-history"
    const val PROD_DOWN = "prod-down"
    const val QC_DEFECT = "qc-defect"
    const val QC_AOI = "qc-aoi"
    const val ALERT_LIST = "alert-list"
    const val SYS_ACCOUNT = "sys-account"
    const val SYS_MENU = "sys-menu"
    const val SYS_DATA = "sys-data"
    const val ALERT_COND = "alert-cond"
    const val SYS_RECIP = "sys-recip"
    const val SYS_GLOSS = "sys-gloss"
    const val CHAT_HISTORY = "chat-history"
    const val SYS_AUDIT = "sys-audit"
    const val SYS_DL = "sys-dl"
    const val SYS_SYNC = "sys-sync"

    // 2026-09-15 에 5개 화면(sys-rank · base-model · sys-model-ver · ai-agent · sys-metric)의 API 를
    // 상수와 함께 지웠다가, 2026-09-22 요청으로 **sys-rank 를 뺀 4개**를 다시 붙였다.
    // (제품군 순위 관리는 이번 요청 대상이 아니라 그대로 둔다)
    // 2026-09-23 에 sys-model-ver 를 다시 뺐다(웹 미호출, V42 가 근거 표를 지움) — 남은 것은 3개다.
    //
    // 세 화면 모두 `tb_sys_menu.use_flg = 'N'` 이다. 권한 뷰(vw_sys_user_menu_perm)가 켜진 메뉴만
    // 내주므로, **메뉴를 켜기 전까지는 통합관리자만** 이 API 에 닿는다. 웹 화면이 붙은 뒤 켜면 된다.

    /** 시스템관리 › AI 모델 설정 (SY-10) — 임계치·분류 기준 */
    const val BASE_MODEL = "base-model"

    /** 시스템관리 › Agent 실행 현황 (SY-12) */
    const val AI_AGENT = "ai-agent"

    /** 시스템관리 › 지표 측정 데이터 관리 (SY-13) */
    const val SYS_METRIC = "sys-metric"

    // 2026-09-10 요구사항 9건 — V25 로 tb_sys_menu 에 등록된다.
    /** AI 통합 대시보드 › 업로드 리포트 **업로드**(동작 권한). 보기는 DASH_AI 를 따른다. */
    const val DASH_AI_UPLOAD = "dash-ai-upload"
    /** 시스템관리 › 업로드 문서 목록 (조회 전용) */
    const val SYS_UPLOAD_DOC = "sys-upload-doc"

    // 보고서 화면 (RP-01~07) — resources/db/V5__report_menu.sql 로 tb_sys_menu 에 등록된다.
    const val RPT_PRESS_MORNING = "rpt-press-morning"
    const val RPT_PLATING_MORNING = "rpt-plating-morning"
    const val RPT_SHIP_PLAN = "rpt-ship-plan"
    const val RPT_YIELD_MODEL = "rpt-yield-model"
    const val RPT_LRR_CUSTOMER = "rpt-lrr-customer"
    const val RPT_SCRAP = "rpt-scrap"

    /** 보고서 화면 전체 — 출력·인쇄처럼 "보고서별 열람 권한"이면 되는 API 에 사용한다. */
    val ALL_REPORT_SCREENS = arrayOf(
        RPT_PRESS_MORNING, RPT_PLATING_MORNING, RPT_SHIP_PLAN,
        RPT_YIELD_MODEL, RPT_LRR_CUSTOMER, RPT_SCRAP
    )
}
