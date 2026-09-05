# Kafka Connect 실습 (Docker)

Source: MariaDB 테이블에 INSERT 하면 자동으로 Kafka 토픽에 메시지가 쌓인다.
Sink: 토픽에 쌓인 메시지가 자동으로 MariaDB 테이블에 들어간다.

강의(Confluent 6.1 tar.gz + MacOS 직접 설치)를 Docker 로 옮긴 버전이다.

| 강의 | 여기 |
|---|---|
| `brew install mariadb` | mariadb 컨테이너 |
| Confluent tar.gz 압축 해제 | `cp-kafka-connect` 이미지 |
| `connect-distributed.properties` 편집 | `CONNECT_*` 환경변수 |
| `plugin.path=/Users/.../lib` | 볼륨 마운트 `./connect-plugins:/plugins` |
| `jdbc:mysql://localhost:3306` | `jdbc:mariadb://mariadb:3306` |

---

# Source Connect (DB → 토픽)

## 1. docker-compose.yml 에 서비스 추가

기존 `kafka`, `kafka-ui` 아래에 같은 들여쓰기로 추가한다.

```yaml
  mariadb:
    image: mariadb:11.4
    container_name: mariadb
    ports:
      - "3306:3306"
    environment:
      MARIADB_ROOT_PASSWORD: test1357
      MARIADB_DATABASE: mydb
    volumes:
      - ./mariadb-data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "healthcheck.sh", "--connect", "--innodb_initialized"]
      interval: 10s
      timeout: 5s
      retries: 10

  connect:
    image: confluentinc/cp-kafka-connect:8.3.1
    container_name: connect
    ports:
      - "8083:8083"
    environment:
      CONNECT_BOOTSTRAP_SERVERS: kafka:19092
      CONNECT_GROUP_ID: connect-cluster
      CONNECT_REST_ADVERTISED_HOST_NAME: connect
      CONNECT_CONFIG_STORAGE_TOPIC: connect-configs
      CONNECT_OFFSET_STORAGE_TOPIC: connect-offsets
      CONNECT_STATUS_STORAGE_TOPIC: connect-status
      CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR: 1
      CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR: 1
      CONNECT_STATUS_STORAGE_REPLICATION_FACTOR: 1
      CONNECT_KEY_CONVERTER: org.apache.kafka.connect.json.JsonConverter
      CONNECT_VALUE_CONVERTER: org.apache.kafka.connect.json.JsonConverter
      CONNECT_PLUGIN_PATH: /usr/share/java,/plugins
    volumes:
      - ./connect-plugins:/plugins
    healthcheck:
      disable: true          # 아래 설명 참고
    depends_on:
      kafka:
        condition: service_healthy
      mariadb:
        condition: service_healthy
```

`.gitignore` 에 `mariadb-data/` 추가.

### environment 설명

`CONNECT_` 접두사 규칙은 `KAFKA_` 와 동일하다. 접두사 떼고 → 소문자 → `_` 를 `.` 로.
즉 아래 환경변수들이 강의에서 편집하던 `connect-distributed.properties` 의 내용을 대신한다.

```
CONNECT_PLUGIN_PATH       →  plugin.path
CONNECT_BOOTSTRAP_SERVERS →  bootstrap.servers
CONNECT_GROUP_ID          →  group.id
```

**연결 / 식별**

| 항목 | 설명 |
|---|---|
| `CONNECT_BOOTSTRAP_SERVERS: kafka:19092` | 접속할 브로커. Connect 는 컨테이너 안이므로 INTERNAL 리스너(19092)를 쓴다. `localhost:9092` 로 쓰면 Connect 자기 자신을 찾는다 |
| `CONNECT_GROUP_ID: connect-cluster` | Connect 워커들의 **컨슈머 그룹 이름**. Connect 도 내부적으로는 그냥 카프카 클라이언트다. 여러 워커를 띄우면 이 이름이 같아야 한 클러스터로 묶인다 |
| `CONNECT_REST_ADVERTISED_HOST_NAME: connect` | 다른 워커가 이 워커를 찾아올 주소. 브로커의 `advertised.listeners` 와 같은 개념. 단일 워커면 큰 의미는 없지만 필수값이다 |

