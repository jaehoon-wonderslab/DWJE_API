-- =====================================================================================
--  V40 되돌리기 — 채운 컬럼 주석을 도로 비운다 (2026-09-22)
--
--  ┌─ 실행 방법 ──────────────────────────────────────────────────────────────────────┐
--  │   psql -h localhost -U dwje_local -d dwjedb -f rollback/V40__down.sql             │
--  │   · BEGIN/COMMIT 을 직접 들고 있다. 두 번 실행해도 안전하다.                       │
--  └──────────────────────────────────────────────────────────────────────────────────┘
--
--  V40 이 채운 750 컬럼은 적용 전에 모두 주석이 비어 있었다. 그래서 되돌리기는
--  그 컬럼들을 다시 NULL 로 만드는 것이다. V39 이전부터 있던 주석은 손대지 않는다.
-- =====================================================================================

\set ON_ERROR_STOP on

-- ── 선행 확인 ───────────────────────────────────────────────────────────────────────
--  주석을 다는 표가 하나라도 없으면 멈춘다. 어느 표가 없는지 한 번에 알려 준다.
--  V35(알림 엔진 스키마)를 건너뛴 서버에서 이 파일만 돌리면 여기서 걸린다.
DO $v40$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(t, ', ' ORDER BY t) INTO missing
      FROM unnest(ARRAY['ax.tb_ai_agent',
                        'ax.tb_ai_agent_run',
                        'ax.tb_ai_chat_agent',
                        'ax.tb_ai_chat_log',
                        'ax.tb_ai_chat_term',
                        'ax.tb_ai_corpus_snapshot',
                        'ax.tb_ai_defect_tag',
                        'ax.tb_ai_defect_tag_map',
                        'ax.tb_ai_model_asset',
                        'ax.tb_ai_model_config',
                        'ax.tb_ai_serving_asset',
                        'ax.tb_ai_serving_profile',
                        'ax.tb_ai_serving_route',
                        'ax.tb_alm_alert',
                        'ax.tb_alm_cond',
                        'ax.tb_alm_cond_channel',
                        'ax.tb_alm_cond_escalation',
                        'ax.tb_alm_cond_group',
                        'ax.tb_alm_cond_state',
                        'ax.tb_alm_cond_target',
                        'ax.tb_alm_escalation_rule',
                        'ax.tb_alm_eval_run',
                        'ax.tb_alm_recip_group',
                        'ax.tb_alm_recip_group_channel',
                        'ax.tb_alm_recip_group_member',
                        'ax.tb_alm_recipient',
                        'ax.tb_alm_send_log',
                        'ax.tb_alm_send_queue',
                        'ax.tb_aoi_defect_image',
                        'ax.tb_dash_upload_doc',
                        'ax.tb_dash_upload_ver',
                        'ax.tb_gls_domain',
                        'ax.tb_gls_term',
                        'ax.tb_gls_variant',
                        'ax.tb_log_audit',
                        'ax.tb_met_metric_collect',
                        'ax.tb_met_metric_source',
                        'ax.tb_met_metric_std',
                        'ax.tb_met_metric_value',
                        'ax.tb_prod_customer',
                        'ax.tb_prod_daily_decision',
                        'ax.tb_prod_day_target',
                        'ax.tb_prod_downtime',
                        'ax.tb_prod_family',
                        'ax.tb_prod_item_map',
                        'ax.tb_prod_product',
                        'ax.tb_prod_project',
                        'ax.tb_prod_ship_plan',
                        'ax.tb_qc_lrr_notice',
                        'ax.tb_rpt_download_blind',
                        'ax.tb_rpt_download_log',
                        'ax.tb_rpt_form',
                        'ax.tb_rpt_form_field',
                        'ax.tb_rpt_report',
                        'ax.tb_rpt_unmask_req',
                        'ax.tb_rpt_usage',
                        'ax.tb_rpt_write_state',
                        'ax.tb_sync_job',
                        'ax.tb_sync_job_error',
                        'ax.tb_sync_map',
                        'ax.tb_sync_run',
                        'ax.tb_sync_schema_drift',
                        'ax.tb_sys_code',
                        'ax.tb_sys_code_group',
                        'ax.tb_sys_code_ref',
                        'ax.tb_sys_data_field',
                        'ax.tb_sys_data_field_attr',
                        'ax.tb_sys_dept',
                        'ax.tb_sys_dept_data_perm',
                        'ax.tb_sys_dept_menu_perm',
                        'ax.tb_sys_email_verify',
                        'ax.tb_sys_login_hist',
                        'ax.tb_sys_menu',
                        'ax.tb_sys_menu_group',
                        'ax.tb_sys_perm_log',
                        'ax.tb_sys_plant',
                        'ax.tb_sys_user',
                        'ax.tb_sys_user_favorite',
                        'ax.tb_sys_user_menu_grant',
                        'vec.tb_code_ref',
                        'vec.tb_doc',
                        'vec.tb_doc_chunk',
                        'vec.tb_doc_chunk_embed_ext',
                        'vec.tb_doc_data_field',
                        'vec.tb_doc_dept_perm',
                        'vec.tb_doc_entity',
                        'vec.tb_doc_version',
                        'vec.tb_embed_model',
                        'vec.tb_ingest_error',
                        'vec.tb_ingest_job',
                        'vec.tb_query_hit',
                        'vec.tb_query_log',
                        'vec.tb_term_embedding']) AS t
     WHERE to_regclass(t) IS NULL;
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION '다음 표가 없어 주석을 달 수 없습니다: %  — 선행 마이그레이션(특히 V35 알림 엔진 스키마)을 먼저 적용하십시오.', missing;
    END IF;
END
$v40$;

BEGIN;

-- ── ax.tb_ai_agent (5)
COMMENT ON COLUMN ax.tb_ai_agent.agent_id   IS NULL;
COMMENT ON COLUMN ax.tb_ai_agent.agent_nm   IS NULL;
COMMENT ON COLUMN ax.tb_ai_agent.agent_desc IS NULL;
COMMENT ON COLUMN ax.tb_ai_agent.sort_seq   IS NULL;
COMMENT ON COLUMN ax.tb_ai_agent.use_flg    IS NULL;

-- ── ax.tb_ai_agent_run (7)
COMMENT ON COLUMN ax.tb_ai_agent_run.run_id         IS NULL;
COMMENT ON COLUMN ax.tb_ai_agent_run.agent_id       IS NULL;
COMMENT ON COLUMN ax.tb_ai_agent_run.run_at         IS NULL;
COMMENT ON COLUMN ax.tb_ai_agent_run.throughput_txt IS NULL;
COMMENT ON COLUMN ax.tb_ai_agent_run.elapsed_ms     IS NULL;
COMMENT ON COLUMN ax.tb_ai_agent_run.message        IS NULL;
COMMENT ON COLUMN ax.tb_ai_agent_run.err_flg        IS NULL;

-- ── ax.tb_ai_chat_agent (4)
COMMENT ON COLUMN ax.tb_ai_chat_agent.chat_id    IS NULL;
COMMENT ON COLUMN ax.tb_ai_chat_agent.agent_id   IS NULL;
COMMENT ON COLUMN ax.tb_ai_chat_agent.call_seq   IS NULL;
COMMENT ON COLUMN ax.tb_ai_chat_agent.elapsed_ms IS NULL;

-- ── ax.tb_ai_chat_log (12)
COMMENT ON COLUMN ax.tb_ai_chat_log.chat_id      IS NULL;
COMMENT ON COLUMN ax.tb_ai_chat_log.session_id   IS NULL;
COMMENT ON COLUMN ax.tb_ai_chat_log.asked_at     IS NULL;
COMMENT ON COLUMN ax.tb_ai_chat_log.user_id      IS NULL;
COMMENT ON COLUMN ax.tb_ai_chat_log.dept_nm      IS NULL;
COMMENT ON COLUMN ax.tb_ai_chat_log.question     IS NULL;
COMMENT ON COLUMN ax.tb_ai_chat_log.intent_cd    IS NULL;
COMMENT ON COLUMN ax.tb_ai_chat_log.intent_nm    IS NULL;
COMMENT ON COLUMN ax.tb_ai_chat_log.answer       IS NULL;
COMMENT ON COLUMN ax.tb_ai_chat_log.response_ms  IS NULL;
COMMENT ON COLUMN ax.tb_ai_chat_log.is_reask     IS NULL;
COMMENT ON COLUMN ax.tb_ai_chat_log.prev_chat_id IS NULL;

-- ── ax.tb_ai_chat_term (3)
COMMENT ON COLUMN ax.tb_ai_chat_term.chat_id    IS NULL;
COMMENT ON COLUMN ax.tb_ai_chat_term.term_id    IS NULL;
COMMENT ON COLUMN ax.tb_ai_chat_term.variant_id IS NULL;

-- ── ax.tb_ai_corpus_snapshot (12)
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.snapshot_id    IS NULL;
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.snapshot_nm    IS NULL;
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.embed_asset_id IS NULL;
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.doc_cnt        IS NULL;
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.token_cnt      IS NULL;
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.built_started  IS NULL;
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.built_ended    IS NULL;
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.remark         IS NULL;
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.ins_date       IS NULL;
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.ins_user       IS NULL;
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.upd_date       IS NULL;
COMMENT ON COLUMN ax.tb_ai_corpus_snapshot.upd_user       IS NULL;

