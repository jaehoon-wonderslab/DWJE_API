package com.dwje.api.service

import com.dwje.api.common.exception.DuplicatedValueException
import com.dwje.api.common.exception.InvalidParameterException
import com.dwje.api.common.exception.ResourceNotFoundException
import com.dwje.api.common.response.PageMeta
import com.dwje.api.common.util.DateUtils
import com.dwje.api.common.util.MenuId
import com.dwje.api.common.util.PageRequestParam
import com.dwje.api.model.request.AiModelConfigRequest
import com.dwje.api.model.request.MaskRuleRequest
import com.dwje.api.model.request.ModelReleaseRequest
import com.dwje.api.model.request.VectorBuildRequest
import com.dwje.api.model.request.FinetuneBuildRequest
import com.dwje.api.repository.AiChatRepository
import com.dwje.api.repository.AiModelRepository
import com.dwje.api.repository.DashboardAiRepository
import com.dwje.api.repository.DashboardKpiRepository
import com.dwje.api.repository.MaskRuleRepository
import com.dwje.api.repository.QualityRepository
import com.dwje.api.repository.VectorIndexRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * AI 모델 설정 · 버전 · Agent 관리 서비스 (SY-08, SY-10, SY-11, SY-12)
 *
 * 접근 부서 : 전산팀 · 통합관리자 (질의 이력은 전 부서)
 * 모델 전환·설정 변경은 감사 로그에 기록된다. (공통 규약 6 — AI 모델)
 */
