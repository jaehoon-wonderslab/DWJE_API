#!/usr/bin/env bash
# =====================================================================================
#  덕우전자 AX 시스템 API (dwje-api) — 안전 중지 스크립트 (Graceful Stop)
#
#  동작 방식
#    1. 연결된 활성 클라이언트 및 DB 커넥션을 감지합니다.
#    2. 서버에 정상 종료 신호(SIGTERM)를 전달하여 신규 요청을 차단하고,
#       진행 중인 인플라이트 요청과 HikariCP 커넥션 풀을 안전하게 닫습니다.
#    3. 연결이 완전히 닫히고 프로세스가 종료될 때까지 대기합니다.
#    4. 제한 시간(기본 30초) 내 종료되지 않으면 남아 있는 소켓 커넥션을 강제 차단하고
#       프로세스를 강제 종료(SIGKILL)하여 자원을 확실히 회수합니다.
#
#  사용법
#    ./stop.sh                       정상 중지 (기본 30초 대기 후 안전 종료)
#    ./stop.sh --timeout=60          대기 시간을 60초로 지정
#    ./stop.sh --force               대기 없이 즉시 강제 종료 (커넥션 즉시 차단)
#    ./stop.sh --port=8080           대상 포트 지정 (기본값: 8080)
# =====================================================================================
if [ -z "${BASH_VERSION:-}" ]; then
    if command -v bash >/dev/null 2>&1; then
        exec bash "$0" "$@"
    fi
    echo "[오류] 이 스크립트는 bash 가 필요합니다. 설치하십시오: sudo apt install -y bash" >&2
    exit 1
fi

set -euo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_DIR="$APP_HOME/run"
PID_FILE="${PID_FILE:-$PID_DIR/dwje-api.pid}"
CONFIG_FILE="${CONFIG_FILE:-$APP_HOME/config/api.env}"

# 환경변수 파일이 있으면 포트 기본값 참조
if [[ -f "$CONFIG_FILE" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$CONFIG_FILE"
    set +a
fi

PORT="${PORT:-8080}"
TIMEOUT="${STOP_TIMEOUT:-30}"
FORCE=0
CUSTOM_PID=""

log()  { printf '\033[1;34m[API 중지]\033[0m %s\n' "$*"; }
info() { printf '\033[1;32m[성공]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[주의]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[오류]\033[0m %s\n' "$*" >&2; exit 1; }

# ── 인자 파싱 ─────────────────────────────────────────────────────────────────────────
for arg in "$@"; do
    case "$arg" in
        --force)        FORCE=1 ;;
        --timeout=*)    TIMEOUT="${arg#*=}" ;;
        --port=*)       PORT="${arg#*=}" ;;
        --pid=*)        CUSTOM_PID="${arg#*=}" ;;
        -h|--help)
            sed -n '2,/^# =\{10,\}$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            die "알 수 없는 옵션: $arg (--force / --timeout=초 / --port=포트 / --pid=PID)"
            ;;
    esac
done

[[ "$TIMEOUT" =~ ^[0-9]+$ ]] || die "--timeout 은 초 단위 숫자여야 합니다: $TIMEOUT"

# ── 1. 대상 PID 확인 ─────────────────────────────────────────────────────────────────
TARGET_PID=""
if [[ -n "$CUSTOM_PID" ]]; then
    TARGET_PID="$CUSTOM_PID"
elif [[ -f "$PID_FILE" ]]; then
    TARGET_PID="$(cat "$PID_FILE" 2>/dev/null || true)"
fi

# PID 파일이 없거나 유효하지 않으면 실행 중인 java 프로세스에서 dwje-api 탐색
find_api_pids() {
    if command -v pgrep >/dev/null 2>&1; then
        pgrep -f "dwje-api.*\.jar" 2>/dev/null || true
    else
        ps -eo pid=,command= 2>/dev/null | grep -E "dwje-api.*\.jar" | grep -v grep | awk '{print $1}' || true
    fi
}

