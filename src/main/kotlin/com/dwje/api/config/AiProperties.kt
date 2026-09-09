package com.dwje.api.config

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

/**
 * sLLM 서빙 설정 (`app.ai.*`)
 *
 * 서빙은 HTTP 다. Ollama · vLLM · llama.cpp server 가 모두 HTTP 서버이므로
 * 런타임이 바뀌어도 이 설정만 갈아 끼우면 된다.
 *
 * HTTP 클라이언트는 `java.net.http.HttpClient`(JDK 21 표준)를 쓴다 —
 * 서블릿 스택에 리액티브 스택(webflux)을 함께 올릴 이유가 없다.
 *
 * @param enabled     모델 호출 사용 여부. 끄면 `MODEL_NOT_READY` 를 낸다
 * @param baseUrl     서빙 주소
 * @param model       모델 태그
 * @param numPredict  최대 생성 토큰
 * @param timeoutSec  호출 제한 시간(초)
 */
data class AiProperties(

    val enabled: Boolean = false,

    @field:NotBlank(message = "sLLM 서빙 주소(app.ai.base-url)는 비워 둘 수 없습니다.")
    val baseUrl: String = "http://localhost:11434",

    @field:NotBlank(message = "sLLM 모델 태그(app.ai.model)는 비워 둘 수 없습니다.")
    val model: String = "gemma4:e4b-dwje",

    /**
     * 임베딩 모델 태그.
     *
     * `vec.tb_doc_chunk.embedding` 이 1024차원(`embed_model_id = 1`)이므로
     * **같은 차원을 내는 모델**이어야 한다. 다르면 유사도가 뜻을 잃는다.
     */
    @field:NotBlank(message = "임베딩 모델 태그(app.ai.embed-model)는 비워 둘 수 없습니다.")
    val embedModel: String = "bge-m3:latest",

    /**
     * 최대 생성 토큰.
     *
     * 이 모델은 추론(`thinking`)에 토큰을 먼저 쓴다. 300 으로 두면 추론에 다 쓰고
     * 본문(`content`)이 **빈 문자열**로 온다.
     *
     * 원인 분석은 문서 인용문이 붙어 브리핑보다 훨씬 길다 — 1,200 에서
     * `done_reason=length` 로 잘렸다. 넉넉히 준다. 최대값이라 실제 생성이
     * 짧으면 시간이 늘지 않는다.
     */
    @field:Min(value = 256, message = "생성 토큰(app.ai.num-predict)은 256 이상이어야 합니다.")
    val numPredict: Int = 3500,

    /**
     * 호출 제한 시간(초).
     *
     * 실측 2~3문장에 약 28초다. 원인 분석은 문서 검색이 붙어 더 길다.
     * 화면 쪽 제한이 300초이므로 그보다 짧게 둔다 — 서버가 먼저 끊고 사유를 남겨야
     * 화면이 "왜 안 나왔는지" 를 알 수 있다.
     */
    @field:Min(value = 5, message = "호출 제한 시간(app.ai.timeout-sec)은 5초 이상이어야 합니다.")
    val timeoutSec: Long = 240
,

    /**
     * 앞선 모델 호출이 끝나기를 기다리는 최대 시간(초).
     *
     * 로컬 서빙은 한 번에 하나씩 처리한다. 이 시간 안에 차례가 오지 않으면
     * 시작하지 않고 포기한다 — 붙들고 있다가 제한 시간에 걸려 죽는 것보다,
     * 빨리 포기하고 사유를 남기는 편이 낫다.
     */
    @field:Min(value = 0, message = "대기 시간(app.ai.queue-wait-sec)은 0 이상이어야 합니다.")
    val queueWaitSec: Long = 200
,

    /**
     * FACA 문서 저장소의 루트 표시 — 경로에서 이 접두를 떼고 상대경로를 만든다.
     *
     * `vec.tb_doc.source_path` 는 적재한 PC 의 마운트 지점을 포함한다
     * (`/Volumes/[C] Windows 11.hidden/덕우전자_NAS/FACA/…`). 다른 PC 에서는 다르므로
     * 그대로 보여 주면 열 수 없는 경로가 된다. 화면에는 이 접두 **이후**만 보여 주고,
     * 실제로 열 때는 배포 환경의 NAS 루트를 앞에 붙인다.
     */
    @field:NotBlank(message = "문서 루트 표시(app.ai.doc-root-marker)는 비워 둘 수 없습니다.")
    val docRootMarker: String = "/FACA/"
,

    /**
     * 원인 분석 대상으로 삼는 불량률 기준(%) — 이 값을 **넘는** 공정만 분석한다.
     *
     * 사용자 지시: "불량률이 3.5% 가 넘어가는 모든 공정에 대해서 결과를 정리".
     * 화면이 "3.5% 초과 N곳" 을 적을 때 지어내지 않도록 응답에도 함께 내린다.
     */
    @field:jakarta.validation.constraints.DecimalMin(
        value = "0.0", message = "불량률 기준(app.ai.cause-threshold)은 0 이상이어야 합니다."
    )
    val causeThreshold: Double = 3.5,

    /**
     * 한 번에 분석할 최대 공정 수.
     *
     * 대상이 늘면 모델 응답이 길어져 잘린다(`done_reason=length`). 상한을 두어
     * 시간과 길이를 예측 가능하게 한다. 넘치면 불량률 높은 순으로 자르고
     * 응답에 몇 곳이 잘렸는지 알린다 — 조용히 빠지면 안 된다.
     */
    @field:Min(value = 1, message = "최대 분석 공정 수(app.ai.cause-max-targets)는 1 이상이어야 합니다.")
    val causeMaxTargets: Int = 5
)
