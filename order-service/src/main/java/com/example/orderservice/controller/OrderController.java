package com.example.orderservice.controller;

import com.example.orderservice.dto.OrderDto;
import com.example.orderservice.jpa.OrderEntity;
import com.example.orderservice.messagequeue.KafkaProducer;
import com.example.orderservice.messagequeue.OrderProducer;
import com.example.orderservice.service.OrdersService;
import com.example.orderservice.vo.RequestOrder;
import com.example.orderservice.vo.ResponseOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/order-service")
@RequiredArgsConstructor
public class
OrderController {
    private final Environment env;
    private final OrdersService ordersService;
    private final KafkaProducer kafkaProducer;
    private final OrderProducer orderProducer;

    @GetMapping("/health-check")
    public String status() {
        return String.format("It's Working in Catalog Service on Port %s (SERVER PORT %s)", env.getProperty("local.server.port"), env.getProperty("server.port"));
    }

    @PostMapping("{userId}/orders")
    public ResponseEntity<ResponseOrder> createOrder(
            @PathVariable String userId,
            @RequestBody RequestOrder orderDetails) {
        log.info("Received request to create a new order for userId {}", userId);
        ModelMapper modelMapper = new ModelMapper();
        modelMapper.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);

        OrderDto orderDto = modelMapper.map(orderDetails, OrderDto.class);
        orderDto.setUserId(userId);

        /* jpa */
//        OrderDto createDto = ordersService.createOrder(orderDto);
//        ResponseOrder responseOrder = modelMapper.map(createDto, ResponseOrder.class);

        /* kafka */
        orderDto.setOrderId(UUID.randomUUID().toString());
        orderDto.setTotalPrice(orderDetails.getQty() * orderDetails.getUnitPrice());

        /* send this order to kafka */
        kafkaProducer.send("example-catalog-topic", orderDto);
        orderProducer.send("orders", orderDto);

        ResponseOrder responseOrder = modelMapper.map(orderDto, ResponseOrder.class);
        return ResponseEntity.status(HttpStatus.CREATED).body(responseOrder);
    }

    @GetMapping("/{userId}/orders")
    public ResponseEntity<List<ResponseOrder>> geOrder(@PathVariable String userId) {
        Iterable<OrderEntity> orderList = ordersService.getOrdersByUserId(userId);

        List<ResponseOrder> result = new ArrayList<>();
        for (OrderEntity v : orderList) {
            result.add(new ModelMapper().map(v, ResponseOrder.class));
        }

        return ResponseEntity.ok(result);
    }
}