-- ── ax.tb_ai_defect_tag (4)
COMMENT ON COLUMN ax.tb_ai_defect_tag.tag_id   IS NULL;
COMMENT ON COLUMN ax.tb_ai_defect_tag.tag_nm   IS NULL;
COMMENT ON COLUMN ax.tb_ai_defect_tag.tag_desc IS NULL;
COMMENT ON COLUMN ax.tb_ai_defect_tag.use_flg  IS NULL;

-- ── ax.tb_ai_defect_tag_map (5)
COMMENT ON COLUMN ax.tb_ai_defect_tag_map.tag_id    IS NULL;
COMMENT ON COLUMN ax.tb_ai_defect_tag_map.plant_cd  IS NULL;
COMMENT ON COLUMN ax.tb_ai_defect_tag_map.defect_cd IS NULL;
COMMENT ON COLUMN ax.tb_ai_defect_tag_map.ins_date  IS NULL;
COMMENT ON COLUMN ax.tb_ai_defect_tag_map.ins_user  IS NULL;

-- ── ax.tb_ai_model_asset (11)
COMMENT ON COLUMN ax.tb_ai_model_asset.asset_id      IS NULL;
COMMENT ON COLUMN ax.tb_ai_model_asset.asset_nm      IS NULL;
COMMENT ON COLUMN ax.tb_ai_model_asset.artifact_size IS NULL;
COMMENT ON COLUMN ax.tb_ai_model_asset.base_model    IS NULL;
COMMENT ON COLUMN ax.tb_ai_model_asset.lora_rank     IS NULL;
COMMENT ON COLUMN ax.tb_ai_model_asset.max_tokens    IS NULL;
COMMENT ON COLUMN ax.tb_ai_model_asset.remark        IS NULL;
COMMENT ON COLUMN ax.tb_ai_model_asset.ins_date      IS NULL;
COMMENT ON COLUMN ax.tb_ai_model_asset.ins_user      IS NULL;
COMMENT ON COLUMN ax.tb_ai_model_asset.upd_date      IS NULL;
COMMENT ON COLUMN ax.tb_ai_model_asset.upd_user      IS NULL;

-- ── ax.tb_ai_model_config (12)
COMMENT ON COLUMN ax.tb_ai_model_config.config_id    IS NULL;
COMMENT ON COLUMN ax.tb_ai_model_config.agent_id     IS NULL;
COMMENT ON COLUMN ax.tb_ai_model_config.config_key   IS NULL;
COMMENT ON COLUMN ax.tb_ai_model_config.config_nm    IS NULL;
COMMENT ON COLUMN ax.tb_ai_model_config.config_value IS NULL;
COMMENT ON COLUMN ax.tb_ai_model_config.unit         IS NULL;
COMMENT ON COLUMN ax.tb_ai_model_config.description  IS NULL;
COMMENT ON COLUMN ax.tb_ai_model_config.use_flg      IS NULL;
COMMENT ON COLUMN ax.tb_ai_model_config.ins_date     IS NULL;
COMMENT ON COLUMN ax.tb_ai_model_config.ins_user     IS NULL;
COMMENT ON COLUMN ax.tb_ai_model_config.upd_date     IS NULL;
COMMENT ON COLUMN ax.tb_ai_model_config.upd_user     IS NULL;

-- ── ax.tb_ai_serving_asset (5)
COMMENT ON COLUMN ax.tb_ai_serving_asset.profile_id IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_asset.asset_id   IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_asset.remark     IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_asset.ins_date   IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_asset.ins_user   IS NULL;

-- ── ax.tb_ai_serving_profile (25)
COMMENT ON COLUMN ax.tb_ai_serving_profile.profile_id         IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.version_no         IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.profile_nm         IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.description        IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.temperature        IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.top_p              IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.max_tokens         IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.search_top_k       IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.search_candidate_k IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.rrf_k              IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.rerank_flg         IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.eval_score         IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.eval_baseline      IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.eval_json          IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.canary_at          IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.activated_at       IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.retired_at         IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.rollback_at        IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.rollback_reason    IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.activated_by       IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.remark             IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.ins_date           IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.ins_user           IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.upd_date           IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_profile.upd_user           IS NULL;

-- ── ax.tb_ai_serving_route (12)
COMMENT ON COLUMN ax.tb_ai_serving_route.route_id   IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_route.service_cd IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_route.dept_id    IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_route.user_id    IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_route.profile_id IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_route.valid_from IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_route.use_flg    IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_route.remark     IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_route.ins_date   IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_route.ins_user   IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_route.upd_date   IS NULL;
COMMENT ON COLUMN ax.tb_ai_serving_route.upd_user   IS NULL;

-- ── ax.tb_alm_alert (19)
COMMENT ON COLUMN ax.tb_alm_alert.alert_id      IS NULL;
COMMENT ON COLUMN ax.tb_alm_alert.cond_id       IS NULL;
COMMENT ON COLUMN ax.tb_alm_alert.metric_id     IS NULL;
COMMENT ON COLUMN ax.tb_alm_alert.severity_cd   IS NULL;
COMMENT ON COLUMN ax.tb_alm_alert.title         IS NULL;
COMMENT ON COLUMN ax.tb_alm_alert.occurred_at   IS NULL;
COMMENT ON COLUMN ax.tb_alm_alert.metric_value  IS NULL;
COMMENT ON COLUMN ax.tb_alm_alert.threshold_val IS NULL;
COMMENT ON COLUMN ax.tb_alm_alert.plant_cd      IS NULL;
COMMENT ON COLUMN ax.tb_alm_alert.wc_cd         IS NULL;
COMMENT ON COLUMN ax.tb_alm_alert.mold_cd       IS NULL;
COMMENT ON COLUMN ax.tb_alm_alert.item_cd       IS NULL;
COMMENT ON COLUMN ax.tb_alm_alert.serial_no     IS NULL;
COMMENT ON COLUMN ax.tb_alm_alert.product_id    IS NULL;
COMMENT ON COLUMN ax.tb_alm_alert.target_desc   IS NULL;
COMMENT ON COLUMN ax.tb_alm_alert.ack_user_id   IS NULL;
COMMENT ON COLUMN ax.tb_alm_alert.ack_at        IS NULL;
COMMENT ON COLUMN ax.tb_alm_alert.ack_note      IS NULL;
COMMENT ON COLUMN ax.tb_alm_alert.ins_date      IS NULL;

-- ── ax.tb_alm_cond (10)
COMMENT ON COLUMN ax.tb_alm_cond.cond_id        IS NULL;
COMMENT ON COLUMN ax.tb_alm_cond.cond_nm        IS NULL;
COMMENT ON COLUMN ax.tb_alm_cond.threshold_unit IS NULL;
COMMENT ON COLUMN ax.tb_alm_cond.target_desc    IS NULL;
COMMENT ON COLUMN ax.tb_alm_cond.window_cd      IS NULL;
COMMENT ON COLUMN ax.tb_alm_cond.use_flg        IS NULL;
COMMENT ON COLUMN ax.tb_alm_cond.ins_date       IS NULL;
COMMENT ON COLUMN ax.tb_alm_cond.ins_user       IS NULL;
COMMENT ON COLUMN ax.tb_alm_cond.upd_date       IS NULL;
COMMENT ON COLUMN ax.tb_alm_cond.upd_user       IS NULL;

-- ── ax.tb_alm_cond_channel (2)
COMMENT ON COLUMN ax.tb_alm_cond_channel.cond_id    IS NULL;
COMMENT ON COLUMN ax.tb_alm_cond_channel.channel_cd IS NULL;

-- ── ax.tb_alm_cond_escalation (3)
COMMENT ON COLUMN ax.tb_alm_cond_escalation.cond_id     IS NULL;
COMMENT ON COLUMN ax.tb_alm_cond_escalation.esc_rule_id IS NULL;
COMMENT ON COLUMN ax.tb_alm_cond_escalation.is_on       IS NULL;

-- ── ax.tb_alm_cond_group (2)
COMMENT ON COLUMN ax.tb_alm_cond_group.cond_id  IS NULL;
COMMENT ON COLUMN ax.tb_alm_cond_group.group_id IS NULL;

-- ── ax.tb_alm_cond_state (5)
COMMENT ON COLUMN ax.tb_alm_cond_state.cond_id       IS NULL;
COMMENT ON COLUMN ax.tb_alm_cond_state.last_value    IS NULL;
COMMENT ON COLUMN ax.tb_alm_cond_state.last_eval_at  IS NULL;
COMMENT ON COLUMN ax.tb_alm_cond_state.last_alert_id IS NULL;
COMMENT ON COLUMN ax.tb_alm_cond_state.upd_date      IS NULL;

-- ── ax.tb_alm_cond_target (1)
COMMENT ON COLUMN ax.tb_alm_cond_target.cond_id IS NULL;

