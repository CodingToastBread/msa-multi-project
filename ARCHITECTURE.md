# ARCHITECTURE — 한 장으로 보는 전체 구조

이 프로젝트의 **애플리케이션 + 인프라**가 어떻게 연결되는지 그림으로만 정리한 문서다.
각 흐름의 자세한 설명은 README 의 해당 장을 본다. 실행 방법은 [`docker/HOW_TO_DOCKER_COMPOSE.md`](docker/HOW_TO_DOCKER_COMPOSE.md).

- [1. 전체 구성도](#1-전체-구성도)
- [2. 요청 흐름](#2-요청-흐름) — Gateway · Eureka · JWT · Feign
- [3. 설정 흐름](#3-설정-흐름) — Config Server · Spring Cloud Bus
- [4. 이벤트 흐름](#4-이벤트-흐름) — Kafka · Connect
- [5. 관측 흐름](#5-관측-흐름) — Zipkin · Prometheus · Grafana
- [6. 실행 모드별 배치](#6-실행-모드별-배치) — IDE / 전체 컨테이너

---

## 1. 전체 구성도

```
                     Client (browser, Postman, 01_reference/test.http)
                                          |
                                          v :8000
+-----------------------------------------------------------------------------------+
| ecommerce-network  172.18.0.0/16                                                  |
|                                                                                   |
|  ENTRY          +---------------------+                                           |
|                 | gateway       :8000 |  fixed IP 172.18.0.100                    |
|                 +---------------------+                                           |
|                                                                                   |
|  SPRING CLOUD   +---------------------+   +---------------------+                 |
|                 | service-discovery   |   | config-service      |                 |
|                 | (Eureka)      :8761 |   | (Config)      :8888 |                 |
|                 +---------------------+   +---------------------+                 |
|                                                                                   |
|  SERVICES       +--------------+  +---------------+  +-----------------+          |
|  port 0         | user-service |  | order-service |  | catalog-service |          |
|  (random,       +--------------+  +---------------+  +-----------------+          |
|   not public)   +---------------+  +----------------+                             |
|                 | first-service |  | second-service |  demo  :8081 / :8082        |
|                 +---------------+  +----------------+                             |
|  - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -  |
|  INFRA          +-----------+  +-----------------+  +-----------+                 |
|                 | rabbitmq  |  | kafka     :9092 |  | mariadb   |                 |
|                 | :5672     |  | kafka-ui  :8090 |  | :3306     |                 |
|                 | :15672 UI |  | connect   :8083 |  |           |                 |
|                 +-----------+  +-----------------+  +-----------+                 |
|                 +---------------------+  +---------------------+                  |
|                 | zipkin        :9411 |  | prometheus    :9090 |                  |
|                 | zipkin-mysql  :3307 |  | grafana       :3000 |                  |
|                 +---------------------+  +---------------------+                  |
+-----------------------------------------------------------------------------------+
```

| 구역 | 컨테이너 | compose profile |
|---|---|---|
| ENTRY · SPRING CLOUD · SERVICES | Spring 서비스 8개 | `app` (켰을 때만 뜬다) |
| INFRA | 인프라 9개 | 없음 (항상 뜬다) |

- 모든 컨테이너는 `docker/docker-compose.yml` 한 파일에 있다.
- 비즈니스 서비스는 **포트를 밖에 열지 않는다.** 외부 요청은 반드시 gateway(`:8000`)를 거친다.
- user / catalog 는 각자 **인메모리 H2** (재시작하면 비워짐), order 는 **MariaDB** 를 쓴다.

---

## 2. 요청 흐름

> README [4장 라우팅](README.md#4-요청-흐름--라우팅) · [5장 JWT](README.md#5-인증-흐름-jwt) · [8장 서비스 간 통신](README.md#8-서비스-간-통신-user--order) · [주의사항 — IP 허용목록](README.md)

```
                                  [0] every service registers itself
                                      "USER-SERVICE = ip:port"
                                                 |
 Client                                          v
   |                                     +----------------+
   | GET /user-service/users/{id}        | Eureka   :8761 |
   | Authorization: Bearer <JWT>         +----------------+
   v                                        ^         ^
 +------------------------------+           |         |
 | gateway  :8000               |  [2] lookup         |
 |  [1] route by path prefix    |-----------+         |
 |  [3] AuthorizationHeader-    |  lb://USER-SERVICE  |
 |      Filter : verify JWT     |                     |
 +------------------------------+                     |
   |                                                  |
   | [4] http://<user-service ip:port>/users/{id}     |
   v                                                  | [6] lookup
 +------------------------------+                     |     order-service
 | user-service                 |---------------------+
 |  [5] IP allow-list           |
 |      (only gateway passes)   |  [7] Feign + CircuitBreaker
 |  login -> issue JWT          |----------------------------->+---------------+
 +------------------------------+  GET /order-service/{id}/    | order-service |
                                   orders                      +---------------+
```

| gateway 경로 | Eureka 이름 | JWT 검사 |
|---|---|---|
| `/user-service/**` | `USER-SERVICE` | 회원가입 · 로그인 · actuator 제외하고 검사 |
| `/order-service/**` | `ORDER-SERVICE` | — |
| `/catalog-service/**` | `CATALOG-SERVICE` | — |
| `/first-service/**`, `/second-service/**` | `MY-FIRST-SERVICE`, `MY-SECOND-SERVICE` | — (필터 실습용) |

---

## 3. 설정 흐름

> README [6장 Config + Bus](README.md#6-설정-중앙화--실시간-갱신-config--rabbitmq-bus) · [9장 암호화](README.md#9-설정값-암호화-cipher) · [주의사항 — Config](README.md)

```
   01_reference/native-repo/
     application.yml   (shared: token.secret {cipher} ...)
     ecommerce.yml     (gateway)
     user-service.yml  (user-service)
             |
             | read  (container: bind mount -> /config-repo)
             v
   +----------------------+
   | config-service :8888 |-----------------------------+
   +----------------------+                             |
       ^            ^                                   |
       | [1] pull config at boot (bootstrap)            |
       |            |                                   |
   +---------+  +--------------+                        |
   | gateway |  | user-service |                        |
   +---------+  +--------------+                        |
       |            |                                   |
       +------------+-----------------------------------+
                    |
                    | [2] POST /actuator/busrefresh -> refresh event to all
                    v
           +------------------+
           | rabbitmq  :5672  |   Spring Cloud Bus
           +------------------+
```

- config-service 는 `{cipher}` 값을 `apiEncryptionKey.jks` 로 복호화해서 내려준다.
- gateway 와 user-service 는 **같은 `token.secret`** 을 받는다. user-service 가 JWT 를 발급하고 gateway 가 검증한다.
- Config Server 를 쓰는 건 **gateway, user-service** 둘뿐이다. order / catalog / first / second 는 자기 yml 만 쓴다.

---

## 4. 이벤트 흐름

> README [10장 Kafka](README.md#10-kafka--서비스-간-데이터-동기화)

```
 +---------------+                     +--------------+                     +-----------------+
 | order-service |  [1] example-       |    kafka     |  [2] consume        | catalog-service |
 |               |      catalog-topic  |    :9092     |      example-       |  stock -= qty   |
 |               |-------------------->|  (19092 for  |-------------------->|                 |
 |               |                     |  containers) |      catalog-topic  +-----------------+
 |               |  [3] orders         |              |
 |               |-------------------->|              |  [4] orders   +-----------+  JDBC Sink  +-----------+
 +---------------+                     +--------------+-------------->|  connect  |------------>|  mariadb  |
                                              ^                       |  :8083    |             |  orders   |
                                              |                       +-----------+             +-----------+
                                       +--------------+
                                       |  kafka-ui    |
                                       |  :8090       |
                                       +--------------+
```

- order-service 는 주문을 DB 에 직접 넣지 않는다. `orders` 토픽으로 발행하면 Sink Connector 가 MariaDB 에 저장하고, 조회만 JPA 로 MariaDB 에서 읽는다.
- Sink Connector(`my-order-sink-connect`) 등록 정보는 Kafka 에 저장된다. `docker/data/kafka` 를 지우면 다시 등록해야 한다. ([04 문서](kafka-practice/ABOUT_KAFKA/04_test_with_spring.md))
- 호스트(IDE)에서는 `localhost:9092`, 컨테이너끼리는 `kafka:19092` 로 붙는다.

---

## 5. 관측 흐름

> README [11장 Zipkin](README.md#11-zipkin--분산-추적) · [12장 Prometheus + Grafana](README.md#12-prometheus--grafana--메트릭-모니터링)

```
 Tracing (push)

 +--------------+  +---------------+     spans      +-------------+       +----------------+
 | user-service |  | order-service |--------------->|   zipkin    |------>|  zipkin-mysql  |
 +--------------+  +---------------+ /api/v2/spans  |   :9411     |       |  :3307         |
        |                  ^                        +-------------+       +----------------+
        +------------------+
          same traceId (Feign call)


 Metrics (pull)

 +-----------------+
 |  gateway :8000  |---> /actuator/prometheus
 |                 |---> /user-service/actuator/prometheus   -> user-service
 |                 |---> /order-service/actuator/prometheus  -> order-service
 +-----------------+
          ^
          | [1] scrape every 15s
          |
 +-----------------+       [2] query        +-----------------+
 |  prometheus     |<-----------------------|  grafana        |
 |  :9090          |                        |  :3000          |
 +-----------------+                        +-----------------+
```

- 비즈니스 서비스는 포트가 랜덤이라 Prometheus 가 **gateway 를 거쳐서** 긁는다.
- Prometheus 설정은 gateway 위치에 따라 `local`(`host.docker.internal:8000`) / `container`(`gateway:8000`) 로 나뉜다.

---

## 6. 실행 모드별 배치

> 실행 명령: [`docker/HOW_TO_DOCKER_COMPOSE.md`](docker/HOW_TO_DOCKER_COMPOSE.md)

```
 [A] IDE mode        docker compose up -d

 +------------------------------ my PC -------------------------------+
 |                                                                    |
 |   IDE : service-discovery  config-service  gateway                 |
 |         user  order  catalog  first  second                        |
 |                  |                                                 |
 |                  |  localhost:5672  localhost:9092  localhost:9411 |
 |                  v                                                 |
 |   +------------------ Docker (ports published) -----------------+  |
 |   |  rabbitmq  kafka  kafka-ui  connect  mariadb                |  |
 |   |  zipkin  zipkin-mysql  prometheus  grafana                  |  |
 |   +-------------------------------------------------------------+  |
 +--------------------------------------------------------------------+


 [B] Full container mode    docker compose --profile app up -d --build

 +------------------------------ my PC -------------------------------+
 |                                                                    |
 |   Browser / test.http                                              |
 |          |  localhost:8000                                         |
 |          v                                                         |
 |   +------------------ Docker : ecommerce-network ---------------+  |
 |   |  gateway  service-discovery  config-service                 |  |
 |   |  user  order  catalog  first  second                        |  |
 |   |        |  rabbitmq:5672  kafka:19092  zipkin:9411           |  |
 |   |        v  (container names)                                 |  |
 |   |  rabbitmq  kafka  kafka-ui  connect  mariadb                |  |
 |   |  zipkin  zipkin-mysql  prometheus  grafana                  |  |
 |   +-------------------------------------------------------------+  |
 +--------------------------------------------------------------------+
```

| | IDE 모드 | 전체 컨테이너 모드 |
|---|---|---|
| Spring 서비스가 인프라를 부르는 주소 | `localhost:포트` (yml 기본값) | 컨테이너 이름 (compose 환경변수) |
| Eureka 등록 주소 | hostname (user-service 는 `localhost`) | 컨테이너 IP (`prefer-ip-address`) |
| user-service 가 허용하는 IP | `127.0.0.1`, `::1` | `172.18.0.100` (gateway 고정 IP) |
| Prometheus 타겟 | `host.docker.internal:8000` | `gateway:8000` |