**내부 상태 저장 토픽**

Connect 는 자기 상태를 파일이 아니라 카프카 토픽에 저장한다. 그래서 컨테이너가 죽어도 등록해둔 커넥터가 유지된다.

| 항목 | 저장하는 것 |
|---|---|
| `CONNECT_CONFIG_STORAGE_TOPIC: connect-configs` | 등록한 커넥터 설정 (REST 로 POST 한 JSON) |
| `CONNECT_OFFSET_STORAGE_TOPIC: connect-offsets` | Source 커넥터가 어디까지 읽었는지 (예: `users` 테이블의 마지막 id) |
| `CONNECT_STATUS_STORAGE_TOPIC: connect-status` | 커넥터/태스크의 RUNNING·FAILED 상태 |

> 토픽 목록에 이 셋이 보이는 이유가 이것이다. Sink 커넥터의 오프셋은 여기가 아니라 일반 컨슈머와 동일하게 `__consumer_offsets` 에 저장된다.

**복제본 설정 — 브로커 1대면 필수**

| 항목 | 설명 |
|---|---|
| `CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR: 1` | 위 세 토픽의 `--replication-factor` |
| `CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR: 1` | 기본값이 3 이라 그냥 두면 |
| `CONNECT_STATUS_STORAGE_REPLICATION_FACTOR: 1` | 브로커 1대에서 3벌을 못 만들어 기동에 실패한다 |

카프카의 `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1` 과 완전히 같은 이유다.

**데이터 형식**

| 항목 | 설명 |
|---|---|
| `CONNECT_KEY_CONVERTER` | 메시지 key 를 어떤 형식으로 다룰지 |
| `CONNECT_VALUE_CONVERTER` | 메시지 value 를 어떤 형식으로 다룰지 |

`JsonConverter` 를 쓰면 `{"schema":{...},"payload":{...}}` 형태가 된다. Sink 가 컬럼 타입을 알아야 테이블을 만들 수 있어서 스키마를 함께 실어 보내는 것. (Sink 섹션 참고)

실무에서는 이 부분을 Avro + Schema Registry 로 바꿔 메시지 크기를 줄이는 경우가 많다.

**플러그인 경로**

| 항목 | 설명 |
|---|---|
| `CONNECT_PLUGIN_PATH: /usr/share/java,/plugins` | 커넥터 jar 를 찾을 디렉터리 목록 |

- `/usr/share/java` — 이미지에 기본 포함된 커넥터들
- `/plugins` — 볼륨으로 마운트한 우리 폴더 (`./connect-plugins`)

강의에서 `plugin.path=/Users/.../lib` 로 편집하던 그 값이다. 컨테이너 안 경로를 적어야 하므로 호스트 경로가 아니라 `/plugins` 다.

**헬스체크 — 왜 끄나**

`cp-kafka-connect` 이미지에는 `HEALTHCHECK` 가 **이미 박혀 있다.** 그런데 그 스크립트가 `curl` 을 쓰는데
정작 이미지 안에 `curl` 이 없다(`nc`, `wget` 도 없음). 그래서 컨테이너가 멀쩡히 동작하는데도 —
8083 은 LISTEN 중이고 REST 도 200 을 준다 — 상태만 영원히 `unhealthy` 로 뜬다.

`healthcheck` 항목을 **안 쓰면 이미지 것이 그대로 상속된다.** 끄려면 `disable: true` 를 명시해야 한다.

| 이미지에 HEALTHCHECK | compose 에 항목 없음 | `disable: true` |
|---|---|---|
| 있음 (`connect`) | 이미지 것이 실행됨 | 꺼짐 |
| 없음 (`kafka`, `mariadb`, `kafka-ui`) | 검사 없음 | 의미 없음 |