-- ── ax.tb_alm_escalation_rule (8)
COMMENT ON COLUMN ax.tb_alm_escalation_rule.esc_rule_id    IS NULL;
COMMENT ON COLUMN ax.tb_alm_escalation_rule.esc_level      IS NULL;
COMMENT ON COLUMN ax.tb_alm_escalation_rule.level_nm       IS NULL;
COMMENT ON COLUMN ax.tb_alm_escalation_rule.after_min      IS NULL;
COMMENT ON COLUMN ax.tb_alm_escalation_rule.to_target_desc IS NULL;
COMMENT ON COLUMN ax.tb_alm_escalation_rule.to_group_id    IS NULL;
COMMENT ON COLUMN ax.tb_alm_escalation_rule.note           IS NULL;
COMMENT ON COLUMN ax.tb_alm_escalation_rule.use_flg        IS NULL;

-- ── ax.tb_alm_eval_run (12)
COMMENT ON COLUMN ax.tb_alm_eval_run.started_at     IS NULL;
COMMENT ON COLUMN ax.tb_alm_eval_run.ended_at       IS NULL;
COMMENT ON COLUMN ax.tb_alm_eval_run.duration_ms    IS NULL;
COMMENT ON COLUMN ax.tb_alm_eval_run.cond_cnt       IS NULL;
COMMENT ON COLUMN ax.tb_alm_eval_run.raise_cnt      IS NULL;
COMMENT ON COLUMN ax.tb_alm_eval_run.queued_cnt     IS NULL;
COMMENT ON COLUMN ax.tb_alm_eval_run.sent_cnt       IS NULL;
COMMENT ON COLUMN ax.tb_alm_eval_run.fail_cnt       IS NULL;
COMMENT ON COLUMN ax.tb_alm_eval_run.triggered_by   IS NULL;
COMMENT ON COLUMN ax.tb_alm_eval_run.host_name      IS NULL;
COMMENT ON COLUMN ax.tb_alm_eval_run.engine_version IS NULL;
COMMENT ON COLUMN ax.tb_alm_eval_run.message        IS NULL;

-- ── ax.tb_alm_recip_group (8)
COMMENT ON COLUMN ax.tb_alm_recip_group.group_id   IS NULL;
COMMENT ON COLUMN ax.tb_alm_recip_group.group_nm   IS NULL;
COMMENT ON COLUMN ax.tb_alm_recip_group.night_recv IS NULL;
COMMENT ON COLUMN ax.tb_alm_recip_group.use_flg    IS NULL;
COMMENT ON COLUMN ax.tb_alm_recip_group.ins_date   IS NULL;
COMMENT ON COLUMN ax.tb_alm_recip_group.ins_user   IS NULL;
COMMENT ON COLUMN ax.tb_alm_recip_group.upd_date   IS NULL;
COMMENT ON COLUMN ax.tb_alm_recip_group.upd_user   IS NULL;

-- ── ax.tb_alm_recip_group_channel (1)
COMMENT ON COLUMN ax.tb_alm_recip_group_channel.group_id IS NULL;

-- ── ax.tb_alm_recip_group_member (4)
COMMENT ON COLUMN ax.tb_alm_recip_group_member.group_id IS NULL;
COMMENT ON COLUMN ax.tb_alm_recip_group_member.user_id  IS NULL;
COMMENT ON COLUMN ax.tb_alm_recip_group_member.ins_date IS NULL;
COMMENT ON COLUMN ax.tb_alm_recip_group_member.ins_user IS NULL;

-- ── ax.tb_alm_recipient (9)
COMMENT ON COLUMN ax.tb_alm_recipient.user_id      IS NULL;
COMMENT ON COLUMN ax.tb_alm_recipient.email        IS NULL;
COMMENT ON COLUMN ax.tb_alm_recipient.messenger_id IS NULL;
COMMENT ON COLUMN ax.tb_alm_recipient.night_recv   IS NULL;
COMMENT ON COLUMN ax.tb_alm_recipient.remark       IS NULL;
COMMENT ON COLUMN ax.tb_alm_recipient.ins_date     IS NULL;
COMMENT ON COLUMN ax.tb_alm_recipient.ins_user     IS NULL;
COMMENT ON COLUMN ax.tb_alm_recipient.upd_date     IS NULL;
COMMENT ON COLUMN ax.tb_alm_recipient.upd_user     IS NULL;

-- ── ax.tb_alm_send_log (9)
COMMENT ON COLUMN ax.tb_alm_send_log.send_id     IS NULL;
COMMENT ON COLUMN ax.tb_alm_send_log.alert_id    IS NULL;
COMMENT ON COLUMN ax.tb_alm_send_log.group_id    IS NULL;
COMMENT ON COLUMN ax.tb_alm_send_log.user_id     IS NULL;
COMMENT ON COLUMN ax.tb_alm_send_log.channel_cd  IS NULL;
COMMENT ON COLUMN ax.tb_alm_send_log.dest_addr   IS NULL;
COMMENT ON COLUMN ax.tb_alm_send_log.sent_at     IS NULL;
COMMENT ON COLUMN ax.tb_alm_send_log.fail_reason IS NULL;
COMMENT ON COLUMN ax.tb_alm_send_log.esc_level   IS NULL;

-- ── ax.tb_alm_send_queue (12)
COMMENT ON COLUMN ax.tb_alm_send_queue.queue_id   IS NULL;
COMMENT ON COLUMN ax.tb_alm_send_queue.alert_id   IS NULL;
COMMENT ON COLUMN ax.tb_alm_send_queue.group_id   IS NULL;
COMMENT ON COLUMN ax.tb_alm_send_queue.user_id    IS NULL;
COMMENT ON COLUMN ax.tb_alm_send_queue.channel_cd IS NULL;
COMMENT ON COLUMN ax.tb_alm_send_queue.dest_addr  IS NULL;
COMMENT ON COLUMN ax.tb_alm_send_queue.subject    IS NULL;
COMMENT ON COLUMN ax.tb_alm_send_queue.esc_level  IS NULL;
COMMENT ON COLUMN ax.tb_alm_send_queue.try_cnt    IS NULL;
COMMENT ON COLUMN ax.tb_alm_send_queue.locked_at  IS NULL;
COMMENT ON COLUMN ax.tb_alm_send_queue.last_error IS NULL;
COMMENT ON COLUMN ax.tb_alm_send_queue.ins_date   IS NULL;

-- ── ax.tb_aoi_defect_image (5)
COMMENT ON COLUMN ax.tb_aoi_defect_image.image_id  IS NULL;
COMMENT ON COLUMN ax.tb_aoi_defect_image.seq       IS NULL;
COMMENT ON COLUMN ax.tb_aoi_defect_image.file_size IS NULL;
COMMENT ON COLUMN ax.tb_aoi_defect_image.ins_date  IS NULL;
COMMENT ON COLUMN ax.tb_aoi_defect_image.ins_user  IS NULL;

-- ── ax.tb_dash_upload_doc (6)
COMMENT ON COLUMN ax.tb_dash_upload_doc.doc_id   IS NULL;
COMMENT ON COLUMN ax.tb_dash_upload_doc.title    IS NULL;
COMMENT ON COLUMN ax.tb_dash_upload_doc.memo     IS NULL;
COMMENT ON COLUMN ax.tb_dash_upload_doc.ins_date IS NULL;
COMMENT ON COLUMN ax.tb_dash_upload_doc.upd_date IS NULL;
COMMENT ON COLUMN ax.tb_dash_upload_doc.upd_user IS NULL;

-- ── ax.tb_dash_upload_ver (6)
COMMENT ON COLUMN ax.tb_dash_upload_ver.doc_id       IS NULL;
COMMENT ON COLUMN ax.tb_dash_upload_ver.ver          IS NULL;
COMMENT ON COLUMN ax.tb_dash_upload_ver.file_nm      IS NULL;
COMMENT ON COLUMN ax.tb_dash_upload_ver.file_size    IS NULL;
COMMENT ON COLUMN ax.tb_dash_upload_ver.warning_json IS NULL;
COMMENT ON COLUMN ax.tb_dash_upload_ver.ins_date     IS NULL;

-- ── ax.tb_gls_domain (4)
COMMENT ON COLUMN ax.tb_gls_domain.domain_id IS NULL;
COMMENT ON COLUMN ax.tb_gls_domain.domain_nm IS NULL;
COMMENT ON COLUMN ax.tb_gls_domain.sort_seq  IS NULL;
COMMENT ON COLUMN ax.tb_gls_domain.use_flg   IS NULL;

-- ── ax.tb_gls_term (8)
COMMENT ON COLUMN ax.tb_gls_term.term_id   IS NULL;
COMMENT ON COLUMN ax.tb_gls_term.term      IS NULL;
COMMENT ON COLUMN ax.tb_gls_term.domain_id IS NULL;
COMMENT ON COLUMN ax.tb_gls_term.use_flg   IS NULL;
COMMENT ON COLUMN ax.tb_gls_term.ins_date  IS NULL;
COMMENT ON COLUMN ax.tb_gls_term.ins_user  IS NULL;
COMMENT ON COLUMN ax.tb_gls_term.upd_date  IS NULL;
COMMENT ON COLUMN ax.tb_gls_term.upd_user  IS NULL;

