package com.graduation.generator.core;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 小时级流量曲线（§20.3）：24 小时权重 + 周末放大。
 * 权重归一化由调用方完成；不把系统当前时间直接当业务时间。
 */
public final class HourlyTraffic {

    /** 24 小时相对流量（0~23 点），晚高峰 + 午间小高峰 */
    public static final double[] HOUR_WEIGHTS = {
            //  0    1    2    3    4    5    6    7    8    9   10   11
            0.15, 0.10, 0.08, 0.06, 0.05, 0.05, 0.10, 0.25, 0.45, 0.60, 0.75, 0.85,
            // 12   13   14   15   16   17   18   19   20   21   22   23
            0.90, 0.80, 0.75, 0.70, 0.65, 0.80, 1.00, 0.95, 1.00, 0.90, 0.70, 0.40
    };

    private HourlyTraffic() {
    }

    /** 周末流量放大系数（周六周日 > 工作日） */
    public static double weekendBoost(LocalDate date, double boost) {
        DayOfWeek dow = date.getDayOfWeek();
        return (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) ? boost : 1.0;
    }
}
