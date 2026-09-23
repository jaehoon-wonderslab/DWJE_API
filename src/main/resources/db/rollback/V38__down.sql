-- =====================================================================================
--  V38 되돌리기 — 미참조 객체·인덱스 복원 (2026-09-21)
--
--  V38 로 지운 것을 그대로 되살린다. 뷰 정의는 삭제 직전 운영 DB 에서 그대로 떠 온 것이다.
--  인덱스는 다시 만들면 되므로 손실이 없고, 표 둘(tb_prod_item_price · tb_alm_duty)은
--  0행이었으므로 구조만 복원하면 원상이다.
--
--  [주의] 인덱스 재생성은 대상 표가 커서 시간이 걸린다(합계 820 MB).
--         운영 중이라면 CREATE INDEX CONCURRENTLY 로 바꿔 거는 것을 권한다.
--
--  ┌─ 실행 방법 ──────────────────────────────────────────────────────────────────────┐
--  │   psql -h localhost -U dwje_local -d dwjedb -f rollback/V38__down.sql            │
--  │   · 이 파일이 BEGIN/COMMIT 을 직접 들고 있으므로 --single-transaction 은 붙이지 않는다.
--  │   · 하나라도 실패하면 전체 롤백된다(부분 복원 없음). 두 번 실행해도 안전하다.     │
--  └──────────────────────────────────────────────────────────────────────────────────┘
-- =====================================================================================

\set ON_ERROR_STOP on
\timing on

\echo ''
\echo '==================== V38 되돌리기 ===================='

BEGIN;

-- ── 1. 인덱스 복원 ──────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS ix_ai_asset_kind ON ax.tb_ai_model_asset USING btree (asset_kind_cd, asset_key, version_tag DESC);
CREATE INDEX IF NOT EXISTS ix_doc_chunk_doc ON vec.tb_doc_chunk USING btree (doc_id, doc_ver, chunk_seq);
CREATE INDEX IF NOT EXISTS ix_pop_defect_defect ON mes.tb_pop_defect_hist USING btree (plant_cd, defect_cd);
CREATE INDEX IF NOT EXISTS ix_pop_defect_item ON mes.tb_pop_defect_hist USING btree (plant_cd, item_cd);
CREATE INDEX IF NOT EXISTS ix_pop_label_type ON mes.tb_pop_label_hist USING btree (plant_cd, label_type);
CREATE INDEX IF NOT EXISTS ix_prod_day_target_lookup ON ax.tb_prod_day_target USING btree (plant_cd, product, wc_cd, apply_from DESC);
CREATE INDEX IF NOT EXISTS ix_prod_product_family ON ax.tb_prod_product USING btree (family_id, seq_in_family);
CREATE INDEX IF NOT EXISTS ix_serving_profile_cd ON ax.tb_ai_serving_profile USING btree (service_cd, profile_cd, version_no DESC);

-- ── 2. 표 복원 (둘 다 0행이었다) ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ax.tb_prod_item_price (
    plant_cd      common.d_plant_cd        NOT NULL,
    item_cd       common.d_item_cd         NOT NULL,
    valid_from    date                     NOT NULL,
    unit_price    numeric(18,4)            NOT NULL,
    price_kind_cd varchar(30)              NOT NULL DEFAULT 'COST',
    currency_cd   varchar(3)               NOT NULL DEFAULT 'KRW',
    remark        varchar(300),
    ins_date      timestamptz              NOT NULL DEFAULT now(),
    ins_user      common.d_user_id,
    CONSTRAINT pk_tb_prod_item_price PRIMARY KEY (plant_cd, item_cd, valid_from, price_kind_cd)
);

--  tb_alm_duty 는 V36 이 폐기 대상으로 지정한 표다. 되살릴 일이 있으면
--  rollback/V36__down.sql 의 정의를 쓴다 — 여기서 중복 정의하지 않는다.

