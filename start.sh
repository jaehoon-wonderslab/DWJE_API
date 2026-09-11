#!/usr/bin/env bash
# =====================================================================================
#  덕우전자 AX 시스템 API (dwje-api) — 기동 스크립트
#
#  사용법 (백그라운드 실행, PID 파일 생성)
#    ./start.sh                           운영(prod) 모드로 기동 (기본값)
#    ./start.sh --profile=dev             개발(dev) 모드로 기동
#    ./start.sh --profile=local           로컬(local) 모드로 기동
#    ./start.sh --port=8080               포트 지정 (기본값: 8080)
#
#  사용법 (포그라운드 실행 — 터미널에 콘솔 로그 실시간 출력)
#    ./start.sh --foreground              터미널에서 직접 실행 (Ctrl+C 종료)
#    ./start.sh -f --profile=local
#
#  환경변수 설정
#    config/api.env 파일이 있으면 자동으로 로드합니다.
#    cp config/api.env.example config/api.env 후 운영 정보를 입력하십시오.
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
LOG_DIR="${LOG_DIR:-$APP_HOME/logs}"
CONSOLE_LOG="$LOG_DIR/console.out"
CONFIG_FILE="${CONFIG_FILE:-$APP_HOME/config/api.env}"

# ── 1. 환경변수 파일 로드 (.env / config/api.env) ────────────────────────────────────
if [[ -f "$CONFIG_FILE" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$CONFIG_FILE"
    set +a
elif [[ -f "$APP_HOME/.env" ]]; then
    set -a
    # shellcheck disable=SC1091
    source "$APP_HOME/.env"
    set +a
fi

PROFILE="${APP_PROFILE:-prod}"
PORT="${PORT:-8080}"
FOREGROUND=0
CUSTOM_JAR=""
EXTRA_ARGS=()

log()  { printf '\033[1;34m[API 기동]\033[0m %s\n' "$*"; }
info() { printf '\033[1;32m[성공]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[주의]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[오류]\033[0m %s\n' "$*" >&2; exit 1; }

# ── 2. 인자 파싱 ─────────────────────────────────────────────────────────────────────
for arg in "$@"; do
    case "$arg" in
        --profile=*)    PROFILE="${arg#*=}" ;;
        --port=*)       PORT="${arg#*=}" ;;
        --jar=*)        CUSTOM_JAR="${arg#*=}" ;;
        -f|--foreground) FOREGROUND=1 ;;
        -h|--help)
            sed -n '2,/^# =\{10,\}$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)              EXTRA_ARGS+=("$arg") ;;
    esac
done

# ── 3. Java 21 이상 환경 검증 ─────────────────────────────────────────────────────────
if ! command -v java >/dev/null 2>&1; then
    die "Java 실행 파일을 찾을 수 없습니다. JDK 21 이상을 설치하십시오. (예: sudo apt install -y openjdk-21-jdk)"
fi

JAVA_VER_STRING="$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')"
JAVA_MAJOR_VER="$(echo "$JAVA_VER_STRING" | awk -F. '{print ($1 >= 21 ? $1 : ($1 == 1 ? $2 : $1))}')"
if [[ "$JAVA_MAJOR_VER" -lt 21 ]]; then
    die "JDK 21 이상이 필요합니다. 현재 설치된 버전: $JAVA_VER_STRING"
fi

# ── 4. JAR 파일 탐색 ─────────────────────────────────────────────────────────────────
JAR_PATH=""
if [[ -n "$CUSTOM_JAR" && -f "$CUSTOM_JAR" ]]; then
    JAR_PATH="$CUSTOM_JAR"
elif [[ -f "$APP_HOME/dwje-api-0.0.1.jar" ]]; then
    JAR_PATH="$APP_HOME/dwje-api-0.0.1.jar"
elif [[ -f "$APP_HOME/dwje-api.jar" ]]; then
    JAR_PATH="$APP_HOME/dwje-api.jar"
