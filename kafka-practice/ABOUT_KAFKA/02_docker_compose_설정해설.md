# docker-compose.yml 해설 — kafka / kafka-ui

로컬 학습용 단일 노드 Kafka 4.x (KRaft) + 관리 UI 구성.

```bash
docker compose up -d      # 기동
docker compose ps         # 상태 확인
docker compose logs -f kafka
docker compose down       # 중지
rm -rf data               # 데이터까지 완전 초기화
```

| 접속 대상 | 주소 |
|---|---|
| 호스트(Spring Boot, CLI) → 브로커 | `localhost:9092` |
| 다른 컨테이너 → 브로커 | `kafka:19092` |
| 관리 UI | http://localhost:8090 |

---

## 전체 파일

```yaml
services:
  kafka:
    image: apache/kafka:4.1.0
    container_name: kafka
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER

      KAFKA_LISTENERS: INTERNAL://:19092,EXTERNAL://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:19092,EXTERNAL://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL

      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
      KAFKA_LOG_DIRS: /var/lib/kafka/data

      PATH: "/opt/kafka/bin:/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    volumes:
      - ./data:/var/lib/kafka/data
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1"]
      interval: 10s
      timeout: 10s
      retries: 10

  kafka-ui:
    image: kafbat/kafka-ui:latest
    container_name: kafka-ui
    ports:
      - "8090:8080"
    environment:
      KAFKA_CLUSTERS_0_NAME: local
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:19092
      DYNAMIC_CONFIG_ENABLED: "true"
    depends_on:
      kafka:
        condition: service_healthy
```

---

# kafka 서비스

## image / ports

```yaml
image: apache/kafka:4.1.0
ports:
  - "9092:9092"
```

- 아파치 공식 이미지. 4.x 이므로 **ZooKeeper 가 없다**.
- `ports` 는 `호스트포트:컨테이너포트`. 이 줄이 있어야 내 PC 에서 도는 Spring Boot 가 `localhost:9092` 로 붙는다.
- 9093(컨트롤러), 19092(컨테이너 간)는 밖에서 쓸 일이 없어 일부러 열지 않았다.

## environment 읽는 법

`KAFKA_` 로 시작하는 변수는 이미지 시작 스크립트가 `server.properties` 항목으로 변환한다.

> `KAFKA_` 떼고 → 소문자 → `_` 를 `.` 로

```
KAFKA_NODE_ID             →  node.id
KAFKA_ADVERTISED_LISTENERS →  advertised.listeners
```

이렇게 바꾸면 카프카 공식 문서에서 그대로 검색된다.

## KRaft — ZooKeeper 대체

```yaml
KAFKA_NODE_ID: 1
KAFKA_PROCESS_ROLES: broker,controller
KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
```

| 항목 | 설명 |
|---|---|
| `NODE_ID` | 이 노드의 번호. 클러스터 내 유일해야 한다 |
| `PROCESS_ROLES` | 이 노드가 맡을 역할. 실무에서는 보통 분리하지만 로컬은 한 프로세스가 겸임 |
| `CONTROLLER_QUORUM_VOTERS` | 컨트롤러 명단. `노드번호@호스트:포트` 형식. 실무 예: `1@c1:9093,2@c2:9093,3@c3:9093` |
| `CONTROLLER_LISTENER_NAMES` | 아래 리스너 중 컨트롤러 통신용이 어느 것인지 지정 |

- `controller` 역할 = 메타데이터 관리, 파티션 담당(리더) 지정. 원래 ZooKeeper 가 하던 일.
- 컨트롤러가 여러 대면 그중 하나만 실제로 일하며, 이를 **액티브 컨트롤러**라 한다. 나머지는 스탠바이.
- `quorum` = 과반. 3대면 2대까지, 5대면 3대까지 장애를 견딘다. 홀수를 쓰는 이유.

## 리스너 — 가장 헷갈리는 부분

```yaml
KAFKA_LISTENERS:            INTERNAL://:19092,EXTERNAL://:9092,CONTROLLER://:9093
KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:19092,EXTERNAL://localhost:9092
```

- `LISTENERS` = 브로커가 실제로 **여는 문**
- `ADVERTISED_LISTENERS` = 클라이언트에게 **"다음부터 여기로 와"** 하고 알려주는 주소

### 왜 알려줘야 하나

카프카 접속은 2단계다.

1. `bootstrap-servers` 로 접속해서 메타데이터(어느 파티션의 담당이 누구인지) 수신
2. **응답에 적힌 주소로 다시 접속**해서 실제 읽기·쓰기

즉 `bootstrap-servers` 는 최종 목적지가 아니라 안내소다.
그래서 advertised 값이 틀리면 **"접속은 되는데 메시지 보낼 때 타임아웃"** 이 난다.

### 접속한 포트가 답을 결정한다

브로커는 클라이언트가 **들어온 포트에 해당하는 리스너**의 주소를 돌려준다.

