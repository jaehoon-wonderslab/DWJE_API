package com.dwje.api.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import javax.sql.DataSource

/**
 * 데이터베이스 접근 설정
 *
 * 본 프로젝트는 iBatis/MyBatis 를 사용하지 않고 Kotlin 소스 내 문자열 변수로 SQL 을 직접 관리한다.
 * SQL Injection 방지 및 PreparedStatement 캐싱을 위해 [NamedParameterJdbcTemplate] 사용을 표준으로 한다.
 */
@Configuration
@EnableTransactionManagement
class DatabaseConfig {

    /**
     * 표준 쿼리 실행 템플릿 — 전 Repository 가 이 빈을 주입받는다.
     *
     * @param dataSource Spring Boot 자동 구성 DataSource (HikariCP)
     */
    @Bean
    fun namedParameterJdbcTemplate(dataSource: DataSource): NamedParameterJdbcTemplate {
        val template = NamedParameterJdbcTemplate(dataSource)
        // 대량 목록 조회 시 커서 페치 크기를 지정해 메모리 사용을 억제한다.
        template.jdbcTemplate.fetchSize = 500
        template.jdbcTemplate.queryTimeout = 60
        return template
    }

    /**
     * 단순 통계·DDL 성 쿼리를 위한 보조 템플릿
     */
    @Bean
    fun jdbcTemplate(namedParameterJdbcTemplate: NamedParameterJdbcTemplate): JdbcTemplate =
        namedParameterJdbcTemplate.jdbcTemplate

    /**
     * 트랜잭션 관리자 — 서비스 계층의 @Transactional 처리에 사용한다.
     */
    @Bean
    fun transactionManager(dataSource: DataSource): PlatformTransactionManager =
        org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)
}
