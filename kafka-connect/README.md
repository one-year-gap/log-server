# Kafka Connect S3 Sink 설정

커넥터 JSON에 S3 버킷/리전이 환경변수 플레이스홀더로 되어 있습니다.  
등록 전에 `S3_BUCKET_NAME`, `AWS_REGION`을 설정한 뒤 치환해서 사용하세요.

## 환경변수

| 변수 | 설명 | 예시 |
|------|------|------|
| `CONNECTOR_FILE` | 등록할 커넥터 JSON 파일명 | `s3-sink-connector-jsonl.gz.json` 또는 `s3-sink-connector-parquet.json` |
| `S3_BUCKET_NAME` | S3 버킷 이름 | `my-logs-bucket` |
| `AWS_REGION` | S3 리전 | `ap-northeast-2` |

## 커넥터 등록 예시

두 커넥터 모두 같은 방식으로 등록할 수 있습니다. `CONNECTOR_FILE`만 바꿔서 사용하세요.

```bash
# CONNECTOR_FILE은 s3-sink-connector-jsonl.gz.json 또는 s3-sink-connector-parquet.json 중 하나로 설정
export CONNECTOR_FILE=s3-sink-connector-jsonl.gz.json
export S3_BUCKET_NAME=your-bucket-name
export AWS_REGION=ap-northeast-2

envsubst < $CONNECTOR_FILE | curl -s -X POST http://<connect-host>:8083/connectors \
  -H "Content-Type: application/json" \
  -d @-
```

치환된 내용을 임시 파일로 만든 뒤 POST하는 방식:

```bash
export CONNECTOR_FILE=s3-sink-connector-parquet.json
export S3_BUCKET_NAME=your-bucket-name
export AWS_REGION=ap-northeast-2

envsubst < $CONNECTOR_FILE > /tmp/connector.json
curl -s -X POST http://<connect-host>:8083/connectors \
  -H "Content-Type: application/json" \
  -d @/tmp/connector.json
```
