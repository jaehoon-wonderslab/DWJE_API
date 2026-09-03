#!/usr/bin/env bash
# =====================================================================================
#  로컬 개발용 PostgreSQL 기동 + 스키마·시드 일괄 적용
#
#  ⚠️  로컬 전용. 운영 DB 에는 절대 사용하지 마시오.
#
#  이 스크립트 하나로 Swagger UI 에서 API 를 바로 시험할 수 있는 상태가 된다.
#    1) pgvector 포함 PostgreSQL 컨테이너 기동 (기본 포트 5432)
#    2) 기준 스키마 3종 적용 (mes → ax → vec)
#    3) 확장 스키마 적용 (db/V*.sql 전체를 버전 순으로)
#    4) 로컬 계정·권한 시드 적용
#
#  사용법:
#    ./setup_local_db.sh              # 기동 + 전체 적용
#    ./setup_local_db.sh --recreate   # 컨테이너 삭제 후 처음부터
#    PORT=15432 ./setup_local_db.sh   # 다른 포트로 기동
# =====================================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
API_DIR="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
SCHEMA_DIR="$(cd "$API_DIR/.." && pwd)/Postgresql 스키마"

CONTAINER="${CONTAINER:-dwje-pg}"
PORT="${PORT:-5432}"
DB_NAME="${DB_NAME:-dwjedb}"
DB_USER="${DB_USER:-dwje_local}"
DB_PASS="${DB_PASS:-dwje_local}"
IMAGE="${IMAGE:-pgvector/pgvector:pg16}"

log() { printf '\033[1;34m▶\033[0m %s\n' "$*"; }
err() { printf '\033[1;31m✗\033[0m %s\n' "$*" >&2; }

[ "${1:-}" = "--recreate" ] && { log "기존 컨테이너 삭제: $CONTAINER"; docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }

if [ ! -d "$SCHEMA_DIR" ]; then
  err "스키마 디렉터리를 찾을 수 없습니다: $SCHEMA_DIR"
  exit 1
fi

# 1) 컨테이너 기동 -------------------------------------------------------------------
if docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  log "컨테이너가 이미 실행 중입니다: $CONTAINER"
else
  if docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    log "기존 컨테이너 시작: $CONTAINER"
    docker start "$CONTAINER" >/dev/null
  else
    log "컨테이너 생성: $CONTAINER (포트 $PORT, 이미지 $IMAGE)"
    docker run -d --name "$CONTAINER" \
      -e POSTGRES_DB="$DB_NAME" -e POSTGRES_USER="$DB_USER" -e POSTGRES_PASSWORD="$DB_PASS" \
      -p "$PORT:5432" "$IMAGE" >/dev/null
  fi
fi

log "기동 대기..."
for i in $(seq 1 60); do
  docker exec "$CONTAINER" pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1 && break
  sleep 1
  [ "$i" = 60 ] && { err "PostgreSQL 기동 실패"; exit 1; }
done

apply() {
  local file="$1" label="$2"
  [ -f "$file" ] || { err "파일 없음: $file"; exit 1; }
  docker cp "$file" "$CONTAINER:/tmp/apply.sql" >/dev/null
  if docker exec "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q -f /tmp/apply.sql >/dev/null 2>/tmp/apply.err; then
    log "적용 완료: $label"
  else
    err "적용 실패: $label"; cat /tmp/apply.err >&2; exit 1
  fi
}

# 2) 기준 스키마 (순서 중요: common/mes → ax → vec) --------------------------------
#    기준 DDL 은 CREATE DOMAIN 등이 멱등하지 않아 재실행하면 실패한다.
#    이미 적용된 DB 에는 건너뛰고, 확장(V*)·시드만 다시 적용한다.
#    기준 스키마를 처음부터 다시 깔려면 --recreate 를 쓴다.
if docker exec "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAc \
     "select to_regclass('ax.tb_sys_user') is not null;" 2>/dev/null | grep -q '^t$'; then
  log "기준 스키마가 이미 적용되어 있어 건너뜁니다 (처음부터 다시 하려면 --recreate)"
else
  apply "$SCHEMA_DIR/mes_db_query.sql"        "MES 스키마 (common, mes)"
  apply "$SCHEMA_DIR/ai_db_query.sql"         "AX 스키마 (ax)"
  apply "$SCHEMA_DIR/ai_db_vector_query.sql"  "벡터 스키마 (vec)"
fi

# 3) 확장 스키마 ---------------------------------------------------------------------
# db/V*.sql 을 버전 순으로 모두 적용한다. 새 마이그레이션을 추가하면 이 스크립트 수정 없이 반영된다.
# 경로에 공백이 있으므로($PWD 에 "004. 개발" 포함) 명령 치환 대신 줄 단위로 읽는다.
while IFS= read -r migration; do
  [ -n "$migration" ] || continue
  apply "$migration" "$(basename "$migration")"
done < <(ls -1 "$SCRIPT_DIR"/../V*.sql | sort -V)

# 4) 로컬 계정·권한 시드 --------------------------------------------------------------
apply "$SCRIPT_DIR/seed_local_accounts.sql" "로컬 계정·권한 시드"

# 제품 마스터 부트스트랩 — MES 품목에서 제품군·제품·품목매핑을 파생시킨다.
# 이 시드가 없으면 공정·제품 대시보드와 제품 랭킹 화면이 전부 빈 상태로 나온다.
# 사용자가 등록한 제품 데이터가 있으면 스크립트 내부 가드가 알아서 중단한다.
apply "$SCRIPT_DIR/seed_product_master.sql" "제품 마스터 부트스트랩 시드"

echo
docker exec "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -c "
SELECT d.dept_nm AS \"부서\",
       string_agg(DISTINCT u.user_id, ',') AS \"사번\",
       count(DISTINCT mp.menu_id)   AS \"메뉴권한\",
       count(DISTINCT dp.field_key) AS \"데이터권한\"
FROM ax.tb_sys_dept d
LEFT JOIN ax.tb_sys_user u            ON u.dept_id  = d.dept_id
LEFT JOIN ax.tb_sys_dept_menu_perm mp ON mp.dept_id = d.dept_id
LEFT JOIN ax.tb_sys_dept_data_perm dp ON dp.dept_id = d.dept_id AND dp.is_allowed
GROUP BY d.dept_nm, d.sort_seq ORDER BY d.sort_seq;"

cat <<MSG

준비 완료. 다음 순서로 Swagger 를 사용하세요.

  1. API 기동      cd "$API_DIR" && ./gradlew bootRun --args='--spring.profiles.active=local'
  2. Swagger 접속  http://localhost:8080/swagger-ui.html
  3. 로그인        [01. 인증·공통] POST /api/v1/auth/login
                   { "loginId": "10004", "password": "Dwje!2026" }
  4. 인증          응답의 accessToken 을 복사 → 우측 상단 [Authorize] 에 붙여넣기
  5. 호출          이후 모든 API 를 Try it out 으로 시험

  로그인 계정 (비밀번호 공통 Dwje!2026)
    10000 관리자  통합관리자  — 전 화면·전 데이터 (권한 제약 없이 둘러볼 때)
    10001 김품질  품질보증팀
    10002 박생산  생산관리팀
    10003 이제조  제조팀
    10004 최전산  전산팀      — 시스템관리 화면
    10005 정경영  경영진

  컨테이너 정리   docker rm -f $CONTAINER
MSG
