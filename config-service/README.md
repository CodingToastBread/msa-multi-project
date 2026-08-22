# config-service (:8888) — Spring Cloud Config Server

> 모든 서비스의 설정을 한 곳에서 관리해서 나눠주는 **설정 중앙저장소**.
> 이 프로젝트에서 제일 중요한 역할은 **JWT `token.secret`을 user-service(발급자)와 gateway(검증자)에게 똑같이 내려주는 것**이다.

---

## 1. 이 모듈에는 코드가 거의 없다

```
config-service/
├── pom.xml
└── src/main/
    ├── java/com/example/configservice/
    │   └── ConfigServiceApplication.java   ← 딱 15줄. Controller 없음!
    └── resources/
        ├── bootstrap.yml     ← encrypt.key (암호화 열쇠)
        └── application.yaml  ← 포트, native 경로, RabbitMQ, actuator
```

```java
@SpringBootApplication
@EnableConfigServer          // ← 이 한 줄이 전부다
public class ConfigServiceApplication { ... }
```

`@EnableConfigServer` 어노테이션 하나가 **설정 배포 서버 + 암복호화 서버**를 통째로 만들어준다.
(`@EnableEurekaServer` 하나로 8761 대시보드가 뜨는 것과 완전히 같은 원리)

---

## 2. 설정을 어디서 읽어오나 — native 모드

`application.yaml`:

```yaml
spring:
  profiles:
    active: native   # 이거 안 하면 기본이 git 모드가 된다
  cloud:
    config:
      server:
        native:
          search-locations: file://${user.home}/study/msa-multi-project/01_reference/native-repo
```

- **native 모드** = git이 아니라 **로컬 폴더의 평범한 .yml 파일**을 읽는다.
- 원본 파일 위치: `01_reference/native-repo/` (`application.yml`, `ecommerce.yml`, `user-service.yml`)

### 파일명 규칙 (자주 헷갈리는 부분)

클라이언트 서비스가 받아가는 파일은 **2개**다.

| 순서 | 파일 | 설명 |
|---|---|---|
| 1 | `application.yml` | 모두가 공통으로 받는 설정 (우선순위 낮음) |
| 2 | `{name}.yml` | 그 서비스 전용 설정 (우선순위 **높음** → 덮어씀) |

여기서 `{name}`은 클라이언트의 `bootstrap.yml`에 적힌 `spring.cloud.config.name` 값이다.

```yaml
# user-service/src/main/resources/bootstrap.yml
# gateway/src/main/resources/bootstrap.yaml   ← 둘 다 똑같아야 한다!
spring:
  cloud:
    config:
      uri: http://127.0.0.1:8888
      name: ecommerce        # → ecommerce.yml 을 받아감
```

> ⚠️ **user-service와 gateway의 `name`이 서로 다르면 서로 다른 secret을 받게 되고, JWT 검증이 조용히 전부 실패한다(401 Invalid JWT token).**
> 그래서 `native-repo/*.yml`의 secret 값을 일부러 `..._application` / `..._ecommerce` / `..._user_service` 로 다르게 심어놨다. → 어느 파일을 읽었는지 눈으로 바로 확인하려고.

### 브라우저로 직접 확인하기

```
http://localhost:8888/ecommerce/default       ← ecommerce.yml + application.yml 합쳐서 JSON으로 보여줌
http://localhost:8888/user-service/default
```

---

## 3. ❓ 내가 만들지도 않은 `/encrypt`가 왜 동작하지?

**Config Server가 공짜로 끼워주는 기능이기 때문이다.**

`@EnableConfigServer` → `ConfigServerAutoConfiguration` → `EncryptionAutoConfiguration`
→ 스프링이 **`EncryptionController`** 라는 컨트롤러 빈을 자동으로 등록한다.

내가 짠 코드가 아니라 **spring-cloud-config-server 라이브러리 안에 들어있는 컨트롤러**라서,
프로젝트 소스를 아무리 grep 해도 `@RequestMapping("/encrypt")`는 안 나온다.

### 자동으로 생기는 엔드포인트

| 엔드포인트 | 메서드 | 용도 |
|---|---|---|
| `/encrypt` | POST | 평문 → 암호문 |
| `/decrypt` | POST | 암호문 → 평문 |
| `/encrypt/status` | GET | 암호화 사용 가능 여부 확인 |
| `/key` | GET | 공개키 조회 (비대칭 방식일 때만 의미 있음) |

---

## 4. `encrypt.key` — 암호화 기능의 ON/OFF 스위치

`bootstrap.yml`:

```yaml
encrypt:
  key: abcdefghik12345     # 대칭키(AES). 이 값이 곧 비밀번호.
```

- 이 값이 **있으면** → 대칭키(AES) 암호화 활성화. `/encrypt` 정상 동작.
- 이 값을 **지우면** → 아래처럼 응답이 바뀐다. (직접 지워보고 확인해볼 것)

