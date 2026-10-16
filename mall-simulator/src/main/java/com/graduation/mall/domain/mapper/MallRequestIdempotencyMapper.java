package com.graduation.mall.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.graduation.mall.domain.entity.MallRequestIdempotency;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface MallRequestIdempotencyMapper extends BaseMapper<MallRequestIdempotency> {

    @Select("SELECT * FROM mall_request_idempotency WHERE idempotency_key = #{key} FOR UPDATE")
    MallRequestIdempotency selectByKeyForUpdate(@Param("key") String key);
}
