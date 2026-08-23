package com.example.userservice.error;

import feign.Response;
import feign.codec.ErrorDecoder;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class FeignErrorDecoder implements ErrorDecoder {
    private final Environment env;

    @Override
    public Exception decode(String methodKey, Response response) {
        // 참고로 이 메소드가 호출된다는 시점에서 Http Response Status 는 2xx 를 벗어난 것이다.
        switch (response.status()) {
            case 400:
                break;
            case 404:
                if (methodKey.contains("getOrders")) {
                    return new ResponseStatusException(HttpStatus.valueOf(response.status()),
                            env.getProperty("order-service.exception.order-is-empty"));
                }
                break;
            default:
                return new Exception(response.reason());
        }
        return null;
    }
}