-- ── ax.tb_gls_variant (6)
COMMENT ON COLUMN ax.tb_gls_variant.variant_id IS NULL;
COMMENT ON COLUMN ax.tb_gls_variant.term_id    IS NULL;
COMMENT ON COLUMN ax.tb_gls_variant.word       IS NULL;
COMMENT ON COLUMN ax.tb_gls_variant.note       IS NULL;
COMMENT ON COLUMN ax.tb_gls_variant.reg_at     IS NULL;
COMMENT ON COLUMN ax.tb_gls_variant.upd_at     IS NULL;

-- ── ax.tb_log_audit (10)
COMMENT ON COLUMN ax.tb_log_audit.audit_id    IS NULL;
COMMENT ON COLUMN ax.tb_log_audit.log_at      IS NULL;
COMMENT ON COLUMN ax.tb_log_audit.user_id     IS NULL;
COMMENT ON COLUMN ax.tb_log_audit.target_desc IS NULL;
COMMENT ON COLUMN ax.tb_log_audit.remark      IS NULL;
COMMENT ON COLUMN ax.tb_log_audit.ip_addr     IS NULL;
COMMENT ON COLUMN ax.tb_log_audit.plant_cd    IS NULL;
COMMENT ON COLUMN ax.tb_log_audit.wc_cd       IS NULL;
COMMENT ON COLUMN ax.tb_log_audit.serial_no   IS NULL;
COMMENT ON COLUMN ax.tb_log_audit.item_cd     IS NULL;

-- ── ax.tb_met_metric_collect (8)
COMMENT ON COLUMN ax.tb_met_metric_collect.metric_id    IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_collect.interval_sec IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_collect.last_run_at  IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_collect.last_error   IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_collect.ins_date     IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_collect.ins_user     IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_collect.upd_date     IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_collect.upd_user     IS NULL;

-- ── ax.tb_met_metric_source (7)
COMMENT ON COLUMN ax.tb_met_metric_source.metric_id  IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_source.src_seq    IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_source.src_schema IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_source.src_table  IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_source.src_column IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_source.agg_expr   IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_source.remark     IS NULL;

-- ── ax.tb_met_metric_std (11)
COMMENT ON COLUMN ax.tb_met_metric_std.metric_id       IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_std.metric_cd       IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_std.metric_nm       IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_std.apply_alert     IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_std.apply_dashboard IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_std.apply_report    IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_std.use_flg         IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_std.ins_date        IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_std.ins_user        IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_std.upd_date        IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_std.upd_user        IS NULL;

-- ── ax.tb_met_metric_value (11)
COMMENT ON COLUMN ax.tb_met_metric_value.value_id     IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_value.metric_id    IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_value.measured_at  IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_value.metric_value IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_value.plant_cd     IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_value.mold_cd      IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_value.lot_no       IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_value.serial_no    IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_value.product_id   IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_value.remark       IS NULL;
COMMENT ON COLUMN ax.tb_met_metric_value.ins_date     IS NULL;

-- ── ax.tb_prod_customer (8)
COMMENT ON COLUMN ax.tb_prod_customer.customer_id IS NULL;
COMMENT ON COLUMN ax.tb_prod_customer.customer_cd IS NULL;
COMMENT ON COLUMN ax.tb_prod_customer.customer_nm IS NULL;
COMMENT ON COLUMN ax.tb_prod_customer.use_flg     IS NULL;
COMMENT ON COLUMN ax.tb_prod_customer.ins_date    IS NULL;
COMMENT ON COLUMN ax.tb_prod_customer.ins_user    IS NULL;
COMMENT ON COLUMN ax.tb_prod_customer.upd_date    IS NULL;
COMMENT ON COLUMN ax.tb_prod_customer.upd_user    IS NULL;

-- ── ax.tb_prod_daily_decision (5)
COMMENT ON COLUMN ax.tb_prod_daily_decision.target_date IS NULL;
COMMENT ON COLUMN ax.tb_prod_daily_decision.ins_date    IS NULL;
COMMENT ON COLUMN ax.tb_prod_daily_decision.ins_user    IS NULL;
COMMENT ON COLUMN ax.tb_prod_daily_decision.upd_date    IS NULL;
COMMENT ON COLUMN ax.tb_prod_daily_decision.upd_user    IS NULL;

-- ── ax.tb_prod_day_target (7)
COMMENT ON COLUMN ax.tb_prod_day_target.target_id IS NULL;
COMMENT ON COLUMN ax.tb_prod_day_target.plant_cd  IS NULL;
COMMENT ON COLUMN ax.tb_prod_day_target.remark    IS NULL;
COMMENT ON COLUMN ax.tb_prod_day_target.ins_date  IS NULL;
COMMENT ON COLUMN ax.tb_prod_day_target.ins_user  IS NULL;
COMMENT ON COLUMN ax.tb_prod_day_target.upd_date  IS NULL;
COMMENT ON COLUMN ax.tb_prod_day_target.upd_user  IS NULL;

-- ── ax.tb_prod_downtime (16)
COMMENT ON COLUMN ax.tb_prod_downtime.downtime_id      IS NULL;
COMMENT ON COLUMN ax.tb_prod_downtime.plant_cd         IS NULL;
COMMENT ON COLUMN ax.tb_prod_downtime.eqpt_cd          IS NULL;
COMMENT ON COLUMN ax.tb_prod_downtime.wc_cd            IS NULL;
COMMENT ON COLUMN ax.tb_prod_downtime.stop_at          IS NULL;
COMMENT ON COLUMN ax.tb_prod_downtime.resume_at        IS NULL;
COMMENT ON COLUMN ax.tb_prod_downtime.elapsed_min      IS NULL;
COMMENT ON COLUMN ax.tb_prod_downtime.remark           IS NULL;
COMMENT ON COLUMN ax.tb_prod_downtime.agent_confidence IS NULL;
COMMENT ON COLUMN ax.tb_prod_downtime.agent_basis      IS NULL;
COMMENT ON COLUMN ax.tb_prod_downtime.registered_at    IS NULL;
COMMENT ON COLUMN ax.tb_prod_downtime.registered_by    IS NULL;
COMMENT ON COLUMN ax.tb_prod_downtime.ins_date         IS NULL;
COMMENT ON COLUMN ax.tb_prod_downtime.ins_user         IS NULL;
COMMENT ON COLUMN ax.tb_prod_downtime.upd_date         IS NULL;
COMMENT ON COLUMN ax.tb_prod_downtime.upd_user         IS NULL;

-- ── ax.tb_prod_family (7)
COMMENT ON COLUMN ax.tb_prod_family.family_id IS NULL;
COMMENT ON COLUMN ax.tb_prod_family.family_nm IS NULL;
COMMENT ON COLUMN ax.tb_prod_family.use_flg   IS NULL;
COMMENT ON COLUMN ax.tb_prod_family.ins_date  IS NULL;
COMMENT ON COLUMN ax.tb_prod_family.ins_user  IS NULL;
COMMENT ON COLUMN ax.tb_prod_family.upd_date  IS NULL;
COMMENT ON COLUMN ax.tb_prod_family.upd_user  IS NULL;

-- ── ax.tb_prod_item_map (6)
COMMENT ON COLUMN ax.tb_prod_item_map.plant_cd   IS NULL;
COMMENT ON COLUMN ax.tb_prod_item_map.item_cd    IS NULL;
COMMENT ON COLUMN ax.tb_prod_item_map.product_id IS NULL;
COMMENT ON COLUMN ax.tb_prod_item_map.remark     IS NULL;
COMMENT ON COLUMN ax.tb_prod_item_map.ins_date   IS NULL;
COMMENT ON COLUMN ax.tb_prod_item_map.ins_user   IS NULL;

-- ── ax.tb_prod_product (11)
COMMENT ON COLUMN ax.tb_prod_product.product_id  IS NULL;
COMMENT ON COLUMN ax.tb_prod_product.model_nm    IS NULL;
COMMENT ON COLUMN ax.tb_prod_product.family_id   IS NULL;
COMMENT ON COLUMN ax.tb_prod_product.customer_id IS NULL;
COMMENT ON COLUMN ax.tb_prod_product.project_id  IS NULL;
COMMENT ON COLUMN ax.tb_prod_product.def_seq     IS NULL;
COMMENT ON COLUMN ax.tb_prod_product.use_flg     IS NULL;
COMMENT ON COLUMN ax.tb_prod_product.ins_date    IS NULL;
COMMENT ON COLUMN ax.tb_prod_product.ins_user    IS NULL;
COMMENT ON COLUMN ax.tb_prod_product.upd_date    IS NULL;
COMMENT ON COLUMN ax.tb_prod_product.upd_user    IS NULL;

-- ── ax.tb_prod_project (4)
COMMENT ON COLUMN ax.tb_prod_project.project_id IS NULL;
COMMENT ON COLUMN ax.tb_prod_project.project_cd IS NULL;
COMMENT ON COLUMN ax.tb_prod_project.project_nm IS NULL;
COMMENT ON COLUMN ax.tb_prod_project.use_flg    IS NULL;

