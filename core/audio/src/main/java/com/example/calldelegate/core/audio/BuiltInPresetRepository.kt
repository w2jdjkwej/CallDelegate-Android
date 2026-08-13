package com.example.calldelegate.core.audio

import com.example.calldelegate.domain.api.PresetRepository
import com.example.calldelegate.domain.model.PresetSample
import com.example.calldelegate.domain.model.SceneType

class BuiltInPresetRepository : PresetRepository {
    private val values = listOf(
        PresetSample("delivery_arrived", "快递到达", "您好，我是顺丰快递员，快递到了，放在驿站可以吗？", SceneType.DELIVERY),
        PresetSample("takeout_blocked", "外卖无法进入", "您好，我是美团骑手，小区进不去，外卖放在北门保安处可以吗？", SceneType.DELIVERY),
        PresetSample("sales_call", "推销电话", "您好，我们有一款低息贷款，本周办理还有专属优惠。", SceneType.SPAM_RISK),
        PresetSample("work_notice", "工作事项通知", "我是研发部张工，通知他明天下午三点参加项目评审，事情比较紧急。", SceneType.WORK),
        PresetSample("unknown_person", "陌生人询问", "你好，请问是王先生本人吗？我有点事情想问。", SceneType.UNKNOWN_IDENTITY),
        PresetSample("long_silence", "长时间沉默", "", null, PresetSample.Kind.SILENCE),
        PresetSample("unrecognizable", "无法识别的语音", "__UNRECOGNIZABLE__", null, PresetSample.Kind.UNRECOGNIZABLE),
    )

    override fun samples(): List<PresetSample> = values
    override fun find(id: String): PresetSample? = values.firstOrNull { it.id == id }
}
