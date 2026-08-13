package com.example.calldelegate.core.ai.rules

/**
 * Independent ride-hailing gate and intent precedence.
 *
 * This policy deliberately uses small evidence groups instead of full-sentence patterns. A
 * shared word such as "订单" or "门" is never sufficient to enter the ride scene.
 */
internal object RideHailingIntentPolicy {
    data class Decision(
        val intentId: String,
        val evidence: List<String>,
    )

    fun decide(text: String): Decision? {
        val normalized = text.replace(WHITESPACE, "")
        if (normalized.isBlank() || hasForeignSceneMarker(normalized) || !hasRideGate(normalized)) {
            return null
        }

        val evidence = mutableListOf("ride_gate:independent_evidence")
        val decision = when {
            isTripException(normalized) -> Decision("trip_exception", evidence + "ride_priority:trip_exception")
            isCannotFindPassenger(normalized) -> Decision("cannot_find_passenger", evidence + "ride_priority:cannot_find_passenger")
            isAskPassengerLocation(normalized) -> Decision("ask_passenger_location", evidence + "ride_priority:ask_passenger_location")
            isConfirmPickupLocation(normalized) -> Decision("confirm_pickup_location", evidence + "ride_priority:confirm_pickup_location")
            isUrgePassenger(normalized) -> Decision("urge_passenger", evidence + "ride_priority:urge_passenger")
            isDriverDelay(normalized) -> Decision("driver_delay", evidence + "ride_priority:driver_delay")
            isDriverArrived(normalized) -> Decision("driver_arrived", evidence + "ride_priority:driver_arrived")
            isTripInfoNotice(normalized) -> Decision("trip_info_notice", evidence + "ride_priority:trip_info_notice")
            isConfirmOrder(normalized) -> Decision("confirm_order_info", evidence + "ride_priority:confirm_order_info")
            else -> null
        }
        return decision?.copy(evidence = decision.evidence.distinct())
    }

    fun shouldRejectWeakRideScene(text: String): Boolean {
        val normalized = text.replace(WHITESPACE, "")
        if (normalized.isBlank()) return false
        if (hasForeignSceneMarker(normalized)) return true
        return normalized == "订单已经确认" || normalized == "订单已确认"
    }

    private fun hasRideGate(text: String): Boolean {
        val identity = hasAny(text, "司机", "师傅", "网约车", "打车", "乘客", "接您", "接你", "接到")
        val trip = hasAny(text, "订单", "上车点", "上车定位", "上车地址", "目的地", "途经点", "行程", "匝道", "手机号码尾号", "手机号尾号")
        val pickup = hasAny(text, "上车点", "上车定位", "上客区", "候车", "等车", "接您", "接你", "出发层", "到达层")
        val vehicle = hasAny(text, "车牌", "车辆", "轿车", "汽车", "停车", "停在")
        val action = hasAny(text, "到达", "到了", "已到", "前往", "准备", "确认", "告诉", "请您", "请你", "等待")
        val orderWithRideContext = text.contains("订单") && hasAny(
            text,
            "司机",
            "乘客",
            "车牌",
            "目的地",
            "上车",
            "行程",
            "平台",
            "前往",
        )
        return (identity && (trip || pickup || vehicle)) ||
            (pickup && (vehicle || action || hasAny(text, "停车场", "地下车库", "临时"))) ||
            orderWithRideContext ||
            (hasAny(text, "目的地", "途经点") && hasAny(text, "确认", "路线", "高速", "平台"))
    }

    private fun isTripException(text: String): Boolean {
        val cancellationOrFault = hasAny(text, "取消订单", "快要取消", "即将取消", "取消了", "车辆故障", "无法继续", "重新派车", "无法前往")
        val blocked = hasAny(text, "封闭", "封路", "施工", "交通管制", "进不去", "无法进入", "没有遮挡")
        val changedPlan = hasAny(text, "修改了上车点", "修改上车点", "调整上车点", "更换上车点", "改到", "改走", "掉头", "原来的上车点")
        val hasActionOrImpact = hasAny(text, "新的位置", "东门", "后门", "绕行", "路线", "前往", "晚", "预计", "停车场", "车库", "接您", "派车")
        return cancellationOrFault || blocked && hasActionOrImpact || changedPlan && hasActionOrImpact
    }

    private fun isCannotFindPassenger(text: String): Boolean {
        val cannotSeePassenger = hasAny(
            text,
            "没看到您",
            "没看到你",
            "没有看到您",
            "没有看到你",
            "看不到您",
            "看不到你",
            "找不到乘客",
            "没有看到乘客",
        )
        val pickupContext = hasAny(text, "已经到", "到了", "上车点", "到附近", "等了", "等您")
        return cannotSeePassenger && pickupContext
    }

