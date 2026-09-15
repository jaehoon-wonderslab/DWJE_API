-- =====================================================================================
--  V34 : 폐기 표 제거 + 기존 7개 항목의 응답 필드명 초기값 (2026-09-16, V33 후속)
--
--  [1. ax.tb_sys_data_field_column 폐기]
--  V33 에서 미뤄 둔 것이다. 그때는 SystemUserRepository.findDataFields() 가 이 표를 읽고 있어
--  지우면 [데이터 접근 권한] 화면의 항목 목록 API 가 깨졌다. 2026-09-16 API 담당이 그 질의를
--  tb_sys_data_field_attr 기준으로 바꾸고 코드 전체에 참조가 없음을 확인해 이제 지운다.
--  0행이고 코드 참조(tb_sys_code_ref)도 없어 다른 정리는 필요 없다.
--
--  [2. 응답 필드명 초기값 34행 — 이게 없으면 실서버에서 아무것도 마스킹되지 않는다]
--  /auth/me 의 dataFields.attrs 가 비면 WEB 이 "필드명 → 항목" 맵을 만들 수 없다.
--  WEB 의 기본값은 목 모드에서만 쓰이므로 실서버에는 이 시드가 있어야 한다.
--  34개를 화면에서 손으로 넣는 것은 비현실적이라 SQL 로 넣는다.
--
--  [근거 — 짐작이 아니라 코드의 마스킹 지점에서 뽑았다]
--  응답 키가 어느 항목인지는 서버 코드가 이미 알고 있다. 아래 세 가지를 기계적으로 훑어
--  "DataField.X 로 실제 가려지는 키" 만 골랐다.
--      mask.applyTo(row, mapOf("키" to DataField.X))
--      val xAllowed = mask.check(DataField.X)  →  "키" to if (xAllowed) ... else null
--      "키" to mask.on(DataField.X) { ... }
--  API 담당이 제안한 32개를 이 방식으로 다시 확인했고, 전부 실재하는 응답 키였다.
--  거기서 2개를 빼고(아래) 4개를 더했다.
--
--  [뺀 것 2개 — 한 키가 두 항목에서 쓰인다]
--   · rate  : ReportService 는 yield 로, DashboardAiService 는 plan 으로 가린다.
--             attr_name 은 전역 UNIQUE 라 한쪽을 고르면 다른 쪽이 잘못 가려지거나 샌다.
--             둘 다 넣을 수 없고 어느 쪽도 맞다고 할 수 없어 뺐다.
--   · spec  : ProductionService 는 금형 규격(mold)으로 쓰지만, AoiSerialService 의 "spec" 은
--             AOI 치수 한계(LSL/USL) 배열이다. mold 로 등록하면 금형 권한이 없는 사람에게
--             AOI 치수 화면의 규격선이 사라진다. 서로 다른 뜻의 같은 이름이라 뺐다.
--
--  [더한 것 4개 — price · plan · worker 를 비워 두지 않아도 됐다]
--  API 담당은 이 셋이 조건 분기 안에 흩어져 목록에 없다고 했는데, 분기를 따라가 보니
--  가려지는 응답 키가 분명한 것이 넷 있었다.
--      price  : unitPrice · planAmount   (ReportRepository.findShipPlan — 단가·금액)
--      plan   : planQty                  (같은 곳. AiBriefingInput 도 mask.on(PLAN){planQty})
--      worker : insUsers                 (DashboardAiService — 작업자 사번 목록)
--  이로써 7개 항목 모두 최소 1개의 응답 필드명을 갖는다.
--
--  [넣지 않은 것]
--   · empNo — 절대 넣지 않는다. 계정 관리·보안 감사 로그가 이 키를 쓴다. worker 로 등록하면
--     작업자 권한이 없는 관리자에게 관리 화면이 통째로 가려진다.
--   · items · actual · plan · gap · denominator · numerator 처럼 흔한 이름. 코드상으로는
--     해당 항목으로 가려지지만 이름이 너무 흔하다. 특히 items 는 28개 파일이 목록 키로 쓴다.
--   · 시드 34개가 응답 키 전부는 아니다. rawQty · totalNgQty · targetQty · weekQty ·
--     currentYield · totalRate 등도 코드상 가려지는 키다. 흔한 이름이 아니라 넣어도 되지만,
--     이번 회차 근거 목록 밖이라 남겨 둔다 — 화면에서 추가하면 된다(그러라고 만든 표다).
--
--  [관리 화면 충돌 확인]
--  34개 전부 SystemUserRepository · AuditLogRepository · AuthRepository · AuditLogService ·
--  AuthService · SystemUserService 의 응답 키와 겹치지 않는 것을 확인했다.
--
--  [적용]
--  docker exec -e PGPASSWORD=dwje_local dwje-pg psql -U dwje_local -d dwjedb -v ON_ERROR_STOP=1 -f V34__data_field_attr_seed.sql
--  두 번 실행해도 안전하다. 운영은 요청자 확인 뒤에 적용한다.
-- =====================================================================================

