httpie 에서는 다음과 같은 순서로 테스트하는게 일반적이다.

## 1.user-service-create-user 로 생성자를 만든다.

POST http://127.0.0.1:8000/user-service/users
```json
{
  "email": "codingToast@gmail.com",
  "name": "Toast Bread",
  "pwd": "12345"
}
```
> 참고로 :8000 포트는 gateway 이다.
gateway 가 eureka(service-discovery)를 통해서 서비스를 찾아낸다.

## 2. 로그인 및 JWT 토큰 세팅

POST http://127.0.0.1:8000/user-service/login
```json
{
  "email": "codingToast@gmail.com",
  "password": "12345"
}
```

이러면 http responose 의 헤더 토큰이 있다.

```text
Token: eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiJhODRkYmE5Yy04MDliLTQwNDMtOGJhZS04Yzk2MjdhZGYxMGMiLCJleHAiOjE3ODk1MzQyODcsImlhdCI6MTc4ODY3MDI4N30.CuiDa4RIQ_DoB_GjaJEkjjRYm4zeHlgzorD0kffoLfX65w9HlqbtAaplcUlbEcZa
```

이거를 Authorization Bearer 값으로 사용하면 된다.



## 3. 사용자 정보

JWT 토큰 세팅한 상태에서 아래 요청 날린다.

GET http://127.0.0.1:8000/user-service/users

그러면 아래와 같은 결과가 나오는데, 여기서 userId 값이 DB 상의 사용자 아이디다.

```json
[
  {
    "email": "codingToast@gmail.com",
    "name": "Toast Bread",
    "userId": "a84dba9c-809b-4043-8bae-8c9627adf10c"
  }
]
```

<br>

이 userId 값을 아래와 같이 요청 path 에 넣는다.

```
GET 127.0.0.1:8000/user-service/users/{userId}
```

이러면 사용자의 주문 목록을 볼수 있다.

<br>

## 4. 신규 주문 넣기

POST http://127.0.0.1:8000/order-service/{userId}/orders
```json
{
  "productId": "CATALOG-001",
  "qty": 10,
  "unitPrice": 1500
}
```
>참고로 카탈로그는 현재는 CATALOG-001, 002, 003 만 있다.<br>
GET http://127.0.0.1:8000/catalog-service/catalogs 로 조회가능하다.

---

이거는 조금 별개인데, kafka-conn/*
이 있는데, 이거는 order-service 2개를 띄울 때 각각의 h2 db 를 쓰던걸<br>
하나의 maria db 로 통일하고, 대신 kafka 에 orders 토픽으로 전달하고, kafka sink connector 로
해당 토픽을 maria db 에 저장하는 방식으로 바꿀 때 사용된 것들이다.

@kafka-practice/docker-compose.yml 에서 실행된 커넥터가 아래와 같은 요청을 받는다.
POST http://127.0.0.1:8083/connectors
```json
{
  "name": "my-order-sink-connect",
  "config": {
    "connector.class": "io.confluent.connect.jdbc.JdbcSinkConnector",
    "connection.url": "jdbc:mariadb://mariadb:3306/mydb",
    "connection.user": "root",
    "connection.password": "test1357",
    "auto.create": "true",
    "auto.evolve": "true",
    "delete.enabled": "false",
    "tasks.max": "1",
    "topics": "orders"
  }
}
```

