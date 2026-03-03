#!/usr/bin/env bash
set -euo pipefail

# Usage
#   ./scripts/create-topics.sh --mode local
#   ./scripts/create-topics.sh --mode msk --bootstrap-servers "b-1.xxx:9092,b-2.xxx:9092"
#
# 기본값은 local 모드입니다.
MODE="local"
BOOTSTRAP_SERVERS="${BOOTSTRAP_SERVERS:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode)
      MODE="$2"
      shift 2
      ;;
    --bootstrap-servers)
      BOOTSTRAP_SERVERS="$2"
      shift 2
      ;;
    *)
      echo "Unknown arg: $1"
      echo "Usage: $0 [--mode local|msk] [--bootstrap-servers <brokers>]"
      exit 1
      ;;
  esac
done

create_topics() {
  local bs="$1"
  local rf="$2"

  kafka-topics.sh --create --if-not-exists \
    --bootstrap-server "${bs}" \
    --topic client-event-logs \
    --partitions 3 \
    --replication-factor "${rf}" \
    --config retention.ms=86400000

  kafka-topics.sh --create --if-not-exists \
    --bootstrap-server "${bs}" \
    --topic error-logs \
    --partitions 1 \
    --replication-factor "${rf}"
}

if [[ "${MODE}" == "local" ]]; then
  BS="${BOOTSTRAP_SERVERS:-localhost:9092}"
  RF=1
  echo "🚀 [local] Kafka 준비 대기 중..."
  sleep 5

  echo "📦 [local] 토픽 생성 시작 (bootstrap=${BS}, replication-factor=${RF})"
  docker exec kafka bash -lc "$(declare -f create_topics); create_topics '${BS}' '${RF}'"

  echo "✅ [local] 생성된 토픽 목록:"
  docker exec kafka kafka-topics.sh --bootstrap-server "${BS}" --list

elif [[ "${MODE}" == "msk" ]]; then
  if [[ -z "${BOOTSTRAP_SERVERS}" ]]; then
    echo "❌ msk 모드는 --bootstrap-servers 값이 필요합니다."
    echo "예: ./scripts/create-topics.sh --mode msk --bootstrap-servers \"b-1.xxx:9092,b-2.xxx:9092\""
    exit 1
  fi
  BS="${BOOTSTRAP_SERVERS}"
  RF=2

  echo "📦 [msk] 토픽 생성 시작 (bootstrap=${BS}, replication-factor=${RF})"
  create_topics "${BS}" "${RF}"

  echo "✅ [msk] 생성된 토픽 목록:"
  kafka-topics.sh --bootstrap-server "${BS}" --list
else
  echo "❌ --mode 는 local 또는 msk 만 가능합니다. (입력값: ${MODE})"
  exit 1
fi