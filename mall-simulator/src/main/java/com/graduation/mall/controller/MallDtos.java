package com.graduation.mall.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商城 REST DTO（首版无登录，userId 由调用方显式传入；商城只使用模拟属性）。
 */
public final class MallDtos {

    private MallDtos() {
    }

    public record CreateUserReq(
            @NotBlank(message = "ageGroup 必填") String ageGroup,
            @NotBlank(message = "cityLevel 必填") String cityLevel,
            @NotBlank(message = "memberLevel 必填") String memberLevel) {
    }

    public record CartAddReq(
            @NotNull(message = "userId 必填") Long userId,
            @NotNull(message = "productId 必填") Long productId,
            @Positive(message = "quantity 必须为正") Integer quantity) {
    }

    public record OrderCreateReq(
            @NotNull(message = "userId 必填") Long userId,
            @NotEmpty(message = "items 不能为空") List<OrderItemReq> items) {
    }

    public record OrderItemReq(
            @NotNull(message = "productId 必填") Long productId,
            @Positive(message = "quantity 必须为正") Integer quantity) {
    }

    public record OrderPayReq(
            @NotNull(message = "userId 必填") Long userId) {
    }

    public record OrderCancelReq(
            @NotNull(message = "userId 必填") Long userId,
            @NotBlank(message = "reason 必填") String reason) {
    }

    public record RefundApplyReq(
            @NotNull(message = "userId 必填") Long userId,
            @NotNull(message = "amount 必填") BigDecimal amount,
            @NotBlank(message = "reason 必填") String reason) {
    }

    public record RefundCompleteReq(
            @NotNull(message = "userId 必填") Long userId) {
    }
}