> 도커 헬스체크는 **상태 딱지를 붙일 뿐 아무것도 고치지 않는다.** `depends_on: condition: service_healthy`
> 같은 소비자가 있어야 값어치가 생긴다. connect 를 기다리는 서비스가 없으므로 꺼도 잃는 것이 없다.
> 나중에 "connect 가 뜬 뒤 커넥터 자동 등록" 같은 걸 붙일 일이 생기면, 끄는 대신 이렇게 덮어쓰면 된다.
>
> ```yaml
> healthcheck:
>   test: ["CMD", "bash", "-c", "echo > /dev/tcp/localhost/8083"]
>   interval: 10s
>   timeout: 5s
>   retries: 20
>   start_period: 60s
> ```
>
> `/dev/tcp/호스트/포트` 는 실제 파일이 아니라 **bash 가 가로채는 가짜 경로**다. 그리로 리다이렉션하면
> bash 가 그 주소로 TCP 연결을 걸어보고, 연결되면 종료코드 0 을 낸다. `curl` 없이 포트 열림만 확인하는 우회법이다.
> (`sh` 로는 안 되고 반드시 `bash` 여야 한다. 포트가 열렸다는 것만 알지 REST 응답까지 보는 것은 아니다.)

---

## 2. 플러그인 준비

**이 실습에서 제일 많이 막히는 지점이다.** 강의에서 쓰던 Confluent Platform tar.gz 에는 JDBC 커넥터가 이미 들어있었지만,
`cp-kafka-connect` 도커 이미지에는 **들어있지 않다.** 직접 받아서 넣어야 한다.

```bash
mkdir -p connect-plugins/jdbc
```

### JDBC 커넥터 받기

최신 버전 확인:

```bash
curl -s https://api.hub.confluent.io/api/plugins/confluentinc/kafka-connect-jdbc/versions \
  | python3 -c "import json,sys; print(json.load(sys.stdin)[-1]['version'])"
```

받아서 풀기 (`VER` 는 위에서 확인한 버전):

```bash
VER=10.9.7
curl -sLO https://hub-downloads.confluent.io/api/plugins/confluentinc/kafka-connect-jdbc/versions/$VER/confluentinc-kafka-connect-jdbc-$VER.zip
unzip -q confluentinc-kafka-connect-jdbc-$VER.zip -d /tmp/jdbc-conn
cp /tmp/jdbc-conn/*/lib/*.jar connect-plugins/jdbc/
```

`lib` **폴더째로 넣으면 안 된다.** 안의 jar 파일만 평평하게 꺼내야 한다(이유는 아래).

### MariaDB 드라이버

커넥터 zip 에는 Oracle·MSSQL·PostgreSQL·SQLite 드라이버만 들어있고 **MariaDB 드라이버는 없다.** 따로 받는다.

```bash
curl -o connect-plugins/jdbc/mariadb-java-client-3.5.2.jar \
  https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.5.2/mariadb-java-client-3.5.2.jar
```

### 왜 전부 한 폴더에 넣나 — plugin.path 규칙

Connect 는 `plugin.path` 경로의 **바로 아래 1단계 폴더 하나 = 플러그인 1개** 로 보고,
그 안의 jar 전부를 **격리된 클래스로더**에 묶어 올린다. 커넥터끼리 의존성 버전이 충돌하지 않게 하려는 구조다.

```
connect-plugins/                       ← CONNECT_PLUGIN_PATH 의 /plugins
└── jdbc/                              ← 이 폴더 하나가 "플러그인 1개"
    ├── kafka-connect-jdbc-*.jar         커넥터 본체
    ├── mariadb-java-client-*.jar        드라이버 ← 반드시 같은 폴더
    └── (의존성 jar 들)
```

드라이버를 `connect-plugins/driver/` 처럼 다른 폴더에 두면 커넥터 클래스로더가 못 본다.

막히는 순서가 정해져 있다.

| 증상 | 원인 |
|---|---|
| `/connector-plugins` 응답에 jdbc 가 없음 | 커넥터 jar 자체가 없음 (MirrorMaker 3개만 보인다) |
| 등록 시 `Failed to find any class that implements Connector` | 위와 같음 |
| 등록은 되는데 태스크가 `No suitable driver found for jdbc:mariadb://` 로 FAILED | 커넥터는 있는데 드라이버가 다른 폴더에 있음 |

### 어떤 jar 가 실제로 필요한가

zip 에는 jar 가 19개(27MB) 들어있지만 MariaDB 실습에 쓰이는 건 일부다.

