package com.dwje.api.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * AOI 치수 원천(MSSQL EDGE) 보조 데이터소스.
 *
 * ## 기본 데이터소스를 건드리지 않는다
 * `DataSource` 타입 빈을 하나 더 등록하면 Spring Boot 의 PostgreSQL 자동 구성이 물러난다. 그래서 HikariDataSource 는
 * 빈으로 내지 않고 이 클래스가 들고 있다가 종료 때 닫는다. 바깥에는 `aoiJdbcTemplate` 한 개만 낸다
 * (기본 템플릿은 [DatabaseConfig] 에서 `@Primary`).
 *
 * ## 원천 부담을 여기서 막는다
 * - 풀 크기 = 동시 접속 상한(기본 3). 조회 하나가 설비 수만큼 쿼리를 내도 이 수를 넘지 못한다.
 * - 읽기 전용 · `ApplicationIntent=ReadOnly` 는 AG 구성이 아니라 쓰지 않는다. 대신 SQL 에 `WITH (NOLOCK)` 을 건다.
 * - 쿼리 제한 시간을 걸어 긴 스캔이 붙들고 있지 않게 한다.
 *
 * 계정이 비어 있으면(`AX_MSSQL_USER` 미주입) 템플릿을 만들지 않는다 — 서비스는 `SOURCE_NOT_CONFIGURED` 로 답한다.
 */
@Configuration
class AoiMssqlConfig(private val appProperties: AppProperties) : DisposableBean {

    private val log = LoggerFactory.getLogger(javaClass)

    private var dataSource: HikariDataSource? = null

    @Bean("aoiJdbcTemplate")
    fun aoiJdbcTemplate(): NamedParameterJdbcTemplate? {
        val cfg = appProperties.aoi.mssql
        if (!cfg.configured) {
            log.warn("AOI 원천(MSSQL) 계정이 설정되지 않았습니다 — AX_MSSQL_USER/AX_MSSQL_PASSWORD 를 주입하면 켜집니다.")
            return null
        }
        val hikari = HikariConfig().apply {
            poolName = "dwje-aoi-mssql"
            // 문자열 파라미터를 varchar 로 보낸다. 기본(nvarchar)이면 varchar 키 열(EQPT_CD·LOT_NO·SERIAL_NO)에
            // 암시적 변환이 걸려 클러스터 PK 탐색이 스캔이 된다 — 시리얼 144건 통계가 3.5초 → 122초로 늘었다(실측).
            jdbcUrl = withVarcharParams(cfg.url)
            username = cfg.username
            password = cfg.password
            maximumPoolSize = cfg.poolSize
            minimumIdle = 0
            isReadOnly = true
            connectionTimeout = 10_000
            idleTimeout = 60_000
            maxLifetime = 600_000
            // 원천 장애가 API 기동을 막지 않게 — 첫 조회 때 붙는다.
            initializationFailTimeout = -1
        }
        val ds = HikariDataSource(hikari)
        dataSource = ds
        return NamedParameterJdbcTemplate(ds).apply {
            jdbcTemplate.queryTimeout = cfg.queryTimeoutSec
            jdbcTemplate.fetchSize = 500
        }
    }

    companion object {
        private const val UNICODE_FLAG = "sendStringParametersAsUnicode"

        /** URL 에 [UNICODE_FLAG] 가 없으면 `false` 로 붙인다. 있으면 그대로 둔다(운영자가 의도한 값). */
        fun withVarcharParams(url: String): String =
            if (url.contains(UNICODE_FLAG, ignoreCase = true)) url
            else url.trimEnd(';') + ";$UNICODE_FLAG=false"
    }

    override fun destroy() {
        dataSource?.close()
    }
}
