# Kafka Connect & MSK 연동 운영 가이드

이 문서는 **실제 AWS 인프라(MSK + ECS Fargate)**에서 log-server를 연동하는 방법을 설명합니다.  
로컬 개발 환경(`docker-compose.local.yml`)과의 차이점, 환경변수 주입 방법, Kafka Connect 배포 절차를 담고 있습니다.

---

## 전체 아키텍처

```
API Server (ECS)
  └─ KafkaProducer (key=member_id)
        │
        ▼
  MSK (kafka.t3.small × 2, Multi-AZ)
  ├── client-event-logs (파티션 3, replication 2)
  └── error-logs / DLQ   (파티션 1, replication 2)
        │
        ├─ Speed Layer (ECS Fargate 0.5vCPU)
        │    └─ SpeedLayerConsumer (Spring Boot)
        │         ├─ click_product_detail만 필터링 (RecordFilterStrategy)
        │         ├─ product_view_history UPSERT (PostgreSQL)
        │         └─ 파싱 실패 → error-logs DLQ
        │
        └─ Batch Layer (ECS Fargate 0.25vCPU)
             └─ Kafka Connect Worker
                  └─ S3 Sink → s3://<bucket>/events/raw/dt=.../hour=.../
```

---

## 1. MSK 설정

### 1-1. MSK 클러스터 스펙

| 항목 | 값 | 비고 |
|---|---|---|
| 브로커 타입 | `kafka.t3.small` | 일 30만 건(3.5 TPS) 충분 |
| 브로커 수 | 2 (Multi-AZ) | MSK 최소 구성 |
| Kafka 버전 | 3.6.x | MSK 지원 최신 안정 버전 |
| 스토리지 | EBS 100GB (브로커당) | 1일 보존 기준 충분 |
| 인증 방식 | PLAINTEXT | 동일 VPC 내부 통신 |
| 토픽 자동 생성 | 비활성화 | 명시적 생성 강제 |

### 1-2. MSK 토픽 생성 (최초 1회)

```bash
# MSK 부트스트랩 주소 확인
MSK_BS=$(aws kafka get-bootstrap-brokers \
  --cluster-arn <MSK_CLUSTER_ARN> \
  --query 'BootstrapBrokerString' --output text)

# click 로그 토픽
kafka-topics.sh --bootstrap-server $MSK_BS \
  --create --if-not-exists \
  --topic client-event-logs \
  --partitions 3 \
  --replication-factor 2 \
  --config retention.ms=86400000

# DLQ 토픽
kafka-topics.sh --bootstrap-server $MSK_BS \
  --create --if-not-exists \
  --topic error-logs \
  --partitions 1 \
  --replication-factor 2
```

### 1-3. Security Group 설정

```
MSK Security Group 인바운드:
  - Port 9092 (PLAINTEXT)
  - Source: Speed Layer ECS Task SG
  - Source: Kafka Connect ECS Task SG
  - Source: API Server ECS Task SG

Speed Layer ECS Task SG 아웃바운드:
  - MSK SG → 9092
  - PostgreSQL RDS SG → 5432

Kafka Connect ECS Task SG 아웃바운드:
  - MSK SG → 9092
  - S3 → VPC Endpoint 또는 NAT Gateway
  - CloudWatch Logs → VPC Endpoint 또는 NAT Gateway
```

---

## 2. Speed Layer (Spring Boot Consumer) — MSK 연동

### 2-1. 로컬 vs MSK 달라지는 점

| 항목 | 로컬 (`docker-compose.local.yml`) | MSK 운영 |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | `b-1.xxx.kafka.ap-northeast-2.amazonaws.com:9092,b-2.xxx...:9092` |
| `DB_URL` | `jdbc:postgresql://localhost:5435/holliverse` | `jdbc:postgresql://<RDS_ENDPOINT>:5432/<DB_NAME>` |
| 인프라 실행 | `docker-compose.local.yml` | AWS MSK + RDS |
| Spring Boot 코드 변경 | 없음 | 없음 (환경변수만 교체) |

### 2-2. ECS Task Definition 환경변수

ECS Task Definition의 `environment` 또는 AWS Secrets Manager/Parameter Store에 다음을 주입합니다.