| jar | 판정 |
|---|---|
| `kafka-connect-jdbc-*` | **필수** — 커넥터 본체 |
| `mariadb-java-client-*` | **필수** — 드라이버 |
| `jsqlparser-*` | **필수** — `query` 모드의 SQL 파싱. 이미지에 없어서 대체 불가.<br>빼면 커넥터 등록이 HTTP 500 (`NoClassDefFoundError: net/sf/jsqlparser/JSQLParserException`) |
| `common-utils-*`, `re2j-*`, `slf4j-api-*` | 이미지 클래스패스에도 있어서 빼도 돌아가지만, 버전이 다르므로(예: common-utils 7.0.12 vs 이미지 8.3.1) 그대로 둔다 |
| `ojdbc8`·`ucp`·`xdb` 등 Oracle 10개, `sqlite-jdbc`, `mssql-jdbc`, `jtds`, `postgresql` | 다른 DB 드라이버. **지워도 무방** (27MB → 2.2MB) |

용량이 신경 쓰이면 위 6개만 남긴다. 나중에 PostgreSQL 등을 붙일 때 해당 드라이버만 다시 넣으면 된다.

`jsqlparser` 가 함정이다. `mode: incrementing` + `table.whitelist` 만 쓰면 이 파서를 안 타서
없어도 되는 것처럼 보이지만, `query` 모드로 넘어가는 순간 등록 자체가 실패한다.

### .gitignore

커넥터 jar 묶음은 저장소에 올리지 않는다. 드라이버 하나만 남긴다(저장소 루트 기준 경로).

```gitignore
kafka-practice/connect-plugins/jdbc/*.jar
!kafka-practice/connect-plugins/jdbc/mariadb-java-client-*.jar
```

> **라이선스** — 이 커넥터는 Apache-2.0 이 아니라 **Confluent Community License** 다.
> 학습·내부 사용은 자유롭지만 이걸로 경쟁 SaaS 를 만드는 건 제한된다.
> 순수 오픈소스가 필요하면 Aiven 의 `jdbc-connector-for-apache-kafka` 로 바꾸고,
> `connector.class` 의 `io.confluent` 를 `io.aiven` 으로 바꾸면 된다.

---

## 3. 기동 및 플러그인 확인

```bash
docker compose up -d
docker compose ps
docker compose logs -f connect
```

Connect 기동에 1분 정도 걸린다. `Kafka Connect started` 확인.

```bash
curl -s localhost:8083/connector-plugins | grep -i jdbc
```

`JdbcSourceConnector` 가 보이면 성공. 안 보이면 `connect-plugins/jdbc/` 에 jar 가 제대로 있는지 확인.

> 여기서 막히는 경우가 가장 많다.

---

## 4. 테이블 생성

```bash
docker exec -it mariadb mariadb -uroot -ptest1357 mydb
```

```sql
create table users (
  id int auto_increment primary key,
  user_id varchar(20),
  pwd varchar(20),
  name varchar(20),
  created_at datetime default current_timestamp
);
```

`mode: incrementing` 을 쓰므로 `id` 같은 증가 컬럼이 반드시 필요하다.

---

## 5. 커넥터 등록

```bash
curl -X POST localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d '{
    "name": "my-source-connect",
    "config": {
      "connector.class": "io.confluent.connect.jdbc.JdbcSourceConnector",
      "connection.url": "jdbc:mariadb://mariadb:3306/mydb",
      "connection.user": "root",
      "connection.password": "test1357",
      "catalog.pattern": "mydb",
      "mode": "incrementing",
      "incrementing.column.name": "id",
      "table.whitelist": "users",
      "topic.prefix": "my_topic_",
      "tasks.max": "1"
    }
  }'
```

| 항목 | 의미 |
|---|---|
| `connection.url` | `mariadb` 는 컨테이너 이름. `localhost` 로 쓰면 Connect 자기 자신을 찾는다 |
| `catalog.pattern` | **테이블을 찾을 DB 를 좁힌다.** 없으면 기동 실패 — 아래 참고 |
| `mode: incrementing` | 증가 컬럼을 보고 새 행을 감지 |
| `table.whitelist` | 감시할 테이블 |
| `topic.prefix` | 토픽 이름 앞에 붙는 접두사 → `my_topic_users` |
| `tasks.max` | 병렬 태스크 수 |