```
GET  /encrypt/status  → {"description":"No key was installed for encryption service","status":"NO_KEY"}
POST /encrypt         → 401 NO_KEY
```

### 왜 `application.yaml`이 아니라 `bootstrap.yml`에 넣나?

암호화 기능은 **일반 애플리케이션 컨텍스트보다 먼저 뜨는 bootstrap 단계**에서 초기화돼야 한다.
그래야 Config Server가 파일에서 읽은 `{cipher}...` 값을 **클라이언트에게 내려주기 전에 복호화**할 수 있다.
`application.yaml`에 넣으면 이미 늦어서 암호화가 안 걸린다.

> 그래서 `pom.xml`에 `spring-cloud-starter-bootstrap` 의존성이 들어있다.
> (Spring Cloud 2020부터 bootstrap.yml은 기본 비활성 → 이 스타터를 넣어야 다시 읽힌다)

---

## 5. 실제 사용법

### ① 암호화하기

```bash
curl -X POST localhost:8888/encrypt \
     -H "Content-Type: text/plain" \
     -d "user_token_native_ecommerce"
# → 74a5b0c1... (긴 hex 문자열)
```

> `Content-Type: text/plain` 중요. 안 붙이면 415나 이상한 값이 나온다.
> `-d` 뒤 문자열에 특수문자가 있으면 반드시 따옴표로 감쌀 것.

### ② 복호화로 검증

```bash
curl -X POST localhost:8888/decrypt \
     -H "Content-Type: text/plain" \
     -d "74a5b0c1..."
# → user_token_native_ecommerce
```

### ③ 설정 파일에 심기

`01_reference/native-repo/ecommerce.yml`:

```yaml
token:
  expiration-time: 864000000
  secret: '{cipher}74a5b0c1...'      # ← 작은따옴표 필수!
```

- `{cipher}` 접두어를 보면 Config Server가 **내려주기 직전에 자동으로 복호화**한다.
- 클라이언트(user-service, gateway)는 암호화된 줄도 모르고 그냥 **평문 secret**을 받는다. 클라이언트 쪽은 코드/설정 수정 **불필요**.
- ⚠️ YAML에서 `{`로 시작하면 flow mapping(객체)으로 파싱되어 에러난다. **반드시 작은따옴표**로 감쌀 것.

### ④ 잘 됐는지 확인

```
http://localhost:8888/ecommerce/default
```
→ JSON에 평문 secret이 보이면 성공. `{cipher}...` 그대로 보이면 복호화 실패 (키 없음/키 불일치).

---

## 6. 설정 바꿨을 때 실시간 반영 (Spring Cloud Bus)

`native-repo/*.yml`을 수정해도, 이미 떠있는 서비스들은 **부팅 때 받아간 옛날 값**을 들고 있다.
전부 재시작하지 않고 갱신하려면:

```bash
curl -X POST localhost:8888/actuator/busrefresh
```

RabbitMQ를 타고 **버스에 연결된 모든 서비스가 동시에** 설정을 다시 받아간다.
(그래서 pom에 `spring-cloud-starter-bus-amqp`, application.yaml에 rabbitmq 접속정보와 `busrefresh` 노출이 있다)

> RabbitMQ는 **4.2.7**이어야 한다. 4.3.x는 호환성 에러.
> `podman run -d -p 5672:5672 -p 15672:15672 --name rabbitmq rabbitmq:4.2.7-management`

---

## 7. 자주 깨지는 지점 체크리스트

| 증상 | 원인 | 확인할 곳 |
|---|---|---|
| 설정이 텅 비어서 내려옴 | native 모드는 폴더가 없어도 **에러 없이 빈 값**을 준다 | `search-locations` 절대경로 — 리포를 다른 위치로 옮기면 깨진다 |
| 401 Invalid JWT token | user-service와 gateway가 **다른 파일**을 읽음 | 양쪽 bootstrap의 `name:` 값이 같은지 (`ecommerce`) |
| `{cipher}...`가 평문으로 안 풀림 | `encrypt.key` 없음 / 다른 키로 암호화함 | `bootstrap.yml`의 `encrypt.key`, `/encrypt/status` |
| 설정 파일 고쳤는데 반영 안 됨 | 클라이언트는 부팅 시 1회만 받아감 | `POST /actuator/busrefresh` |
| 서비스가 빈 설정으로 부팅됨 | config-service보다 먼저 떴음 | 기동 순서: RabbitMQ → discovery → **config** → gateway → 나머지 |

---

## 8. 실행

```bash
./mvnw -pl config-service spring-boot:run
```

기동 순서상 **service-discovery 다음, gateway/비즈니스 서비스보다 먼저** 띄워야 한다.