```json
[
  { "name": "KAFKA_BOOTSTRAP_SERVERS",     "value": "b-1.xxx.kafka.ap-northeast-2.amazonaws.com:9092,b-2.xxx.kafka.ap-northeast-2.amazonaws.com:9092" },
  { "name": "KAFKA_TOPIC_CLIENT_EVENTS",   "value": "client-event-logs" },
  { "name": "KAFKA_TOPIC_ERROR",           "value": "error-logs" },
  { "name": "KAFKA_GROUP_SPEED",           "value": "speed-layer-group" },
  { "name": "KAFKA_MAX_POLL_RECORDS",      "value": "1" },
  { "name": "KAFKA_DLQ_ACKS",             "value": "all" },
  { "name": "KAFKA_DLQ_RETRIES",          "value": "3" },
  { "name": "DB_URL",                      "value": "jdbc:postgresql://<RDS_ENDPOINT>:5432/<DB_NAME>" },
  { "name": "DB_USERNAME",                 "value": "<DB_USERNAME>" },
  { "name": "DB_PASSWORD",                 "value": "<DB_PASSWORD>" },
  { "name": "JPA_DDL_AUTO",               "value": "validate" }
]
```

> **보안**: `DB_PASSWORD` 등 민감 값은 `valueFrom`으로 AWS Secrets Manager ARN을 참조하는 방식을 권장합니다.

### 2-3. `application.yaml` 환경변수 매핑 요약

```yaml
# application.yaml (코드 기준 — 운영 시 환경변수로 덮어씀)
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5435/holliverse}
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
  jpa:
    hibernate:
      ddl-auto: ${JPA_DDL_AUTO:validate}

app:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    topics:
      client-events: ${KAFKA_TOPIC_CLIENT_EVENTS:client-event-logs}
      error: ${KAFKA_TOPIC_ERROR:error-logs}
    groups:
      speed: ${KAFKA_GROUP_SPEED:speed-layer-group}
    listener:
      max-poll-records: ${KAFKA_MAX_POLL_RECORDS:1}
      ack-mode: RECORD
    producer:
      dlq-acks: ${KAFKA_DLQ_ACKS:all}
      dlq-retries: ${KAFKA_DLQ_RETRIES:3}
```

### 2-4. ECS Fargate 태스크 스펙

| 항목 | 값 |
|---|---|
| Launch type | Fargate |
| vCPU | 0.5 |
| Memory | 1 GB |
| 초기 태스크 수 | 1 (최대 3, 파티션 수 기준) |
| Consumer Group | `speed-layer-group` |
| 네트워크 | VPC Private Subnet |

---

## 3. Batch Layer (Kafka Connect S3 Sink) — MSK 연동

### 3-1. ECS Fargate 태스크 스펙

| 항목 | 값 |
|---|---|
| 컨테이너 이미지 | `confluentinc/cp-kafka-connect:7.7.1` (S3 Sink 플러그인 포함) |
| vCPU | 0.25 |
| Memory | 0.5 GB |
| 태스크 수 | 1 (고정) |
| Consumer Group | `s3-sink-group` (Connect 내부 자동 관리) |

### 3-2. IAM Task Role 최소 권한

ECS Task Role에 다음 권한을 부여합니다.

```json
{
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:ListBucket"],
      "Resource": [
        "arn:aws:s3:::<bucket>",
        "arn:aws:s3:::<bucket>/events/raw/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": ["logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents"],
      "Resource": "arn:aws:logs:*:*:/aws/ecs/*"
    }
  ]
}
```

### 3-3. Kafka Connect Worker 환경변수 (ECS Task Definition)

```json
[
  { "name": "CONNECT_BOOTSTRAP_SERVERS",            "value": "b-1.xxx.kafka.ap-northeast-2.amazonaws.com:9092,b-2.xxx.kafka.ap-northeast-2.amazonaws.com:9092" },
  { "name": "CONNECT_REST_PORT",                    "value": "8083" },
  { "name": "CONNECT_GROUP_ID",                     "value": "kafka-connect-s3-group" },
  { "name": "CONNECT_CONFIG_STORAGE_TOPIC",         "value": "connect-configs" },
  { "name": "CONNECT_OFFSET_STORAGE_TOPIC",         "value": "connect-offsets" },
  { "name": "CONNECT_STATUS_STORAGE_TOPIC",         "value": "connect-status" },
  { "name": "CONNECT_KEY_CONVERTER",                "value": "org.apache.kafka.connect.storage.StringConverter" },
  { "name": "CONNECT_VALUE_CONVERTER",              "value": "org.apache.kafka.connect.storage.StringConverter" },
  { "name": "CONNECT_PLUGIN_PATH",                  "value": "/usr/share/java,/usr/share/confluent-hub-components" },
  { "name": "AWS_REGION",                           "value": "ap-northeast-2" }
]
```