elif [[ -f "$APP_HOME/build/libs/dwje-api-0.0.1.jar" ]]; then
    JAR_PATH="$APP_HOME/build/libs/dwje-api-0.0.1.jar"
else
    FOUND_JARS="$(find "$APP_HOME" -maxdepth 3 -name "dwje-api*.jar" ! -name "*plain.jar" 2>/dev/null | head -n 1 || true)"
    if [[ -n "$FOUND_JARS" ]]; then
        JAR_PATH="$FOUND_JARS"
    fi
fi

if [[ -z "$JAR_PATH" || ! -f "$JAR_PATH" ]]; then
    die "배포 JAR 파일을 찾을 수 없습니다. 먼저 빌드를 수행하십시오:
    · 로컬/IntelliJ: Gradle > Tasks > build > bootJar
    · 터미널: ./gradlew clean bootJar -x test
    산출물 위치: build/libs/dwje-api-0.0.1.jar"
fi

# ── 5. 필수 환경변수 사전 검증 ───────────────────────────────────────────────────────
MISSING_VARS=()
if [[ "$PROFILE" == "prod" ]]; then
    [[ -z "${PROD_DB_PASSWORD:-}" ]] && MISSING_VARS+=("PROD_DB_PASSWORD")
    [[ -z "${PROD_JWT_SECRET:-}" ]]  && MISSING_VARS+=("PROD_JWT_SECRET")
    [[ -z "${PROD_MAIL_HOST:-}" ]]   && MISSING_VARS+=("PROD_MAIL_HOST")
    [[ -z "${PROD_MAIL_USERNAME:-}" ]] && MISSING_VARS+=("PROD_MAIL_USERNAME")
    [[ -z "${PROD_MAIL_PASSWORD:-}" ]] && MISSING_VARS+=("PROD_MAIL_PASSWORD")
elif [[ "$PROFILE" == "dev" ]]; then
    [[ -z "${DEV_DB_PASSWORD:-}" ]] && MISSING_VARS+=("DEV_DB_PASSWORD")
    [[ -z "${DEV_JWT_SECRET:-}" ]]  && MISSING_VARS+=("DEV_JWT_SECRET")
    [[ -z "${DEV_MAIL_HOST:-}" ]]   && MISSING_VARS+=("DEV_MAIL_HOST")
    [[ -z "${DEV_MAIL_USERNAME:-}" ]] && MISSING_VARS+=("DEV_MAIL_USERNAME")
    [[ -z "${DEV_MAIL_PASSWORD:-}" ]] && MISSING_VARS+=("DEV_MAIL_PASSWORD")
fi

if [[ "${#MISSING_VARS[@]}" -gt 0 ]]; then
    die "[$PROFILE] 프로파일 기동에 필요한 환경변수가 누락되었습니다:
  누락 목록: ${MISSING_VARS[*]}
  해결 방법: $CONFIG_FILE 파일에 해당 변수를 설정하거나 export 하십시오.
  (참고: config/api.env.example)"
fi

# ── 6. 중복 기동 및 포트 점유 검사 ───────────────────────────────────────────────────
if [[ -f "$PID_FILE" ]]; then
    OLD_PID="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [[ -n "$OLD_PID" ]] && kill -0 "$OLD_PID" 2>/dev/null; then
        die "API 서버가 이미 실행 중입니다 (PID: $OLD_PID). 중지 후 다시 시도하십시오: ./stop.sh"
    fi
    rm -f "$PID_FILE"
fi

# 포트 중복 점유 검사
PORT_OCCUPIED=0
if command -v ss >/dev/null 2>&1; then
    if ss -tln | grep -q ":${PORT}\b"; then PORT_OCCUPIED=1; fi
elif command -v lsof >/dev/null 2>&1; then
    if lsof -ti ":$PORT" >/dev/null 2>&1; then PORT_OCCUPIED=1; fi
fi