-- ── 3. 뷰 복원 (삭제 직전 정의 그대로) ──────────────────────────────────────────────
CREATE OR REPLACE VIEW ax.vw_alert_context AS
SELECT a.alert_id,
a.occurred_at,
a.severity_cd,
sv.code_nm AS severity_nm,
a.title,
c.cond_nm,
c.metric_desc,
c.op_cd,
c.threshold_text,
ms.metric_nm,
ms.unit_cd,
ms.std_val,
ms.warn_val,
ms.crit_val,
a.metric_value,
a.evidence_desc,
ag.agent_no,
ag.agent_nm,
a.plant_cd,
a.wc_cd,
w.wc_nm,
a.eqpt_cd,
e.eqpt_nm,
a.mold_cd,
m.mold_nm,
a.item_cd,
i.item_nm,
a.defect_cd,
md.defect_nm,
a.lot_no,
a.serial_no,
p.model_cd,
a.target_desc,
a.ack_state_cd,
a.ack_user_id,
u.user_nm AS ack_user_nm,
a.ack_at,
a.esc_level,
( SELECT count(*) AS count
FROM ax.tb_alm_send_log sl
WHERE sl.alert_id = a.alert_id) AS send_cnt
FROM ax.tb_alm_alert a
LEFT JOIN ax.tb_alm_cond c ON c.cond_id = a.cond_id
LEFT JOIN ax.tb_met_metric_std ms ON ms.metric_id = a.metric_id
LEFT JOIN ax.tb_ai_agent ag ON ag.agent_id = a.detect_agent_id
LEFT JOIN ax.tb_sys_code sv ON sv.group_cd::text = 'ALM_SEVERITY'::text AND sv.code::text = a.severity_cd::text
LEFT JOIN mes.tb_md_workcenter w ON w.plant_cd::text = a.plant_cd::text AND w.wc_cd::text = a.wc_cd::text
LEFT JOIN mes.tb_md_eqpt e ON e.plant_cd::text = a.plant_cd::text AND e.eqpt_cd::text = a.eqpt_cd::text
LEFT JOIN mes.tb_md_mold m ON m.plant_cd::text = a.plant_cd::text AND m.mold_cd::text = a.mold_cd::text
LEFT JOIN mes.tb_md_item i ON i.plant_cd::text = a.plant_cd::text AND i.item_cd::text = a.item_cd::text
LEFT JOIN mes.tb_md_defect md ON md.plant_cd::text = a.plant_cd::text AND md.defect_cd::text = a.defect_cd::text
LEFT JOIN ax.tb_prod_item_map im ON im.plant_cd::text = a.plant_cd::text AND im.item_cd::text = a.item_cd::text
LEFT JOIN ax.tb_prod_product p ON p.product_id = im.product_id
LEFT JOIN ax.tb_sys_user u ON u.user_id::text = a.ack_user_id::text;

CREATE OR REPLACE VIEW ax.vw_defect_detail AS
SELECT dh.plant_cd,
dh.wc_cd,
w.wc_nm,
dh.lot_no,
dh.serial_no,
dh.defect_cd,
md.defect_nm,
md.use_flg AS defect_use_flg,
dbi.grade,
dbi.yield_flg,
dbi.sort_seq,
dh.item_cd,
i.item_nm,
p.model_cd,
pf.family_nm,
t.tag_nm AS ai_defect_tag,
dh.qty,
l.normal,
l.defect AS lot_defect_qty,
l.eqpt_cd,
e.eqpt_nm,
l.mold_cd,
(dh.ins_date AT TIME ZONE 'Asia/Seoul'::text) AS defect_reg_at,
dh.ins_user AS defect_ins_user,
u.user_nm AS defect_ins_user_nm,
dh.remark
FROM mes.tb_pop_defect_hist dh
LEFT JOIN mes.tb_md_defect md ON md.plant_cd::text = dh.plant_cd::text AND md.defect_cd::text = dh.defect_cd::text
LEFT JOIN mes.tb_md_workcenter w ON w.plant_cd::text = dh.plant_cd::text AND w.wc_cd::text = dh.wc_cd::text
LEFT JOIN mes.tb_md_item i ON i.plant_cd::text = dh.plant_cd::text AND i.item_cd::text = dh.item_cd::text
LEFT JOIN mes.tb_md_defect_by_item dbi ON dbi.plant_cd::text = dh.plant_cd::text AND dbi.wc_cd::text = dh.wc_cd::text AND dbi.item_cd::text = dh.item_cd::text AND dbi.defect_cd::text = dh.defect_cd::text
LEFT JOIN mes.tb_pop_label_hist l ON l.plant_cd::text = dh.plant_cd::text AND l.wc_cd::text = dh.wc_cd::text AND l.lot_no::text = dh.lot_no::text AND l.serial_no::text = dh.serial_no::text
LEFT JOIN mes.tb_md_eqpt e ON e.plant_cd::text = l.plant_cd::text AND e.eqpt_cd::text = l.eqpt_cd::text
LEFT JOIN ax.tb_ai_defect_tag_map tm ON tm.plant_cd::text = dh.plant_cd::text AND tm.defect_cd::text = dh.defect_cd::text
LEFT JOIN ax.tb_ai_defect_tag t ON t.tag_id = tm.tag_id
LEFT JOIN ax.tb_prod_item_map im ON im.plant_cd::text = dh.plant_cd::text AND im.item_cd::text = dh.item_cd::text
LEFT JOIN ax.tb_prod_product p ON p.product_id = im.product_id
LEFT JOIN ax.tb_prod_family pf ON pf.family_id = p.family_id
LEFT JOIN ax.tb_sys_user u ON u.user_id::text = dh.ins_user::text;