-- ── ax.tb_prod_ship_plan (13)
COMMENT ON COLUMN ax.tb_prod_ship_plan.plan_id     IS NULL;
COMMENT ON COLUMN ax.tb_prod_ship_plan.plan_year   IS NULL;
COMMENT ON COLUMN ax.tb_prod_ship_plan.plan_month  IS NULL;
COMMENT ON COLUMN ax.tb_prod_ship_plan.product_id  IS NULL;
COMMENT ON COLUMN ax.tb_prod_ship_plan.customer_id IS NULL;
COMMENT ON COLUMN ax.tb_prod_ship_plan.plan_qty    IS NULL;
COMMENT ON COLUMN ax.tb_prod_ship_plan.unit_price  IS NULL;
COMMENT ON COLUMN ax.tb_prod_ship_plan.actual_qty  IS NULL;
COMMENT ON COLUMN ax.tb_prod_ship_plan.remark      IS NULL;
COMMENT ON COLUMN ax.tb_prod_ship_plan.ins_date    IS NULL;
COMMENT ON COLUMN ax.tb_prod_ship_plan.ins_user    IS NULL;
COMMENT ON COLUMN ax.tb_prod_ship_plan.upd_date    IS NULL;
COMMENT ON COLUMN ax.tb_prod_ship_plan.upd_user    IS NULL;

-- ── ax.tb_qc_lrr_notice (18)
COMMENT ON COLUMN ax.tb_qc_lrr_notice.notice_id   IS NULL;
COMMENT ON COLUMN ax.tb_qc_lrr_notice.notice_no   IS NULL;
COMMENT ON COLUMN ax.tb_qc_lrr_notice.customer_id IS NULL;
COMMENT ON COLUMN ax.tb_qc_lrr_notice.product_id  IS NULL;
COMMENT ON COLUMN ax.tb_qc_lrr_notice.plant_cd    IS NULL;
COMMENT ON COLUMN ax.tb_qc_lrr_notice.lot_no      IS NULL;
COMMENT ON COLUMN ax.tb_qc_lrr_notice.item_cd     IS NULL;
COMMENT ON COLUMN ax.tb_qc_lrr_notice.defect_cd   IS NULL;
COMMENT ON COLUMN ax.tb_qc_lrr_notice.defect_txt  IS NULL;
COMMENT ON COLUMN ax.tb_qc_lrr_notice.notice_date IS NULL;
COMMENT ON COLUMN ax.tb_qc_lrr_notice.ship_month  IS NULL;
COMMENT ON COLUMN ax.tb_qc_lrr_notice.lrr_qty     IS NULL;
COMMENT ON COLUMN ax.tb_qc_lrr_notice.ship_qty    IS NULL;
COMMENT ON COLUMN ax.tb_qc_lrr_notice.remark      IS NULL;
COMMENT ON COLUMN ax.tb_qc_lrr_notice.ins_date    IS NULL;
COMMENT ON COLUMN ax.tb_qc_lrr_notice.ins_user    IS NULL;
COMMENT ON COLUMN ax.tb_qc_lrr_notice.upd_date    IS NULL;
COMMENT ON COLUMN ax.tb_qc_lrr_notice.upd_user    IS NULL;

-- ── ax.tb_rpt_download_blind (3)
COMMENT ON COLUMN ax.tb_rpt_download_blind.dl_id     IS NULL;
COMMENT ON COLUMN ax.tb_rpt_download_blind.field_key IS NULL;
COMMENT ON COLUMN ax.tb_rpt_download_blind.cell_cnt  IS NULL;

-- ── ax.tb_rpt_download_log (10)
COMMENT ON COLUMN ax.tb_rpt_download_log.dl_id         IS NULL;
COMMENT ON COLUMN ax.tb_rpt_download_log.downloaded_at IS NULL;
COMMENT ON COLUMN ax.tb_rpt_download_log.user_id       IS NULL;
COMMENT ON COLUMN ax.tb_rpt_download_log.dept_nm       IS NULL;
COMMENT ON COLUMN ax.tb_rpt_download_log.report_id     IS NULL;
COMMENT ON COLUMN ax.tb_rpt_download_log.menu_id       IS NULL;
COMMENT ON COLUMN ax.tb_rpt_download_log.row_cnt       IS NULL;
COMMENT ON COLUMN ax.tb_rpt_download_log.ip_addr       IS NULL;
COMMENT ON COLUMN ax.tb_rpt_download_log.result_cd     IS NULL;
COMMENT ON COLUMN ax.tb_rpt_download_log.file_nm       IS NULL;

-- ── ax.tb_rpt_form (9)
COMMENT ON COLUMN ax.tb_rpt_form.form_id     IS NULL;
COMMENT ON COLUMN ax.tb_rpt_form.form_nm     IS NULL;
COMMENT ON COLUMN ax.tb_rpt_form.customer_id IS NULL;
COMMENT ON COLUMN ax.tb_rpt_form.report_id   IS NULL;
COMMENT ON COLUMN ax.tb_rpt_form.use_flg     IS NULL;
COMMENT ON COLUMN ax.tb_rpt_form.ins_date    IS NULL;
COMMENT ON COLUMN ax.tb_rpt_form.ins_user    IS NULL;
COMMENT ON COLUMN ax.tb_rpt_form.upd_date    IS NULL;
COMMENT ON COLUMN ax.tb_rpt_form.upd_user    IS NULL;

-- ── ax.tb_rpt_form_field (6)
COMMENT ON COLUMN ax.tb_rpt_form_field.form_id     IS NULL;
COMMENT ON COLUMN ax.tb_rpt_form_field.field_seq   IS NULL;
COMMENT ON COLUMN ax.tb_rpt_form_field.field_nm    IS NULL;
COMMENT ON COLUMN ax.tb_rpt_form_field.field_code  IS NULL;
COMMENT ON COLUMN ax.tb_rpt_form_field.is_required IS NULL;
COMMENT ON COLUMN ax.tb_rpt_form_field.remark      IS NULL;

-- ── ax.tb_rpt_report (5)
COMMENT ON COLUMN ax.tb_rpt_report.report_id    IS NULL;
COMMENT ON COLUMN ax.tb_rpt_report.report_nm    IS NULL;
COMMENT ON COLUMN ax.tb_rpt_report.report_group IS NULL;
COMMENT ON COLUMN ax.tb_rpt_report.sort_seq     IS NULL;
COMMENT ON COLUMN ax.tb_rpt_report.use_flg      IS NULL;

-- ── ax.tb_rpt_unmask_req (10)
COMMENT ON COLUMN ax.tb_rpt_unmask_req.req_id         IS NULL;
COMMENT ON COLUMN ax.tb_rpt_unmask_req.menu_id        IS NULL;
COMMENT ON COLUMN ax.tb_rpt_unmask_req.field_keys     IS NULL;
COMMENT ON COLUMN ax.tb_rpt_unmask_req.reason         IS NULL;
COMMENT ON COLUMN ax.tb_rpt_unmask_req.requested_at   IS NULL;
COMMENT ON COLUMN ax.tb_rpt_unmask_req.requester_id   IS NULL;
COMMENT ON COLUMN ax.tb_rpt_unmask_req.requester_dept IS NULL;
COMMENT ON COLUMN ax.tb_rpt_unmask_req.approver_id    IS NULL;
COMMENT ON COLUMN ax.tb_rpt_unmask_req.approved_at    IS NULL;
COMMENT ON COLUMN ax.tb_rpt_unmask_req.approve_note   IS NULL;

-- ── ax.tb_rpt_usage (1)
COMMENT ON COLUMN ax.tb_rpt_usage.user_id IS NULL;

-- ── ax.tb_rpt_write_state (4)
COMMENT ON COLUMN ax.tb_rpt_write_state.ins_date IS NULL;
COMMENT ON COLUMN ax.tb_rpt_write_state.ins_user IS NULL;
COMMENT ON COLUMN ax.tb_rpt_write_state.upd_date IS NULL;
COMMENT ON COLUMN ax.tb_rpt_write_state.upd_user IS NULL;

-- ── ax.tb_sync_job (9)
COMMENT ON COLUMN ax.tb_sync_job.map_id       IS NULL;
COMMENT ON COLUMN ax.tb_sync_job.sync_kind_cd IS NULL;
COMMENT ON COLUMN ax.tb_sync_job.ended_at     IS NULL;
COMMENT ON COLUMN ax.tb_sync_job.duration_sec IS NULL;
COMMENT ON COLUMN ax.tb_sync_job.target_rows  IS NULL;
COMMENT ON COLUMN ax.tb_sync_job.ok_rows      IS NULL;
COMMENT ON COLUMN ax.tb_sync_job.ng_rows      IS NULL;
COMMENT ON COLUMN ax.tb_sync_job.triggered_by IS NULL;
COMMENT ON COLUMN ax.tb_sync_job.remark       IS NULL;

-- ── ax.tb_sync_job_error (8)
COMMENT ON COLUMN ax.tb_sync_job_error.err_id     IS NULL;
COMMENT ON COLUMN ax.tb_sync_job_error.job_id     IS NULL;
COMMENT ON COLUMN ax.tb_sync_job_error.err_seq    IS NULL;
COMMENT ON COLUMN ax.tb_sync_job_error.err_code   IS NULL;
COMMENT ON COLUMN ax.tb_sync_job_error.payload    IS NULL;
COMMENT ON COLUMN ax.tb_sync_job_error.retried_at IS NULL;
COMMENT ON COLUMN ax.tb_sync_job_error.resolved   IS NULL;
COMMENT ON COLUMN ax.tb_sync_job_error.ins_date   IS NULL;

