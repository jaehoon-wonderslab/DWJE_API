-- =====================================================================================
--  V82 : 데이터 접근 권한을 「항목」 단위로 — 기본 7종 묶음을 응답 필드명(항목)마다 나눈다 (2026-10-07)
--
--  [배경]
--  데이터 접근 권한 화면(/system/data-perm)을 「항목 × 부서」 표 하나로 바꾼다(사용자 결정 2026-10-07 — 묶음 없이 항목마다 부서 체크).
--  지금은 권한이 「종류」(tb_sys_data_field) 단위이고, 기본 7종(qty · yield · price · customer · plan · mold · worker)이
--  응답 필드명 여러 개를 한 묶음으로 갖고 있어 항목 하나만 따로 정할 수 없었다.
--  설계: WEB docs/DATA_ITEM_MASKING_DESIGN_20261007.md
--
--  [이 파일이 하는 일] — 두 번 실행해도 안전하다
--   1. ax.tb_sys_data_attr_origin (attr_name PK, origin_key) — 응답 필드명이 원래 어느 기본 묶음 것인지.
--      서버 코드 약 200곳이 기본 7종 key 로 직접 가린다(MaskingSupport). 그 판정을 이 표로 계산한다(아래 [판정]).
--      필드명을 다른 항목으로 옮기거나 풀어도(행 삭제) 이 표는 남는다.
--   2. 기본 7종에 든 필드명마다 항목(종류) 하나 — key 'i_' || lower(필드명), 이름은 아래 표.
--      적용(apply_flg)은 원래 묶음 값, 부서 권한(tb_sys_dept_data_perm)은 원래 묶음 행을 그대로 복사한다 — 바뀌는 사람이 없다.
--   3. 필드명을 새 항목으로 옮긴다. 기본 7종 행 · 그 부서 권한 행은 남긴다(알림 조건 · 지표 기준 · 문서 태그가 key 를 참조).
--
--  [판정 — API AuthRepository.findDataPermSets]
--   기본 묶음 G 의 「느슨한」 권한 = G 에서 온 필드명 중 하나라도 볼 수 있음 → 응답 구조를 가리는 코드(JSON 키)는 이것을 쓰고,
--   어느 키를 실제로 가릴지는 공통 마스킹(DataFieldMaskingAdvice)이 항목 권한으로 정한다.
--   「엄격한」 권한 = G 에서 온 필드명을 모두 볼 수 있음 → AI 프롬프트 · 메일 · 문장처럼 키가 없는 출력이 쓴다.
--   필드명의 「볼 수 있음」 = 어느 항목에도 없음(통제 밖) · 그 항목이 미적용 · 그 항목의 부서 권한이 허용.
--
--  [순서] — 이 파일 → 새 API. 옛 API 는 새 항목(i_*)을 몰라도 attr 표를 그대로 읽으므로 이 파일 뒤에도 동작한다
--   (다만 옛 API 의 코드 마스킹은 기본 7종 행 권한을 그대로 쓴다 — 복사했으므로 결과가 같다).
--
--  [실행]
--  psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f V82__data_item_perm.sql
--  되돌리기는 rollback/V82__down.sql (필드명을 원래 묶음으로 돌리고 i_* 항목 · 기록표를 지운다).
-- =====================================================================================

BEGIN;

-- 1. 원래 묶음 기록표 ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ax.tb_sys_data_attr_origin (
    attr_name  varchar(60) NOT NULL,
    origin_key varchar(30) NOT NULL,
    ins_date   timestamptz NOT NULL DEFAULT now(),
    ins_user   common.d_user_id,
    CONSTRAINT pk_sys_data_attr_origin PRIMARY KEY (attr_name),
    CONSTRAINT fk_sys_data_attr_origin_field FOREIGN KEY (origin_key)
        REFERENCES ax.tb_sys_data_field(field_key) ON DELETE CASCADE
);
COMMENT ON TABLE  ax.tb_sys_data_attr_origin            IS '응답 필드명의 원래 기본 묶음(V82). 서버 코드가 기본 7종 key 로 가리는 판정을 항목 권한에서 계산할 때 쓴다';
COMMENT ON COLUMN ax.tb_sys_data_attr_origin.attr_name  IS 'API 응답 JSON 필드명(대소문자 구분). tb_sys_data_field_attr 에서 빠져도 이 행은 남는다';
COMMENT ON COLUMN ax.tb_sys_data_attr_origin.origin_key IS '원래 기본 묶음 key(qty · yield · price · customer · plan · mold · worker)';