if [[ "$PORT_OCCUPIED" -eq 1 ]]; then
    die "포트 ${PORT} 가 이미 다른 프로세스에 의해 사용 중입니다. 점유 중인 프로세스를 확인하십시오."
fi

# ── 7. JVM 옵션 구성 ─────────────────────────────────────────────────────────────────
DEFAULT_JAVA_OPTS="-Xms1g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Duser.timezone=Asia/Seoul -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
JAVA_OPTS="${JAVA_OPTS:-$DEFAULT_JAVA_OPTS}"

mkdir -p "$PID_DIR" "$LOG_DIR"

log "JAR 파일 : $JAR_PATH"
log "프로파일 : $PROFILE"
log "서비스포트: $PORT"
log "Java 버전: $JAVA_VER_STRING"

# ── 8. 기동 (포그라운드 / 백그라운드) ─────────────────────────────────────────────────
SPRING_ARGS=(
    "--spring.profiles.active=$PROFILE"
    "--server.port=$PORT"
)
if [[ "${#EXTRA_ARGS[@]}" -gt 0 ]]; then
    SPRING_ARGS+=("${EXTRA_ARGS[@]}")
fi

if [[ "$FOREGROUND" -eq 1 ]]; then
    log "포그라운드 모드로 시작합니다 (Ctrl+C 로 종료)..."
    # shellcheck disable=SC2086
    exec java $JAVA_OPTS -jar "$JAR_PATH" "${SPRING_ARGS[@]}"
fi

log "백그라운드 모드로 기동합니다..."
# shellcheck disable=SC2086
nohup java $JAVA_OPTS -jar "$JAR_PATH" "${SPRING_ARGS[@]}" >> "$CONSOLE_LOG" 2>&1 &
NEW_PID=$!
echo "$NEW_PID" > "$PID_FILE"

log "PID $NEW_PID 발급 완료. 서버 초기화 대기 중..."

# ── 9. 기동 상태 헬스체크 (최대 30초 대기) ───────────────────────────────────────────
STARTED=0
for ((i=1; i<=30; i++)); do
    if ! kill -0 "$NEW_PID" 2>/dev/null; then
        rm -f "$PID_FILE"
        printf '\n'
        warn "서버 프로세스가 기동 중 비정상 종료되었습니다. 콘솔 로그를 확인하십시오:"
        echo "----------------------------------------------------------------------"
        tail -n 25 "$CONSOLE_LOG" || true
        echo "----------------------------------------------------------------------"
        die "기동 실패. 로그 파일: $CONSOLE_LOG"
    fi

    # HTTP 헬스체크 시도
    if command -v curl >/dev/null 2>&1; then
        HTTP_STATUS="$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${PORT}/api/v1/health" 2>/dev/null || true)"
        if [[ "$HTTP_STATUS" == "200" ]]; then
            STARTED=1
            break
        fi
    fi
    sleep 1
    printf '.'
done
printf '\n'

if [[ "$STARTED" -eq 1 ]]; then
    info "API 서버가 성공적으로 기동되었습니다! (PID: $NEW_PID, PORT: $PORT, 소요: ${i}초)"
    echo "  · 상태 확인 URL : http://localhost:${PORT}/api/v1/health"
    echo "  · 콘솔 로그 출력: tail -f $CONSOLE_LOG"
    echo "  · 중지 명령어   : ./stop.sh"
else
    # 헬스체크 curl 이 없거나 시간이 다소 걸리는 경우 프로세스 생존 여부로 최종 안내
    if kill -0 "$NEW_PID" 2>/dev/null; then
        info "프로세스 실행 중 (PID: $NEW_PID). 헬스체크 응답 대기 중입니다. 로그를 확인하십시오:"
        echo "  tail -f $CONSOLE_LOG"
    else
        rm -f "$PID_FILE"
        die "서버 기동에 실패했습니다. 로그를 확인하십시오: $CONSOLE_LOG"
    fi
fi