-- ── ax.tb_sync_map (15)
COMMENT ON COLUMN ax.tb_sync_map.map_id        IS NULL;
COMMENT ON COLUMN ax.tb_sync_map.src_db        IS NULL;
COMMENT ON COLUMN ax.tb_sync_map.src_schema    IS NULL;
COMMENT ON COLUMN ax.tb_sync_map.src_table     IS NULL;
COMMENT ON COLUMN ax.tb_sync_map.tgt_schema    IS NULL;
COMMENT ON COLUMN ax.tb_sync_map.tgt_table     IS NULL;
COMMENT ON COLUMN ax.tb_sync_map.schedule_cron IS NULL;
COMMENT ON COLUMN ax.tb_sync_map.last_sync_at  IS NULL;
COMMENT ON COLUMN ax.tb_sync_map.last_job_id   IS NULL;
COMMENT ON COLUMN ax.tb_sync_map.use_flg       IS NULL;
COMMENT ON COLUMN ax.tb_sync_map.remark        IS NULL;
COMMENT ON COLUMN ax.tb_sync_map.ins_date      IS NULL;
COMMENT ON COLUMN ax.tb_sync_map.ins_user      IS NULL;
COMMENT ON COLUMN ax.tb_sync_map.upd_date      IS NULL;
COMMENT ON COLUMN ax.tb_sync_map.upd_user      IS NULL;

-- ── ax.tb_sync_run (11)
COMMENT ON COLUMN ax.tb_sync_run.started_at      IS NULL;
COMMENT ON COLUMN ax.tb_sync_run.ended_at        IS NULL;
COMMENT ON COLUMN ax.tb_sync_run.duration_sec    IS NULL;
COMMENT ON COLUMN ax.tb_sync_run.triggered_by_cd IS NULL;
COMMENT ON COLUMN ax.tb_sync_run.triggered_by    IS NULL;
COMMENT ON COLUMN ax.tb_sync_run.success_cnt     IS NULL;
COMMENT ON COLUMN ax.tb_sync_run.fail_cnt        IS NULL;
COMMENT ON COLUMN ax.tb_sync_run.ok_rows         IS NULL;
COMMENT ON COLUMN ax.tb_sync_run.ng_rows         IS NULL;
COMMENT ON COLUMN ax.tb_sync_run.target_url      IS NULL;
COMMENT ON COLUMN ax.tb_sync_run.engine_version  IS NULL;

-- ── ax.tb_sync_schema_drift (2)
COMMENT ON COLUMN ax.tb_sync_schema_drift.drift_id    IS NULL;
COMMENT ON COLUMN ax.tb_sync_schema_drift.resolved_at IS NULL;

-- ── ax.tb_sys_code (11)
COMMENT ON COLUMN ax.tb_sys_code.group_cd  IS NULL;
COMMENT ON COLUMN ax.tb_sys_code.code      IS NULL;
COMMENT ON COLUMN ax.tb_sys_code.code_nm   IS NULL;
COMMENT ON COLUMN ax.tb_sys_code.code_desc IS NULL;
COMMENT ON COLUMN ax.tb_sys_code.attr2     IS NULL;
COMMENT ON COLUMN ax.tb_sys_code.sort_seq  IS NULL;
COMMENT ON COLUMN ax.tb_sys_code.use_flg   IS NULL;
COMMENT ON COLUMN ax.tb_sys_code.ins_date  IS NULL;
COMMENT ON COLUMN ax.tb_sys_code.ins_user  IS NULL;
COMMENT ON COLUMN ax.tb_sys_code.upd_date  IS NULL;
COMMENT ON COLUMN ax.tb_sys_code.upd_user  IS NULL;

-- ── ax.tb_sys_code_group (8)
COMMENT ON COLUMN ax.tb_sys_code_group.group_nm   IS NULL;
COMMENT ON COLUMN ax.tb_sys_code_group.group_desc IS NULL;
COMMENT ON COLUMN ax.tb_sys_code_group.use_flg    IS NULL;
COMMENT ON COLUMN ax.tb_sys_code_group.sort_seq   IS NULL;
COMMENT ON COLUMN ax.tb_sys_code_group.ins_date   IS NULL;
COMMENT ON COLUMN ax.tb_sys_code_group.ins_user   IS NULL;
COMMENT ON COLUMN ax.tb_sys_code_group.upd_date   IS NULL;
COMMENT ON COLUMN ax.tb_sys_code_group.upd_user   IS NULL;

-- ── ax.tb_sys_code_ref (4)
COMMENT ON COLUMN ax.tb_sys_code_ref.target_table  IS NULL;
COMMENT ON COLUMN ax.tb_sys_code_ref.target_column IS NULL;
COMMENT ON COLUMN ax.tb_sys_code_ref.group_cd      IS NULL;
COMMENT ON COLUMN ax.tb_sys_code_ref.nullable_flg  IS NULL;

-- ── ax.tb_sys_data_field (8)
COMMENT ON COLUMN ax.tb_sys_data_field.field_key  IS NULL;
COMMENT ON COLUMN ax.tb_sys_data_field.field_nm   IS NULL;
COMMENT ON COLUMN ax.tb_sys_data_field.field_desc IS NULL;
COMMENT ON COLUMN ax.tb_sys_data_field.sort_seq   IS NULL;
COMMENT ON COLUMN ax.tb_sys_data_field.ins_date   IS NULL;
COMMENT ON COLUMN ax.tb_sys_data_field.ins_user   IS NULL;
COMMENT ON COLUMN ax.tb_sys_data_field.upd_date   IS NULL;
COMMENT ON COLUMN ax.tb_sys_data_field.upd_user   IS NULL;

-- ── ax.tb_sys_data_field_attr (3)
COMMENT ON COLUMN ax.tb_sys_data_field_attr.field_key IS NULL;
COMMENT ON COLUMN ax.tb_sys_data_field_attr.ins_date  IS NULL;
COMMENT ON COLUMN ax.tb_sys_data_field_attr.ins_user  IS NULL;

-- ── ax.tb_sys_dept (8)
COMMENT ON COLUMN ax.tb_sys_dept.dept_nm   IS NULL;
COMMENT ON COLUMN ax.tb_sys_dept.dept_desc IS NULL;
COMMENT ON COLUMN ax.tb_sys_dept.sort_seq  IS NULL;
COMMENT ON COLUMN ax.tb_sys_dept.use_flg   IS NULL;
COMMENT ON COLUMN ax.tb_sys_dept.ins_date  IS NULL;
COMMENT ON COLUMN ax.tb_sys_dept.ins_user  IS NULL;
COMMENT ON COLUMN ax.tb_sys_dept.upd_date  IS NULL;
COMMENT ON COLUMN ax.tb_sys_dept.upd_user  IS NULL;

-- ── ax.tb_sys_dept_data_perm (7)
COMMENT ON COLUMN ax.tb_sys_dept_data_perm.dept_id    IS NULL;
COMMENT ON COLUMN ax.tb_sys_dept_data_perm.field_key  IS NULL;
COMMENT ON COLUMN ax.tb_sys_dept_data_perm.is_allowed IS NULL;
COMMENT ON COLUMN ax.tb_sys_dept_data_perm.ins_date   IS NULL;
COMMENT ON COLUMN ax.tb_sys_dept_data_perm.ins_user   IS NULL;
COMMENT ON COLUMN ax.tb_sys_dept_data_perm.upd_date   IS NULL;
COMMENT ON COLUMN ax.tb_sys_dept_data_perm.upd_user   IS NULL;

-- ── ax.tb_sys_dept_menu_perm (7)
COMMENT ON COLUMN ax.tb_sys_dept_menu_perm.dept_id  IS NULL;
COMMENT ON COLUMN ax.tb_sys_dept_menu_perm.menu_id  IS NULL;
COMMENT ON COLUMN ax.tb_sys_dept_menu_perm.can_read IS NULL;
COMMENT ON COLUMN ax.tb_sys_dept_menu_perm.ins_date IS NULL;
COMMENT ON COLUMN ax.tb_sys_dept_menu_perm.ins_user IS NULL;
COMMENT ON COLUMN ax.tb_sys_dept_menu_perm.upd_date IS NULL;
COMMENT ON COLUMN ax.tb_sys_dept_menu_perm.upd_user IS NULL;

-- ── ax.tb_sys_email_verify (9)
COMMENT ON COLUMN ax.tb_sys_email_verify.verify_id      IS NULL;
COMMENT ON COLUMN ax.tb_sys_email_verify.email          IS NULL;
COMMENT ON COLUMN ax.tb_sys_email_verify.expires_at     IS NULL;
COMMENT ON COLUMN ax.tb_sys_email_verify.verified_at    IS NULL;
COMMENT ON COLUMN ax.tb_sys_email_verify.consumed_at    IS NULL;
COMMENT ON COLUMN ax.tb_sys_email_verify.send_result_cd IS NULL;
COMMENT ON COLUMN ax.tb_sys_email_verify.fail_reason    IS NULL;
COMMENT ON COLUMN ax.tb_sys_email_verify.ip_addr        IS NULL;
COMMENT ON COLUMN ax.tb_sys_email_verify.ins_date       IS NULL;