INSERT INTO ax.tb_sys_data_attr_origin (attr_name, origin_key, ins_user)
SELECT a.attr_name, a.field_key, 'V82'
  FROM ax.tb_sys_data_field_attr a
 WHERE a.field_key IN ('qty', 'yield', 'price', 'customer', 'plan', 'mold', 'worker')
ON CONFLICT (attr_name) DO NOTHING;

-- 2. 항목 이름 — 화면 열 제목 기준(없으면 필드명) ------------------------------------------
CREATE TEMP TABLE tmp_v82_name (attr_name varchar(60) PRIMARY KEY, item_nm varchar(50)) ON COMMIT DROP;
INSERT INTO tmp_v82_name VALUES
 ('qty','수량'), ('inputQty','투입 수량'), ('okQty','양품 수량'), ('ngQty','불량 수량'), ('totalQty','생산 합계'),
 ('prevNgQty','전기 불량 수량'), ('hourlyThroughput','시간당 생산량'), ('totalThroughput','누계 생산량'), ('todayQty','금일 생산량'),
 ('sampleQty','검사 수량'), ('shipQty','출하 수량'), ('lrrQty','LRR 수량'), ('dayActual','일 실적'), ('dayTarget','일목표'),
 ('weekActual','주간 실적'), ('weekTarget','주간 목표'), ('ratedActual','정격 실적'), ('uptimeRate','가동률'),
 ('yield','수율'), ('defectRate','불량률'), ('momChange','전월 대비 증감'), ('avgRate','평균 수율'), ('weekRate','주간 달성률'),
 ('lrrRate','LRR(%)'), ('yoyImprovement','전년 대비 개선'),
 ('unitPrice','단가'), ('planAmount','계획 금액'),
 ('customer','고객사'), ('customerCd','고객사 코드'),
 ('planQty','출하 계획 수량'),
 ('moldCd','금형 코드'), ('moldNm','금형명'), ('cavity','캐비티'), ('strokeSpeed','타발 속도'),
 ('insUsers','등록자');

-- 3. 항목 만들기 · 부서 권한 복사 · 필드명 옮기기 -------------------------------------------
DO $$
DECLARE
    r       record;
    v_key   varchar(30);
    v_seq   integer;
    v_made  integer := 0;
BEGIN
    SELECT coalesce(max(sort_seq), 0) INTO v_seq FROM ax.tb_sys_data_field;
    FOR r IN
        SELECT a.attr_name, a.field_key AS origin, f.apply_flg,
               coalesce(n.item_nm, a.attr_name) AS item_nm
          FROM ax.tb_sys_data_field_attr a
          JOIN ax.tb_sys_data_field f ON f.field_key = a.field_key
          LEFT JOIN tmp_v82_name n ON n.attr_name = a.attr_name
         WHERE a.field_key IN ('qty', 'yield', 'price', 'customer', 'plan', 'mold', 'worker')
         ORDER BY f.sort_seq, a.attr_name
    LOOP
        v_key := left('i_' || lower(regexp_replace(r.attr_name, '[^A-Za-z0-9]', '', 'g')), 30);
        IF EXISTS (SELECT 1 FROM ax.tb_sys_data_field WHERE field_key = v_key) THEN
            RAISE EXCEPTION 'V82: 항목 key 가 이미 있습니다 — % (필드명 %)', v_key, r.attr_name;
        END IF;
        v_seq := v_seq + 1;
        INSERT INTO ax.tb_sys_data_field (field_key, field_nm, field_desc, sort_seq, use_flg, apply_flg, ins_user, upd_user)
        VALUES (v_key, r.item_nm, NULL, v_seq, 'Y', r.apply_flg, 'V82', 'V82');
        INSERT INTO ax.tb_sys_dept_data_perm (dept_id, field_key, is_allowed, ins_user, upd_user)
        SELECT p.dept_id, v_key, p.is_allowed, 'V82', 'V82'
          FROM ax.tb_sys_dept_data_perm p
         WHERE p.field_key = r.origin;
        UPDATE ax.tb_sys_data_field_attr SET field_key = v_key WHERE attr_name = r.attr_name;
        v_made := v_made + 1;
    END LOOP;
    RAISE NOTICE 'V82: 항목 % 개를 만들었습니다', v_made;