CREATE OR REPLACE VIEW ax.vw_eqpt_operator AS
SELECT eu.plant_cd,
eu.wc_cd,
w.wc_nm,
eu.eqpt_cd,
e.eqpt_nm,
e.use_flg AS eqpt_use_flg,
ebw.start_flg,
eu.user_id,
u.user_nm,
u.user_state_cd,
d.dept_nm,
u.position_cd,
(eu.ins_date AT TIME ZONE 'Asia/Seoul'::text) AS assigned_at
FROM mes.tb_md_eqpt_by_user eu
LEFT JOIN mes.tb_md_workcenter w ON w.plant_cd::text = eu.plant_cd::text AND w.wc_cd::text = eu.wc_cd::text
LEFT JOIN mes.tb_md_eqpt e ON e.plant_cd::text = eu.plant_cd::text AND e.eqpt_cd::text = eu.eqpt_cd::text
LEFT JOIN mes.tb_md_eqpt_by_workcenter ebw ON ebw.plant_cd::text = eu.plant_cd::text AND ebw.wc_cd::text = eu.wc_cd::text AND ebw.eqpt_cd::text = eu.eqpt_cd::text
LEFT JOIN ax.tb_sys_user u ON u.user_id::text = eu.user_id::text
LEFT JOIN ax.tb_sys_dept d ON d.dept_id = u.dept_id;

CREATE OR REPLACE VIEW ax.vw_metric_value_dim AS
SELECT v.value_id,
v.measured_at,
ms.metric_cd,
ms.metric_nm,
ms.cat_cd,
ms.unit_cd,
ms.window_cd,
ms.std_val,
ms.warn_val,
ms.crit_val,
ms.use_flg AS metric_use_flg,
v.metric_value,
v.judge_cd,
v.src_cd,
v.plant_cd,
v.wc_cd,
w.wc_nm,
v.eqpt_cd,
e.eqpt_nm,
e.use_flg AS eqpt_use_flg,
v.mold_cd,
m.mold_nm,
v.item_cd,
i.item_nm,
i.item_acct,
v.lot_no,
v.serial_no,
p.model_cd,
pf.family_nm,
od.dept_nm AS owner_dept_nm
FROM ax.tb_met_metric_value v
JOIN ax.tb_met_metric_std ms ON ms.metric_id = v.metric_id
LEFT JOIN ax.tb_sys_dept od ON od.dept_id = ms.owner_dept_id
LEFT JOIN mes.tb_md_workcenter w ON w.plant_cd::text = v.plant_cd::text AND w.wc_cd::text = v.wc_cd::text
LEFT JOIN mes.tb_md_eqpt e ON e.plant_cd::text = v.plant_cd::text AND e.eqpt_cd::text = v.eqpt_cd::text
LEFT JOIN mes.tb_md_mold m ON m.plant_cd::text = v.plant_cd::text AND m.mold_cd::text = v.mold_cd::text
LEFT JOIN mes.tb_md_item i ON i.plant_cd::text = v.plant_cd::text AND i.item_cd::text = v.item_cd::text
LEFT JOIN ax.tb_prod_item_map im ON im.plant_cd::text = v.plant_cd::text AND im.item_cd::text = v.item_cd::text
LEFT JOIN ax.tb_prod_product p ON p.product_id = im.product_id
LEFT JOIN ax.tb_prod_family pf ON pf.family_id = p.family_id;

