package com.graduation.mall.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.graduation.mall.domain.entity.MallOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface MallOrderMapper extends BaseMapper<MallOrder> {

    @Select("SELECT * FROM mall_order WHERE idempotency_key = #{key} LIMIT 1 FOR UPDATE")
    MallOrder selectIdempotentOrderForUpdate(@Param("key") String key);
}
