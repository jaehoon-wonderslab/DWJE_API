-- =====================================================================================
--  V10 : 생산 불량 미계상 불량코드 기준정보
--
--  [배경]
--  mes.tb_pop_defect_hist 의 불량코드 중 일부는 생산 불량이 아니다.
--  해당 코드가 붙은 라벨은 tb_pop_label_hist.defect 가 항상 0 이고(= 양품으로 계상),
--  dh.qty 에는 그 라벨의 전체 수량이 기록된다. 재작업·반품 처리 표시에 가깝다.
--
--  2026-08 한 달 실측
--    일반 코드   97,035행 중 label.defect=0 인 행     0건 (0.0%)
--    (R)/T 코드  18,823행 중 label.defect=0 인 행 18,823건 (100.0%)
--  수량으로는 (R)/T 가 788만으로 전체 불량이력의 44% 를 차지해,
--  이를 섞으면 불량률이 1.95% → 3.61% 로 부풀고 불량 유형 1위가 '기타 (R)' 이 된다.
--
--  [설계]
--  mes 는 조회 전용이고 tb_md_defect 에 구분 컬럼도 없어 원본에 표시할 수 없다.
--  그래서 제외 대상을 ax 기준정보로 관리한다. 코드 수정 없이 전산팀이 조정할 수 있다.
--  판정 근거는 불량코드 이름의 '(R)' 접미사 규약이며, 규약이 바뀌면 이 목록만 갱신한다.
--
--  [영향]
--  불량 수량·불량률은 이미 tb_pop_label_hist 기준이라 이 목록과 무관하게 정확하다.
--  이 목록은 불량 *유형 구성* 조회에서 생산 불량이 아닌 코드를 걸러내는 데 쓴다.
--  집계에서 빼고 싶지 않으면 use_flg 를 'N' 으로 바꾸면 된다.
-- =====================================================================================

INSERT INTO ax.tb_sys_code_group (group_cd, group_nm, group_desc, use_flg, sort_seq)
VALUES ('QC_DEFECT_NONPROD', '생산 불량 미계상 불량코드',
        'mes.tb_pop_defect_hist 에는 있으나 생산 불량으로 계상하지 않는 코드. 불량 유형 구성 조회에서 제외한다.',
        'Y', 90)
ON CONFLICT (group_cd) DO NOTHING;

INSERT INTO ax.tb_sys_code (group_cd, code, code_nm, code_desc, sort_seq, use_flg, ins_user, upd_user)
VALUES
    ('QC_DEFECT_NONPROD', 'DF142', 'T',            '재작업·반품 처리 표시', 1, 'Y', 'SEED', 'SEED'),
    ('QC_DEFECT_NONPROD', 'DF144', '치수 (R)',     '재작업·반품 처리 표시', 2, 'Y', 'SEED', 'SEED'),
    ('QC_DEFECT_NONPROD', 'DF145', '각도 (R)',     '재작업·반품 처리 표시', 3, 'Y', 'SEED', 'SEED'),
    ('QC_DEFECT_NONPROD', 'DF146', '도장불량 (R)', '재작업·반품 처리 표시', 4, 'Y', 'SEED', 'SEED'),
    ('QC_DEFECT_NONPROD', 'DF151', '얼룩 (R)',     '재작업·반품 처리 표시', 5, 'Y', 'SEED', 'SEED'),
    ('QC_DEFECT_NONPROD', 'DF152', '스크래치 (R)', '재작업·반품 처리 표시', 6, 'Y', 'SEED', 'SEED'),
    ('QC_DEFECT_NONPROD', 'DF153', '찍힘 (R)',     '재작업·반품 처리 표시', 7, 'Y', 'SEED', 'SEED'),
    ('QC_DEFECT_NONPROD', 'DF154', '변형 (R)',     '재작업·반품 처리 표시', 8, 'Y', 'SEED', 'SEED'),
    ('QC_DEFECT_NONPROD', 'DF155', '동노출 (R)',   '재작업·반품 처리 표시', 9, 'Y', 'SEED', 'SEED'),
    ('QC_DEFECT_NONPROD', 'DF156', '도장이물 (R)', '재작업·반품 처리 표시', 10, 'Y', 'SEED', 'SEED'),
    ('QC_DEFECT_NONPROD', 'DF157', 'Burr (R)',     '재작업·반품 처리 표시', 11, 'Y', 'SEED', 'SEED'),
    ('QC_DEFECT_NONPROD', 'DF158', '기타 (R)',     '재작업·반품 처리 표시', 12, 'Y', 'SEED', 'SEED'),
    ('QC_DEFECT_NONPROD', 'DF162', '겹침불량 (R)', '재작업·반품 처리 표시', 13, 'Y', 'SEED', 'SEED')
ON CONFLICT (group_cd, code) DO NOTHING;
