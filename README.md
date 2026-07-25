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

> ⚠️ **Eureka 등록명이 곧 라우팅 대상**이다. `spring.application.name`과 게이트웨이의 `lb://` 타깃이 정확히 일치해야 한다. (first/second는 `MY-FIRST-SERVICE`/`MY-SECOND-SERVICE`로 등록됨에 주의)

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

### ③ JWT 발급/검증은 **같은 name = 같은 secret** 이어야 동작한다 ★ 가장 중요

user-service(발급)와 gateway(검증)가 **같은 `token.secret`**을 받아야 서명이 맞는다. 그러려면 두 서비스의 `bootstrap` name이 **같아야** 한다.

```
✅ 올바른 상태 (현재):
   user-service/bootstrap.yml   name: ecommerce ─► ...ecommerce.yml → secret = ...ECOMMERCE ┐
   gateway/bootstrap.yaml       name: ecommerce ─► ...ecommerce.yml → secret = ...ECOMMERCE ┘
                                                                        └─ 일치 → JWT 통과 ✅

❌ 잘못된 상태 (과거 버그):
   user-service  name: config-service ─► (config-service.yml 없음) → application.yml 만 → ...APPLICATION
   gateway       name: ecommerce      ─► ecommerce.yml            → ...ECOMMERCE
                                                                      └─ 불일치 → 모든 토큰 401 ❌
```

> **user-service와 gateway의 bootstrap name은 반드시 동일하게 유지할 것.** 한쪽만 바꾸면 JWT 검증이 조용히 전부 실패한다. (에러 메시지가 "Invalid JWT token" 401 뿐이라 원인 파악이 어렵다)

**검증 방법** — 서비스를 띄운 뒤:
```
http://localhost:8888/ecommerce/default    → token.secret 이 user_token_native_ecommerce 로 나오면 정상
```
로그인 → 받은 토큰으로 `GET :8000/user-service/users` 호출 시 401이 안 뜨면 발급/검증이 맞물린 것.

---

## 부록 — 아직 구현되지 않은 부분 (다음 학습 후보)

- `UserServiceImpl.getUserByUserId`는 주문 목록을 **빈 배열**로 반환한다. user→order 서비스 간 호출(FeignClient/WebClient)이 아직 미구현. (`ResponseOrder` 클래스만 존재)
- 회복탄력성(Resilience4j/Circuit Breaker), 분산추적(Zipkin), 메시지 기반 비동기(Kafka) 등은 미도입.
