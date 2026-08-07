package com.kaisheng.pddhunter.model

/**
 * 优惠券数据模型
 */
data class CouponInfo(
    val id: String = "",
    val title: String = "",
    val amount: Double = 0.0,        // 券面额
    val minConsume: Double = 0.0,    // 最低消费
    val type: CouponType = CouponType.UNKNOWN,
    val status: CouponStatus = CouponStatus.AVAILABLE,
    val source: String = "",         // 来源: 领券中心/店铺/平台
    val expireTime: Long = 0L,       // 过期时间戳
    val url: String = "",            // 跳转链接
    val extra: String = ""           // 额外信息
)

enum class CouponType(val label: String) {
    PLATFORM("平台券"),
    SHOP("店铺券"),
    PRODUCT("商品券"),
    RED_PACKET("红包"),
    SIGN("签到券"),
    TASK("任务券"),
    UNKNOWN("未知")
}

enum class CouponStatus(val label: String) {
    AVAILABLE("可领取"),
    CLAIMING("领取中"),
    CLAIMED("已领取"),
    EXPIRED("已过期"),
    FAILED("领取失败")
}

/**
 * 券检测结果
 */
data class DetectionResult(
    val success: Boolean = false,
    val coupons: List<CouponInfo> = emptyList(),
    val message: String = ""
)

/**
 * 领取结果
 */
data class ClaimResult(
    val success: Boolean = false,
    val couponId: String = "",
    val message: String = ""
)