-- 1. 폐기 표 제거 ----------------------------------------------------------------------
DROP TABLE IF EXISTS ax.tb_sys_data_field_column;

-- 2. 응답 필드명 초기값 ------------------------------------------------------------------
--    ins_user='V34' 로 표시해 둔다. 되돌릴 때 이 표시로 시드만 골라 지운다
--    (화면에서 나중에 추가한 행은 건드리지 않기 위해서다).
INSERT INTO ax.tb_sys_data_field_attr (field_key, attr_name, remark, ins_user) VALUES
 -- 수량 (17)
 ('qty','qty',              '공통 — 수량',                       'V34'),
 ('qty','inputQty',         '실적·생산 모니터링·아침회의 투입',   'V34'),
 ('qty','okQty',            '실적·불량·일일보고 양품',            'V34'),
 ('qty','ngQty',            '실적·불량·AOI 불량',                 'V34'),
 ('qty','totalQty',         '불량 현황 합계',                     'V34'),
 ('qty','prevNgQty',        '불량 현황 전기 대비',                'V34'),
 ('qty','hourlyThroughput', '생산 모니터링 시간당',               'V34'),
 ('qty','totalThroughput',  '생산 모니터링 누계',                 'V34'),
 ('qty','todayQty',         'AI 대시보드 금일',                   'V34'),
 ('qty','sampleQty',        'AOI 검사 수량',                      'V34'),
 ('qty','shipQty',          '출하 계획 보고서',                   'V34'),
 ('qty','lrrQty',           'LRR 보고서',                         'V34'),
 ('qty','dayActual',        '아침회의 일 실적',                   'V34'),
 ('qty','dayTarget',        '아침회의 일 목표',                   'V34'),
 ('qty','weekActual',       '아침회의 주 실적',                   'V34'),
 ('qty','weekTarget',       '아침회의 주 목표',                   'V34'),
 ('qty','ratedActual',      '아침회의 정격 실적',                 'V34'),
 -- 수율·불량률 (7)
 ('yield','yield',          '공통 — 수율',                        'V34'),
 ('yield','defectRate',     '공통 — 불량률',                      'V34'),
 ('yield','momChange',      '불량 현황 전월 대비',                'V34'),
 ('yield','avgRate',        '보고서 평균률',                      'V34'),
 ('yield','weekRate',       '아침회의 주간률',                    'V34'),
 ('yield','lrrRate',        'LRR 보고서',                         'V34'),
 ('yield','yoyImprovement', '수율 보고서 전년 대비',              'V34'),
 -- 단가·금액 (2)
 ('price','unitPrice',      '출하 계획 보고서 단가',              'V34'),
 ('price','planAmount',     '출하 계획 보고서 금액',              'V34'),
 -- 고객사 (2)
 ('customer','customer',    '공통 — 고객사명',                    'V34'),
 ('customer','customerCd',  '공통 — 고객사 코드',                 'V34'),
 -- 출하 계획 (1)
 ('plan','planQty',         '출하 계획 수량 · AI 브리핑',         'V34'),
 -- 금형·설비 (4)
 ('mold','moldCd',          '생산 모니터링·알림·AOI 금형 코드',   'V34'),
 ('mold','moldNm',          '생산 모니터링 금형명',               'V34'),
 ('mold','cavity',          'AOI 캐비티',                         'V34'),
 ('mold','strokeSpeed',     '생산 모니터링 타발 속도',            'V34'),
 -- 작업자 (1)
 ('worker','insUsers',      'AI 대시보드 불량 등록자 사번 목록',  'V34')
ON CONFLICT DO NOTHING;
