## keystore:

```bash
keytool -genkeypair -alias apiEncryptionKey -keyalg RSA -dname "CN=Kenneth Lee, OU=API Development, O=joneconsulting.co.kr, L=Seoul, C=KR" -keypass "1q2w3e4r" -keystore apiEncryptionKey.jks -storepass "1q2w3e4r"
keytool -list -keystore apiEncryptionKey.jks -v   # 확인  
```

여기서 생성된 jks 파일은 config-server 의 설정파일에 들어갑니다.
이제 이 keystore 파일 하나로 public/private 대칭키를 생성한다.

<br>

## public pem key

```bash
keytool -exportcert -alias apiEncryptionKey -keystore apiEncryptionKey.jks -rfc -file public-key.pem
```

<br>

## private pem key

```bash
# 일단 pkcs12 로 파일 만들고 나서 그걸 다시 openssl 로 처리해야 최종 private key 가 생성된다.
keytool -importkeystore -srckeystore apiEncryptionKey.jks -srcalias apiEncryptionKey -destkeystore test-private.p12 -deststoretype PKCS12 
openssl pkcs12 -in test-private.p12 -nocerts -nodes -out private_key.pem
```

<br>

## 참고

실제로 이 프로젝트에서 사용하는 건  keystore 파일 하나다.
