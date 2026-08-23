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
└── 01_reference/
    ├── native-repo/      ← Config Server가 읽는 설정 파일 저장소 (native 모드)
    ├── git-local-repo/   ← Config Server용 git 모드 저장소 (대기)
    └── test.http         ← 전체 흐름 테스트용 요청 모음 (전부 :8000 으로 감)
```

> `:0` = OS가 포트를 랜덤 배정. 그래서 이 서비스들은 **포트로 부르지 않고 Eureka에 등록된 이름으로만** 찾는다. 이게 MSA의 핵심 감각.

---

## 2. 3대 인프라 서비스 — MSA의 뼈대

비즈니스 서비스(user/catalog/order)는 서로를 **직접 호출하지 않는다.** 항상 아래 3개를 거친다.

```
        ┌──────────────────────────────────────────────────────────┐
        │                    ① Config Server (:8888)                │
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
        │              ② Eureka Server (:8761)                      │
        │   "서비스 주소록 — 누가 어느 IP:포트에 떠있는지 관리"      │
        └──────────────────────────────────────────────────────────┘
                    ▲ "USER-SERVICE 어디 있어?" 조회(discover)
                    │
        ┌───────────┴──────────────────────────────────────────────┐
        │              ③ API Gateway (:8000)                        │
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

세 서비스는 동일한 계층 패턴(`controller → service(impl) → jpa`)을 따르며, 전송 객체는 `vo`(요청/응답)와 `dto`(내부)로 나누고 **ModelMapper(STRICT)**로 변환한다. 모두 **인메모리 H2**(`jdbc:h2:mem:testdb`)를 쓰므로 재시작하면 데이터가 사라진다. (`/h2-console`에서 조회 가능)

| 서비스 | Eureka 등록명 | 주요 엔드포인트 | 비고 |
|--------|--------------|----------------|------|
| **user-service** | `USER-SERVICE` | `POST /users`(가입), `POST /login`, `GET /users/{userId}` | Security + JWT 발급 |
| **catalog-service** | `CATALOG-SERVICE` | `GET /catalogs`, `/health-check` | `ddl-auto: create-drop` (부팅마다 재시딩) |
| **order-service** | `ORDER-SERVICE` | `POST /{userId}/orders`, `GET /{userId}/orders` | userId 기준 주문 |
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

로그인은 **DB에 저장된 회원**을 인증한다. 즉 **① 회원가입 → ② 로그인**의 2단계다. (`application.yaml`의 `spring.security.user`(user/password)는 안 쓰이는 잔재이니 헷갈리지 말 것 — 실제 로그인은 아래처럼 email 기반이다.)

**① 회원가입** — `POST :8000/user-service/users`
```json
{ "email": "example@example.com", "name": "example", "pwd": "1234" }
```
- 비밀번호는 BCrypt로 암호화되어 H2 DB에 저장된다. (H2라 재시작하면 사라짐 → 매번 재가입 필요)

**② 로그인** — `POST :8000/user-service/login`
```json
{ "email": "example@example.com", "password": "1234" }
```
- 성공 시 **응답 헤더**로 `token`(JWT)과 `userId`가 온다. (응답 바디 아님 — 헤더를 봐야 함)

**③ 인증 필요한 요청** — 받은 JWT를 헤더에 실어 호출
```
GET :8000/user-service/users
Authorization: Bearer <②에서 받은 token>
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

중앙 런처가 없으므로 **터미널을 여러 개 열고 순서대로** 띄운다. 순서를 어기면 등록 실패로 무한 재시도한다.

```
1️⃣ RabbitMQ 컨테이너            podman run ... rabbitmq:4.2.7-management
2️⃣ service-discovery (Eureka)   ./mvnw -pl service-discovery spring-boot:run
3️⃣ config-service               ← 다른 서비스가 부팅 시 여기 설정을 받아가므로 먼저
4️⃣ gateway
5️⃣ user / catalog / order / first / second  (순서 무관)
```

전체 흐름 테스트는 `01_reference/test.http` (모든 요청이 게이트웨이 `:8000`으로 감).

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
// ① RestTemplate: @LoadBalanced 를 붙여야 호스트 자리에 Eureka 이름을 쓸 수 있다
//    URL은 native-repo/user-service.yml 의 order-service.url

// ② OpenFeign: @EnableFeignClients + 인터페이스 선언만
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

## 주의사항 — Config ⚠️ (반드시 읽을 것)

이 프로젝트를 다시 띄울 때 **가장 자주 깨지는 지점이 Config 설정**이다. 아래 세 가지를 반드시 확인할 것.

### ① Config Server의 경로는 **로컬 PC 절대경로**에 의존한다

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

### ② native 모드는 **파일 이름으로 매칭**한다

`native-repo/` 폴더 안 파일은 이름 규칙으로 골라 읽힌다.

```
📁 native-repo/
   ├── application.yml    ← 【공통】 모든 서비스가 받아감 (낮은 우선순위)
   ├── ecommerce.yml      ← name=ecommerce 인 서비스가 받아감 (전용, 높은 우선순위)
   └── user-service.yml   ← name=user-service 인 서비스가 받아감
```

- 규칙: 각 서비스는 **`application.yml`(공통) + `{bootstrap의 name}.yml`(전용)** 을 받아 합친다. 겹치면 전용 파일이 이긴다.
- 각 파일의 `token.secret` 값은 **일부러 서로 다르게** 되어 있다 (`..._application`, `..._ecommerce`, `..._user_service`). → 서비스가 실제로 **어느 파일을 받았는지 secret 값으로 역추적**하기 위한 실습 장치다.

### ③ JWT 발급/검증은 **양쪽이 같은 secret**을 받아야 동작한다 ★ 가장 중요

`token.secret`은 **공통 `application.yml`에만** 들어있다. 공통 파일은 모든 서비스가 받아가므로,
발급하는 user-service와 검증하는 gateway가 자동으로 같은 값을 쓰게 된다.

> ⚠️ 우선순위가 `application.yml(공통) < {name}.yml(전용)`이므로,
> 전용 파일에 `token:`을 다시 넣으면 그 서비스만 다른 secret을 갖게 되어 **모든 토큰이 401**이 된다.

---

## 주의사항 — IP 허용목록 ⚠️

`user-service/.../security/WebSecurity.java`의 `hasIpAddress(...)` 목록에 **현재 내 PC의 LAN IP**가 들어있어야 한다. 없으면 gateway → user-service 요청이 전부 **401**.

```java
"hasIpAddress('127.0.0.1') or hasIpAddress('::1') or hasIpAddress('192.168.219.141')"
                                                                  └─ 여기를 내 IP로 수정
```

- **왜 127.0.0.1로는 안 되나**: Eureka는 loopback이 아닌 IP(LAN IP)로 등록되고, gateway는 그 주소로 접속한다. 그래서 user-service가 보는 소스 IP는 `127.0.0.1`이 아니라 LAN IP다. (`127.0.0.1`은 user-service를 **직접** 호출할 때만 해당)
- 공유기/와이파이가 바뀌면 IP도 바뀌므로 **다시 띄울 때마다 확인**. 현재 IP는 `ifconfig | grep "inet "`, 실제 등록값은 `http://localhost:8761`에서 확인.

