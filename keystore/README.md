# keystore — Config Server 설정값 암호화용 키

Config Server가 `{cipher}...` 값을 복호화할 때 쓰는 **열쇠 파일**이 여기 있다.
실제로 이 프로젝트가 쓰는 건 **`apiEncryptionKey.jks` 파일 하나**다. (pem 추출은 참고용)

---

## 1. 키 만들기

```bash
keytool -genkeypair -alias apiEncryptionKey -keyalg RSA -dname "CN=Kenneth Lee, OU=API Development, O=joneconsulting.co.kr, L=Seoul, C=KR" -keypass "1q2w3e4r" -keystore apiEncryptionKey.jks -storepass "1q2w3e4r"
keytool -list -keystore apiEncryptionKey.jks -v   # 확인  
```

> 📌 **용어 정리**: `-genkeypair -keyalg RSA` = key**pair**(공개키/개인키) 생성 → **비대칭키** 방식이다.
> (커밋 메시지에 "대칭키"라고 적었는데, 대칭키는 아래 ②의 `encrypt.key` 쪽이다)

### public pem key

```bash
keytool -exportcert -alias apiEncryptionKey -keystore apiEncryptionKey.jks -rfc -file public-key.pem
```

### private pem key

```bash
# 일단 pkcs12 로 파일 만들고 나서 그걸 다시 openssl 로 처리해야 최종 private key 가 생성된다.
keytool -importkeystore -srckeystore apiEncryptionKey.jks -srcalias apiEncryptionKey -destkeystore test-private.p12 -deststoretype PKCS12 
openssl pkcs12 -in test-private.p12 -nocerts -nodes -out private_key.pem
```

---

## 2. 이 파일이 꽂히는 곳 — `config-service/src/main/resources/bootstrap.yml`

```yaml
encrypt:
#  key: abcdefghik12345          # ② 대칭키(AES) 방식 — 지금은 주석 처리
  key-store:                     # ① 키스토어(RSA) 방식 — 현재 사용중
    location: file:///${user.home}/study/msa-multi-project/keystore/apiEncryptionKey.jks
    password: 1q2w3e4r
    alias: apiEncryptionKey
```

| | ① key-store (현재) | ② encrypt.key |
|---|---|---|
| 방식 | **비대칭** (RSA 공개키/개인키) | **대칭** (AES, 문자열 하나가 곧 비밀번호) |
| 설정 | `.jks` 파일 + password + alias | yml에 한 줄 |

> **왜 `application.yaml`이 아니라 `bootstrap.yml`인가**
> 암호화 기능은 일반 컨텍스트보다 **먼저 뜨는 bootstrap 단계**에서 초기화돼야,
> Config Server가 `{cipher}...`를 **클라이언트에게 내려주기 전에** 복호화할 수 있다.
> → 그래서 `pom.xml`에 `spring-cloud-starter-bootstrap` 의존성이 들어있다.
> (Spring Cloud 2020부터 bootstrap.yml은 기본 비활성)

> ⚠️ **절대경로 의존**: `~/study/msa-multi-project/keystore/` 에 있어야 한다.
> 경로가 틀려도 **에러 없이 복호화만 조용히 실패**해서, 증상이 "JWT 401" 또는 "설정값 null"로만 나타난다.
> (`config-service`의 `search-locations`와 함께 **경로 의존 2곳** 중 하나)

---

## 3. 쓰는 법

```bash
# 암호화
curl -X POST localhost:8888/encrypt -H "Content-Type: text/plain" -d "평문값"
# → AYCGhtnYsl...

# 복호화(검증)
curl -X POST localhost:8888/decrypt -H "Content-Type: text/plain" -d "AYCGhtnYsl..."
```

```yaml
# native-repo/*.yml 에 심기
token:
  secret: '{cipher}AYCGhtnYsl...'     # ← 작은따옴표 필수
```

- `Content-Type: text/plain` 빼면 415가 나거나 이상한 값이 나온다.
- YAML은 `{`로 시작하면 flow mapping(객체)으로 파싱 → **반드시 작은따옴표.**
- 클라이언트(user-service, gateway)는 **평문**을 받는다 → 클라이언트 쪽 수정 불필요.

### 현재 암호화되어 있는 값

| 파일 | 항목 |
|---|---|
| `01_reference/native-repo/application.yml` | `token.secret` (JWT 서명키) |
| `01_reference/native-repo/user-service.yml` | `spring.datasource.password` (H2) |

---

## 4. 확인 / 트러블슈팅

| 확인 | 정상 | 비정상일 때 |
|---|---|---|
| `GET :8888/encrypt/status` | `{"status":"OK"}` | `NO_KEY` → 키 설정 자체가 안 읽힘 |
| `GET :8888/ecommerce/default` | secret이 **평문**으로 보임 | `{cipher}...` 그대로 → 경로/비번/alias 불일치 |

> 🔐 학습용이라 `.jks`와 비밀번호가 리포에 그대로 커밋되어 있다. **실무에서는 절대 금지.**
