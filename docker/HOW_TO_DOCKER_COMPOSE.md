# HOW TO — docker compose 로 실행하기

## 빠른 실행

```bash
cd docker

# [1] 인프라만 띄우기 → Spring 서비스는 IDE 로 실행
#     --profile 이 없으면 profiles: [app] 이 붙은 Spring 서비스 8개는 뜨지 않는다
docker compose up -d

# [2] 인프라 + Spring 서비스 전부 컨테이너로 띄우기
#     --profile app 을 줘야 Spring 서비스까지 뜬다. jar 를 먼저 빌드할 것 (루트에서 ./mvnw clean package -DskipTests)
PROMETHEUS_MODE=container docker compose --profile app up -d --build

# 상태 확인
docker compose ps

# 전부 내리기 — stop/down 도 profile 을 따른다. --profile app 을 빼면 인프라만 내려간다
docker compose --profile app down
```

---

이 프로젝트의 **모든 컨테이너**(인프라 9개 + Spring 서비스 8개)는 `docker/docker-compose.yml` **한 파일**에 있다.
상황에 따라 "무엇까지 띄울지" 만 고르면 된다. 이 문서는 그 방법을 시나리오별로 정리한다.

> 모든 `docker compose` 명령은 **`docker/` 폴더에서** 실행한다. (compose 파일과 `.env` 를 자동으로 찾는 기준 폴더)

---

## 목차