if [[ -z "$TARGET_PID" || ! "$TARGET_PID" =~ ^[0-9]+$ ]]; then
    FOUND="$(find_api_pids)"
    if [[ -z "$FOUND" ]]; then
        # 포트를 물고 있는 프로세스가 있는지 확인
        PORT_PID=""
        if command -v lsof >/dev/null 2>&1; then
            PORT_PID="$(lsof -ti ":$PORT" 2>/dev/null | head -n 1 || true)"
        elif command -v fuser >/dev/null 2>&1; then
            PORT_PID="$(fuser "$PORT/tcp" 2>/dev/null | tr -d ' ' || true)"
        fi

        if [[ -n "$PORT_PID" ]]; then
            warn "PID 파일은 없으나 포트 ${PORT} 를 점유 중인 프로세스(PID: $PORT_PID)를 감지했습니다."
            TARGET_PID="$PORT_PID"
        else
            log "실행 중인 API 서버가 없습니다."
            rm -f "$PID_FILE"
            exit 0
        fi
    else
        COUNT="$(echo "$FOUND" | wc -w)"
        if [[ "$COUNT" -gt 1 ]]; then
            die "API 서버 프로세스가 여러 개 감지되었습니다 (PID: $FOUND). --pid=<PID> 로 지정해 주십시오."
        fi
        TARGET_PID="$(echo "$FOUND" | tr -d ' ')"
        warn "PID 파일이 없어 활성 프로세스에서 PID $TARGET_PID 를 찾았습니다."
    fi
fi

# 좀비 프로세스 감지 (우분투 /proc 또는 ps)
is_zombie() {
    if [ -r "/proc/$1/stat" ]; then
        [ "$(sed -e 's/^.*) //' -e 's/ .*//' "/proc/$1/stat" 2>/dev/null)" = "Z" ]
    else
        [ "$(ps -p "$1" -o state= 2>/dev/null | tr -d ' ' | cut -c1)" = "Z" ]
    fi
}

is_alive() {
    kill -0 "$1" 2>/dev/null && ! is_zombie "$1"
}

# 프로세스 존재 여부 검사
if ! kill -0 "$TARGET_PID" 2>/dev/null; then
    log "PID $TARGET_PID 는 이미 실행 중이 아닙니다. 남아있던 PID 파일을 정리합니다."
    rm -f "$PID_FILE"
    exit 0
fi

if is_zombie "$TARGET_PID"; then
    log "PID $TARGET_PID 는 이미 종료된 좀비 프로세스입니다. PID 파일을 정리합니다."
    rm -f "$PID_FILE"
    exit 0
fi

# ── 2. 현재 활성 커넥션(Connection) 상태 집계 ───────────────────────────────────────
# 2-1. 클라이언트 인바운드 HTTP 연결 (포트 8080)
count_active_connections() {
    local count=0
    if command -v ss >/dev/null 2>&1; then
        count="$(ss -tn state established "( sport = :$PORT or dport = :$PORT )" 2>/dev/null | grep -vc "Recv-Q" || true)"
    elif command -v lsof >/dev/null 2>&1; then
        count="$(lsof -i ":$PORT" -sTCP:ESTABLISHED 2>/dev/null | grep -vc "COMMAND" || true)"
    elif command -v netstat >/dev/null 2>&1; then
        count="$(netstat -tn 2>/dev/null | grep ":$PORT " | grep -c "ESTABLISHED" || true)"
    fi
    echo "$count"
}

# 2-2. 백엔드 아웃바운드 DB 커넥션 풀 연결 (PostgreSQL 5432 등)
count_db_connections() {
    local db_count=0
    if [[ -z "$TARGET_PID" ]] || ! is_alive "$TARGET_PID"; then
        echo 0
        return
    fi
    if command -v ss >/dev/null 2>&1; then
        db_count="$(ss -tnp state established 2>/dev/null | grep "pid=$TARGET_PID," | grep -E ":5432|:1433" | grep -vc "Recv-Q" || true)"
    elif command -v lsof >/dev/null 2>&1; then
        db_count="$(lsof -a -p "$TARGET_PID" -iTCP -sTCP:ESTABLISHED 2>/dev/null | grep -E ":5432|:postgresql|:1433" | grep -vc "COMMAND" || true)"
    fi
    echo "$db_count"
}

INITIAL_CONNS="$(count_active_connections)"
INITIAL_DB_CONNS="$(count_db_connections)"
log "대상 프로세스: PID $TARGET_PID (포트: $PORT)"
log "현재 활성 커넥션: 클라이언트 HTTP ${INITIAL_CONNS}건, 데이터베이스 풀 ${INITIAL_DB_CONNS}건 감지됨"