-- ── ax.tb_sys_login_hist (6)
COMMENT ON COLUMN ax.tb_sys_login_hist.login_id    IS NULL;
COMMENT ON COLUMN ax.tb_sys_login_hist.login_at    IS NULL;
COMMENT ON COLUMN ax.tb_sys_login_hist.logout_at   IS NULL;
COMMENT ON COLUMN ax.tb_sys_login_hist.fail_reason IS NULL;
COMMENT ON COLUMN ax.tb_sys_login_hist.ip_addr     IS NULL;
COMMENT ON COLUMN ax.tb_sys_login_hist.user_agent  IS NULL;

-- ── ax.tb_sys_menu (9)
COMMENT ON COLUMN ax.tb_sys_menu.menu_nm    IS NULL;
COMMENT ON COLUMN ax.tb_sys_menu.group_id   IS NULL;
COMMENT ON COLUMN ax.tb_sys_menu.route_path IS NULL;
COMMENT ON COLUMN ax.tb_sys_menu.sort_seq   IS NULL;
COMMENT ON COLUMN ax.tb_sys_menu.use_flg    IS NULL;
COMMENT ON COLUMN ax.tb_sys_menu.ins_date   IS NULL;
COMMENT ON COLUMN ax.tb_sys_menu.ins_user   IS NULL;
COMMENT ON COLUMN ax.tb_sys_menu.upd_date   IS NULL;
COMMENT ON COLUMN ax.tb_sys_menu.upd_user   IS NULL;

-- ── ax.tb_sys_menu_group (4)
COMMENT ON COLUMN ax.tb_sys_menu_group.group_id IS NULL;
COMMENT ON COLUMN ax.tb_sys_menu_group.group_nm IS NULL;
COMMENT ON COLUMN ax.tb_sys_menu_group.sort_seq IS NULL;
COMMENT ON COLUMN ax.tb_sys_menu_group.use_flg  IS NULL;

-- ── ax.tb_sys_perm_log (7)
COMMENT ON COLUMN ax.tb_sys_perm_log.log_id         IS NULL;
COMMENT ON COLUMN ax.tb_sys_perm_log.log_at         IS NULL;
COMMENT ON COLUMN ax.tb_sys_perm_log.target_kind_cd IS NULL;
COMMENT ON COLUMN ax.tb_sys_perm_log.target_dept_id IS NULL;
COMMENT ON COLUMN ax.tb_sys_perm_log.target_user_id IS NULL;
COMMENT ON COLUMN ax.tb_sys_perm_log.detail         IS NULL;
COMMENT ON COLUMN ax.tb_sys_perm_log.actor_user_id  IS NULL;

-- ── ax.tb_sys_plant (9)
COMMENT ON COLUMN ax.tb_sys_plant.plant_cd IS NULL;
COMMENT ON COLUMN ax.tb_sys_plant.plant_nm IS NULL;
COMMENT ON COLUMN ax.tb_sys_plant.remark   IS NULL;
COMMENT ON COLUMN ax.tb_sys_plant.sort_seq IS NULL;
COMMENT ON COLUMN ax.tb_sys_plant.use_flg  IS NULL;
COMMENT ON COLUMN ax.tb_sys_plant.ins_date IS NULL;
COMMENT ON COLUMN ax.tb_sys_plant.ins_user IS NULL;
COMMENT ON COLUMN ax.tb_sys_plant.upd_date IS NULL;
COMMENT ON COLUMN ax.tb_sys_plant.upd_user IS NULL;

-- ── ax.tb_sys_user (11)
COMMENT ON COLUMN ax.tb_sys_user.user_nm        IS NULL;
COMMENT ON COLUMN ax.tb_sys_user.dept_id        IS NULL;
COMMENT ON COLUMN ax.tb_sys_user.plant_cd       IS NULL;
COMMENT ON COLUMN ax.tb_sys_user.pwd_hash       IS NULL;
COMMENT ON COLUMN ax.tb_sys_user.pwd_upd_at     IS NULL;
COMMENT ON COLUMN ax.tb_sys_user.login_fail_cnt IS NULL;
COMMENT ON COLUMN ax.tb_sys_user.remark         IS NULL;
COMMENT ON COLUMN ax.tb_sys_user.ins_date       IS NULL;
COMMENT ON COLUMN ax.tb_sys_user.ins_user       IS NULL;
COMMENT ON COLUMN ax.tb_sys_user.upd_date       IS NULL;
COMMENT ON COLUMN ax.tb_sys_user.upd_user       IS NULL;

-- ── ax.tb_sys_user_favorite (2)
COMMENT ON COLUMN ax.tb_sys_user_favorite.user_id  IS NULL;
COMMENT ON COLUMN ax.tb_sys_user_favorite.ins_date IS NULL;

-- ── ax.tb_sys_user_menu_grant (3)
COMMENT ON COLUMN ax.tb_sys_user_menu_grant.ins_date IS NULL;
COMMENT ON COLUMN ax.tb_sys_user_menu_grant.upd_date IS NULL;
COMMENT ON COLUMN ax.tb_sys_user_menu_grant.upd_user IS NULL;

-- ── vec.tb_code_ref (4)
COMMENT ON COLUMN vec.tb_code_ref.target_table  IS NULL;
COMMENT ON COLUMN vec.tb_code_ref.target_column IS NULL;
COMMENT ON COLUMN vec.tb_code_ref.group_cd      IS NULL;
COMMENT ON COLUMN vec.tb_code_ref.nullable_flg  IS NULL;

-- ── vec.tb_doc (26)
COMMENT ON COLUMN vec.tb_doc.doc_id         IS NULL;
COMMENT ON COLUMN vec.tb_doc.title          IS NULL;
COMMENT ON COLUMN vec.tb_doc.source_path    IS NULL;
COMMENT ON COLUMN vec.tb_doc.file_nm        IS NULL;
COMMENT ON COLUMN vec.tb_doc.mime_type      IS NULL;
COMMENT ON COLUMN vec.tb_doc.file_size      IS NULL;
COMMENT ON COLUMN vec.tb_doc.page_cnt       IS NULL;
COMMENT ON COLUMN vec.tb_doc.lang_cd        IS NULL;
COMMENT ON COLUMN vec.tb_doc.report_id      IS NULL;
COMMENT ON COLUMN vec.tb_doc.customer_id    IS NULL;
COMMENT ON COLUMN vec.tb_doc.product_id     IS NULL;
COMMENT ON COLUMN vec.tb_doc.author_user_id IS NULL;
COMMENT ON COLUMN vec.tb_doc.plant_cd       IS NULL;
COMMENT ON COLUMN vec.tb_doc.wc_cd          IS NULL;
COMMENT ON COLUMN vec.tb_doc.eqpt_cd        IS NULL;
COMMENT ON COLUMN vec.tb_doc.mold_cd        IS NULL;
COMMENT ON COLUMN vec.tb_doc.item_cd        IS NULL;
COMMENT ON COLUMN vec.tb_doc.serial_no      IS NULL;
COMMENT ON COLUMN vec.tb_doc.defect_cd      IS NULL;
COMMENT ON COLUMN vec.tb_doc.chunk_cnt      IS NULL;
COMMENT ON COLUMN vec.tb_doc.del_flg        IS NULL;
COMMENT ON COLUMN vec.tb_doc.remark         IS NULL;
COMMENT ON COLUMN vec.tb_doc.ins_date       IS NULL;
COMMENT ON COLUMN vec.tb_doc.ins_user       IS NULL;
COMMENT ON COLUMN vec.tb_doc.upd_date       IS NULL;
COMMENT ON COLUMN vec.tb_doc.upd_user       IS NULL;

-- ── vec.tb_doc_chunk (14)
COMMENT ON COLUMN vec.tb_doc_chunk.chunk_id       IS NULL;
COMMENT ON COLUMN vec.tb_doc_chunk.doc_id         IS NULL;
COMMENT ON COLUMN vec.tb_doc_chunk.doc_ver        IS NULL;
COMMENT ON COLUMN vec.tb_doc_chunk.chunk_seq      IS NULL;
COMMENT ON COLUMN vec.tb_doc_chunk.char_cnt       IS NULL;
COMMENT ON COLUMN vec.tb_doc_chunk.heading        IS NULL;
COMMENT ON COLUMN vec.tb_doc_chunk.embed_model_id IS NULL;
COMMENT ON COLUMN vec.tb_doc_chunk.embedded_at    IS NULL;
COMMENT ON COLUMN vec.tb_doc_chunk.doc_type_cd    IS NULL;
COMMENT ON COLUMN vec.tb_doc_chunk.doc_date       IS NULL;
COMMENT ON COLUMN vec.tb_doc_chunk.plant_cd       IS NULL;
COMMENT ON COLUMN vec.tb_doc_chunk.owner_dept_id  IS NULL;
COMMENT ON COLUMN vec.tb_doc_chunk.scope_cd       IS NULL;
COMMENT ON COLUMN vec.tb_doc_chunk.ins_date       IS NULL;