    private fun isAskPassengerLocation(text: String): Boolean {
        if (hasAny(text, "请告诉我您", "请告诉我你", "您现在在哪", "你现在在哪", "您在哪", "你在哪")) {
            return true
        }
        return hasAny(text, "哪个匝道", "哪个出口", "哪个门") &&
            hasAny(text, "告诉", "靠近", "位置", "您", "你") &&
            !text.contains("请确认")
    }

    private fun isConfirmPickupLocation(text: String): Boolean {
        val explicitConfirmation = hasAny(text, "确认上车点", "核对上车点", "请确认", "确认是否", "上车定位偏", "定位不一致")
        val knownCandidate = hasAny(text, "上车点", "上车定位", "临时上客区", "候车", "出发层", "到达层", "哪个匝道", "另一个出口")
        val locationCorrection = hasAny(text, "不在", "而是在", "对面", "附近", "绕行", "改到", "换到")
        return (explicitConfirmation && knownCandidate) ||
            (knownCandidate && locationCorrection && hasAny(text, "车辆", "车牌", "位置", "接您", "南门", "出口", "入口"))
    }

    private fun isUrgePassenger(text: String): Boolean {
        val pressure = hasAny(text, "尽快", "请准备好", "准备好后", "不能长时间停车", "还要多久", "多久下来", "请下来", "请出来")
        val request = hasAny(text, "上车", "下来", "出来", "到上车点", "候车")
        return pressure && request
    }

    private fun isDriverDelay(text: String): Boolean {
        val etaOrDelay = hasAny(text, "还没到", "分钟到", "预计", "堵车", "绕路", "红绿灯", "交通拥堵", "晚")
        val arrived = hasAny(text, "已经到了", "已经到达", "已到达", "已经到")
        return etaOrDelay && !arrived
    }

    private fun isDriverArrived(text: String): Boolean {
        val arrival = hasAny(text, "已经到达", "已经到了", "已到达", "我在上车点", "停在上车点", "停在", "看到您了", "向路边靠近", "进入地下停车场")
        val vehicleAtPickup = hasAny(text, "车辆", "轿车", "车牌", "上客区", "电梯口", "停车场") &&
            hasAny(text, "停", "到达", "靠近", "旁边", "附近")
        return arrival || vehicleAtPickup
    }

    /**
     * The driver telling the passenger what the trip is, as opposed to asking them to confirm it.
     *
     * [isConfirmOrder] accepts 订单 beside 司机 or 接到 as though mentioning an order in a driver's
     * company were a request to verify it, and its reply is a refusal to disclose passenger or
     * order details. So 我是代驾司机，已经接到您的订单，正在前往您停车的位置 -- a driver saying
     * where he is going -- was answered 为保护行程隐私，我不能确认乘客或订单信息. Eleven of the
     * thirteen turns that reached that reply on the fourth blind set had asked for nothing at all.
     *
     * Ordering matters: this sits above [isConfirmOrder] so a statement is read as a statement,
     * and it stands down the moment the turn contains an act of confirmation, which leaves
     * 我是顺风车车主，想确认您订单上的出发时间有没有变化 where it belongs.
     */
    private fun isTripInfoNotice(text: String): Boolean {
        val tripDetail = hasAny(
            text,
            "拼车订单", "顺风车", "代驾", "商务车", "专车", "快车",
            "跨城订单", "多个下车点", "同行乘客", "公司账户", "统一结算",
            "无障碍", "婴儿车", "车牌信息", "车辆信息", "通行费",
            "机场接送", "送机", "接机", "航站楼", "出发大厅",
            "网约车专区", "网约车专用", "接客点", "候客区", "禁停",
        )
        return tripDetail && !isConfirmationAct(text)
    }

    /** Wording that turns a sentence about the trip into a request about it. */
    private fun isConfirmationAct(text: String): Boolean = hasAny(
        text,
        "确认", "核对", "请问", "是吗", "对吗", "有没有", "是否", "几位", "麻烦告诉",
    )

    private fun isConfirmOrder(text: String): Boolean {
        val order = hasAny(text, "订单", "目的地", "途经点", "几位乘客", "大件行李", "路线", "高速", "普通道路", "手机号码尾号", "手机号尾号")
        val confirmation = hasAny(text, "确认", "核对", "正确", "增加", "修改", "希望", "哪一条", "哪条")
        val orderIdentity = hasAny(text, "司机", "乘客", "车牌", "接到", "接的是", "订单显示", "前往")
        return order && (confirmation || orderIdentity)
    }

    private fun hasForeignSceneMarker(text: String): Boolean = hasAny(
        text,
        "快递",
        "外卖",
        "骑手",
        "酒店预订",
        "保险",
        "保险续保",
        "房产",
        "会议室",
        "客服售后",
    )

    private fun hasAny(text: String, vararg terms: String): Boolean = terms.any(text::contains)

    private val WHITESPACE = Regex("\\s+")
}
