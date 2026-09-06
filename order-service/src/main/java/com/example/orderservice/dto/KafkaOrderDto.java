package com.example.orderservice.dto;

import jakarta.persistence.criteria.Order;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class KafkaOrderDto implements Serializable {
    private Schema schema;
    private Payload payload;
}