@Service
class AiAdminService(
    private val aiModelRepository: AiModelRepository,
    private val aiChatRepository: AiChatRepository,
    private val vectorIndexRepository: VectorIndexRepository,
    private val maskRuleRepository: MaskRuleRepository,
    private val qualityRepository: QualityRepository,
    private val dashboardAiRepository: DashboardAiRepository,
    private val dashboardKpiRepository: DashboardKpiRepository,
    private val authorizationService: AuthorizationService,
    private val auditLogService: AuditLogService,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // =================================================================================
    // SY-08. 자연어 질의 이력
    // =================================================================================

    /** 질의 이력 요약 (No.186) */
    @Transactional(readOnly = true)
    fun getChatHistorySummary(from: String?, to: String?, userGroup: String?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.CHAT_HISTORY)
        val (fromDate, toDate) = DateUtils.periodOf(from, to)

        val summary = aiChatRepository.findHistorySummary(fromDate, toDate, userGroup).toMutableMap()
        // 목표 의도 정확도는 AI 성능 검증 기준(90%)을 따른다.
        summary["targetAccuracy"] = 90.0
        return summary.toMap()
    }

    /** 질의 이력 조회 (No.187) */
    @Transactional(readOnly = true)
    fun getChatHistory(
        from: String?,
        to: String?,
        userGroup: String?,
        intent: String?,
        page: Int?,
        size: Int?
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.CHAT_HISTORY)

        val (fromDate, toDate) = DateUtils.periodOf(from, to)
        val paging = PageRequestParam.of(page, size)

        val total = aiChatRepository.countHistory(fromDate, toDate, userGroup, intent)
        val rows = aiChatRepository.findHistory(fromDate, toDate, userGroup, intent, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /** 질의 상세 조회 (No.188) */
    @Transactional(readOnly = true)
    fun getChatDetail(messageId: Long): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.CHAT_HISTORY)

        val chat = aiChatRepository.findChatLog(messageId)
            ?: throw ResourceNotFoundException("질의를 찾을 수 없습니다. [messageId=$messageId]")

        val query = vectorIndexRepository.findQueryDetail(messageId)
        val hits = (query?.get("queryId") as? Long)?.let { vectorIndexRepository.findQueryHits(it) } ?: emptyList()

        return mapOf(
            "messageId" to messageId,
            "question" to chat["question"],
            "normalizedQuestion" to chat["normalizedQuestion"],
            "intent" to chat["intent"],
            "intentNm" to chat["intentNm"],
            "prompt" to chat["normalizedQuestion"],
            "answer" to chat["answer"],
            "hits" to hits,
            "search" to query,
            "agents" to aiChatRepository.findChatAgents(messageId),
            "elapsedMs" to chat["responseMs"],
            "maskedCnt" to chat["maskedCnt"],
            "rating" to chat["rating"]
        )
    }

    /** 학습데이터 내보내기 대상 (No.189) */
    @Transactional(readOnly = true)
    fun getTrainsetLines(from: String?, to: String?, ratingFilter: String?): List<String> {
        authorizationService.requireMenu(MenuId.CHAT_HISTORY)
        val (fromDate, toDate) = DateUtils.periodOf(from, to, 90)

        // JSONL 한 줄에 한 샘플(prompt/completion)을 담는다.
        return aiChatRepository.findTrainsetRows(fromDate, toDate, ratingFilter, 10000).map { row ->
            objectMapper.writeValueAsString(
                mapOf(
                    "messages" to listOf(
                        mapOf("role" to "user", "content" to (row["normalizedQuestion"] ?: row["question"])),
                        mapOf("role" to "assistant", "content" to stripHtml(row["answer"] as? String))
                    ),
                    "meta" to mapOf("intent" to row["intent"], "rating" to row["rating"], "chatId" to row["chatId"])
                )
            )
        }
    }

    // =================================================================================
    // SY-10. AI 모델 설정
    // =================================================================================

    /** AI 모델 설정 조회 (No.191) */
    @Transactional(readOnly = true)
    fun getModelConfig(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.BASE_MODEL)

        val thresholds = qualityRepository.findModelConfigs("ANOMALY")
        val classify = qualityRepository.findModelConfigs("CLASSIFY").associate {
            (it["key"] as String) to it["value"]
        }

        return mapOf(
            "thresholds" to thresholds.map {
                mapOf(
                    "agentCd" to it["agentCd"],
                    "key" to it["key"],
                    "metric" to it["name"],
                    "value" to it["value"],
                    "unit" to it["unit"],
                    "valueType" to it["valueType"],
                    "options" to it["options"],
                    "description" to it["description"]
                )
            },
            "classification" to mapOf(
                "judgeBoundary" to classify["judge_boundary"],
                "borderlineRange" to classify["borderline_range"],
                "hitlCriteria" to classify["hitl_criteria"],
                "raw" to classify
            )
        )
    }

    /** AI 모델 설정 저장 (No.192 — 감사 로그 기록) */
    @Transactional
    fun saveModelConfig(request: AiModelConfigRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.BASE_MODEL)

        var updated = 0
        request.thresholds.forEach { t ->
            val key = t["key"] as? String ?: return@forEach
            val value = t["value"]?.toString() ?: return@forEach
            updated += qualityRepository.updateModelConfig("ANOMALY", key, value, principal.userId)
        }
        request.classification.forEach { (key, value) ->
            if (value != null) {
                updated += qualityRepository.updateModelConfig("CLASSIFY", key, value.toString(), principal.userId)
            }
        }

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.BASE_MODEL,
            targetDesc = "AI 모델 설정 변경",
            remark = "변경 ${updated}건"
        )

        return mapOf("success" to true, "updatedCnt" to updated)
    }

    /** 보안 필터링 패턴 목록 (No.193) */
    @Transactional(readOnly = true)
    fun getMaskRules(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.BASE_MODEL)
        return mapOf("items" to maskRuleRepository.findRules(null))
    }

    /** 보안 필터링 패턴 등록·수정 (No.194) */
    @Transactional
    fun saveMaskRule(ruleId: Int?, request: MaskRuleRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.BASE_MODEL)

        val resultRuleId = if (ruleId != null && maskRuleRepository.exists(ruleId)) {
            maskRuleRepository.updateRule(
                ruleId = ruleId,
                ruleNm = request.name,
                fieldKey = request.fieldKey,
                maskTypeCd = request.action,
                customerId = request.customerId,
                policyDesc = request.customerPolicy,
                useYn = if (request.useYn ?: true) "Y" else "N",
                actor = principal.userId
            )
            ruleId
        } else {
            maskRuleRepository.insertRule(
                ruleNm = request.name,
                fieldKey = request.fieldKey,
                maskTypeCd = request.action,
                customerId = request.customerId,
                policyDesc = request.customerPolicy,
                actor = principal.userId
            )
        }

        maskRuleRepository.replaceRuleColumns(resultRuleId, request.targetFields)

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.BASE_MODEL,
            fieldKey = request.fieldKey,
            targetDesc = "보안 필터링 패턴 저장 [${request.name}]",
            remark = "처리=${request.action}, 대상=${request.targetFields.size}개 컬럼"
        )

        return mapOf("ruleId" to resultRuleId)
    }

    // =================================================================================
    // SY-11. AI 모델 버전 관리
    // =================================================================================

    /** 모델 버전 요약 (No.195) */
    @Transactional(readOnly = true)
    fun getReleaseSummary(): Map<String, Any?> {
        authorizationService.requireAnyMenu(MenuId.SYS_MODEL_VER, MenuId.BASE_MODEL)
        return aiModelRepository.findReleaseSummary()
    }

    /** 릴리스 목록 조회 (No.196) */
    @Transactional(readOnly = true)
    fun getReleases(state: String?, page: Int?, size: Int?): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireAnyMenu(MenuId.SYS_MODEL_VER, MenuId.BASE_MODEL)
        val paging = PageRequestParam.of(page, size)

        val total = aiModelRepository.countReleases(state)
        val rows = aiModelRepository.findReleases(state, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /** 릴리스 등록 (No.197) */
    @Transactional
    fun createRelease(request: ModelReleaseRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_MODEL_VER)

        val (profileCd, versionNo) = parseVersion(request.ver)
            ?: (("CHAT-DEFAULT") to aiModelRepository.nextVersionNo("CHAT-DEFAULT"))

        if (aiModelRepository.findReleaseByVer(profileCd, versionNo) != null) {
            throw DuplicatedValueException("이미 등록된 버전입니다. [${request.ver}]", "ver")
        }

        val snapshotId = request.vecId?.let { aiModelRepository.findSnapshotId(it) }
        val profileId = aiModelRepository.insertRelease(
            profileCd = profileCd,
            versionNo = versionNo,
            profileNm = request.ver ?: "$profileCd-v$versionNo",
            corpusSnapshotId = snapshotId,
            description = request.note,
            actor = principal.userId
        )

        // 파인튜닝 자산을 LoRA 역할로 연결한다.
        request.ftId?.let { ftId ->
            val parts = ftId.split(":")
            if (parts.size == 2) {
                aiModelRepository.findAssetId(parts[0], parts[1])?.let {
                    aiModelRepository.linkServingAsset(profileId, "LORA_ASSISTANT", it, principal.userId)
                }
            }
        }

        aiModelRepository.insertDeployLog(
            actionCd = "CANARY",
            profileId = profileId,
            profileNm = "$profileCd-v$versionNo",
            fromProfileId = null,
            fromProfileNm = null,
            reason = "릴리스 등록",
            actorUserId = principal.userId,
            actorDeptNm = principal.deptName
        )

        return mapOf("ver" to "$profileCd-v$versionNo", "profileId" to profileId)
    }

    /**
     * 모델 자산 목록 (파인튜닝 폼의 베이스 모델·LoRA 선택지)
     *
     * @param kind AI_ASSET_KIND — LLM_BASE / LORA / EMBED / RERANK. 비우면 전체
     */
    @Transactional(readOnly = true)
    fun getAssets(kind: String?): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_MODEL_VER)
        return mapOf("items" to aiModelRepository.findAssets(kind))
    }

    /**
     * 임베딩 모델 목록 (재색인 폼의 embedModelId 선택지)
     */
    @Transactional(readOnly = true)
    fun getEmbedModels(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_MODEL_VER)
        return mapOf("items" to aiModelRepository.findEmbedModels())
    }

    /** 적용 전 성능 비교 (No.198) */
    @Transactional(readOnly = true)
    fun getApplyPreview(ver: String): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.SYS_MODEL_VER)

        val (profileCd, versionNo) = parseVersion(ver)
            ?: throw InvalidParameterException("버전 형식이 올바르지 않습니다. [$ver]", "ver")

        val target = aiModelRepository.findReleaseByVer(profileCd, versionNo)
            ?: throw ResourceNotFoundException("릴리스를 찾을 수 없습니다. [$ver]")
        val current = dashboardKpiRepository.findServingEvaluation()

        val currentEval = parseEval(current?.get("evalJson") as? String)
        val targetEval = parseEval(target["evalJson"] as? String)

        // 항목별 증감을 함께 제공한다.
        val delta = targetEval.keys.union(currentEval.keys).associateWith { key ->
            val t = targetEval[key]
            val c = currentEval[key]
            if (t != null && c != null) Math.round((t - c) * 1000) / 1000.0 else null
        }

        return mapOf(
            "currentVer" to current?.get("ver"),
            "targetVer" to ver,
            "current" to currentEval,
            "target" to targetEval,
            "delta" to delta,
            "mustPassFail" to target["mustPassFail"]
        )
    }

    /** 서비스 적용 (No.199 — 감사 로그 기록) */
    @Transactional
    fun applyRelease(ver: String, mode: String?): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_MODEL_VER)

        val (profileCd, versionNo) = parseVersion(ver)
            ?: throw InvalidParameterException("버전 형식이 올바르지 않습니다. [$ver]", "ver")

        val target = aiModelRepository.findReleaseByVer(profileCd, versionNo)
            ?: throw ResourceNotFoundException("릴리스를 찾을 수 없습니다. [$ver]")

        // 필수 검증 항목을 통과하지 못한 버전은 서비스에 올릴 수 없다. (DDL CHECK 제약과 동일)
        if ((target["mustPassFail"] as? Int ?: 0) > 0) {
            throw com.dwje.api.common.exception.BusinessRuleException(
                "필수 검증 항목 ${target["mustPassFail"]}건이 미통과 상태여서 적용할 수 없습니다."
            )
        }

        val current = dashboardKpiRepository.findServingEvaluation()
        val immediate = mode == null || mode.contains("즉시") || mode.equals("immediate", true)

        aiModelRepository.activateRelease(target["profileId"] as Int, immediate, principal.userId)
        aiModelRepository.insertDeployLog(
            actionCd = if (immediate) "PROMOTE" else "CANARY",
            profileId = target["profileId"] as Int,
            profileNm = ver,
            fromProfileId = current?.get("profileId") as? Int,
            fromProfileNm = current?.get("ver") as? String,
            reason = mode,
            actorUserId = principal.userId,
            actorDeptNm = principal.deptName
        )

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.SYS_MODEL_VER,
            targetDesc = "AI 모델 서비스 적용 [$ver]",
            remark = "전환 방식=${if (immediate) "즉시 전환" else "점진 전환"}, 이전=${current?.get("ver") ?: "-"}"
        )

        log.info("AI 모델 서비스 적용 : ver={} mode={}", ver, mode)
        return mapOf("ver" to ver, "state" to if (immediate) "ACTIVE" else "CANARY")
    }

    /** 직전 버전 롤백 (No.200 — 감사 로그 기록) */
    @Transactional
    fun rollbackRelease(reason: String?): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_MODEL_VER)

        val result = aiModelRepository.rollbackRelease(reason, principal.userId)
            ?: throw com.dwje.api.common.exception.BusinessRuleException("서비스 중인 버전이 없어 롤백할 수 없습니다.")

        aiModelRepository.insertDeployLog(
            actionCd = "ROLLBACK",
            profileId = null,
            profileNm = (result["servingVer"] as? String) ?: "-",
            fromProfileId = result["rolledBackProfileId"] as? Int,
            fromProfileNm = null,
            reason = reason,
            actorUserId = principal.userId,
            actorDeptNm = principal.deptName
        )

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.SYS_MODEL_VER,
            targetDesc = "AI 모델 롤백",
            remark = "복원 버전=${result["servingVer"]}, 사유=${reason ?: "-"}"
        )

        return result
    }

    /** 릴리스 보관 (No.201) */
    @Transactional
    fun archiveRelease(ver: String): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_MODEL_VER)

        val (profileCd, versionNo) = parseVersion(ver)
            ?: throw InvalidParameterException("버전 형식이 올바르지 않습니다. [$ver]", "ver")

        val target = aiModelRepository.findReleaseByVer(profileCd, versionNo)
            ?: throw ResourceNotFoundException("릴리스를 찾을 수 없습니다. [$ver]")

        // 서비스 중인 버전은 보관할 수 없다. (E-RULE-001)
        val archived = aiModelRepository.archiveRelease(target["profileId"] as Int, principal.userId)
        if (archived == 0) {
            throw com.dwje.api.common.exception.BusinessRuleException("서비스 중인 버전은 보관할 수 없습니다.")
        }

        return mapOf("success" to true)
    }

    /** 벡터 인덱스 목록 (No.202) */
    @Transactional(readOnly = true)
    fun getVectorBuilds(state: String?, page: Int?, size: Int?): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireAnyMenu(MenuId.SYS_MODEL_VER, MenuId.BASE_MODEL)
        val paging = PageRequestParam.of(page, size)

        val total = vectorIndexRepository.countIngestJobs(state)
        val rows = vectorIndexRepository.findIngestJobs(state, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /** 재색인 실행 (No.203) */
    @Transactional
    fun createVectorBuild(request: VectorBuildRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_MODEL_VER)

        val stats = vectorIndexRepository.findIndexTargetStats(request.sources)
        val jobId = vectorIndexRepository.insertIngestJob(
            jobTypeCd = if (request.sources.isEmpty()) "FULL" else "INCR",
            embedModelId = request.embedModelId ?: vectorIndexRepository.findDefaultEmbedModelId(),
            docCnt = (stats["docCnt"] as Long).toInt(),
            chunkCnt = (stats["chunkCnt"] as Long).toInt(),
            triggeredBy = principal.userId,
            remark = "재색인 실행 — 대상=${request.sources.ifEmpty { listOf("전체") }.joinToString(",")}, chunkSize=${request.chunkSize ?: "기본"}"
        )

        return mapOf("vecId" to jobId, "jobId" to jobId, "targetDocCnt" to stats["docCnt"])
    }

    /** 벡터 인덱스 상세 (No.204) */
    @Transactional(readOnly = true)
    fun getVectorBuild(vecId: String): Map<String, Any?> {
        authorizationService.requireAnyMenu(MenuId.SYS_MODEL_VER, MenuId.BASE_MODEL)

        val job = vectorIndexRepository.findIngestJob(vecId)
            ?: throw ResourceNotFoundException("색인 작업을 찾을 수 없습니다. [vecId=$vecId]")

        return job + mapOf("errors" to vectorIndexRepository.findIngestErrors(vecId, 200))
    }

    /** 파인튜닝 체크포인트 목록 (No.205) */
    @Transactional(readOnly = true)
    fun getFinetuneBuilds(state: String?, page: Int?, size: Int?): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireAnyMenu(MenuId.SYS_MODEL_VER, MenuId.BASE_MODEL)
        val paging = PageRequestParam.of(page, size)

        val total = aiModelRepository.countFinetuneAssets(state)
        val rows = aiModelRepository.findFinetuneAssets(state, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    /** 파인튜닝 실행 (No.206) */
    @Transactional
    fun createFinetuneBuild(request: FinetuneBuildRequest): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.SYS_MODEL_VER)

        val baseModel = request.baseModel
            ?: throw InvalidParameterException("베이스 모델을 선택해 주세요.", "baseModel")

        val versionTag = "v${LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm"))}"
        val assetKey = request.trainsetId ?: "lora-assistant"

        val assetId = aiModelRepository.insertFinetuneAsset(
            assetKey = assetKey,
            versionTag = versionTag,
            assetNm = "$assetKey $versionTag (${request.method ?: "LORA"})",
            baseModel = baseModel,
            trainMethodCd = request.method ?: "LORA",
            artifactPath = "/models/lora/$assetKey/$versionTag",
            remark = "epoch=${request.epoch ?: 3}, trainset=${request.trainsetId ?: "-"}",
            actor = principal.userId
        )

        // 학습 실행을 Agent 이력에도 남긴다.
        qualityRepository.insertAgentRun(
            agentNo = "⑥",
            stateCd = "RUNNING",
            message = "파인튜닝 실행 — base=$baseModel, method=${request.method ?: "LORA"}",
            throughput = "epoch ${request.epoch ?: 3}"
        )

        return mapOf("ftId" to "$assetKey:$versionTag", "assetId" to assetId, "jobId" to assetId)
    }

    /** 파인튜닝 상세 (No.207) */
    @Transactional(readOnly = true)
    fun getFinetuneBuild(ftId: String): Map<String, Any?> {
        authorizationService.requireAnyMenu(MenuId.SYS_MODEL_VER, MenuId.BASE_MODEL)

        val parts = ftId.split(":")
        if (parts.size != 2) throw InvalidParameterException("ftId 형식은 assetKey:versionTag 입니다.", "ftId")

        return aiModelRepository.findFinetuneAsset(parts[0], parts[1])
            ?: throw ResourceNotFoundException("파인튜닝 체크포인트를 찾을 수 없습니다. [$ftId]")
    }

    /** 버전별 성능 추이 (No.208) */
    @Transactional(readOnly = true)
    fun getPerformanceTrend(): Map<String, Any?> {
        authorizationService.requireAnyMenu(MenuId.SYS_MODEL_VER, MenuId.DASH_KPI)

        val rows = dashboardKpiRepository.findServingPerformanceTrend(30)
        val labels = rows.map { it["ver"] }

        // eval_json 의 공통 항목을 계열로 전개한다.
        val evalMaps = rows.map { parseEval(it["evalJson"] as? String) }
        val keys = evalMaps.flatMap { it.keys }.distinct()

        val series = keys.map { key ->
            mapOf("name" to key, "data" to evalMaps.map { it[key] })
        } + listOf(mapOf("name" to "종합 점수", "data" to rows.map { it["score"] }))

        return mapOf("labels" to labels, "series" to series)
    }

    /** 배포·학습 이력 (No.209) */
    @Transactional(readOnly = true)
    fun getDeployLogs(page: Int?, size: Int?): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireAnyMenu(MenuId.SYS_MODEL_VER, MenuId.BASE_MODEL)
        val paging = PageRequestParam.of(page, size)

        val total = aiModelRepository.countDeployLogs()
        val rows = aiModelRepository.findDeployLogs(paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    // =================================================================================
    // SY-12. Agent 실행 현황
    // =================================================================================

    /** Agent 요약 (No.210) */
    @Transactional(readOnly = true)
    fun getAgentSummary(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.AI_AGENT)
        return mapOf("master" to dashboardAiRepository.findMasterState()) + aiModelRepository.findAgentSummary()
    }

    /** Agent 목록 조회 (No.211) */
    @Transactional(readOnly = true)
    fun getAgents(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.AI_AGENT)
        return mapOf("items" to dashboardAiRepository.findAgentStatus())
    }

    /** Master AI 파이프라인 조회 (No.212) */
    @Transactional(readOnly = true)
    fun getPipeline(): Map<String, Any?> {
        authorizationService.requireMenu(MenuId.AI_AGENT)

        val master = dashboardAiRepository.findMasterState()
        val stages = aiModelRepository.findPipelineStages().map {
            it + mapOf(
                // 전체 상태를 각 단계에 반영한다. (단계별 개별 계측은 Agent 런타임 연동 시 확장)
                "state" to master["state"],
                "elapsedMs" to master["avgElapsedMs"]
            )
        }

        return mapOf("master" to master, "stages" to stages)
    }

    /** Agent 재시작 (No.213 — 감사 로그 기록) */
    @Transactional
    fun restartAgent(agentCd: String): Map<String, Any?> {
        val principal = authorizationService.requireMenu(MenuId.AI_AGENT)

        if (!aiModelRepository.existsAgent(agentCd)) {
            throw ResourceNotFoundException("Agent 를 찾을 수 없습니다. [$agentCd]")
        }

        val runId = aiModelRepository.insertAgentRestart(agentCd, principal.userId)

        auditLogService.record(
            logType = "AUTO_GEN",
            menuId = MenuId.AI_AGENT,
            targetDesc = "Agent 재시작 [$agentCd]",
            remark = "runId=$runId"
        )

        return mapOf("success" to true, "restartedAt" to LocalDateTime.now().format(DateUtils.DATETIME), "runId" to runId)
    }

    /** Agent 실행 이력 (No.214) */
    @Transactional(readOnly = true)
    fun getAgentRuns(
        agentCd: String,
        from: String?,
        to: String?,
        state: String?,
        page: Int?,
        size: Int?
    ): Pair<List<Map<String, Any?>>, PageMeta> {
        authorizationService.requireMenu(MenuId.AI_AGENT)

        val (fromDate, toDate) = DateUtils.periodOf(from, to, 7)
        val paging = PageRequestParam.of(page, size)

        val total = aiModelRepository.countAgentRuns(agentCd, fromDate, toDate, state)
        val rows = aiModelRepository.findAgentRuns(agentCd, fromDate, toDate, state, paging.limit, paging.offset)

        return rows to PageMeta.of(paging.page, paging.size, total)
    }

    // ---------------------------------------------------------------------------------
    // 내부 보조
    // ---------------------------------------------------------------------------------

    /**
     * "profileCd-vN" 형식 버전 태그를 분해한다.
     */
    private fun parseVersion(ver: String?): Pair<String, Int>? {
        if (ver.isNullOrBlank()) return null
        val idx = ver.lastIndexOf("-v")
        if (idx <= 0) return null
        val versionNo = ver.substring(idx + 2).toIntOrNull() ?: return null
        return ver.substring(0, idx) to versionNo
    }

    /** eval_json 을 항목별 점수 맵으로 변환한다. */
    private fun parseEval(json: String?): Map<String, Double> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            objectMapper.readTree(json).fields().asSequence()
                .mapNotNull { (key, node) -> if (node.isNumber) key to node.asDouble() else null }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    /** HTML 태그를 제거해 학습 샘플로 정리한다. */
    private fun stripHtml(html: String?): String? =
        html?.replace(Regex("<[^>]*>"), " ")?.replace(Regex("\\s+"), " ")?.trim()
}