- [0. 한눈에 보기](#0-한눈에-보기)
- [1. 먼저 알아야 할 개념 — profiles](#1-먼저-알아야-할-개념--profiles)
- [2. 사전 준비](#2-사전-준비)
- [3. 시나리오별 실행](#3-시나리오별-실행)
  - [A. IDE 로 실습 (인프라만 컨테이너)](#a-ide-로-실습-인프라만-컨테이너)
  - [B. 전체를 컨테이너로](#b-전체를-컨테이너로)
  - [C. 필요한 인프라만 골라서](#c-필요한-인프라만-골라서)
  - [D. 코드를 고친 뒤 서비스 하나만 다시 띄우기](#d-코드를-고친-뒤-서비스-하나만-다시-띄우기)
  - [E. 모드 전환 (A ↔ B)](#e-모드-전환-a--b)
  - [F. `.env` 로 모드 고정하기](#f-env-로-모드-고정하기)
- [4. 상태 확인 · 로그 · 접속 주소](#4-상태-확인--로그--접속-주소)
- [5. 중지 · 삭제 · 데이터 초기화](#5-중지--삭제--데이터-초기화)
- [6. 잘 떴는지 확인하는 순서](#6-잘-떴는지-확인하는-순서)
- [7. 원리 — 같은 yml 로 로컬과 컨테이너를 둘 다 되게 하는 법](#7-원리--같은-yml-로-로컬과-컨테이너를-둘-다-되게-하는-법)
- [8. 트러블슈팅](#8-트러블슈팅)
- [9. 명령어 치트시트](#9-명령어-치트시트)

---

## 0. 한눈에 보기

```
docker/
├── docker-compose.yml        컨테이너 17개 전부 (인프라 / Spring 구역으로 나눠 작성)
├── .env                      실행 모드 고정용 (기본은 전부 주석)
├── HOW_TO_DOCKER_COMPOSE.md  이 문서
├── kafka/connect-plugins/    Kafka Connect JDBC 커넥터 + MariaDB 드라이버
├── mariadb/mariadb-ddl.sql   MariaDB 테이블 DDL (수동 실행)
├── zipkin/initdb.d/          Zipkin 용 MySQL 스키마 (최초 1회 자동 실행)
├── monitoring/
│   ├── prometheus/local/prometheus.yml       gateway 를 IDE 에서 띄울 때
│   ├── prometheus/container/prometheus.yml   gateway 도 컨테이너일 때
│   └── grafana/provisioning/                 Grafana 데이터소스 자동 등록
└── data/                     bind mount 데이터 (kafka, mariadb, zipkin-mysql) — gitignore
```

| 구분 | 컨테이너 | profile |
|---|---|---|
| 인프라 (9개) | rabbitmq, kafka, kafka-ui, connect, mariadb, zipkin, zipkin-mysql, prometheus, grafana | 없음 → **항상** 뜬다 |
| Spring (8개) | service-discovery, config-service, gateway, user-service, order-service, catalog-service, first-service, second-service | `app` → **켰을 때만** 뜬다 |

| 하고 싶은 것 | 명령 |
|---|---|
| IDE 로 실습 (인프라만) | `docker compose up -d` |
| 전체를 컨테이너로 | `PROMETHEUS_MODE=container docker compose --profile app up -d --build` |
| 필요한 것만 | `docker compose up -d kafka kafka-ui` |
| 서비스 하나 다시 빌드 | `docker compose up -d --build user-service` |
| 전부 내리기 | `docker compose --profile app down` |

---

## 1. 먼저 알아야 할 개념 — profiles

**profiles** 는 서비스에 "그룹 이름표" 를 붙여두고, 실행할 때 **어떤 그룹까지 켤지** 고르는 compose 기능이다.
파일을 여러 개로 쪼개지 않고도 상황별로 뜨는 서비스 묶음을 바꿀 수 있다.

규칙은 두 가지뿐이다.

1. `profiles` 가 **없는** 서비스 → **항상** 뜬다.
2. `profiles` 가 **있는** 서비스 → 그 profile 을 **켰을 때만** 뜬다.

```yaml
services:
  kafka:                 # profiles 없음 → 항상
    image: apache/kafka:4.1.0

  gateway:
    profiles: [app]      # app 을 켰을 때만
    build: ../gateway
```

```bash
docker compose up -d                 # profiles 없는 것만   → 인프라 9개
docker compose --profile app up -d   # 없는 것 + app        → 17개
```

profile 을 켜는 방법은 세 가지다.

| 방법 | 예시 |
|---|---|
| `--profile` 옵션 | `docker compose --profile app up -d` |
| 환경변수 | `COMPOSE_PROFILES=app docker compose up -d` |
| `.env` 파일 | `COMPOSE_PROFILES=app` 한 줄 ([F](#f-env-로-모드-고정하기) 참고) |

알아둘 동작:

- **서비스 이름을 직접 적으면 profile 을 안 켜도 뜬다.** `docker compose up -d user-service` 는 `--profile app` 없이 동작한다.
- **`stop` / `down` 에도 똑같이 적용된다.** `docker compose down` 만 치면 **인프라만** 내려가고 Spring 컨테이너는 남는다.
- **`ps` 는 profile 과 상관없이** 이 프로젝트에서 떠 있는 컨테이너를 전부 보여준다.
- **의존 방향.** app 서비스가 인프라에 `depends_on` 하는 건 괜찮다(현재 구조). 반대로 profile 없는 서비스가 app 서비스에 의존하면 app 을 안 켰을 때 실패한다.

---

## 2. 사전 준비

### [1] Docker Desktop 메모리

전부 띄우면 약 **6GB** 를 쓴다. Docker Desktop → Settings → Resources 에서 메모리를 넉넉히 준다. (현재 16GB)
인프라만 띄울 때는 절반 정도면 충분하다.

### [2] Maven wrapper 실행 권한 (최초 1회)

```bash
chmod +x mvnw     # permission denied 가 나면
```

### [3] jar 빌드 — 전체 컨테이너 모드(B, D)에서만 필요

Spring 서비스의 `Dockerfile` 은 **호스트에서 빌드한 `target/*.jar` 를 복사만** 한다. 컨테이너 안에서 빌드하지 않는다.
그래서 **코드를 고칠 때마다 jar 를 먼저 빌드**해야 한다. (루트에서)

```bash
./mvnw clean package -DskipTests                  # 전체
./mvnw -pl user-service package -DskipTests       # 하나만
```

> IDE 로 실습(A)할 때는 IDE 가 알아서 빌드하므로 필요 없다.

### [4] 네트워크 — 준비할 것 없음

`ecommerce-network` (서브넷 `172.18.0.0/16`) 는 **compose 가 `up` 할 때 만들고 `down` 할 때 지운다.**
`docker network create` 를 따로 할 필요 없다.

- gateway 는 **고정 IP `172.18.0.100`** 을 받는다. user-service 가 이 IP 에서 온 요청만 허용하기 때문이다.
- 나머지 컨테이너는 `172.18.1.x` (`ip_range`) 에서 자동으로 받는다. 고정 IP 와 겹치지 않게 하려고 대역을 나눴다.
- 네트워크 설정(`ipam`)을 바꿨다면 `docker compose --profile app down` 으로 네트워크를 지운 뒤 다시 올려야 반영된다.

---

## 3. 시나리오별 실행

### A. IDE 로 실습 (인프라만 컨테이너)

**가장 자주 쓰는 모드.** 인프라는 컨테이너로, Spring 서비스는 IDE(또는 `./mvnw spring-boot:run`)로 띄운다.
코드 수정 → IDE 재시작만으로 바로 확인할 수 있어서 실습에 편하다.

```bash
cd docker
docker compose up -d      # 인프라 9개
docker compose ps         # 전부 Up (healthy) 확인
```

그다음 IDE 에서 **순서대로** 실행한다. 순서를 어기면 등록 실패로 무한 재시도하거나 설정을 못 받는다.

```
[1] service-discovery   (:8761)  ← 다른 서비스가 여기에 등록하므로 가장 먼저
[2] config-service      (:8888)  ← 다른 서비스가 부팅할 때 설정을 받아가므로 두 번째
[3] gateway             (:8000)
[4] user / order / catalog / first / second  (순서 무관)
```

- **Spring 설정은 손댈 게 없다.** IDE 에는 환경변수가 없으니 yml 의 **기본값**(`127.0.0.1:5672`, `localhost:9092` …)이 쓰이고,
  인프라 컨테이너가 전부 호스트 포트를 열어두었다. ([7. 원리](#7-원리--같은-yml-로-로컬과-컨테이너를-둘-다-되게-하는-법))
- **Prometheus 도 그대로 된다.** `PROMETHEUS_MODE` 를 안 주면 기본 `local` → `host.docker.internal:8000` (IDE 의 gateway) 을 긁는다.

> ⚠️ 이 모드에서는 **Spring 서비스를 전부 IDE 로** 띄운다. "일부는 컨테이너, 일부는 IDE" 로 섞으면 기본 설정으로는 동작하지 않는다.
> ([8. 트러블슈팅](#섞어-쓰면-gateway-가-서비스를-못-찾는다))

### B. 전체를 컨테이너로

인프라 + Spring 서비스 17개를 명령 하나로 띄운다. 기동 순서는 `depends_on` + healthcheck 가 보장한다.

```bash
# [1] jar 빌드 (루트에서)
./mvnw clean package -DskipTests

# [2] 기동 (docker/ 에서)
cd docker
PROMETHEUS_MODE=container docker compose --profile app up -d --build
```

| 옵션 | 의미 |
|---|---|
| `--profile app` | Spring 서비스(profile: app)까지 켠다 |
| `--build` | 이미지를 다시 만든다. jar 가 바뀌었으면 새 jar 가 들어간다 (안 바뀌었으면 캐시라 빠르다) |
| `PROMETHEUS_MODE=container` | Prometheus 가 `gateway:8000` (컨테이너 이름) 으로 긁는다 |

자동으로 지켜지는 기동 순서:

```
rabbitmq, kafka, mariadb, zipkin-mysql (healthy 대기)
  → service-discovery (healthy 대기)
  → config-service    (healthy 대기)
  → gateway, user-service, order-service, catalog-service, first-service, second-service
```

> 모두 `Started` 가 떠도 **30초 정도는 gateway 가 503** 을 줄 수 있다. 서비스가 Eureka 에 등록되고 gateway 가 목록을 받아오기까지 걸리는 시간이다.

### C. 필요한 인프라만 골라서

서비스 이름을 적으면 **그것과 그 `depends_on` 대상만** 뜬다.

| 실습 | 명령 | 같이 뜨는 것 |
|---|---|---|
| Kafka CLI / UI | `docker compose up -d kafka kafka-ui` | — |
| Kafka Connect (Source/Sink) | `docker compose up -d kafka kafka-ui connect` | mariadb |
| MariaDB 만 | `docker compose up -d mariadb` | — |
| Zipkin 분산 추적 | `docker compose up -d zipkin` | zipkin-mysql |
| Config + Bus | `docker compose up -d rabbitmq` | — |
| 모니터링 | `docker compose up -d prometheus grafana` | — |

메모리를 아끼고 싶을 때 쓴다. 특히 `connect` 는 무거운 편(약 500MB)이라 Connect 실습이 아니면 빼도 된다.

### D. 코드를 고친 뒤 서비스 하나만 다시 띄우기

**B 모드**에서 user-service 코드를 고쳤다면:

```bash
./mvnw -pl user-service package -DskipTests          # [1] jar 다시 빌드 (루트에서)
cd docker && docker compose up -d --build user-service  # [2] 이미지 다시 만들고 컨테이너 교체
```

- 서비스 이름을 적었으므로 `--profile app` 은 필요 없다.
- **`docker compose restart user-service` 는 안 된다.** restart 는 기존 컨테이너를 다시 켤 뿐, 새 이미지(새 jar)로 바꾸지 않는다.
- 설정 파일(`01_reference/native-repo/*.yml`)만 고쳤다면 재빌드 없이 busrefresh 로 반영할 수 있다.
  `curl -X POST localhost:8888/actuator/busrefresh` (Spring Cloud Bus 로 전 서비스에 전파)
  단, `Environment` / `@RefreshScope` 로 읽는 값만 바뀐다. DB 접속 정보처럼 부팅 때 한 번 쓰고 끝나는 값은 컨테이너를 다시 띄워야 한다.

### E. 모드 전환 (A ↔ B)

**B(전체 컨테이너) → A(IDE)**: Spring 컨테이너가 8000, 8761, 8888 포트를 잡고 있으므로 먼저 내린다.

```bash
docker compose --profile app down   # 전부 내리기 (데이터는 유지)
docker compose up -d                # 인프라만 다시 (Prometheus 는 기본 local 로 돌아간다)
```

**A(IDE) → B(전체 컨테이너)**: IDE 에서 띄운 Spring 서비스를 **전부 종료**한 뒤,

```bash
./mvnw clean package -DskipTests
PROMETHEUS_MODE=container docker compose --profile app up -d --build
```

인프라는 이미 떠 있으므로 그대로 두고 Spring 컨테이너만 추가된다. (Prometheus 는 설정이 바뀌어서 다시 만들어진다)

### F. `.env` 로 모드 고정하기

한동안 한 모드로만 쓸 거라면 매번 옵션을 치는 대신 `docker/.env` 에 적어둔다. compose 가 자동으로 읽는다.

```bash
# docker/.env — B(전체 컨테이너) 모드로 고정하려면 주석 해제
COMPOSE_PROFILES=app
PROMETHEUS_MODE=container
```

그러면 `docker compose up -d --build` 만으로 17개가 뜨고, `docker compose down` 도 전부 내린다.

> ⚠️ **A 모드로 돌아갈 때 다시 주석 처리할 것.** 잊으면 `docker compose up -d` 가 Spring 컨테이너까지 띄워서 IDE 와 포트가 충돌한다.

---

## 4. 상태 확인 · 로그 · 접속 주소

```bash
docker compose ps                        # 상태 (Up / healthy / Restarting)
docker compose logs -f gateway           # 특정 서비스 로그 따라가기
docker compose logs --tail 100 user-service
docker stats --no-stream                 # 컨테이너별 메모리/CPU
docker exec -it kafka bash               # 컨테이너 안으로 들어가기
```

| 대상 | 주소 | 계정 |
|---|---|---|
| Gateway (모든 API 의 입구) | http://localhost:8000 | — |
| Eureka 대시보드 | http://localhost:8761 | — |
| Config Server | http://localhost:8888/ecommerce/default | — |
| RabbitMQ 관리 UI | http://localhost:15672 | guest / guest |
| Kafka 브로커 (호스트에서) | `localhost:9092` | — |
| Kafka UI | http://localhost:8090 | — |
| Kafka Connect REST | http://localhost:8083/connectors | — |
| MariaDB | `localhost:3306` / `mydb` | root / test1357 |
| Zipkin UI | http://localhost:9411 | — |
| Zipkin MySQL | `localhost:3307` / `zipkin` | zipkin / zipkin |
| Prometheus | http://localhost:9090/targets | — |
| Grafana | http://localhost:3000 | admin / admin |

> user / order / catalog / first / second 는 **호스트 포트를 열지 않는다** (`server.port: 0`, 랜덤). 반드시 gateway(`:8000`) 를 거친다.

---

## 5. 중지 · 삭제 · 데이터 초기화

| 명령 | 컨테이너 | 네트워크 | `data/` (bind) | named volume (prometheus, grafana) |
|---|---|---|---|---|
| `docker compose --profile app stop` | 멈춤 (유지) | 유지 | 유지 | 유지 |
| `docker compose --profile app start` | 다시 켬 | — | — | — |
| `docker compose --profile app down` | **삭제** | **삭제** | 유지 | 유지 |
| `docker compose --profile app down -v` | 삭제 | 삭제 | 유지 | **삭제** |

> ⚠️ `stop` / `down` 에 **`--profile app` 을 빼먹으면 인프라만** 내려간다. Spring 컨테이너는 남고, 네트워크는 그 컨테이너들이 붙어 있어서 지워지지 않는다.

데이터를 **완전히 초기화**하려면 컨테이너를 내린 뒤 `data/` 아래 폴더를 지운다.

```bash
docker compose --profile app down
rm -rf data/kafka          # 토픽/메시지 전부 + Kafka Connect 커넥터 등록 정보 (Sink 재등록 필요)
rm -rf data/mariadb        # MariaDB 전부 (DDL 은 mariadb/mariadb-ddl.sql 로 다시 실행)
rm -rf data/zipkin-mysql   # trace 전부 (다음 기동 때 zipkin/initdb.d 스키마가 다시 실행됨)
```

> user-service / catalog-service 의 H2 는 인메모리라 **재시작하면 항상 비어서** 뜬다. (회원가입부터 다시)
> order-service 의 주문은 MariaDB(`data/mariadb`)에 남는다.
>
> `data/kafka` 를 지웠다면 Sink 커넥터를 다시 등록해야 주문이 MariaDB 에 저장된다. 명령은 [`ABOUT_KAFKA/04`](../kafka-practice/ABOUT_KAFKA/04_test_with_spring.md) 참고.

---

## 6. 잘 떴는지 확인하는 순서

위에서부터 확인하면 어디서 막혔는지 바로 알 수 있다.

```bash
# [1] 컨테이너가 전부 떴나 — Restarting / Exited 가 없어야 한다
docker compose ps

# [2] Config Server 가 설정을 제대로 주나 — token.secret 이 복호화된 평문으로 나와야 한다
curl -s localhost:8888/ecommerce/default

# [3] Eureka 에 6개 등록됐나 — (B 모드) IP 가 172.18.x.x 여야 한다 / (A 모드) USER-SERVICE 는 localhost
open http://localhost:8761

# [4] gateway 경유 호출이 되나 — 503 이면 30초 더 기다린다
curl -s localhost:8000/catalog-service/catalogs

# [5] 전체 흐름 — 01_reference/test.http 를 위에서부터 실행 (회원가입 → 로그인 → 주문 → 조회)

# [6] 모니터링 — 타겟 3개가 UP 인가
open http://localhost:9090/targets
```

---

## 7. 원리 — 같은 yml 로 로컬과 컨테이너를 둘 다 되게 하는 법

### 컨테이너 안의 `localhost` 는 "그 컨테이너 자신" 이다

IDE 에서 도는 user-service 에게 `127.0.0.1:5672` 는 호스트에 열린 RabbitMQ 포트다.
하지만 컨테이너 안의 user-service 에게 `127.0.0.1` 은 **user-service 컨테이너 자신**이라, 거기엔 RabbitMQ 가 없다.

대신 **같은 네트워크 안에서는 컨테이너 이름이 곧 호스트명**이다. 컨테이너에서는 `rabbitmq:5672` 로 부르면 된다.

### 해결: `${환경변수:로컬 기본값}`

yml 의 주소를 전부 이렇게 바꿔 두었다.

```yaml
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:127.0.0.1}     # 환경변수가 없으면 127.0.0.1
```

- **IDE 실행** → 환경변수 없음 → 기본값 `127.0.0.1` → 호스트 포트로 접속
- **컨테이너 실행** → compose 가 `RABBITMQ_HOST: rabbitmq` 를 넘김 → 컨테이너 이름으로 접속

그래서 **yml 파일 하나로 두 모드가 다 된다.** `application-container.yaml` 같은 파일을 따로 둘 필요가 없다.

| 환경변수 | 쓰는 서비스 | 로컬 기본값 | 컨테이너 값 |
|---|---|---|---|
| `EUREKA_SERVER_URL` | Eureka 클라이언트 전부 | `http://127.0.0.1:8761/eureka` | `http://service-discovery:8761/eureka` |
| `EUREKA_PREFER_IP_ADDRESS` | Eureka 클라이언트 전부 | `false` | `true` |
| `CONFIG_SERVER_URI` | gateway, user (bootstrap) | `http://127.0.0.1:8888` | `http://config-service:8888` |
| `RABBITMQ_HOST` | config, gateway, user | `127.0.0.1` | `rabbitmq` |
| `ZIPKIN_ENDPOINT` | user, order | `http://127.0.0.1:9411/api/v2/spans` | `http://zipkin:9411/api/v2/spans` |
| `KAFKA_BOOTSTRAP_SERVERS` | order, catalog | `localhost:9092` | `kafka:19092` |
| `DB_URL` | order | `jdbc:mariadb://localhost:3306/mydb` | `jdbc:mariadb://mariadb:3306/mydb` |
| `NATIVE_REPO_LOCATION` | config | `file://${user.home}/.../native-repo` | `file:/config-repo/` (볼륨 마운트) |
| `ENCRYPT_KEY_STORE_LOCATION` | config (bootstrap) | `file:///${user.home}/.../apiEncryptionKey.jks` | `file:/apiEncryptionKey.jks` (이미지에 COPY) |
| `GATEWAY_ALLOWED_IPS` | user (WebSecurity) | `127.0.0.1,::1` | `172.18.0.100` (gateway 고정 IP) |
| `PROMETHEUS_MODE` | prometheus (compose 변수) | `local` | `container` |

> **새 주소를 추가할 때도 이 패턴을 지킬 것.** yml 이나 Java 에 `localhost` 를 하드코딩하면 컨테이너에서 깨진다.

### 왜 `prefer-ip-address` 가 필요한가

Eureka 클라이언트는 기본으로 **hostname** 을 등록한다. 컨테이너의 hostname 은 `3f2a9c...` 같은 컨테이너 ID 라서,
gateway 가 `lb://USER-SERVICE` 로 찾아가려 해도 그 이름을 해석하지 못한다. 그래서 컨테이너에서는 IP 로 등록한다.

반대로 **IDE 에서 user-service 는 `hostname: localhost` 로 등록**한다. 기본값(LAN IP)으로 등록되면 gateway 가 LAN IP 로 호출해서
user-service 의 IP 검사에 걸리기 때문이다. 컨테이너에서는 `prefer-ip-address=true` 라 이 hostname 은 무시된다.

---

## 8. 트러블슈팅

### 코드를 고쳤는데 컨테이너에 반영이 안 된다

- jar 를 다시 빌드했는지 확인한다. `Dockerfile` 은 `target/*.jar` 를 복사만 한다.
- `restart` 가 아니라 `up -d --build <서비스>` 로 교체해야 한다.

### `docker compose up -d` 했는데 Spring 서비스가 안 뜬다

정상이다. Spring 서비스는 `profiles: [app]` 이라 **`--profile app` 을 줘야** 뜬다.

### 반대로 `up -d` 만 했는데 Spring 서비스까지 뜬다

`docker/.env` 에 `COMPOSE_PROFILES=app` 이 주석 해제되어 있다.

### IDE 로 서비스를 띄우는데 `Port 8000 was already in use`

Spring 컨테이너가 떠 있다. `docker compose --profile app down` 후 `docker compose up -d` 로 인프라만 다시 올린다.

### 섞어 쓰면 gateway 가 서비스를 못 찾는다

"gateway 는 컨테이너, user-service 만 IDE" 처럼 섞으면, IDE 의 user-service 는 호스트의 hostname 으로 Eureka 에 등록되고
gateway 컨테이너는 그 이름을 해석하지 못한다. **Spring 서비스는 전부 IDE 이거나 전부 컨테이너**로 맞춘다.

### gateway 가 503 을 준다

- 기동 직후라면 30초 기다린다 (Eureka 등록 → gateway 캐시 갱신).
- 계속 503 이면 Eureka 대시보드(`:8761`)에 해당 서비스가 있는지, `docker compose logs <서비스>` 에 에러가 있는지 본다.

### 회원가입(`POST /user-service/users`)이 401 이다

JWT 필터가 없는 경로인데 401 이면 **user-service `WebSecurity` 의 IP 검사**에 걸린 것이다. (거부 시 403 이 아니라 401 로 응답한다)
user-service 는 `gateway.allowed-ips` 에 적힌 IP 에서 온 요청만 받는다.

- **IDE**: 허용 `127.0.0.1, ::1`. user-service 가 `eureka.instance.hostname: localhost` 로 등록되어야 gateway 가 localhost 로 호출한다.
  Eureka 대시보드에서 USER-SERVICE 가 `localhost` 로 보이는지 확인한다.
- **컨테이너**: 허용 `172.18.0.100` (`GATEWAY_ALLOWED_IPS`). gateway 컨테이너의 고정 IP(`ipv4_address`)와 같은 값이어야 한다.
  `docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' gateway` 로 확인한다.

자세한 원리: README `주의사항 — IP 허용목록`

### 로그인은 되는데 인증이 필요한 요청이 전부 401 (Invalid JWT token)

config-service 가 **빈 설정**을 주고 있을 가능성이 높다. native 모드는 폴더가 없어도 에러 없이 빈 설정을 준다.
`curl localhost:8888/ecommerce/default` 에 `token.secret` 이 있는지 확인한다. 없으면 `01_reference/native-repo` 볼륨 마운트를 확인한다.

### Kafka 연결이 안 된다 (컨테이너에서)

컨테이너끼리는 **`kafka:19092`** 다. `9092` 는 호스트용(EXTERNAL) 리스너인데 `localhost:9092` 로 광고되기 때문에,
컨테이너가 그 주소로 다시 접속하면 자기 자신을 찾다가 실패한다. (호스트/IDE 에서는 `localhost:9092` 가 맞다)

### config-service 가 `UnsupportedClassVersionError` 로 죽는다

베이스 이미지가 Java 21 미만이다. 프로젝트는 Java 21 로 컴파일되므로 `eclipse-temurin:21-jre` 를 써야 한다.

### Zipkin 스키마를 고쳤는데 반영이 안 된다

`initdb.d` 는 **DB 데이터가 비어 있을 때 최초 1회만** 실행된다. `rm -rf data/zipkin-mysql` 후 다시 띄운다.

### connect 가 `healthy` 로 안 나온다

정상이다. 이미지의 healthcheck 가 이미지에 없는 curl 을 써서 항상 실패하므로 compose 에서 꺼 두었다. 상태에 health 표시가 없다.

### 메모리 부족으로 컨테이너가 죽는다 (Exited 137)

Docker Desktop 메모리를 늘리거나, [C](#c-필요한-인프라만-골라서) 처럼 필요한 인프라만 띄운다.

---

## 9. 명령어 치트시트

```bash
# ── 빌드 (루트) ────────────────────────────────────────────
./mvnw clean package -DskipTests                     # 전체 jar
./mvnw -pl user-service package -DskipTests          # 하나만

# ── 기동 (docker/) ─────────────────────────────────────────
docker compose up -d                                                  # A. 인프라만 (IDE 실습)
PROMETHEUS_MODE=container docker compose --profile app up -d --build  # B. 전체 컨테이너
docker compose up -d kafka kafka-ui                                   # C. 골라서
docker compose up -d --build user-service                             # D. 하나만 교체

# ── 확인 ───────────────────────────────────────────────────
docker compose ps
docker compose logs -f gateway
docker stats --no-stream

# ── 중지 / 삭제 ────────────────────────────────────────────
docker compose --profile app stop       # 멈춤
docker compose --profile app start      # 다시 켬
docker compose --profile app down       # 컨테이너 + 네트워크 삭제 (데이터 유지)
docker compose --profile app down -v    # + prometheus/grafana 볼륨 삭제

# ── 모드 전환 ──────────────────────────────────────────────
docker compose --profile app down && docker compose up -d             # B → A
```
