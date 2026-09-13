# msa-multi-project

Spring Boot · Spring Cloud 기반 **MSA(마이크로서비스) 실습 프로젝트**.
Maven 멀티모듈(reactor) 구조로, 루트 `pom.xml`(`packaging=pom`)이 8개 서비스를 자식 모듈로 묶고 공통 부모(Spring Boot 3.5.14 / Java 21 / Spring Cloud 2025.0.2)를 제공한다. Git 서브모듈이 아니라 **하나의 리포 안에 8개 서비스가 폴더로** 들어있는 구조다.

---

## 1. 전체 구조 (모듈 지도)

```
msa-multi-project/                     ← 루트 (부모 POM, 버전/의존성 통합관리)
├── service-discovery/   (:8761)   Eureka Server        ── 서비스 주소록
├── config-service/      (:8888)   Config Server        ── 설정 중앙저장소
├── gateway/             (:8000)   Spring Cloud Gateway ── 유일한 대문(입구)
│
├── user-service/        (:0 랜덤)  회원 + 로그인 + JWT 발급
├── catalog-service/     (:0 랜덤)  상품 카탈로그
├── order-service/       (:0 랜덤)  주문
│
├── first-service/       (:8081)   ┐ 게이트웨이 라우팅/필터 실습용
├── second-service/      (:8082)   ┘ 데모 (도메인 아님)
│
├── 01_reference/
│   ├── native-repo/      ← Config Server가 읽는 설정 파일 저장소 (native 모드)
│   ├── git-local-repo/   ← Config Server용 git 모드 저장소 (대기)
│   ├── httpie/           ← Httpie 요청 export
│   └── test.http         ← 전체 흐름 테스트용 요청 모음 (전부 :8000 으로 감)
│
├── kafka-practice/       ← 카프카 학습 문서 (README, ABOUT_KAFKA/)
│
└── docker/               ← 모든 컨테이너를 여기서 관리 (7장 참고)
    ├── docker-compose.yml    인프라 9개 + Spring 서비스 8개(profile: app)
    ├── HOW_TO_DOCKER_COMPOSE.md  ← 실행 방법 상세 문서
    ├── .env                  실행 모드 고정용 (COMPOSE_PROFILES, PROMETHEUS_MODE)
    ├── kafka/connect-plugins/    Kafka Connect JDBC 커넥터 + MariaDB 드라이버
    ├── mariadb/mariadb-ddl.sql   MariaDB 테이블 DDL
    ├── zipkin/initdb.d/          Zipkin MySQL 스키마
    ├── monitoring/               prometheus/{local,container}/, grafana/provisioning/
    └── data/                     bind mount 데이터 (kafka, mariadb, zipkin-mysql) — gitignore
```

> `:0` = OS가 포트를 랜덤 배정. 그래서 이 서비스들은 **포트로 부르지 않고 Eureka에 등록된 이름으로만** 찾는다. 이게 MSA의 핵심 감각.

---

## 2. 3대 인프라 서비스 — MSA의 뼈대

비즈니스 서비스(user/catalog/order)는 서로를 **직접 호출하지 않는다.** 항상 아래 3개를 거친다.

```
        ┌──────────────────────────────────────────────────────────┐
        │                  [1] Config Server (:8888)                │
        │   "모든 서비스의 설정(비밀키 등)을 한 곳에서 관리"          │
        │   설정 원본 → 01_reference/native-repo/*.yml              │
        └──────────────────────────────────────────────────────────┘
                    ▲ 부팅 시 설정 가져감(pull)
                    │
   ┌────────────────┼─────────────────┐
   │                │                 │
┌──┴───┐       ┌────┴────┐       ┌────┴────┐
│ user │       │ catalog │       │  order  │  … 각 비즈니스 서비스
└──┬───┘       └────┬────┘       └────┬────┘
   │                │                 │
   └────────────────┼─────────────────┘
                    ▼ "나 여기 있어요!" 등록(register)
        ┌──────────────────────────────────────────────────────────┐
        │            [2] Eureka Server (:8761)                      │
        │   "서비스 주소록 — 누가 어느 IP:포트에 떠있는지 관리"      │
        └──────────────────────────────────────────────────────────┘
                    ▲ "USER-SERVICE 어디 있어?" 조회(discover)
                    │
        ┌───────────┴──────────────────────────────────────────────┐
        │            [3] API Gateway (:8000)                        │
        │   "바깥세상 → 내부로 들어오는 유일한 문. 경로별 라우팅"     │
        └───────────┬──────────────────────────────────────────────┘
                    ▲
                    │  외부 클라이언트(Postman, test.http)는 오직 여기(:8000)로만 요청
                  🌐 사용자
```

| 서비스 | 역할 | 비유 |
|--------|------|------|
| **Config Server** | 설정 중앙관리 | 회사 공용 문서함 (모두 여기서 규칙을 받아감) |
| **Eureka** | 서비스 위치 등록/검색 | 전화번호부 (이름으로 상대 IP를 찾음) |
| **Gateway** | 단일 진입점 + 라우팅 | 건물 정문 (모든 손님은 여기로만 들어옴) |

---

## 3. 비즈니스 서비스

