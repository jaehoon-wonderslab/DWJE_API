#!/usr/bin/env bash
# =====================================================================================
#  덕우전자 AX 시스템 API (dwje-api) — 상태 확인 스크립트
# =====================================================================================
if [ -z "${BASH_VERSION:-}" ]; then
    if command -v bash >/dev/null 2>&1; then
        exec bash "$0" "$@"
    fi
    exit 1
fi

set -euo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_DIR="$APP_HOME/run"
PID_FILE="${PID_FILE:-$PID_DIR/dwje-api.pid}"
CONFIG_FILE="${CONFIG_FILE:-$APP_HOME/config/api.env}"

if [[ -f "$CONFIG_FILE" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$CONFIG_FILE"
    set +a
fi

PORT="${PORT:-8080}"

log()  { printf '\033[1;34m[상태 확인]\033[0m %s\n' "$*"; }
info() { printf '\033[1;32m%s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m%s\033[0m\n' "$*"; }
err()  { printf '\033[1;31m%s\033[0m\n' "$*"; }

TARGET_PID=""
if [[ -f "$PID_FILE" ]]; then
    TARGET_PID="$(cat "$PID_FILE" 2>/dev/null || true)"
fi

if [[ -z "$TARGET_PID" ]]; then
    if command -v pgrep >/dev/null 2>&1; then
        TARGET_PID="$(pgrep -f "dwje-api.*\.jar" | head -n 1 || true)"
    fi
fi

if [[ -z "$TARGET_PID" ]] || ! kill -0 "$TARGET_PID" 2>/dev/null; then
    err "● dwje-api: 중지됨 (실행 중이 아닙니다)"
    exit 0
fi

info "● dwje-api: 실행 중 (PID: $TARGET_PID)"

# 1. 프로세스 상세 정보 (CPU, 메모리, 기동 시간)
if command -v ps >/dev/null 2>&1; then
    echo "----------------------------------------------------------------------"
    ps -p "$TARGET_PID" -o pid,user,%cpu,%mem,etime,command 2>/dev/null || true
    echo "----------------------------------------------------------------------"
fi

# 2. 활성 TCP 커넥션 수
CONN_COUNT=0
if command -v ss >/dev/null 2>&1; then
    CONN_COUNT="$(ss -tn state established "( sport = :$PORT or dport = :$PORT )" 2>/dev/null | grep -vc "Recv-Q" || true)"
elif command -v lsof >/dev/null 2>&1; then
    CONN_COUNT="$(lsof -i ":$PORT" -sTCP:ESTABLISHED 2>/dev/null | grep -vc "COMMAND" || true)"
fi
echo "  · 서비스 포트       : $PORT"
echo "  · 현재 연결된 커넥션: ${CONN_COUNT}건"

# 3. 헬스체크 API 호출
if command -v curl >/dev/null 2>&1; then
    HEALTH_RES="$(curl -s -m 3 "http://localhost:${PORT}/api/v1/health" 2>/dev/null || true)"
    if [[ -n "$HEALTH_RES" ]]; then
        echo "  · 헬스체크 응답     : $HEALTH_RES"
    else
        warn "  · 헬스체크 응답     : 응답 없음 (서버 시작 중이거나 비정상 상태)"
    fi
fi