CREATE OR REPLACE VIEW ax.vw_sync_status AS
SELECT sm.map_id,
(((sm.src_db::text || '.'::text) || sm.src_schema::text) || '.'::text) || sm.src_table::text AS src_full,
(sm.tgt_schema::text || '.'::text) || sm.tgt_table::text AS tgt_full,
sm.sync_kind_cd,
sm.schedule_desc,
sm.key_columns,
sm.cdc_column,
sm.cumulative_rows,
sm.last_sync_at,
sm.use_flg,
j.job_id,
j.started_at,
j.ended_at,
j.duration_sec,
j.target_rows,
j.ok_rows,
j.ng_rows,
j.state_cd,
j.checksum_match,
j.retry_cnt,
( SELECT count(*) AS count
FROM ax.tb_sync_job_error je
WHERE je.job_id::text = j.job_id::text AND NOT je.resolved) AS open_error_cnt,
(EXISTS ( SELECT 1
FROM information_schema.tables t
WHERE t.table_schema::name = sm.tgt_schema::text AND t.table_name::name = sm.tgt_table::text)) AS tgt_table_exists
FROM ax.tb_sync_map sm
LEFT JOIN LATERAL ( SELECT jj.job_id,
jj.map_id,
jj.sync_kind_cd,
jj.started_at,
jj.ended_at,
jj.duration_sec,
jj.target_rows,
jj.ok_rows,
jj.ng_rows,
jj.state_cd,
jj.checksum_match,
jj.retry_cnt,
jj.triggered_by_cd,
jj.triggered_by,
jj.remark
FROM ax.tb_sync_job jj
WHERE jj.map_id = sm.map_id
ORDER BY jj.started_at DESC
LIMIT 1) j ON true;

CREATE OR REPLACE VIEW ax.vw_user_menu_perm AS
SELECT u.user_id,
u.user_nm,
d.dept_id,
d.dept_nm,
mn.menu_id,
mn.menu_nm,
mg.group_nm,
mn.is_sub_page,
CASE
WHEN d.is_super_admin THEN true
ELSE COALESCE(pm.can_read, false)
END AS can_read,
CASE
WHEN d.is_super_admin THEN true
ELSE COALESCE(pm.can_write, false)
END AS can_write
FROM ax.tb_sys_user u
JOIN ax.tb_sys_dept d ON d.dept_id = u.dept_id
CROSS JOIN ax.tb_sys_menu mn
JOIN ax.tb_sys_menu_group mg ON mg.group_id::text = mn.group_id::text
LEFT JOIN ax.tb_sys_dept_menu_perm pm ON pm.dept_id = d.dept_id AND pm.menu_id::text = mn.menu_id::text
WHERE u.user_state_cd::text = 'ACTIVE'::text AND mn.use_flg::bpchar = 'Y'::bpchar;

COMMIT;

\echo ''
\echo '-- 복원 결과 (뷰 6 · 표 1 · 인덱스 8 이어야 정상) --'
SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE n.nspname='ax' AND c.relkind='v'
           AND c.relname IN ('vw_alert_context','vw_defect_detail','vw_eqpt_operator',
                             'vw_metric_value_dim','vw_sync_status','vw_user_menu_perm'))  AS "뷰",
       (SELECT count(*) FROM pg_class WHERE relname='tb_prod_item_price')                  AS "표",
       (SELECT count(*) FROM pg_class
         WHERE relname IN ('ix_prod_product_family','ix_doc_chunk_doc','ix_ai_asset_kind',
                           'ix_serving_profile_cd','ix_prod_day_target_lookup',
                           'ix_pop_defect_item','ix_pop_label_type','ix_pop_defect_defect')) AS "인덱스";
\echo ''
\echo '==================== 되돌리기 완료 ===================='
\echo ''