-- ── vec.tb_doc_chunk_embed_ext (3)
COMMENT ON COLUMN vec.tb_doc_chunk_embed_ext.chunk_id    IS NULL;
COMMENT ON COLUMN vec.tb_doc_chunk_embed_ext.model_id    IS NULL;
COMMENT ON COLUMN vec.tb_doc_chunk_embed_ext.embedded_at IS NULL;

-- ── vec.tb_doc_data_field (3)
COMMENT ON COLUMN vec.tb_doc_data_field.doc_id    IS NULL;
COMMENT ON COLUMN vec.tb_doc_data_field.field_key IS NULL;
COMMENT ON COLUMN vec.tb_doc_data_field.ins_date  IS NULL;

-- ── vec.tb_doc_dept_perm (5)
COMMENT ON COLUMN vec.tb_doc_dept_perm.doc_id   IS NULL;
COMMENT ON COLUMN vec.tb_doc_dept_perm.dept_id  IS NULL;
COMMENT ON COLUMN vec.tb_doc_dept_perm.can_read IS NULL;
COMMENT ON COLUMN vec.tb_doc_dept_perm.ins_date IS NULL;
COMMENT ON COLUMN vec.tb_doc_dept_perm.ins_user IS NULL;

-- ── vec.tb_doc_entity (15)
COMMENT ON COLUMN vec.tb_doc_entity.entity_id   IS NULL;
COMMENT ON COLUMN vec.tb_doc_entity.doc_id      IS NULL;
COMMENT ON COLUMN vec.tb_doc_entity.chunk_id    IS NULL;
COMMENT ON COLUMN vec.tb_doc_entity.plant_cd    IS NULL;
COMMENT ON COLUMN vec.tb_doc_entity.wc_cd       IS NULL;
COMMENT ON COLUMN vec.tb_doc_entity.eqpt_cd     IS NULL;
COMMENT ON COLUMN vec.tb_doc_entity.mold_cd     IS NULL;
COMMENT ON COLUMN vec.tb_doc_entity.item_cd     IS NULL;
COMMENT ON COLUMN vec.tb_doc_entity.lot_no      IS NULL;
COMMENT ON COLUMN vec.tb_doc_entity.serial_no   IS NULL;
COMMENT ON COLUMN vec.tb_doc_entity.defect_cd   IS NULL;
COMMENT ON COLUMN vec.tb_doc_entity.product_id  IS NULL;
COMMENT ON COLUMN vec.tb_doc_entity.customer_id IS NULL;
COMMENT ON COLUMN vec.tb_doc_entity.ref_user_id IS NULL;
COMMENT ON COLUMN vec.tb_doc_entity.ins_date    IS NULL;

-- ── vec.tb_doc_version (11)
COMMENT ON COLUMN vec.tb_doc_version.doc_id        IS NULL;
COMMENT ON COLUMN vec.tb_doc_version.doc_ver       IS NULL;
COMMENT ON COLUMN vec.tb_doc_version.content_hash  IS NULL;
COMMENT ON COLUMN vec.tb_doc_version.parser_ver    IS NULL;
COMMENT ON COLUMN vec.tb_doc_version.page_cnt      IS NULL;
COMMENT ON COLUMN vec.tb_doc_version.char_cnt      IS NULL;
COMMENT ON COLUMN vec.tb_doc_version.chunk_cnt     IS NULL;
COMMENT ON COLUMN vec.tb_doc_version.extracted_at  IS NULL;
COMMENT ON COLUMN vec.tb_doc_version.embedded_at   IS NULL;
COMMENT ON COLUMN vec.tb_doc_version.ingest_job_id IS NULL;
COMMENT ON COLUMN vec.tb_doc_version.remark        IS NULL;

-- ── vec.tb_embed_model (11)
COMMENT ON COLUMN vec.tb_embed_model.model_id     IS NULL;
COMMENT ON COLUMN vec.tb_embed_model.model_nm     IS NULL;
COMMENT ON COLUMN vec.tb_embed_model.max_tokens   IS NULL;
COMMENT ON COLUMN vec.tb_embed_model.endpoint_url IS NULL;
COMMENT ON COLUMN vec.tb_embed_model.is_default   IS NULL;
COMMENT ON COLUMN vec.tb_embed_model.use_flg      IS NULL;
COMMENT ON COLUMN vec.tb_embed_model.remark       IS NULL;
COMMENT ON COLUMN vec.tb_embed_model.ins_date     IS NULL;
COMMENT ON COLUMN vec.tb_embed_model.ins_user     IS NULL;
COMMENT ON COLUMN vec.tb_embed_model.upd_date     IS NULL;
COMMENT ON COLUMN vec.tb_embed_model.upd_user     IS NULL;

-- ── vec.tb_ingest_error (9)
COMMENT ON COLUMN vec.tb_ingest_error.err_id   IS NULL;
COMMENT ON COLUMN vec.tb_ingest_error.job_id   IS NULL;
COMMENT ON COLUMN vec.tb_ingest_error.doc_id   IS NULL;
COMMENT ON COLUMN vec.tb_ingest_error.chunk_id IS NULL;
COMMENT ON COLUMN vec.tb_ingest_error.err_code IS NULL;
COMMENT ON COLUMN vec.tb_ingest_error.err_msg  IS NULL;
COMMENT ON COLUMN vec.tb_ingest_error.payload  IS NULL;
COMMENT ON COLUMN vec.tb_ingest_error.resolved IS NULL;
COMMENT ON COLUMN vec.tb_ingest_error.ins_date IS NULL;

-- ── vec.tb_ingest_job (14)
COMMENT ON COLUMN vec.tb_ingest_job.job_id          IS NULL;
COMMENT ON COLUMN vec.tb_ingest_job.embed_model_id  IS NULL;
COMMENT ON COLUMN vec.tb_ingest_job.started_at      IS NULL;
COMMENT ON COLUMN vec.tb_ingest_job.ended_at        IS NULL;
COMMENT ON COLUMN vec.tb_ingest_job.duration_sec    IS NULL;
COMMENT ON COLUMN vec.tb_ingest_job.doc_cnt         IS NULL;
COMMENT ON COLUMN vec.tb_ingest_job.chunk_cnt       IS NULL;
COMMENT ON COLUMN vec.tb_ingest_job.embed_cnt       IS NULL;
COMMENT ON COLUMN vec.tb_ingest_job.ok_cnt          IS NULL;
COMMENT ON COLUMN vec.tb_ingest_job.ng_cnt          IS NULL;
COMMENT ON COLUMN vec.tb_ingest_job.state_cd        IS NULL;
COMMENT ON COLUMN vec.tb_ingest_job.triggered_by_cd IS NULL;
COMMENT ON COLUMN vec.tb_ingest_job.triggered_by    IS NULL;
COMMENT ON COLUMN vec.tb_ingest_job.remark          IS NULL;

-- ── vec.tb_query_hit (7)
COMMENT ON COLUMN vec.tb_query_hit.query_id  IS NULL;
COMMENT ON COLUMN vec.tb_query_hit.chunk_id  IS NULL;
COMMENT ON COLUMN vec.tb_query_hit.doc_id    IS NULL;
COMMENT ON COLUMN vec.tb_query_hit.vec_sim   IS NULL;
COMMENT ON COLUMN vec.tb_query_hit.ts_score  IS NULL;
COMMENT ON COLUMN vec.tb_query_hit.trgm_sim  IS NULL;
COMMENT ON COLUMN vec.tb_query_hit.rrf_score IS NULL;

-- ── vec.tb_query_log (14)
COMMENT ON COLUMN vec.tb_query_log.query_id        IS NULL;
COMMENT ON COLUMN vec.tb_query_log.chat_id         IS NULL;
COMMENT ON COLUMN vec.tb_query_log.asked_at        IS NULL;
COMMENT ON COLUMN vec.tb_query_log.user_id         IS NULL;
COMMENT ON COLUMN vec.tb_query_log.dept_id         IS NULL;
COMMENT ON COLUMN vec.tb_query_log.query_text      IS NULL;
COMMENT ON COLUMN vec.tb_query_log.query_embedding IS NULL;
COMMENT ON COLUMN vec.tb_query_log.embed_model_id  IS NULL;
COMMENT ON COLUMN vec.tb_query_log.top_k           IS NULL;
COMMENT ON COLUMN vec.tb_query_log.candidate_k     IS NULL;
COMMENT ON COLUMN vec.tb_query_log.hit_cnt         IS NULL;
COMMENT ON COLUMN vec.tb_query_log.embed_ms        IS NULL;
COMMENT ON COLUMN vec.tb_query_log.search_ms       IS NULL;
COMMENT ON COLUMN vec.tb_query_log.total_ms        IS NULL;

-- ── vec.tb_term_embedding (6)
COMMENT ON COLUMN vec.tb_term_embedding.term_emb_id    IS NULL;
COMMENT ON COLUMN vec.tb_term_embedding.term_id        IS NULL;
COMMENT ON COLUMN vec.tb_term_embedding.variant_id     IS NULL;
COMMENT ON COLUMN vec.tb_term_embedding.embed_model_id IS NULL;
COMMENT ON COLUMN vec.tb_term_embedding.embedding      IS NULL;
COMMENT ON COLUMN vec.tb_term_embedding.embedded_at    IS NULL;

COMMIT;