| 누가 | 어느 문으로 | 돌려받는 주소 |
|---|---|---|
| 호스트 (Spring Boot, CLI) | EXTERNAL :9092 | `localhost:9092` |
| 다른 컨테이너 (kafka-ui, Connect) | INTERNAL :19092 | `kafka:19092` |
| 자기 자신 (KRaft) | CONTROLLER :9093 | quorum voters 값 |

문이 3개인데 안내문이 2개인 이유: CONTROLLER 는 클라이언트가 찾아올 문이 아니라 advertised 가 필요 없다.

> **흔한 실수**
> kafka-ui 설정을 `kafka:9092` 로 하면 — 접속은 된다. 하지만 9092 는 EXTERNAL 문이라 안내문이 `localhost:9092` 이고,
> 컨테이너 안에서 `localhost` 는 kafka-ui 자기 자신이므로 실패한다.

### 나머지 두 줄

```yaml
KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT
KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
```

- `INTERNAL` / `EXTERNAL` 은 예약어가 아니라 임의로 지은 별명이다. 별명을 새로 지었으므로 각각 어떤 프로토콜인지 알려줘야 한다.
- `PLAINTEXT` = 암호화·인증 없음. 로컬 학습용이므로 괜찮지만 이대로 외부에 열면 안 된다.
- `INTER_BROKER_LISTENER_NAME` = 브로커끼리 통신할 때 쓸 문. 단일 노드라 실질적 의미는 없지만 값은 필요하다.

## 단일 노드용 설정

```yaml
KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
```

카프카는 내부적으로도 토픽을 쓴다.

| 내부 토픽 | 저장하는 것 |
|---|---|
| `__consumer_offsets` | 컨슈머 그룹이 어디까지 읽었는지 |
| `__transaction_state` | 트랜잭션 상태 |

이 토픽들의 기본 `replication-factor` 가 **3** 인데, 브로커 1대에서는 3벌을 만들 수 없어 에러가 난다.
`MIN_ISR` 은 `min.insync.replicas` — 쓰기를 성공으로 인정하려면 최소 몇 벌이 받아야 하는가. (`acks=all` 일 때만 의미 있음)

> **안 적으면 어떻게 되나**
> 브로커는 정상적으로 뜬다. 내부 토픽은 처음 필요해질 때 만들어지기 때문에,
> `--group` 을 붙여 컨슈머를 띄우는 순간 터진다.
>
> 컨슈머 터미널: `{__consumer_offsets=INVALID_REPLICATION_FACTOR}` WARN 무한 반복
> 브로커 로그: `Number of alive brokers '1' does not meet the required replication factor '3'`
>
> `--group` 없이 쓰면 임시 그룹이라 `__consumer_offsets` 를 안 건드려 멀쩡한 것도 혼란 포인트.

```yaml
KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
KAFKA_LOG_DIRS: /var/lib/kafka/data
```

| 항목 | 기본값 | 설명 |
|---|---|---|
| `GROUP_INITIAL_REBALANCE_DELAY_MS` | 3000 | 컨슈머 그룹 첫 리밸런싱 대기 시간. 여러 컨슈머가 동시에 뜰 것을 기다려 한 번에 처리하려는 것. 로컬은 0 |
| `AUTO_CREATE_TOPICS_ENABLE` | true | 없는 토픽에 보내면 자동 생성(파티션 1, 복제본 1). 오타 토픽이 조용히 생기는 부작용이 있어 운영에서는 보통 끈다 |
| `LOG_DIRS` | /tmp/kraft-combined-logs | 메시지가 실제로 저장되는 경로 |

여기서 "로그"는 애플리케이션 로그가 아니라 **메시지 저장 파일**이다.

### 뺄 수 있는 것 / 없는 것

| | |
|---|---|
| 빼도 됨 | `AUTO_CREATE_TOPICS_ENABLE` (기본값과 동일), `GROUP_INITIAL_REBALANCE_DELAY_MS` (3초 기다릴 뿐) |
| 빼면 안 됨 | 복제본 3줄, `LOG_DIRS` (볼륨 마운트 경로와 짝이 맞아야 함) |

## PATH

```yaml
PATH: "/opt/kafka/bin:/opt/java/openjdk/bin:..."
```

카프카 설정이 아니라 리눅스 환경변수. 컨테이너에 들어가서 `kafka-topics.sh` 를 긴 경로 없이 바로 치기 위한 것.

> `docker exec -it kafka bash -l` 처럼 `-l`(로그인 셸)을 붙이면 PATH 가 초기화되어 `command not found` 가 난다. 그냥 `bash` 로 들어갈 것.

## volumes

```yaml
volumes:
  - ./data:/var/lib/kafka/data
```

내 PC 의 `data` 폴더를 컨테이너 안 경로에 연결(bind mount). 위 `LOG_DIRS` 와 짝이다.