### `catalog.pattern` 이 없으면 — 등록은 되는데 곧바로 FAILED

강의에는 없는 옵션이다. 빼고 등록하면 `HTTP 201` 이 오지만 상태를 보면 이렇게 되어 있다.

```
ConnectException: The connector uses the unqualified table name as the topic name
and has detected duplicate unqualified table names.
```

커넥터는 테이블을 찾을 때 **서버의 모든 DB 를 뒤진다.** MariaDB 11.4 에는 `performance_schema.users` 가 있어서,
`table.whitelist: "users"` 가 `mydb.users` 와 둘 다 걸린다. 어느 쪽인지 모르니 기동을 거부하는 것이다.

```sql
select table_schema, table_name from information_schema.tables where table_name='users';
-- mydb                users
-- performance_schema  users   ← 이것 때문에 충돌
```

`connection.url` 끝에 `/mydb` 를 붙여도 소용없다. 그건 "접속할 기본 DB"일 뿐 **메타데이터 탐색 범위와는 무관하다.**
`catalog.pattern` 이 그 탐색 범위를 좁힌다. 커넥터가 내부적으로 호출하는 JDBC 표준 API 의 첫 인자에 들어간다.

```java
DatabaseMetaData.getTables(catalog, schemaPattern, tableNamePattern, types)
                           ↑ catalog.pattern (기본값 null = 모든 DB 탐색)
```

DB 마다 이 계층을 쓰는 방식이 다르다.

| DB | catalog | schema |
|---|---|---|
| MySQL / MariaDB | **데이터베이스 이름** (`mydb`) | 안 씀 |
| PostgreSQL / Oracle | 안 씀 | **네임스페이스** (`public` 등) |

그래서 PostgreSQL 을 붙일 때는 `catalog.pattern` 이 아니라 짝이 되는 `schema.pattern` 을 쓴다.

> Source 전용 옵션이다. `JdbcSinkConnector` 에는 이 옵션이 아예 없다 —
> Sink 는 토픽 이름이 곧 테이블 이름이라 테이블을 탐색할 일이 없기 때문이다. 8번은 손댈 것이 없다.

---

## 6. 상태 확인

```bash
curl -s localhost:8083/connectors
curl -s localhost:8083/connectors/my-source-connect/status
```

`"state": "RUNNING"` 이어야 한다. `FAILED` 면 응답에 스택트레이스가 함께 온다.

---

## 7. 동작 확인

**터미널 A — 컨슈머**

```bash
docker exec -it kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic my_topic_users --from-beginning
```

**터미널 B — DB INSERT**

```bash
docker exec -it mariadb mariadb -uroot -ptest1357 mydb
```

```sql
insert into users(user_id, pwd, name) values('user1', 'test1111', 'User name');
```

몇 초 뒤 터미널 A 에 `{"schema":{...},"payload":{...}}` 형태의 JSON 이 출력된다.

**토픽 목록**

```bash
docker exec -it kafka kafka-topics.sh --bootstrap-server localhost:9092 --list
```

`connect-configs`, `connect-offsets`, `connect-status`, `my_topic_users` 가 보인다.

**관리 UI** — http://localhost:8090 → Topics → my_topic_users → Messages

---

---

# Sink Connect (토픽 → DB)

Source 가 이미 동작 중이라는 전제. compose 와 플러그인은 손댈 것이 없다. 같은 JDBC 커넥터에 Sink 도 포함되어 있다.

## 8. Sink 커넥터 등록

```bash
curl -X POST localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d '{
    "name": "my-sink-connect",
    "config": {
      "connector.class": "io.confluent.connect.jdbc.JdbcSinkConnector",
      "connection.url": "jdbc:mariadb://mariadb:3306/mydb",
      "connection.user": "root",
      "connection.password": "test1357",
      "auto.create": "true",
      "auto.evolve": "true",
      "delete.enabled": "false",
      "tasks.max": "1",
      "topics": "my_topic_users"
    }
  }'
```

| 항목 | 의미 |
|---|---|
| `topics` | 구독할 토픽. **이 이름이 그대로 테이블 이름이 된다** |
| `auto.create` | 테이블이 없으면 자동 생성 |
| `auto.evolve` | 스키마가 바뀌면 컬럼 자동 추가 |
| `delete.enabled` | tombstone 메시지로 행 삭제할지 여부 |