# ── 3. 즉시 강제 종료 (--force 플래그 처리) ─────────────────────────────────────────
if [[ "$FORCE" -eq 1 ]]; then
    warn "--force 옵션이 지정되었습니다. 연결된 모든 커넥션을 즉시 끊고 강제 종료합니다."
    if command -v fuser >/dev/null 2>&1; then
        fuser -k -KILL "$PORT/tcp" >/dev/null 2>&1 || true
    fi
    kill -KILL "$TARGET_PID" 2>/dev/null || true
    sleep 1
    rm -f "$PID_FILE"
    info "프로세스(PID $TARGET_PID) 및 포트 ${PORT} 커넥션이 강제 종료되었습니다."
    exit 0
fi

# ── 4. 정상 종료 신호 전송 (Graceful Shutdown & Drain Connections) ───────────────────
log "종료 신호(SIGTERM)를 전달합니다. 신규 요청 차단 및 기존 커넥션 정리를 시작합니다..."
kill -TERM "$TARGET_PID" 2>/dev/null || die "종료 신호를 전달하지 못했습니다 (권한을 확인하십시오)."

# ── 5. 커넥션 정리 및 프로세스 종료 모니터링 루프 ────────────────────────────────────
START_TS=$(date +%s)
SHUTDOWN_SUCCESS=0

while is_alive "$TARGET_PID"; do
    NOW_TS=$(date +%s)
    ELAPSED=$(( NOW_TS - START_TS ))

    if [[ "$ELAPSED" -ge "$TIMEOUT" ]]; then
        break
    fi

    CURR_CLIENT_CONNS="$(count_active_connections)"
    CURR_DB_CONNS="$(count_db_connections)"
    printf '\r\033[1;34m[API 중지]\033[0m 커넥션 정리 대기 중... (경과: %d초/%d초 | 클라이언트: %d건, DB풀: %d건)   ' "$ELAPSED" "$TIMEOUT" "$CURR_CLIENT_CONNS" "$CURR_DB_CONNS"
    sleep 1
done
printf '\n'

if ! is_alive "$TARGET_PID"; then
    SHUTDOWN_SUCCESS=1
fi

# ── 6. 타임아웃 초과 시 잔여 커넥션 강제 차단 및 강제 종료 ──────────────────────────
if [[ "$SHUTDOWN_SUCCESS" -eq 0 ]]; then
    FINAL_CONNS="$(count_active_connections)"
    warn "제한 시간(${TIMEOUT}초)이 경과하였으나 여전히 프로세스가 종료되지 않았습니다 (잔여 커넥션: ${FINAL_CONNS}건)."
    warn "남아 있는 모든 네트워크 소켓 커넥션을 강제 차단하고 프로세스를 종료(SIGKILL)합니다."

    # Linux fuser 로 포트에 열려 있는 모든 소켓 강제 회수
    if command -v fuser >/dev/null 2>&1; then
        fuser -k -KILL "$PORT/tcp" >/dev/null 2>&1 || true
    fi

    kill -KILL "$TARGET_PID" 2>/dev/null || true
    sleep 1

    # 만약 하위 프로세스가 남아있을 경우 대비
    if kill -0 "$TARGET_PID" 2>/dev/null; then
        die "프로세스 종료에 실패했습니다. 수동으로 종료하십시오: kill -9 $TARGET_PID"
    fi
fi

# ── 7. 최종 검증 및 정리 ─────────────────────────────────────────────────────────────
rm -f "$PID_FILE"
TOTAL_ELAPSED=$(( $(date +%s) - START_TS ))

# 포트 해제 여부 검증
PORT_STILL_OPEN=0
if command -v ss >/dev/null 2>&1; then
    if ss -tln | grep -q ":${PORT}\b"; then PORT_STILL_OPEN=1; fi
elif command -v lsof >/dev/null 2>&1; then
    if lsof -ti ":$PORT" >/dev/null 2>&1; then PORT_STILL_OPEN=1; fi
fi

if [[ "$PORT_STILL_OPEN" -eq 1 ]]; then
    warn "포트 ${PORT} 가 아직 LISTEN 상태로 남아 있습니다. 프로세스 상태를 점검하십시오."
else
    info "모든 커넥션이 정상적으로 정리되었으며 포트 ${PORT} 가 완전히 해제되었습니다."
fi

info "API 서버 중지 완료 (PID: $TARGET_PID, 소요: ${TOTAL_ELAPSED}초)"