- 컨테이너를 지워도 메시지가 남는다
- 완전 초기화는 `rm -rf data`
- 파티션은 이 아래에 디렉터리로 존재한다: `data/quickstart-events-0/00000000000000000000.log`

> KRaft 는 이 폴더에 클러스터 ID 를 저장한다. `NODE_ID` 등을 바꿨는데 `data` 를 그대로 두면 ID 불일치로 기동에 실패한다. 그럴 땐 `data` 를 통째로 지우고 다시 올린다.

## healthcheck

```yaml
healthcheck:
  test: ["CMD-SHELL", "... kafka-broker-api-versions.sh --bootstrap-server localhost:9092 ..."]
  interval: 10s
  retries: 10
```

10초마다 브로커에 말을 걸어보고 응답하면 healthy. 최대 10회 시도.

컨테이너가 **시작된 것**과 카프카가 **준비된 것**은 다르다(기동에 십몇 초 걸림). 아래 `depends_on` 과 짝을 이룬다.

---

# kafka-ui 서비스

```yaml
image: kafbat/kafka-ui:latest
ports:
  - "8090:8080"
environment:
  KAFKA_CLUSTERS_0_NAME: local
  KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:19092
  DYNAMIC_CONFIG_ENABLED: "true"
depends_on:
  kafka:
    condition: service_healthy
```

| 항목 | 설명 |
|---|---|
| `8090:8080` | UI 는 컨테이너 안에서 8080 을 쓰는데, 8080 은 보통 Spring Boot 가 쓰므로 8090 으로 내보냈다 |
| `KAFKA_CLUSTERS_0_*` | 0번 클러스터. 여러 개 등록할 수 있어 번호가 붙는다 |
| `BOOTSTRAPSERVERS: kafka:19092` | UI 도 도커 네트워크 안이므로 INTERNAL 리스너를 쓴다 |
| `DYNAMIC_CONFIG_ENABLED` | UI 에서 클러스터 설정 추가·수정 허용 |
| `depends_on ... service_healthy` | 카프카가 healthy 될 때까지 UI 를 띄우지 않는다 |

`depends_on` 만 쓰면 "컨테이너가 시작됨"까지만 기다려서, UI 가 먼저 떠 연결 실패 화면을 보게 된다.

**개선 제안**: `latest` 는 어느 날 버전이 올라가 깨질 수 있다. 카프카는 `4.1.0` 으로 고정했으니 UI 도 특정 버전으로 박아두는 편이 낫다.

---

# 네트워크

`networks:` 를 따로 쓰지 않아도 compose 가 프로젝트 이름으로 기본 네트워크를 자동 생성하고 모든 서비스를 여기에 붙인다.

```bash
docker network ls   # kafka-practice_default 같은 이름이 보인다
```

이 네트워크 안에서는 **서비스 이름이 그대로 호스트명**이 된다. `kafka-ui` 가 `kafka:19092` 로 접속할 수 있는 이유이며, 새 서비스(mariadb, connect 등)를 추가해도 자동으로 같은 네트워크에 들어간다.

---

# CLI 빠른 참조

```bash
docker exec -it kafka bash        # 컨테이너 진입 (나올 때 exit)

# 토픽
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic quickstart-events --partitions 1
kafka-topics.sh --bootstrap-server localhost:9092 --list
kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic quickstart-events
kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic quickstart-events

# 메시지 (Ctrl+D 종료)
kafka-console-producer.sh --bootstrap-server localhost:9092 --topic quickstart-events
kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic quickstart-events --from-beginning

# 파티션·offset 까지 표시
kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic quickstart-events \
  --from-beginning --property print.key=true --property print.partition=true --property print.offset=true

# 컨슈머 그룹 상태 (LAG = 밀린 양)
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group study-group

# offset 되감기 (그룹에 붙어있는 컨슈머가 없어야 함)
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group study-group --topic quickstart-events \
  --reset-offsets --to-earliest --execute

# 디스크에 저장된 로그 파일 직접 열어보기
kafka-dump-log.sh --files /var/lib/kafka/data/quickstart-events-0/00000000000000000000.log --print-data-log
```

컨테이너 밖에서 한 줄로 실행하려면 앞에 `docker exec -it kafka` 를 붙인다.

> `--zookeeper` 옵션은 4.x 에 없다. `--bootstrap-server` 만 쓴다.
> `--broker-list` 도 없어졌다. 프로듀서도 `--bootstrap-server` 를 쓴다.

## 로그 보기

```bash
docker compose logs -f kafka
```

메시지 한 건 한 건은 로그에 찍히지 않는다. 브로커 로그에 나오는 것:

- 기동/종료, 토픽 생성, 파티션 배치
- 컨슈머 그룹 가입·탈퇴, 리밸런싱
- 담당 브로커 변경, 에러

메시지 흐름은 관리 UI(http://localhost:8090) 또는 위의 `print.*` 옵션으로 확인한다.