> Connect 내부 토픽(`connect-configs`, `connect-offsets`, `connect-status`)도 MSK에 자동 생성됩니다.  
> 운영 안정성을 위해 사전에 replication-factor=2로 수동 생성을 권장합니다.

### 3-4. 커넥터 설정 파일

#### JSONL.gz 포맷 (최종 후보) — `s3-sink-connector-jsonl.gz.json`

```json
{
  "name": "s3-sink-client-event-logs-jsonl",
  "config": {
    "connector.class": "io.confluent.connect.s3.S3SinkConnector",
    "tasks.max": "1",
    "topics": "client-event-logs",

    "s3.region": "${AWS_REGION}",
    "s3.bucket.name": "${S3_BUCKET_NAME}",
    "s3.part.size": "5242880",

    "flush.size": "500",
    "rotate.interval.ms": "300000",

    "storage.class": "io.confluent.connect.s3.storage.S3Storage",
    "format.class": "io.confluent.connect.s3.format.json.JsonFormat",
    "compression.type": "gzip",

    "partitioner.class": "io.confluent.connect.storage.partitioner.TimeBasedPartitioner",
    "timestamp.extractor": "RecordField",
    "timestamp.field": "timestamp",
    "path.format": "'events/raw/dt='YYYY-MM-dd'/hour='HH",
    "partition.duration.ms": "3600000",
    "locale": "ko_KR",
    "timezone": "UTC"
  }
}
```

#### Parquet 포맷 (Athena 최적화, 실험용) — `s3-sink-connector-parquet.json`

```json
{
  "name": "s3-sink-client-event-logs-parquet",
  "config": {
    "connector.class": "io.confluent.connect.s3.S3SinkConnector",
    "tasks.max": "1",
    "topics": "client-event-logs",

    "s3.region": "${AWS_REGION}",
    "s3.bucket.name": "${S3_BUCKET_NAME}",
    "s3.part.size": "5242880",

    "flush.size": "500",
    "rotate.interval.ms": "300000",

    "storage.class": "io.confluent.connect.s3.storage.S3Storage",
    "format.class": "io.confluent.connect.s3.format.parquet.ParquetFormat",
    "parquet.codec": "snappy",

    "partitioner.class": "io.confluent.connect.storage.partitioner.TimeBasedPartitioner",
    "timestamp.extractor": "RecordField",
    "timestamp.field": "timestamp",
    "path.format": "'events/raw/dt='YYYY-MM-dd'/hour='HH",
    "partition.duration.ms": "3600000",
    "locale": "ko_KR",
    "timezone": "UTC"
  }
}
```

#### 주요 설정 의도

| 설정 | 값 | 의도 |
|---|---|---|
| `flush.size` | 500 | 500건 쌓이면 S3에 플러시 |
| `rotate.interval.ms` | 300000 (5분) | 500건 미만이어도 5분마다 강제 플러시 |
| `timestamp.field` | `timestamp` | 이벤트 시간 기준으로 dt/hour 파티션 계산 |
| `partition.duration.ms` | 3600000 (1시간) | 1시간 단위 파티션 디렉터리 생성 |
| `path.format` | `events/raw/dt=YYYY-MM-dd/hour=HH` | Athena/ETL 파티션 표준 규칙 |

### 3-5. 커넥터 배포 절차

#### 1) Connect Worker가 기동되면 REST API로 커넥터 등록

```bash
export S3_BUCKET_NAME=holliverse-logs
export AWS_REGION=ap-northeast-2
export CONNECT_HOST=<connect-task-private-ip>  # ECS Task IP 또는 ALB

# JSONL.gz 커넥터 등록
envsubst < kafka-connect/s3-sink-connector-jsonl.gz.json \
  | curl -s -X POST http://$CONNECT_HOST:8083/connectors \
    -H "Content-Type: application/json" \
    -d @-
```

#### 2) 커넥터 상태 확인