END $$;

-- 4. 서버 코드만 가리던 필드명(같은 뜻의 다른 이름) 등록 ----------------------------------
--  기본 7종 key 로 코드가 가리던 응답 키 중 항목 표에 없던 것(2026-10-07 전수 조사).
--  항목 단위로 정하면 코드 판정은 「그 묶음 항목 중 하나라도 볼 수 있음」(느슨한 판정)으로 통과하므로,
--  이 키들이 항목 표에 없으면 같은 뜻의 항목을 숨겨도 이 이름으로는 보인다. 같은 뜻의 항목에 넣고, 없으면 새 항목을 만든다.
--  화면마다 뜻이 다른 이름(rate · ratio · total · cnt · value · actual · label · spec · name …)은 넣지 않는다 —
--  넣으면 다른 화면의 다른 값까지 가려진다. 그 이름들은 코드의 기본 7종 판정이 계속 가린다(묶음 항목을 하나도 못 볼 때).
CREATE TEMP TABLE tmp_v82_alias (attr_name varchar(60) PRIMARY KEY, origin varchar(30), target varchar(30), item_nm varchar(50)) ON COMMIT DROP;
INSERT INTO tmp_v82_alias VALUES
 -- 생산·출하 수량(qty) — 같은 뜻의 기존 항목으로
 ('ngCnt','qty','i_ngqty','불량 수량'), ('failCnt','qty','i_ngqty','불량 수량'), ('prodNgCnt','qty','i_ngqty','불량 수량'),
 ('totalNgQty','qty','i_ngqty','불량 수량'), ('typedNgQty','qty','i_ngqty','불량 수량'), ('untypedNgQty','qty','i_ngqty','불량 수량'),
 ('ngFinalCnt','qty','i_ngqty','불량 수량'), ('defectQuantity','qty','i_ngqty','불량 수량'),
 ('prodOkCnt','qty','i_okqty','양품 수량'), ('passCnt','qty','i_okqty','양품 수량'),
 ('measCnt','qty','i_sampleqty','검사 수량'), ('prodCnt','qty','i_sampleqty','검사 수량'), ('inspCnt','qty','i_sampleqty','검사 수량'),
 ('targetQty','qty','i_daytarget','일목표'), ('weekTargetQty','qty','i_weektarget','주간 목표'),
 ('weekQty','qty','i_weekactual','주간 실적'), ('weekQtyAllShift','qty','i_weekactual','주간 실적'),
 ('quantity','qty','i_qty','수량'), ('avgHourlyQty','qty','i_hourlythroughput','시간당 생산량'),
 -- 생산·출하 수량(qty) — 새 항목
 ('rawQty','qty','i_rawqty','원천 불량 수량'), ('estimatedNgQty','qty','i_estimatedngqty','예상 불량 수량'),
 ('shipShare','qty','i_shipshare','출하 비중'), ('cumActual','qty','i_cumactual','누적 실적'),
 ('judgeCnt','qty','i_judgecnt','판정 건수'), ('explainedCnt','qty','i_explainedcnt','설명된 불량 건수'),
 ('serialCnt','qty','i_serialcnt','시리얼 수'), ('distinctSerialCnt','qty','i_serialcnt','시리얼 수'),
 ('distinctProdCnt','qty','i_distinctprodcnt','검사 제품 수'),
 ('violCnt','qty','i_violcnt','FAI 위반 건수'), ('violSingleCnt','qty','i_violcnt','FAI 위반 건수'),
 ('overCnt','qty','i_overundercnt','FAI 초과·미달 건수'), ('underCnt','qty','i_overundercnt','FAI 초과·미달 건수'),
 ('exceedSum','qty','i_exceedsum','FAI 초과량 합'), ('zeroCnt','qty','i_zerocnt','불량 없음 건수'),
 ('seqCnt','qty','i_seqcnt','검사 회차'), ('daySeqCnt','qty','i_seqcnt','검사 회차'), ('failSeqCnt','qty','i_failseqcnt','불량 회차'),
 -- 수율·불량률(yield) — 같은 뜻의 기존 항목으로
 ('failRate','yield','i_defectrate','불량률'), ('ngRate','yield','i_defectrate','불량률'), ('ngRateAll','yield','i_defectrate','불량률'),
 ('yieldRate','yield','i_yield','수율'), ('currentYield','yield','i_yield','수율'),
 -- 수율·불량률(yield) — 새 항목
 ('explainedRate','yield','i_explainedrate','설명률'), ('sharePct','yield','i_sharepct','불량 비중'), ('ngPct','yield','i_sharepct','불량 비중'),
 ('violPct','yield','i_violpct','FAI 위반율'), ('failRatePt','yield','i_defectratept','불량률 증감'), ('ngRatePt','yield','i_defectratept','불량률 증감'),
 ('explainedRatePt','yield','i_explainedratept','설명률 증감'), ('predictedDefectRate','yield','i_predicteddefectrate','예측 불량률'),
 ('estimatedRate','yield','i_estimatedrate','예상 불량률'), ('modelConfidence','yield','i_modelconfidence','예측 신뢰도'),
 ('totalRate','yield','i_totalrate','KPI 종합 달성률'),
 -- 출하 계획(plan)
 ('cumPlan','plan','i_cumplan','누적 계획');

