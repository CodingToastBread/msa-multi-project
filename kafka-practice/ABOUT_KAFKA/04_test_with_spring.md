

git commit id : 6a262ad08ce40da7619db6ae091c2220429df978
에 필요한 내용

```bash
curl -X POST localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d '{
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
  }'
```

이후 체크

```bash
curl -X GET http://127.0.0.1:8083/connectors/my-order-sink-connect
```

두개의 order-service 가 따로따로 동작해도, 하다의 DB 에 데이터를 넣기위해서
order-service 에서 kafka 에 orders 토픽을 전달하고, 이게 지금 만든 kafka sink 에 의해서 DB 에 저장된다.