```bash
# 등록된 커넥터 목록
curl http://$CONNECT_HOST:8083/connectors

# 특정 커넥터 상태 (RUNNING 확인)
curl http://$CONNECT_HOST:8083/connectors/s3-sink-client-event-logs-jsonl/status

# 커넥터 삭제 (재등록 시)
curl -X DELETE http://$CONNECT_HOST:8083/connectors/s3-sink-client-event-logs-jsonl
```

#### 3) S3 적재 확인

```bash
aws s3 ls s3://holliverse-logs/events/raw/ --recursive | head -20
# 예상 경로: events/raw/dt=2026-03-05/hour=12/part-00001.jsonl.gz
```

---

## 4. 로컬 개발 환경

MSK는 VPC 내부에서만 접속 가능하므로, 로컬에서는 `docker-compose.local.yml`의 Kafka를 사용합니다.

### 4-1. 로컬 기동 순서

```bash
# 1. Kafka + Kafka UI + PostgreSQL 컨테이너 기동
docker compose -f docker-compose.local.yml up -d

# 2. 기동 확인 (약 10초 소요)
docker logs kafka --tail 20

# 3. 토픽 생성
chmod +x scripts/create-topics.sh
./scripts/create-topics.sh --mode local

# 4. Spring Boot 실행 (application.yaml 기본값 = localhost)
./gradlew bootRun
```

### 4-2. 테스트 메시지 전송

```bash
# click_product_detail — product_view_history에 UPSERT됨
docker exec -i kafka kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic client-event-logs <<'EOF'
{"event_id":1000000000001,"timestamp":"2026-03-05T12:00:00Z","event":"click","event_name":"click_product_detail","member_id":45,"event_properties":{"page_url":"https://api.holliverse.site/api/v1/customer/plans","product_id":10,"product_name":"5G 요금제","product_type":"mobile","tags":["영상OTT","인기"]}}
EOF

# event_name이 다른 경우 — RecordFilterStrategy에서 폐기, DB 미적재
docker exec -i kafka kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic client-event-logs <<'EOF'
{"event_id":1000000000002,"timestamp":"2026-03-05T12:01:00Z","event":"click","event_name":"page_view","member_id":45,"event_properties":{"product_id":99,"product_name":"무시대상","product_type":"etc","tags":[]}}
EOF

# event_id 타입 오류 (String) — 역직렬화 실패 → error-logs DLQ로 전송
docker exec -i kafka kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic client-event-logs <<'EOF'
{"event_id":"uuid-1234-5678","timestamp":"2026-03-05T12:02:00Z","event":"click","event_name":"click_product_detail","member_id":45,"event_properties":{"product_id":47,"product_name":"타입오류","product_type":"mobile","tags":[]}}
EOF
```

### 4-3. PostgreSQL 적재 확인

```bash
# PostgreSQL 접속
docker exec -it postgres psql -U loguser -d logdb

# 적재된 레코드 확인
SELECT member_id, product_id, product_name, product_type, tags, viewed_at, last_event_id
FROM product_view_history
ORDER BY viewed_at DESC;

# 유저별 최근 본 상품 3개 조회 (RAG 서빙용 쿼리)
SELECT product_id, product_name, product_type, tags, viewed_at
FROM product_view_history
WHERE member_id = 45
ORDER BY viewed_at DESC
LIMIT 3;
```

### 4-4. DLQ(error-logs) 확인

```bash
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic error-logs \
  --from-beginning
```

---

## 5. 로컬 vs MSK 비교 요약

| 항목 | 로컬 | MSK 운영 |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | `b-1.xxx...:9092,b-2.xxx...:9092` |
| `DB_URL` | `jdbc:postgresql://localhost:5435/holliverse` | `jdbc:postgresql://<RDS_ENDPOINT>:5432/<DB>` |
| Kafka 기동 | `docker-compose.local.yml` | AWS MSK 자동 관리 |
| 토픽 생성 | `create-topics.sh --mode local` | `create-topics.sh --mode msk` |
| 모니터링 | Kafka UI (localhost:8081) | CloudWatch 자동 연동 |
| Spring Boot 코드 | 변경 없음 | 변경 없음 (환경변수만 교체) |
| Kafka Connect | 로컬 미사용 (테스트 불필요) | ECS Fargate Task |
