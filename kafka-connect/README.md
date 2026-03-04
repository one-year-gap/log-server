# Kafka Connect S3 Sink 설정

커넥터 JSON에 S3 버킷/리전이 환경변수 플레이스홀더로 되어 있습니다.  
등록 전에 `S3_BUCKET_NAME`, `AWS_REGION`을 설정한 뒤 치환해서 사용하세요.

## 환경변수

| 변수 | 설명 | 예시 |
|------|------|------|
| `S3_BUCKET_NAME` | S3 버킷 이름 | `my-logs-bucket` |
| `AWS_REGION` | S3 리전 | `ap-northeast-2` |

## 커넥터 등록 예시

```bash
export S3_BUCKET_NAME=your-bucket-name
export AWS_REGION=ap-northeast-2
envsubst < s3-sink-connector-jsonl.gz.json | curl -s -X POST http://<connect-host>:8083/connectors \
  -H "Content-Type: application/json" \
  -d @-
```

또는 치환된 파일을 임시로 만든 뒤 POST:

```bash
export S3_BUCKET_NAME=your-bucket-name
export AWS_REGION=ap-northeast-2
envsubst < s3-sink-connector-jsonl.gz.json > /tmp/connector.json
curl -s -X POST http://<connect-host>:8083/connectors \
  -H "Content-Type: application/json" \
  -d @/tmp/connector.json
```
