# Kafka 연습 (Docker, KRaft 모드)

강의는 Kafka 2.x + ZooKeeper 기준이지만, 여기서는 **Kafka 4.1 / ZooKeeper 없음(KRaft)** 으로 띄웁니다.
Kafka 4.0부터 ZooKeeper는 아예 제거됐습니다. 강의에 `zookeeper-server-start.sh` 가 나오면 **그 단계는 통째로 건너뛰시면 됩니다.**

## 실행

```bash
docker compose up -d      # 기동 (kafka + 관리 UI)
docker compose ps         # 상태 확인
docker compose down       # 중지
rm -rf data               # 데이터까지 완전 초기화
```

| 접속 대상 | 주소 |
|---|---|
| 호스트(Spring Boot, CLI) → 브로커 | `localhost:9092` |
| 다른 컨테이너 → 브로커 | `kafka:19092` |
| 관리 UI (브라우저) | http://localhost:8090 |

메시지는 **`kafka-practice/data/`** 에 그대로 쌓입니다(bind mount). git에는 올라가지 않도록 `.gitignore` 처리해 두었습니다.

> 리스너를 2개 두는 이유: 브로커가 클라이언트에게 "나한테 연결하려면 이 주소로 와"라고 알려주는 값(`advertised.listeners`)이
> 호스트에서는 `localhost:9092`, 도커 네트워크 안에서는 `kafka:19092` 로 서로 달라야 하기 때문입니다. 입문자가 제일 많이 막히는 지점입니다.

## 개념 3줄

```
Producer ──► [ Topic: practice-topic ]                          ──► Consumer Group
                 ├ Partition 0  [msg][msg]        ┐
                 ├ Partition 1  [msg][msg][msg]   ├─ 파티션 = 병렬 처리 단위
                 └ Partition 2  [msg]             ┘   (한 파티션은 그룹 내 컨슈머 1명이 담당)
```

- **Topic** = 메시지가 쌓이는 이름표, **Partition** = 그 토픽을 쪼갠 로그 파일(병렬성 단위)입니다.
- **같은 key → 항상 같은 파티션 → 순서 보장.** key가 없으면 파티션에 흩어져서 전체 순서는 보장되지 않습니다.
- **Consumer Group**: 그룹 단위로 offset(어디까지 읽었나)을 기억합니다. 그룹이 다르면 같은 메시지를 각자 다시 읽습니다.
- 메시지는 읽어도 **사라지지 않습니다.** offset만 앞으로 갈 뿐이라, 되감으면 처음부터 다시 읽을 수 있습니다.

## CLI 사용법

CLI 도구는 컨테이너 안에 있습니다. **컨테이너에 한 번 들어가서 작업하는 쪽이 편합니다.**

```bash
docker exec -it kafka bash     # 나올 때는 exit
export BS=localhost:9092       # 매번 치는 브로커 주소를 변수로
```

`PATH` 에 `/opt/kafka/bin` 을 넣어두었기 때문에 안에서는 경로 없이 바로 실행됩니다.

> 주의: `bash -l` 처럼 **`-l`(로그인 셸)을 붙이면 PATH가 초기화**되어 `command not found` 가 납니다. 그냥 `bash` 로 들어가세요.

```bash
# 토픽 생성 / 목록 / 상세 / 삭제
kafka-topics.sh --bootstrap-server $BS --create --topic practice-topic --partitions 3 --replication-factor 1
kafka-topics.sh --bootstrap-server $BS --list
kafka-topics.sh --bootstrap-server $BS --describe --topic practice-topic
kafka-topics.sh --bootstrap-server $BS --delete   --topic practice-topic

# 메시지 보내기 (한 줄 = 메시지 1건, Ctrl+D 로 종료)
kafka-console-producer.sh --bootstrap-server $BS --topic practice-topic

# key 를 붙여서 보내기  (u1:주문생성 → key=u1, value=주문생성)
kafka-console-producer.sh --bootstrap-server $BS --topic practice-topic \
  --property parse.key=true --property key.separator=:

# 읽기 (처음부터, key/파티션까지 표시)
kafka-console-consumer.sh --bootstrap-server $BS --topic practice-topic \
  --from-beginning --property print.key=true --property print.partition=true

# 컨슈머 그룹으로 읽기 → offset 이 저장됨
kafka-console-consumer.sh --bootstrap-server $BS --topic practice-topic --from-beginning --group study-group

# 그룹의 offset / LAG(밀린 양) 확인 — 운영에서 제일 자주 보는 명령
kafka-consumer-groups.sh --bootstrap-server $BS --describe --group study-group

# offset 되감기 (그룹에 붙어있는 컨슈머가 없어야 함)
kafka-consumer-groups.sh --bootstrap-server $BS --group study-group --topic practice-topic \
  --reset-offsets --to-earliest --execute
```

`--bootstrap-server` 만 씁니다. 강의에 `--zookeeper` 옵션이 나오면 **4.x 에는 없는 옵션**입니다.

컨테이너 밖에서 한 줄로 실행하고 싶을 때는 앞에 `docker exec kafka /opt/kafka/bin/` 을 붙이면 됩니다.

## Spring Boot 3 연결 시

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
```

- 단순 발행/구독이면 `spring-kafka` 의 `KafkaTemplate` + `@KafkaListener` 를 씁니다.
- 이 프로젝트처럼 MSA 이벤트 흐름을 붙일 거라면 `spring-cloud-stream-binder-kafka` 도 선택지입니다.
- 로컬 단일 노드라 복제본이 1개뿐이므로, 프로듀서에 `acks=all` 을 줘도 실제 복제 안전성은 없습니다. 학습용으로만 사용하세요.