DO $$
DECLARE
    r      record;
    v_seq  integer;
    v_add  integer := 0;
    v_new  integer := 0;
BEGIN
    SELECT coalesce(max(sort_seq), 0) INTO v_seq FROM ax.tb_sys_data_field;
    FOR r IN SELECT * FROM tmp_v82_alias ORDER BY target, attr_name LOOP
        -- 이미 어느 항목에 있으면(운영 중 화면에서 넣은 것) 그대로 둔다 — 기록표만 남긴다
        INSERT INTO ax.tb_sys_data_attr_origin (attr_name, origin_key, ins_user)
        VALUES (r.attr_name, r.origin, 'V82') ON CONFLICT (attr_name) DO NOTHING;
        CONTINUE WHEN EXISTS (SELECT 1 FROM ax.tb_sys_data_field_attr WHERE attr_name = r.attr_name);

        IF NOT EXISTS (SELECT 1 FROM ax.tb_sys_data_field WHERE field_key = r.target) THEN
            v_seq := v_seq + 1;
            INSERT INTO ax.tb_sys_data_field (field_key, field_nm, field_desc, sort_seq, use_flg, apply_flg, ins_user, upd_user)
            SELECT r.target, r.item_nm, NULL, v_seq, 'Y', f.apply_flg, 'V82', 'V82'
              FROM ax.tb_sys_data_field f WHERE f.field_key = r.origin;
            INSERT INTO ax.tb_sys_dept_data_perm (dept_id, field_key, is_allowed, ins_user, upd_user)
            SELECT p.dept_id, r.target, p.is_allowed, 'V82', 'V82'
              FROM ax.tb_sys_dept_data_perm p WHERE p.field_key = r.origin;
            v_new := v_new + 1;
        END IF;
        INSERT INTO ax.tb_sys_data_field_attr (field_key, attr_name, remark, ins_user)
        VALUES (r.target, r.attr_name, r.item_nm, 'V82');
        v_add := v_add + 1;
    END LOOP;
    RAISE NOTICE 'V82: 같은 뜻의 필드명 % 개를 넣었습니다(새 항목 % 개)', v_add, v_new;
END $$;

COMMIT;