세 서비스는 동일한 계층 패턴(`controller → service(impl) → jpa`)을 따르며, 전송 객체는 `vo`(요청/응답)와 `dto`(내부)로 나누고 **ModelMapper(STRICT)**로 변환한다. 대부분 **인메모리 H2**(`jdbc:h2:mem:testdb`)를 쓰므로 재시작하면 데이터가 사라진다. (`/h2-console`에서 조회 가능) 단 **order-service만 예외로 MariaDB를 쓴다** — 인스턴스를 여러 대 띄우면 인메모리 H2로는 주문이 흩어지기 때문이다. (자세한 건 [10장 Kafka](#10-kafka--서비스-간-데이터-동기화))

| 서비스 | Eureka 등록명 | 주요 엔드포인트 | 비고 |
|--------|--------------|----------------|------|
| **user-service** | `USER-SERVICE` | `POST /users`(가입), `POST /login`, `GET /users/{userId}` | Security + JWT 발급 |
| **catalog-service** | `CATALOG-SERVICE` | `GET /catalogs`, `/health-check` | `ddl-auto: create-drop` (부팅마다 재시딩) |
| **order-service** | `ORDER-SERVICE` | `POST /{userId}/orders`, `GET /{userId}/orders` | 혼자만 **MariaDB**(H2 아님)를 쓰고, 주문을 DB에 직접 저장하지 않고 Kafka로 발행한다 — 이유는 [10장 Kafka](#10-kafka--서비스-간-데이터-동기화) |
| **first-service** | `MY-FIRST-SERVICE` | 데모 | 게이트웨이 필터 실습 |
| **second-service** | `MY-SECOND-SERVICE` | 데모 | 게이트웨이 필터 실습 |

### Eureka 등록명은 어떻게 정해지나 (복습 포인트)

> 오랜만에 봤을 때 헷갈리는 지점이라 기록해 둔다.

**등록명 = 각 서비스 `application.yaml`의 `spring.application.name` 한 줄이 전부다.**
별도 등록 코드는 없다 — Eureka Client 의존성 + 이 이름만 있으면 부팅 시 자동으로 `:8761`에 신고된다.

```yaml
# user-service/src/main/resources/application.yaml
spring:
  application:
    name: user-service      # ← 이 값이 그대로 Eureka 등록명이 됨
```

기억해야 할 규칙 3가지:

1. **Eureka엔 대문자로 저장된다.** → `user-service` 로 선언해도 등록부엔 `USER-SERVICE`. 그래서 gateway는 `lb://USER-SERVICE`(대문자)로 부른다.
2. **폴더명 ≠ 등록명.** `first-service` 폴더의 `application.name`은 `my-first-service`라서 등록명은 `MY-FIRST-SERVICE`다. 진짜 이름은 항상 `application.name`을 봐야 한다. (라우팅이 안 되면 여기부터 의심)
3. **이름(종류) vs instance-id(개체)는 다르다.** `application.name`은 "서비스 종류 이름", `eureka.instance.instance-id`는 같은 종류를 여러 대 띄웠을 때 구분하는 개체 이름표다. `server.port: 0`(랜덤 포트)로 여러 인스턴스가 공존할 수 있어서, instance-id에 `${random.value}`를 붙여 충돌을 막는다.

```
application.name: user-service
        │ 부팅 시 Eureka Client가 자동 신고
        ▼
┌──────────── Eureka Server (:8761) ────────────┐
│  USER-SERVICE  → 192.168.x.x:52413 (instance A) │  ← 같은 이름,
│  USER-SERVICE  → 192.168.x.x:52890 (instance B) │     여러 대면 instance-id로 구분
└─────────────────────────────────────────────────┘
        ▲ "USER-SERVICE 어디야?" → IP 목록 받아 로드밸런싱
   gateway (lb://USER-SERVICE)
```

> ⚠️ **핵심**: `spring.application.name`(= 등록명)과 게이트웨이의 `lb://` 타깃이 **정확히 일치**해야 라우팅된다. 안 맞으면 503. first/second가 `MY-FIRST-SERVICE`/`MY-SECOND-SERVICE`인 걸 특히 조심.

---

## 4. 요청 흐름 — 라우팅

예: `GET :8000/catalog-service/catalogs`

```
🌐 클라이언트
   │  GET http://localhost:8000/catalog-service/catalogs
   ▼
┌─────────────────────── Gateway (:8000) ───────────────────────┐
│ 1) GlobalFilter (모든 요청 공통 로깅 - default-filters)         │
│ 2) 경로 매칭: /catalog-service/** → uri: lb://CATALOG-SERVICE  │
│ 3) "lb://" = Eureka에 CATALOG-SERVICE 위치 물어봄 →           │
│              여러 인스턴스면 클라이언트 사이드 로드밸런싱         │
└───────────────────────────┬───────────────────────────────────┘
                            ▼  실제 IP:랜덤포트로 전달
                    ┌──────────────────┐
                    │  catalog-service │
                    │  Controller      │
                    │    → Service     │
                    │      → JPA/H2    │
                    └──────────────────┘
```

**경로 매핑** (`gateway/src/main/resources/application.yaml`):

```
/user-service/**   → lb://USER-SERVICE       (인증 필요 경로엔 AuthorizationHeaderFilter)
/catalog-service/**→ lb://CATALOG-SERVICE
/order-service/**  → lb://ORDER-SERVICE
/first-service/**  → lb://MY-FIRST-SERVICE    (CustomFilter)
/second-service/** → lb://MY-SECOND-SERVICE   (CustomFilter + LoggingFilter)
```

---

## 5. 인증 흐름 (JWT)

### (A) 로그인 & 토큰 발급 — user-service

```
🌐 POST :8000/user-service/login  { email, password }
   │
   ▼  Gateway → USER-SERVICE
┌──────────────────── user-service ────────────────────┐
│ AuthenticationFilter (스프링 시큐리티 필터)             │
│  1) JSON 바디에서 email/password 읽음 (RequestLogin)   │
│  2) UserServiceImpl(UserDetailsService)로 검증         │
│     - 비밀번호는 BCrypt로 대조                         │
│  3) 성공 시 successfulAuthentication():                │
│     ┌──────────────────────────────────────────┐     │
│     │ JWT 생성:                                  │     │
│     │  subject = userId                          │     │
│     │  exp     = token.expiration-time           │     │
│     │  서명키  = token.secret ◄── Config Server! │     │
│     └──────────────────────────────────────────┘     │
│  4) 응답 헤더에 token, userId 실어서 반환               │
└───────────────────────────────────────────────────────┘
```

### (B) 인증 필요한 요청 — gateway의 `AuthorizationHeaderFilter`가 문지기

```
🌐 GET :8000/user-service/users   (Authorization: Bearer <JWT>)
   │
   ▼
┌──────────── Gateway: AuthorizationHeaderFilter ────────────┐
│ 1) Authorization 헤더 없으면 → 401                          │
│ 2) "Bearer " 떼고 JWT 파싱                                  │
│ 3) token.secret 으로 서명 검증 ◄── 여기도 Config Server 값  │
│ 4) 유효하면 통과, 아니면 → 401                              │
└─────────────────────────────────────────────────────────────┘
```

> 🔑 **핵심**: JWT를 *발급*하는 곳(user-service)과 *검증*하는 곳(gateway)이 **똑같은 `token.secret`을 공유해야 한다.** 그래서 이 비밀키를 각 서비스에 하드코딩하지 않고 Config Server 한 곳에 두고 모두가 부팅 시 받아간다. → 아래 [주의사항](#주의사항--config--반드시-읽을-것) 참고.

### (C) 실제 사용법 — 회원가입 → 로그인 → 인증요청 (복습용)

로그인은 **DB에 저장된 회원**을 인증한다. 즉 **[1] 회원가입 → [2] 로그인**의 2단계다. (`application.yaml`의 `spring.security.user`(user/password)는 안 쓰이는 잔재이니 헷갈리지 말 것 — 실제 로그인은 아래처럼 email 기반이다.)

**[1] 회원가입** — `POST :8000/user-service/users`
```json
{ "email": "example@example.com", "name": "example", "pwd": "1234" }
```
- 비밀번호는 BCrypt로 암호화되어 H2 DB에 저장된다. (H2라 재시작하면 사라짐 → 매번 재가입 필요)

**[2] 로그인** — `POST :8000/user-service/login`
```json
{ "email": "example@example.com", "password": "1234" }
```
- 성공 시 **응답 헤더**로 `token`(JWT)과 `userId`가 온다. (응답 바디 아님 — 헤더를 봐야 함)

**[3] 인증 필요한 요청** — 받은 JWT를 헤더에 실어 호출
```
GET :8000/user-service/users
Authorization: Bearer <[2]에서 받은 token>
```
- gateway의 `AuthorizationHeaderFilter`가 JWT를 검증한다. 헤더 없거나 서명 안 맞으면 401.

> ⚠️ **필드 이름 함정**: 회원가입은 `pwd`, 로그인은 `password`로 키 이름이 **다르다** (각각 `RequestUser`, `RequestLogin` VO를 따름). 복붙하다 자주 틀리는 지점.
>
> 실행 가능한 예시 요청은 `01_reference/test.http`에 모두 있다.

---

## 6. 설정 중앙화 + 실시간 갱신 (Config + RabbitMQ Bus)

### 설정을 받는 시점 — 부팅할 때 (bootstrap)

각 서비스는 `bootstrap.yml`을 먼저 읽어 **application.yaml 로드 전에** Config Server에 접속해 설정을 받아온다.

```
bootstrap.yml 읽음 → Config Server(:8888) 접속
   → 내 설정 받아옴(token.secret 등) → 그 다음 application.yaml 로드
```

### 설정이 바뀌면? — Spring Cloud Bus로 전체에 방송

설정 파일을 고친 뒤 `/actuator/busrefresh`를 **아무 서비스 하나에** 호출하면, RabbitMQ를 통해 구독 중인 모든 서비스에 전파되어 동시에 새 값을 다시 받아간다.

```
   설정파일 수정 (native-repo/ecommerce.yml 의 secret 변경)
                    │
                    ▼
   POST :8888/actuator/busrefresh
                    │
                    ▼
        ┌───────────────────────────┐
        │      RabbitMQ (:5672)      │  ← 메시지 브로커 (방송국)
        │   Spring Cloud Bus 채널    │
        └─────┬─────────┬─────────┬──┘
              ▼         ▼         ▼
          gateway    user-svc   config-svc  … 구독중인 모든 서비스가 동시에 새 값 반영
```

> 🐰 RabbitMQ는 **4.2.7 버전**을 사용한다. 4.3.x는 호환성 에러가 나므로 주의.
> ```bash
> podman run -d -p 5672:5672 -p 15672:15672 --name rabbitmq rabbitmq:4.2.7-management
> ```

---

## 7. 빌드 & 실행

Maven 래퍼를 리포 루트에서 사용한다.

```bash
./mvnw clean install                          # 전체 빌드
./mvnw -pl user-service spring-boot:run        # 특정 서비스 실행
./mvnw -pl order-service test                  # 모듈 테스트
```

Spring 서비스를 로컬(IDE / `spring-boot:run`)에서 띄울 때는 **터미널을 여러 개 열고 순서대로** 띄운다. 순서를 어기면 등록 실패로 무한 재시도한다.

```
[1] 인프라 컨테이너              cd docker && docker compose up -d   (RabbitMQ, Kafka, Zipkin, ...)
[2] service-discovery (Eureka)   ./mvnw -pl service-discovery spring-boot:run
[3] config-service               ← 다른 서비스가 부팅 시 여기 설정을 받아가므로 먼저
[4] gateway
[5] user / catalog / order / first / second  (순서 무관)
```

전체 흐름 테스트는 `01_reference/test.http` (모든 요청이 게이트웨이 `:8000`으로 감).

### 컨테이너로 실행 (docker compose) → [`docker/HOW_TO_DOCKER_COMPOSE.md`](docker/HOW_TO_DOCKER_COMPOSE.md)

모든 컨테이너(인프라 9개 + Spring 서비스 8개)는 **`docker/docker-compose.yml` 한 파일**에 있다.
Spring 서비스에만 `profiles: [app]` 이 붙어 있어서, 명령 하나로 "무엇까지 띄울지" 를 고른다. (명령은 `docker/` 에서)

| 하고 싶은 것 | 명령 |
|---|---|
| IDE 로 실습 (인프라만 컨테이너) | `docker compose up -d` → 위 순서대로 IDE 에서 실행 |
| 전체를 컨테이너로 | `./mvnw clean package -DskipTests` 후 `PROMETHEUS_MODE=container docker compose --profile app up -d --build` |
| 필요한 인프라만 | `docker compose up -d kafka kafka-ui` |
| 서비스 하나 다시 빌드 | `docker compose up -d --build user-service` |
| 전부 내리기 | `docker compose --profile app down` |

yml 의 주소는 `${RABBITMQ_HOST:127.0.0.1}` 처럼 **환경변수 + 로컬 기본값**으로 되어 있어서, 같은 설정으로 IDE 실행과 컨테이너 실행이 둘 다 된다.

profiles 개념, 시나리오별 실행(모드 전환 · `.env` 고정), 접속 주소, 데이터 초기화, 트러블슈팅은 **[HOW_TO_DOCKER_COMPOSE.md](docker/HOW_TO_DOCKER_COMPOSE.md)** 에 정리했다.

---

## 8. 서비스 간 통신 (user → order)

`GET /users/{userId}` 응답에 주문 목록을 채우려고 user-service가 order-service를 호출한다.
**게이트웨이를 안 거치고 Eureka로 직접 부른다** — 이게 경로 헷갈림의 원인.

```
 :8000/user-service/users/{id}          user-service 내부
        │                                     │
   [게이트웨이]                            [Feign] ──► Eureka ──► order-service 직접 호출
   RewritePath로 /user-service 깎음            └─ 게이트웨이를 안 탐 = RewritePath 없음
        ▼                                        = 컨트롤러 실제 경로를 그대로 써야 함
   user-service:/users/{id}
```

### 두 가지 방식 — RestTemplate(주석 보존) / OpenFeign(현재)

```java
// [1] RestTemplate: @LoadBalanced 를 붙여야 호스트 자리에 Eureka 이름을 쓸 수 있다
//    URL은 native-repo/user-service.yml 의 order-service.url

// [2] OpenFeign: @EnableFeignClients + 인터페이스 선언만
@FeignClient(name = "order-service", configuration = FeignErrorDecoder.class)
List<ResponseOrder> getOrders(@PathVariable String userId);   // @GetMapping("/order-service/{userId}/orders")
```

- **로그**: `Logger.Level.FULL` 빈 + `logging.level.…client: DEBUG` — **둘 다** 켜야 찍힌다.
- **에러**: `FeignErrorDecoder`로 상태코드별 예외 변환 → 서비스에선 `catch (FeignException)` 후 **빈 배열 폴백**(= Circuit Breaker의 원시 버전). 그래서 order-service가 죽으면 `orders`가 조용히 `[]`로 나온다.

---

## 9. 설정값 암호화 (`{cipher}`)

Config Server는 `@EnableConfigServer` 하나로 **`/encrypt`, `/decrypt` 엔드포인트까지 자동 제공**한다.
(내 코드가 아니라 라이브러리 안의 `EncryptionController` — grep해도 안 나온다)

```
평문 ──POST :8888/encrypt──► 암호문
        native-repo/*.yml 에 secret: '{cipher}암호문'   ← 작은따옴표 필수 (YAML이 {를 객체로 봄)
                    │
        Config Server가 내려주기 직전에 자동 복호화
                    ▼
        클라이언트는 평문을 받음 → 클라이언트 쪽 코드/설정 수정 불필요
```

- 열쇠는 `config-service/bootstrap.yml`에 있다. **대칭키(`encrypt.key`)는 주석 처리**, 현재는 **keystore(RSA)** 사용.
- 지금 암호화된 값: `application.yml`의 `token.secret`, `user-service.yml`의 H2 `password`.
- 확인: `:8888/ecommerce/default` 에서 **평문**이면 성공, `{cipher}…` 그대로면 복호화 실패.
- 👉 keystore 생성·설정·트러블슈팅은 **[`keystore/README.md`](keystore/README.md)** 참고.

---

## 10. Kafka — 서비스 간 데이터 동기화

지금까지는 서비스끼리 **직접 호출**(Feign, 8장)했다. 여기서는 **메시지를 던져놓고 끝**내는 비동기 방식을 쓴다.
주문이 발생하면 order-service는 Kafka에 **발행만** 하고, 나머지는 각자 알아서 가져간다.

```
                        POST :8000/order-service/{userId}/orders
                                      │
                        ┌─────── order-service ────────┐
                        │  (발행만 하고 응답 반환)        │
                        └───┬───────────────────┬───────┘
       [1] KafkaProducer    │                   │   [2] OrderProducer
           topic:           │                   │       topic: orders
           example-catalog-topic                │
                            ▼                   ▼
                    ┌──────────────────────────────────┐
                    │        Kafka (:9092)             │
                    └────┬─────────────────────┬───────┘
                         ▼                     ▼
                  catalog-service        Kafka Connect (JDBC Sink)
                  @KafkaListener               │
                         │                     ▼
                    재고(stock) 차감        MariaDB orders 테이블
```

핵심은 **같은 주문 이벤트를 두 갈래로 쓴다**는 것:

| 경로 | 토픽 | 받는 쪽 | 목적 |
|---|---|---|---|
| **[1] 직접 컨슘** | `example-catalog-topic` | catalog-service 코드 (`@KafkaListener`) | 재고 차감 (**내 코드**가 처리) |
| **[2] Connect Sink** | `orders` | Kafka Connect JDBC Sink | DB 저장 (**코드 없이** 커넥터가 처리) |

### (A) 왜 H2를 버리고 MariaDB + Sink로 갔나

order-service는 `server.port: 0`이라 **여러 대가 동시에 뜬다.** 그런데 기존엔 각 인스턴스가 자기 메모리 안의 H2를 들고 있었다.

```
[ 이전 — 각자 인메모리 H2 ]              [ 지금 — 토픽으로 모아서 한 DB ]

order-svc A ─► H2(A)  주문 1건          order-svc A ─┐
order-svc B ─► H2(B)  주문 1건          order-svc B ─┼─► topic: orders
order-svc C ─► H2(C)  주문 1건          order-svc C ─┘         │
                                                               ▼
"내 주문 조회했는데 3건 중 1건만 나옴"                    JDBC Sink Connector
 = 어느 인스턴스에 걸렸느냐에 따라 결과가 달라짐                 │
                                                               ▼
                                                     MariaDB orders (한 곳 ✓)
```

그래서 **DB에 쓰는 일 자체를 order-service에서 떼어냈다.**
`OrderController`의 JPA 저장 코드는 주석 처리되어 있고, 대신 주문을 `orders` 토픽으로 보낸다 → Sink 커넥터가 그걸 꺼내 MariaDB에 넣는다 → 몇 대가 떠 있든 주문은 **한 테이블에 모인다.**

> 요약: 인스턴스가 늘어나면 **인메모리 DB는 곧바로 깨진다.** 공용 DB(MariaDB)로 옮기고, 쓰기 경로를 Kafka로 일원화한 것.

### (B) `orders` 토픽 메시지는 왜 이렇게 생겼나 — schema + payload

Sink 커넥터는 "이 값이 어느 컬럼의 무슨 타입인지"를 모른다. 그래서 메시지에 **스키마를 같이 실어** 보낸다.
(`OrderProducer` + `dto/{Schema,Field,Payload,KafkaOrderDto}` 가 이걸 만드는 코드)

```json
{
  "schema":  { "type":"struct", "name":"orders",
               "fields":[ {"type":"string","field":"order_id"} ] },
  "payload": { "order_id":"...", "user_id":"...", "qty":3 }
}
```

> 📌 payload의 키는 **DB 컬럼명(snake_case)** 이다. 자바 필드명(`orderId`)이 아니라 `order_id`.
> 반면 [1]번(catalog-service)으로 가는 메시지는 `OrderDto`를 그대로 직렬화한 평범한 JSON이라 `productId`/`qty` 처럼 camelCase다. **두 토픽의 형식이 다르다**는 점을 헷갈리지 말 것.

### (C) 코드 위치

```
order-service/messagequeue/
  ├── KafkaProducerConfig.java   ← 브로커 주소(spring.kafka.bootstrap-servers) + String 직렬화
  ├── KafkaProducer.java         ← [1] OrderDto 그대로 → example-catalog-topic
  └── OrderProducer.java         ← [2] schema+payload 로 감싸서 → orders

catalog-service/messagequeue/
  ├── KafkaConsumerConfig.java   ← 브로커 주소 + groupId(consumerGroup)
  └── KafkaConsumer.java         ← @KafkaListener → productId로 찾아 stock 차감
```

### (D) 띄우는 순서

```bash
cd docker && docker compose up -d kafka kafka-ui mariadb connect   # 인프라 전체는 docker compose up -d
```

| 확인할 것 | 주소 |
|---|---|
| Kafka UI (토픽/메시지 눈으로 보기) | http://localhost:8090 |
| Connect REST | http://localhost:8083/connectors |

Sink 커넥터 등록 명령은 → [`kafka-practice/ABOUT_KAFKA/04_test_with_spring.md`](kafka-practice/ABOUT_KAFKA/04_test_with_spring.md)

### (E) 자주 밟는 지뢰 ⚠️

**1. `StringDeserializer` import를 잘못 잡는다** (IDE 자동완성이 Jackson 걸 가져옴)

```java
✗ com.fasterxml.jackson.databind.deser.std.StringDeserializer   // Jackson 내부용
✓ org.apache.kafka.common.serialization.StringDeserializer      // Kafka 용
```

> 증상: 기동 시 `Failed to start bean 'internalKafkaListenerEndpointRegistry'`
> → 진짜 원인은 스택트레이스 맨 아래 `... is not an instance of ...Deserializer`. **로그 맨 밑줄부터 볼 것.**

**2. 브로커 주소를 헷갈린다** — 호스트(Spring Boot)에서는 `localhost:9092`, 컨테이너끼리는 `kafka:19092`.

**3. order-service는 이제 H2가 아니라 MariaDB다.** 컨테이너가 안 떠 있으면 부팅부터 실패. 테이블 DDL은 `docker/mariadb/mariadb-ddl.sql`.

### (F) 더 깊게 — 학습 문서

| 문서 | 내용 |
|---|---|
| [`kafka-practice/README.md`](kafka-practice/README.md) | CLI 빠른 참조 (compose 는 `docker/` 로 통합됨) |
| [`ABOUT_KAFKA/01_kafka_개념.md`](kafka-practice/ABOUT_KAFKA/01_kafka_개념.md) | Topic / Partition / Consumer Group / KRaft |
| [`ABOUT_KAFKA/02_docker_compose_설정해설.md`](kafka-practice/ABOUT_KAFKA/02_docker_compose_설정해설.md) | 리스너 2개를 두는 이유 등 compose 한 줄씩 |
| [`ABOUT_KAFKA/03_kafka_connect_실습.md`](kafka-practice/ABOUT_KAFKA/03_kafka_connect_실습.md) | Source / Sink 커넥터 등록 실습 |
| [`ABOUT_KAFKA/04_test_with_spring.md`](kafka-practice/ABOUT_KAFKA/04_test_with_spring.md) | order-service → Sink 연동 명령 |

---

## 11. Zipkin — 분산 추적

`gateway → user-service → order-service` 로 이어지는 **하나의 요청**이 어디서 얼마나 걸렸는지 본다.
요청 하나가 흐르는 전 구간에 같은 `traceId` 를 매기고, 구간(서비스·호출)마다 `spanId` 를 따로 발급한다.
이 둘을 서비스 간 호출 때 HTTP 헤더로 넘겨서 하나로 엮는 원리다.

```bash
cd docker && docker compose up -d zipkin   # Zipkin 3 + MySQL 8 (zipkin-mysql 은 depends_on 으로 같이 뜬다)
# UI: http://localhost:9411
```

### 구성 — 의존성 + 설정

| 의존성 | 역할 |
|---|---|
| `spring-boot-starter-actuator` | **추적 자동설정이 여기 들어있다.** 없으면 `management.*` 가 통째로 무시된다 |
| `micrometer-tracing-bridge-brave` | trace/span 생성 및 전파 |
| `zipkin-reporter-brave` | span 을 Zipkin 으로 전송 |
| `feign-micrometer` | Feign 구간 추적 (user-service 만) |

```yaml
management:
  tracing:
    sampling:
      probability: 1.0                                  # 실습 100%, 운영은 0.1 정도
#    propagation:                                       # trace id 를 주고받을 헤더 형식
#      consume: B3                                      #   기본 W3C(traceparent) / B3 는 X-B3-*
#      produce: B3                                      #   바꿀 거면 gateway 포함 전 서비스 동일하게
  zipkin:
    tracing:
      endpoint: http://127.0.0.1:9411/api/v2/spans
logging:
  pattern:
    correlation: "[${spring.application.name:},%X{traceId:-},%X{spanId:-}] "
```

### 자주 밟는 지뢰 ⚠️

- **`endpoint` 에 `/api/v2/spans` 까지 적어야 한다.** 빠뜨리면 에러 없이 조용히 전송만 실패한다.
- **Feign 호출은 `feign-micrometer` 가 있어야 추적된다.** 자동 계측 대상은 RestTemplate 계열뿐이라,
  없으면 user-service → order-service 구간이 끊겨 **trace 가 2개로 쪼개져 보인다.**
- **`docker/zipkin/initdb.d` 스키마는 최초 1회만 실행된다.** 고쳤으면 `rm -rf docker/data/zipkin-mysql` 후 재기동.
- MySQL 호스트 포트는 **3307**. mariadb 가 3306 을 쓰고 있다.

> 현재 적용 범위: **user-service, order-service**. gateway·catalog-service 는 아직 미적용이라
> 전체 흐름이 아니라 두 서비스 구간만 보인다.

---

## 12. Prometheus + Grafana — 메트릭 모니터링

Zipkin이 **요청 하나**를 쫓는다면, Prometheus는 **전체를 숫자로** 본다.
각 서비스가 `/actuator/prometheus` 에 현재 수치를 뱉어두면 Prometheus가 주기적으로 긁어가(scrape) 시계열로 쌓고, Grafana가 그걸 그린다.
서비스가 보내는(push) 게 아니라 **Prometheus가 가지러 오는(pull)** 구조다.

```bash
cd docker && docker compose up -d prometheus grafana   # Prometheus(:9090) + Grafana(:3000, admin/admin)
# 타겟 상태: http://localhost:9090/targets
```

Prometheus 설정 파일은 **gateway 가 어디서 떠 있느냐**에 따라 둘로 나뉜다. `PROMETHEUS_MODE` 로 고른다.

| 모드 | 파일 | 타겟 주소 | 언제 |
|---|---|---|---|
| `local` (기본) | `docker/monitoring/prometheus/local/prometheus.yml` | `host.docker.internal:8000` | gateway 를 호스트(IDE)에서 실행 |
| `container` | `docker/monitoring/prometheus/container/prometheus.yml` | `gateway:8000` | gateway 도 컨테이너 |

```bash
cd docker && docker compose up -d prometheus                            # local
cd docker && PROMETHEUS_MODE=container docker compose up -d prometheus  # container (또는 .env 에서 고정)
```

> 모드를 바꿨으면 prometheus 컨테이너를 다시 만들어야 반영된다 (`up -d` 가 설정 변경을 감지해 재생성한다).

### 구성 — 의존성 + 설정

| 의존성 | 역할 |
|---|---|
| `spring-boot-starter-actuator` | 메트릭 수집의 본체 |
| `micrometer-registry-prometheus` | 수집한 메트릭을 **Prometheus 텍스트 포맷**으로 변환. 이게 있어야 `/actuator/prometheus` 가 생긴다 |

```yaml
management:
  endpoints:
    web:
      exposure:
        include:
          - metrics
          - prometheus      # 이 줄이 없으면 404
```

적용 범위는 **gateway, user-service, order-service** 세 개. `@Timed` 를 붙이면 그 메서드만 따로 집계된다.

```java
@GetMapping("/health-check")
@Timed(value = "users.status", longTask = true)   // longTask = 아직 끝나지 않은 호출의 경과 시간
public String status() { ... }
```

### 왜 게이트웨이(:8000)를 거쳐 긁나

비즈니스 서비스는 `server.port: 0`(랜덤)이라 **Prometheus가 적어둘 고정 주소가 없다.**
그래서 포트가 고정된 게이트웨이를 경유한다. 게이트웨이의 actuator 전용 라우트가 이걸 위한 것.

```yaml
# docker/monitoring/prometheus/local/prometheus.yml
scrape_configs:
  - job_name: user-service
    metrics_path: /user-service/actuator/prometheus
    static_configs:
      - targets: ['host.docker.internal:8000']
```

### 라우트 순서 ★ (여기서 제일 많이 막힌다)

게이트웨이 라우트는 **선언 순서대로 검사하고 첫 매치에서 끝난다.** "경로가 더 구체적인 쪽 우선" 같은 규칙은 없다.
그래서 actuator 라우트는 반드시 catch-all **위**에 둬야 한다.

```yaml
- id: user-service-actuator                 # 구체적인 것이 위
  predicates:
    - Path=/user-service/actuator/**
    - Method=GET,POST
  filters:
    - RewritePath=/user-service/(?<segment>.*), /$\{segment}
- id: user-service                          # catch-all 은 맨 아래
  predicates:
    - Path=/user-service/**
  filters:
    - RewritePath=/user-service/(?<segment>.*), /$\{segment}
    - AuthorizationHeaderFilter             # 위아래가 바뀌면 actuator 도 이 필터를 탄다
```

순서가 뒤바뀌면 user-service는 토큰이 없다고 **401**, order-service는 경로 변환이 안 돼서 **404** 가 난다. 증상이 달라서 헷갈린다.

### 자주 밟는 지뢰 ⚠️

- **`micrometer-registry-prometheus` 없으면 `/actuator/prometheus` 가 404.** 엔드포인트를 만드는 건 레지스트리다.
- **order-service catch-all 에만 `RewritePath` 가 없다.** `OrderController` 가 `@RequestMapping("/order-service")` 라 접두어째 받는다.
- **라우트 `id` 중복 주의.** 로그와 `routeId` 라벨에서 어느 라우트인지 구분되지 않는다.
- 컨테이너에서 호스트를 부르는 주소는 `host.docker.internal`.

> 인스턴스를 2개 이상 띄우면 **메트릭이 섞인다.** 게이트웨이가 스크레이프 요청까지 로드밸런싱해서
> 매번 다른 인스턴스가 응답하기 때문. 다중 인스턴스 실습에 들어가면 `eureka_sd_configs` 로 바꿀 것.

### Grafana

데이터소스는 손으로 추가하지 않아도 된다. 그라파나는 부팅할 때
`/etc/grafana/provisioning/datasources/*.yml` 을 읽어 거기 적힌 데이터소스를 등록하는데(프로비저닝),
compose 가 `docker/monitoring/grafana/provisioning` 을 그 경로에 마운트해 둔다.

대시보드는 UI 에서 Import 한다.

| 번호 | 대시보드 |
|---|---|
| `4701` | JVM (Micrometer) |
| `11378` | Spring Boot Statistics |

게이트웨이 라우트별 지표는 `spring_cloud_gateway_requests_seconds_count` 로 직접 조회한다.

---

## 주의사항 — Config ⚠️ (반드시 읽을 것)

이 프로젝트를 다시 띄울 때 **가장 자주 깨지는 지점이 Config 설정**이다. 아래 세 가지를 반드시 확인할 것.

### [1] Config Server의 경로는 **로컬 PC 절대경로**에 의존한다

`config-service/src/main/resources/application.yaml`의 `search-locations`는 native 모드에서 **내 로컬 PC 폴더**를 직접 읽는다.

```yaml
spring:
  profiles:
    active: native   # native = git이 아니라 로컬 파일시스템에서 설정을 읽음
  cloud:
    config:
      server:
        native:
          search-locations: file://${user.home}/study/msa-multi-project/01_reference/native-repo
        git:
          uri: file://${user.home}/study/msa-multi-project/01_reference/git-local-repo
```

- **프로젝트를 다른 경로/PC로 옮기면 이 경로부터 깨진다.** `${user.home}` 기준 상대 위치가 맞는지 항상 확인.
- native 모드는 폴더를 못 찾아도 **에러 없이 빈 설정을 반환**하는 경우가 있어, `token.secret`이 안 넘어와 JWT가 조용히 깨질 수 있다. (원인 찾기 어려움)

### [2] native 모드는 **파일 이름으로 매칭**한다

`native-repo/` 폴더 안 파일은 이름 규칙으로 골라 읽힌다.

```
📁 native-repo/
   ├── application.yml    ← 【공통】 모든 서비스가 받아감 (낮은 우선순위)
   ├── ecommerce.yml      ← name=ecommerce 인 서비스가 받아감 (전용, 높은 우선순위)
   └── user-service.yml   ← name=user-service 인 서비스가 받아감
```

- 규칙: 각 서비스는 **`application.yml`(공통) + `{bootstrap의 name}.yml`(전용)** 을 받아 합친다. 겹치면 전용 파일이 이긴다.
- 각 파일의 `token.secret` 값은 **일부러 서로 다르게** 되어 있다 (`..._application`, `..._ecommerce`, `..._user_service`). → 서비스가 실제로 **어느 파일을 받았는지 secret 값으로 역추적**하기 위한 실습 장치다.

### [3] JWT 발급/검증은 **양쪽이 같은 secret**을 받아야 동작한다 ★ 가장 중요

`token.secret`은 **공통 `application.yml`에만** 들어있다. 공통 파일은 모든 서비스가 받아가므로,
발급하는 user-service와 검증하는 gateway가 자동으로 같은 값을 쓰게 된다.

> ⚠️ 우선순위가 `application.yml(공통) < {name}.yml(전용)`이므로,
> 전용 파일에 `token:`을 다시 넣으면 그 서비스만 다른 secret을 갖게 되어 **모든 토큰이 401**이 된다.

---

## 주의사항 — IP 허용목록 ⚠️

`user-service/.../security/WebSecurity.java` 는 **gateway 가 보낸 요청만** 받도록 출발지 IP 를 검사한다. (`hasIpAddress`)
허용 IP 는 코드에 박지 않고 설정 `gateway.allowed-ips` 로 받는다.

| 실행 방식 | 허용 IP | 그 IP 가 되도록 하는 설정 |
|---|---|---|
| IDE (로컬) | `127.0.0.1`, `::1` (기본값) | user-service 가 Eureka 에 `hostname: localhost` 로 등록 → gateway 가 `localhost` 로 호출 |
| 컨테이너 | `172.18.0.100` (`GATEWAY_ALLOWED_IPS`) | compose 가 gateway 컨테이너에 고정 IP 부여 (`ipv4_address`) |

```yaml
# user-service/src/main/resources/application.yaml
eureka:
  instance:
    hostname: localhost                                   # IDE 에서만 의미 있음 (컨테이너는 prefer-ip-address=true 라 무시)
gateway:
  allowed-ips: "${GATEWAY_ALLOWED_IPS:127.0.0.1,::1}"
```

### 왜 예전에는 LAN IP 를 넣어야 했나

`hasIpAddress` 가 보는 건 **요청을 보낸 쪽 IP**(`request.getRemoteAddr()`) 이고, 그 값은 **gateway 가 어느 주소로 접속했느냐**로 정해진다.

```
[hostname 미지정] Eureka 에 LAN IP(172.30.1.18) 로 등록 → gateway 가 172.30.1.18 로 접속 → 출발지 172.30.1.18
[hostname=localhost] Eureka 에 localhost 로 등록       → gateway 가 localhost 로 접속   → 출발지 127.0.0.1
```

같은 PC 안의 통신이라도 LAN 주소로 걸면 LAN 주소가, localhost 로 걸면 `127.0.0.1` 이 출발지가 된다.
예전에는 LAN IP 로 등록됐기 때문에 코드에 `192.168.x.x` 를 박아야 했고, **와이파이가 바뀔 때마다 깨졌다.**
지금은 localhost 로 등록하므로 네트워크가 바뀌어도 그대로다. (실측: 수정 전 LAN IP 출발지 → 401, 수정 후 `127.0.0.1` 출발지 → 201)

### 자주 밟는 지뢰

- **거부되면 403 이 아니라 401 이 난다.** `httpBasic` 이 켜져 있어서 "인증하라" 로 응답한다. JWT 오류(401)와 구분이 안 되므로,
  **JWT 필터가 없는 회원가입(`POST /user-service/users`)이 401 이면 IP 문제**다.
- **컨테이너에서 네트워크 대역(`172.18.0.0/16`) 전체를 허용하면 안 된다.** gateway 를 우회해 다른 컨테이너(prometheus 등)가 직접 호출해도 통과한다.
  (실측: 대역 허용 시 prometheus 컨테이너 → user-service `/users` 200, gateway IP 만 허용 시 401)
- **gateway 고정 IP 와 `GATEWAY_ALLOWED_IPS` 는 같은 값이어야 한다.** 둘 다 `docker/docker-compose.yml` 에 있다.
- **`localhost` 등록은 gateway 와 user-service 가 같은 PC 에 있을 때만 맞다.** 다른 PC 의 gateway 가 받으면 자기 자신을 찾아간다.
- `/actuator/**`, `/h2-console/**` 은 IP 검사 없이 열려 있다. (Prometheus 스크레이프용)
- native-repo 의 `gateway.ip` 는 health-check 화면에 **표시만** 될 뿐 검사에는 쓰이지 않는다.