등록 확인:

```bash
curl -s localhost:8083/connectors
# ["my-sink-connect","my-source-connect"]
```

## 9. 테이블 확인

```bash
docker exec -it mariadb mariadb -uroot -ptest1357 mydb
```

```sql
show tables;
```

`my_topic_users` 가 생겼는지 확인. 아직 없으면 정상 — Sink 는 **메시지가 들어와야** 테이블을 만든다.

## 10. 테스트 1 — DB INSERT (Source → Sink 연쇄)

```sql
insert into users(user_id, pwd, name) values('user3', 'user3', 'Kenneth');
select * from my_topic_users;
```

`users` 에 넣은 행이 `my_topic_users` 에도 나타난다. 흐름:

```
users 테이블 → (Source) → my_topic_users 토픽 → (Sink) → my_topic_users 테이블
```

## 11. 테스트 2 — 프로듀서로 토픽에 직접 전송

DB 를 거치지 않고 토픽에 직접 넣어도 Sink 가 테이블에 꽂는다.

```bash
docker exec -it kafka kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic my_topic_users
```

`>` 프롬프트에 **한 줄로** 붙여넣는다.

```json
{"schema":{"type":"struct","fields":[{"type":"int32","optional":false,"field":"id"},{"type":"string","optional":true,"field":"user_id"},{"type":"string","optional":true,"field":"pwd"},{"type":"string","optional":true,"field":"name"}],"optional":false,"name":"users"},"payload":{"id":100,"user_id":"my_id","pwd":"my_password","name":"Kenneth"}}
```

```sql
select * from my_topic_users;
```

id 100 행이 들어와 있다.

### schema 가 필요한 이유

Sink 는 컬럼 타입을 알아야 테이블을 만들고 INSERT 할 수 있는데, JSON 자체에는 타입 정보가 없다 (`"id":100` 이 int 인지 bigint 인지 모름).

그래서 `schema`(구조) + `payload`(값) 를 함께 보내는 형식을 쓴다. compose 의 아래 설정이 그 선언이다.

```yaml
CONNECT_VALUE_CONVERTER: org.apache.kafka.connect.json.JsonConverter
```

`schema` 없이 값만 보내면 Sink 태스크가 실패한다.

## 12. 문제 대응

```bash
curl -s localhost:8083/connectors/my-sink-connect/status
```

`"state": "FAILED"` 면 응답에 스택트레이스가 함께 온다. 대부분 schema 형식 문제.

재시작:

```bash
curl -X POST localhost:8083/connectors/my-sink-connect/restart
```

> **주의 — 무한 루프**
> Source 가 `users` 를 읽고 Sink 가 `my_topic_users` 에 쓰는 현재 구성은 안전하다(서로 다른 테이블).
> 만약 Sink 대상 테이블을 `users` 로 바꾸면, Sink 가 쓴 행을 Source 가 다시 읽어 토픽에 넣고 Sink 가 또 쓰는 무한 반복이 발생한다.

---

## 참고 — 구조

```
                    ┌──────────── Source ────────────┐
users 테이블 ──► Connect ──► my_topic_users 토픽 ──► Connect ──► my_topic_users 테이블
                                                  └───── Sink ─────┘

MariaDB ──► Kafka Connect ──► Kafka 클러스터
(3306)      (8083, 컨테이너)    (9092 호스트 / 19092 컨테이너)
```

- MariaDB 와 Kafka 는 서로 직접 연결되지 않는다. Connect 가 중간에서 양쪽을 잡는다.
- Connect 는 별도 프로세스(컨테이너)이고, JDBC 커넥터는 그 안에 로드되는 jar 플러그인이다.
- Connect 도 Kafka 입장에서는 그냥 클라이언트다. Spring Boot 앱과 같은 위치.

## 정리 / 초기화

```bash
docker compose down          # 중지
rm -rf data mariadb-data     # 데이터까지 완전 초기화
```

커넥터만 삭제하려면:

```bash
curl -X DELETE localhost:8083/connectors/my-source-connect
```