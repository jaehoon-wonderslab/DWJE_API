-- =====================================================================================
--  V28 : AOI 불량 사진 매핑 키 재설계 — MSSQL DIMENSION 원천 수용
--        (REQ_20260911 E-1 · E-2, B4)
--
--  [배경]
--  2026-09-11 발주자 지시로 AOI 원천이 MES 라벨 이력에서 MSSQL(EDGE.dbo.TB_SAMSUN_DIMENSION)
--  로 바뀐다. PostgreSQL 은 그대로 쓰고 NAS 사진 매핑(ax.tb_aoi_defect_image)만 두 원천을 받는다.
--
--   · MES 라벨 이력 키 : plant_cd · wc_cd · lot_no · serial_no        → defect_id 'plant-wc-lot-serial'
--   · MSSQL DIMENSION  : WC_CD · EQPT_CD · LOT_NO · SERIAL_NO (plant 없음)
--                                                                     → defect_id 'wc~eqpt~lot~serial'
--
--  [두 원천을 함께 두는 이유 — E-1 판단]
--  기존 2행(로컬 픽스처, 웹이 이미지 뷰어 실측에 쓰는 중)을 살려야 하고, 웹은 두 형식을 모두
--  통과시키도록 이미 만들어져 있다. 예측 화면(/quality/aoi/prediction/*)은 계속 PostgreSQL·MES
--  라벨을 읽는다. 그래서 키를 갈아엎지 않고 source_cd 로 구분한다.
--  source_cd 를 두지 않으면 한 컬럼에 형식이 다른 두 id 가 섞여, 어느 원천인지도 키가 온전한지도
--  판정할 수 없다. 비용은 컬럼 하나와 CHECK 하나뿐이다.
--
--  [타입을 common 도메인에서 varchar 로 넓히는 이유]
--  MES 도메인은 d_lot_no varchar(8) · d_lot_serial varchar(5) · d_wc_cd varchar(10) 로 짧다.
--  MSSQL 쪽 실제 값은 아직 확인하지 못했다(2026-09-11 현재 192.168.7.203 사내망 밖이라 접속 불가,
--  REQ 의 C절 샘플 확인이 끝나지 않았다). 도메인 길이에 묶어 두면 값이 길 때 적재가 실패한다.
--  설계 원칙 4(MES 참조 컬럼은 common 도메인)의 근거는 MES 와의 조인인데, DIMENSION 행은 MES 와
--  조인하지 않는다. LABEL 행은 varchar 끼리 비교라 형변환 없이 MES 인덱스를 그대로 탄다.
--  C절 답이 오면 실제 최대 길이에 맞춰 좁힐 수 있다.
--
--  [적용]
--  docker exec -e PGPASSWORD=dwje_local dwje-pg psql -U dwje_local -d dwjedb -v ON_ERROR_STOP=1 -f V28__aoi_defect_image_dimension_key.sql
--  두 번 실행해도 안전하다. 기존 행은 source_cd='LABEL' 로 남고 defect_id 문자열이 바뀌지 않는다.
-- =====================================================================================

-- 1. 공통코드 — 사진 원천 --------------------------------------------------------------
INSERT INTO ax.tb_sys_code_group (group_cd, group_nm, group_desc, use_flg, sort_seq) VALUES
 ('AOI_IMG_SOURCE', 'AOI 사진 원천', 'ax.tb_aoi_defect_image.source_cd — 판정 키가 어느 원천의 것인지', 'Y', 97)
ON CONFLICT (group_cd) DO NOTHING;

INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, sort_seq, use_flg, ins_user, upd_user) VALUES
 ('AOI_IMG_SOURCE', 'LABEL',     'MES 라벨 이력', 'mes.tb_pop_label_hist 키 (plant·wc·lot·serial)',        1, 'Y', 'V28', 'V28'),
 ('AOI_IMG_SOURCE', 'DIMENSION', 'MSSQL 치수검사', 'EDGE.dbo.TB_SAMSUN_DIMENSION 키 (wc·eqpt·lot·serial)', 2, 'Y', 'V28', 'V28')
ON CONFLICT (group_cd, code) DO NOTHING;

INSERT INTO ax.tb_sys_code_ref (target_table, target_column, group_cd, nullable_flg) VALUES
 ('tb_aoi_defect_image', 'source_cd', 'AOI_IMG_SOURCE', 'N')
ON CONFLICT (target_table, target_column) DO NOTHING;

-- 2. 공통코드 — 작업장 표시명 대응표 (E-2) ------------------------------------------------
--    MSSQL WC_CD 를 화면 표시명(도금·도장·레이저)으로 바꾼다. 값은 WC_CD 실제 값을 확인한 뒤 넣는다.
--    코드가 비어 있으면 API 는 mes.tb_md_workcenter.wc_nm 을, 그것도 없으면 WC_CD 를 그대로 쓴다.
INSERT INTO ax.tb_sys_code_group (group_cd, group_nm, group_desc, use_flg, sort_seq) VALUES
 ('AOI_WC', 'AOI 작업장 표시명',
  'MSSQL DIMENSION 의 WC_CD → 화면 표시명 대응표. code = WC_CD 값, code_nm = 표시명(도금·도장·레이저). '
  '비어 있으면 mes.tb_md_workcenter.wc_nm, 그것도 없으면 WC_CD 원값을 쓴다. WC_CD 실제 값 확인 후 코드를 넣는다.',
  'Y', 96)
ON CONFLICT (group_cd) DO NOTHING;

-- 3. 매핑 테이블 재설계 -------------------------------------------------------------------
DO $v28$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'ax' AND table_name = 'tb_aoi_defect_image'
                  AND column_name = 'source_cd') THEN
        RAISE NOTICE 'V28 이미 적용됨 — 테이블 재설계를 건너뜁니다';
        RETURN;
    END IF;

    -- 생성 컬럼이 있으면 원본 컬럼의 타입을 바꿀 수 없다. 먼저 떼고 마지막에 다시 붙인다.
    ALTER TABLE ax.tb_aoi_defect_image DROP COLUMN IF EXISTS defect_id;
    ALTER TABLE ax.tb_aoi_defect_image DROP CONSTRAINT IF EXISTS uq_aoi_defect_image_seq;
    DROP INDEX IF EXISTS ax.ix_aoi_defect_image_defect_id;

    ALTER TABLE ax.tb_aoi_defect_image
        ADD COLUMN source_cd varchar(30) NOT NULL DEFAULT 'LABEL',
        ADD COLUMN eqpt_cd   varchar(50);

    -- plant 는 LABEL 원천에만 있다.
    ALTER TABLE ax.tb_aoi_defect_image ALTER COLUMN plant_cd DROP NOT NULL;

    -- MSSQL 값 길이를 모르므로 도메인(8·5·10자)에서 넓힌다.
    ALTER TABLE ax.tb_aoi_defect_image ALTER COLUMN wc_cd     TYPE varchar(30);
    ALTER TABLE ax.tb_aoi_defect_image ALTER COLUMN lot_no    TYPE varchar(30);
    ALTER TABLE ax.tb_aoi_defect_image ALTER COLUMN serial_no TYPE varchar(30);

    -- 원천별 키 조립. 구분자가 달라(- 와 ~) 두 형식이 섞여도 충돌하지 않는다.
    ALTER TABLE ax.tb_aoi_defect_image
        ADD COLUMN defect_id varchar(200) GENERATED ALWAYS AS (
            CASE WHEN source_cd = 'DIMENSION'
                 THEN wc_cd::text    || '~' || eqpt_cd::text || '~' || lot_no::text || '~' || serial_no::text
                 ELSE plant_cd::text || '-' || wc_cd::text   || '-' || lot_no::text || '-' || serial_no::text
            END) STORED;

    -- 원천별로 키가 온전해야 한다. 아니면 defect_id 가 NULL 이 되어 사진을 찾을 수 없다.
    ALTER TABLE ax.tb_aoi_defect_image ADD CONSTRAINT ck_aoi_defect_image_key CHECK (
        CASE source_cd
            WHEN 'LABEL'     THEN plant_cd IS NOT NULL
            WHEN 'DIMENSION' THEN eqpt_cd  IS NOT NULL
            ELSE false
        END);

    -- 같은 판정의 같은 순번 사진은 하나다. 조회(defectId 로 찾기)도 이 인덱스를 탄다.
    ALTER TABLE ax.tb_aoi_defect_image ADD CONSTRAINT uq_aoi_defect_image_defect_seq UNIQUE (defect_id, seq);
END
$v28$;

-- V27 이 만든 (defect_id, seq) 인덱스는 uq_aoi_defect_image_defect_seq 가 대신한다.
-- V27 을 다시 실행하면 그 인덱스가 되살아나므로(그때는 defect_id 컬럼이 이미 있다) DO 블록 밖에
-- 두어 몇 번을 실행하든 마지막에 지워지게 한다.
DROP INDEX IF EXISTS ax.ix_aoi_defect_image_defect_id;

COMMENT ON TABLE  ax.tb_aoi_defect_image             IS 'AOI 판정 ↔ NAS 불량 사진 매핑. 원천 2종 — MES 라벨 이력(LABEL) · MSSQL EDGE.dbo.TB_SAMSUN_DIMENSION(DIMENSION)';
COMMENT ON COLUMN ax.tb_aoi_defect_image.source_cd   IS '판정 키 원천. 공통코드 그룹 = AOI_IMG_SOURCE (LABEL=MES 라벨 이력, DIMENSION=MSSQL 치수검사)';
COMMENT ON COLUMN ax.tb_aoi_defect_image.plant_cd    IS '공장 코드. LABEL 원천에만 있다 (MSSQL DIMENSION 에는 공장 컬럼이 없음)';
COMMENT ON COLUMN ax.tb_aoi_defect_image.wc_cd       IS '작업장 코드. DIMENSION 은 이전 작업장(도금·도장·레이저). 표시명은 공통코드 AOI_WC → mes.tb_md_workcenter.wc_nm → 원값 순으로 찾는다';
COMMENT ON COLUMN ax.tb_aoi_defect_image.eqpt_cd     IS '설비 코드. DIMENSION 원천에만 있다';
COMMENT ON COLUMN ax.tb_aoi_defect_image.defect_id   IS '생성 컬럼. DIMENSION = wc~eqpt~lot~serial, LABEL = plant-wc-lot-serial. API 의 defectId';
COMMENT ON COLUMN ax.tb_aoi_defect_image.lot_no      IS '로트 번호. MSSQL 값 길이를 확인하지 못해 도메인(varchar(8)) 대신 varchar(30)';
COMMENT ON COLUMN ax.tb_aoi_defect_image.serial_no   IS '시리얼 번호. 같은 이유로 varchar(30)';
