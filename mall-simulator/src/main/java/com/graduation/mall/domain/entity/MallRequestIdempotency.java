package com.graduation.mall.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** Transactional result ledger for replay-safe first-party mall writes. */
@Data
@TableName("mall_request_idempotency")
public class MallRequestIdempotency {

    @TableId(type = IdType.INPUT)
    private String idempotencyKey;

    private String operation;

    private String requestHash;

    private String resultValue;
}
