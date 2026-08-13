package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.domain.api.EntityExtractor
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.SlotExtractionRequest
import com.example.calldelegate.domain.model.SlotExtractionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RegexEntityExtractor : EntityExtractor {
    override suspend fun extract(text: String, expectedSlots: Set<String>): Map<String, String> =
        extract(SlotExtractionRequest(text = text, expectedSlots = expectedSlots)).slots

    override suspend fun extract(request: SlotExtractionRequest): SlotExtractionResult = withContext(Dispatchers.Default) {
        extractSynchronously(request)
    }

    private fun extractSynchronously(request: SlotExtractionRequest): SlotExtractionResult {
        val text = request.text.trim()
        if (text.isBlank()) return SlotExtractionResult(emptyMap())
        val slots = linkedMapOf<String, String>()
        val rejected = mutableListOf<String>()
        fun wants(slot: String): Boolean = request.expectedSlots.isEmpty() || slot in request.expectedSlots

        if (wants("contact")) phoneRegex.find(text)?.value?.let { slots["contact"] = it.replace(" ", "") }
        if (wants("time") || wants("viewingTime") || wants("expiryTime")) {
            selectCorrectedMatch(text, timeRegex, rejected, "time")?.let { time ->
                if (wants("time")) slots["time"] = time
                if (request.scene == SceneType.REAL_ESTATE && wants("viewingTime")) slots["viewingTime"] = time
                if (request.scene == SceneType.INSURANCE_FINANCE && wants("expiryTime")) slots["expiryTime"] = time
            }
        }

        fun recordIdentity(rawValue: String) {
            val identity = rawValue.trim().takeIf(::isPlausibleIdentity) ?: return
            if (wants("callerIdentity")) slots.putIfAbsent("callerIdentity", identity)
            if (request.scene == SceneType.RIDE_HAILING && wants("driverName")) {
                identity.removePrefix("司机").removePrefix("师傅").trim()
                    .takeIf(::isPlausibleIdentity)
                    ?.let { slots.putIfAbsent("driverName", it) }
            }
        }

        departmentIdentityRegex.find(text)?.let { match ->
            if (wants("organization")) {
                match.groupValues.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)?.let { slots["organization"] = it }
            }
            if (wants("callerIdentity") || wants("driverName")) {
                match.groupValues.getOrNull(2)?.let(::recordIdentity)
            }
        }
        if (wants("organization")) {
            organizationRegex.find(text)?.groupValues?.getOrNull(1)?.trim()?.let { slots.putIfAbsent("organization", it) }
        }
        if (wants("callerIdentity") || wants("driverName")) {
            identityRegex.find(text)?.groupValues?.getOrNull(1)?.let(::recordIdentity)
        }

        if (wants("location")) {
            val location = extractLocation(text, rejected, request.scene)
            if (location != null) {
                slots["location"] = location
                if (request.scene == SceneType.RIDE_HAILING) slots["pickupLocation"] = location
            }
        }

        if (request.scene == SceneType.RIDE_HAILING && "location" !in slots) {
            selectCapturedValue(text, rideArrivalLocationRegex, rejected, "pickupLocation")
                ?.takeIf(::isPlausibleLocation)
                ?.let {
                    slots["location"] = it
                    slots["pickupLocation"] = it
                }
        }

        if (wants("urgent")) {
            when (detectUrgent(text, allowShortAnswer = "urgent" in request.expectedSlots)) {
                false -> slots["urgent"] = "false"
                true -> slots["urgent"] = "true"
                null -> Unit
            }
        }
        if (wants("callbackNeeded")) {
            when (detectCallbackNeeded(text, allowShortAnswer = "callbackNeeded" in request.expectedSlots)) {
                false -> slots["callbackNeeded"] = "false"
                true -> slots["callbackNeeded"] = "true"
                null -> Unit
            }
        }

        if (wants("organization")) knownOrganizations.firstOrNull { it in text }?.let { slots.putIfAbsent("organization", it) }
        if (wants("platform")) knownPlatforms.firstOrNull { it in text }?.let { slots["platform"] = it }
        if (wants("pickupCode")) pickupCodeRegex.find(text)?.groupValues?.getOrNull(1)?.let { slots["pickupCode"] = it.uppercase() }
        if (wants("licensePlate")) {
            (
                licensePlateRegex.find(text)?.value
                    ?: licensePlateTailRegex.find(text)?.groupValues?.getOrNull(1)?.let(::normalizeSpokenDigits)
            )?.let { slots["licensePlate"] = it.uppercase() }
        }
        if (wants("phoneTail")) {
            phoneTailRegex.find(text)?.groupValues?.getOrNull(1)?.let(::normalizeSpokenDigits)?.let {
                slots["phoneTail"] = it
            }
        }
        if (wants("vehicleColor")) vehicleColors.firstOrNull { it in text }?.let { slots["vehicleColor"] = it }
        if (wants("vehicleModel")) vehicleModels.firstOrNull { it in text }?.let { slots["vehicleModel"] = it }
        if (request.scene == SceneType.DELIVERY && wants("orderNumber")) {
            extractOrderNumber(text)?.let { slots["orderNumber"] = it }
        } else if (wants("orderId")) {
            (
                orderIdRegex.find(text)?.groupValues?.getOrNull(1)
                    ?: orderIdTailRegex.find(text)?.groupValues?.getOrNull(1)
                )?.let { slots["orderId"] = it }
        }
        if (request.scene == SceneType.RIDE_HAILING && wants("destination")) {
            destinationRegex.findAll(text).lastOrNull()
                ?.groupValues
                ?.getOrNull(1)
                ?.let(::normalizeDestination)
                ?.let { slots["destination"] = it }
        }
        if (request.scene != SceneType.DELIVERY && wants("community")) {
            communityRegex.find(text)?.value?.let { slots["community"] = it }
        }
        // A caller answering a question answers it in as few words as they can. Asked which pickup
        // point, drivers say 南门; asked which listing, agents say 万科城. Neither carries the verb or
        // preposition every pattern above keys on, so the answer read as nothing at all and the
        // assistant asked again. Only consulted while the question is still open: the slot is
        // wanted and nothing else in the turn filled it.
        BARE_ANSWER_SLOTS.forEach { slot ->
            if (!wants(slot) || slots[slot] != null) return@forEach
            bareAnswer(text)?.takeIf(::isPlausibleLocation)?.let { answer ->
                slots[slot] = answer
                if (slot == "pickupLocation" && slots["location"] == null) slots["location"] = answer
            }
        }
        if (wants("insuranceType")) insuranceTypes.firstOrNull { it in text }?.let { slots["insuranceType"] = it }
        if (request.scene != SceneType.DELIVERY && wants("propertyType")) {
            propertyTypes.firstOrNull { it in text }?.let { slots["propertyType"] = it }
        }
        if (request.scene != SceneType.DELIVERY && wants("serviceType")) {
            serviceTypes.firstOrNull { it in text }?.let { slots["serviceType"] = it }
        }
        if (wants("issueType")) {
            val issueType = if (request.scene == SceneType.DELIVERY) {
                extractDeliveryIssueType(text)
            } else {
                issueTypes.firstOrNull { it in text }
            }
            issueType?.let { slots["issueType"] = it }
        }
        if (wants("estimatedTime")) {
            extractEstimatedTime(text, rejected)?.let { slots["estimatedTime"] = it }
        }
        if (wants("contactPurpose")) {
            contactPurposes.firstOrNull { it in text }?.let { slots["contactPurpose"] = it }
        }

        if (
            request.scene != SceneType.DELIVERY &&
            ("purpose" in request.expectedSlots || request.expectedSlots.isEmpty()) &&
            request.existingSlots["purpose"].isNullOrBlank()
        ) {
            text.take(MAX_PURPOSE_LENGTH).let { slots["purpose"] = it }
        }

        val overwritten = slots.keys.filterTo(linkedSetOf()) { key ->
            request.existingSlots[key]?.let { existing -> existing != slots[key] } == true
        }
        return SlotExtractionResult(
            slots = slots,
            overwrittenSlots = overwritten,
            rejectedEvidence = rejected.distinct(),
        )
    }

    private fun selectCorrectedMatch(
        text: String,
        regex: Regex,
        rejected: MutableList<String>,
        evidenceId: String,
    ): String? {
        val candidates = regex.findAll(text).toList()
        candidates.filter { isNegatedAt(text, it.range.first) }.forEach { rejected += "slot:$evidenceId:negated" }
        return candidates.lastOrNull { !isNegatedAt(text, it.range.first) }?.value?.trim()
    }

    private fun selectCapturedValue(
        text: String,
        regex: Regex,
        rejected: MutableList<String>,
        evidenceId: String,
    ): String? {
        val candidates = regex.findAll(text).toList()
        candidates.filter { isNegatedAt(text, it.range.first) }.forEach { rejected += "slot:$evidenceId:negated" }
        return candidates.lastOrNull { !isNegatedAt(text, it.range.first) }
            ?.groupValues?.getOrNull(1)
            ?.let(::normalizeLocation)
    }

    private fun isNegatedAt(text: String, startIndex: Int): Boolean {
        val canonical = text.replace('，', ',').replace('。', '.').replace('！', '!').replace('？', '?')
        return isNegatedEvidence(canonical, startIndex)
    }

    private fun isPlausibleIdentity(value: String): Boolean =
        value.length in 2..12 && value !in invalidIdentityCandidates && roleOnlyPattern.matches(value).not()

    /**
     * Reads a whole short utterance as the answer to the question just asked.
     *
     * Returns null for anything long enough to be a sentence: a caller saying more than a name is
     * saying something the patterns above are meant to parse, and guessing over them would be worse
     * than asking again.
     */
    private fun bareAnswer(text: String): String? {
        val stripped = text.replace(bareAnswerNoiseRegex, "").trim()
        if (stripped.length !in 2..BARE_ANSWER_MAX_CHARS) return null
        if (stripped.any { it in "，。；！？,.;!?" }) return null
        return stripped
    }

    private fun isPlausibleLocation(value: String): Boolean =
        isPlausibleLocation(value, null)

    private fun isPlausibleLocation(value: String, scene: SceneType?): Boolean =
        value.length in 1..72 &&
            !(scene == SceneType.INSURANCE_FINANCE && value.length > MAX_INSURANCE_LOCATION_LENGTH) &&
            !(scene == SceneType.RIDE_HAILING && (
                value.length > MAX_RIDE_LOCATION_LENGTH ||
                    rideLocationClauseRegex.containsMatchIn(value)
                )) &&
            value !in invalidLocationCandidates &&
            locationMarkers.any(value::contains) &&
            isKnownLocationEnding(value)

    private fun extractLocation(
        text: String,
        rejected: MutableList<String>,
        scene: SceneType?,
    ): String? {
        val preferredText = preferCorrectedLocationText(text, rejected)
        val corrected = "slot:location:corrected" in rejected
        val directionalGates = directionalGateRegex.findAll(preferredText).map { it.value }.distinct().toList()
        // Two gates in one turn usually means the caller has not settled on one, and asking beats
        // guessing. Not when they have just corrected themselves: a correction is how that doubt
        // gets resolved, and the second mention is typically the reason for it --
        // 不行，还是放前台吧，门口有狗 named 前台 and explained why not 门口, and the conflict rule
        // threw the whole slot away, so the assistant repeated the spot that had been ruled out.
        if (directionalGates.size > 1 && !corrected) {
            rejected += "slot:location:conflict"
            return null
        }
        val candidates = mutableListOf<LocationCandidate>()
        locationCandidateRegexes.forEach { pattern ->
            val regex = pattern.first
            val priority = pattern.second
            regex.findAll(preferredText).forEach { match ->
                if (isNegatedAt(preferredText, match.range.first)) {
                    rejected += "slot:location:negated"
                } else {
                    match.groupValues.getOrNull(1)
                        ?.let(::normalizeLocation)
                        ?.takeIf(String::isNotBlank)
                        ?.let { value ->
                            if (locationQuestionTargetRegex.containsMatchIn(value)) {
                                rejected += "slot:location:question_target"
                            } else {
                                candidates += LocationCandidate(
                                    value = enrichRelativeLocation(preferredText, value, match.range.first),
                                    priority = priority,
                                    startIndex = match.range.first,
                                )
                            }
                        }
                }
            }
        }

        val distinctCandidates = candidates.distinctBy { it.value to it.priority }
        val plausibleCandidates = distinctCandidates.filter { isPlausibleLocation(it.value, scene) }
        if (plausibleCandidates.size < distinctCandidates.size) rejected += "slot:location:implausible"
        val highestPriority = plausibleCandidates.maxOfOrNull(LocationCandidate::priority) ?: return null
        val plausible = plausibleCandidates
            .filter { it.priority == highestPriority }
            .sortedBy(LocationCandidate::startIndex)
            .map(LocationCandidate::value)
            .distinct()
        if (plausible.isEmpty()) return null

        val maximal = plausible.filter { candidate ->
            plausible.none { other -> other != candidate && other.contains(candidate) }
        }.distinct()
        if (maximal.size > 1) {
            // Same reasoning as the gate check above: after a correction the caller has already
            // chosen, and what follows tends to be why. 不行，还是放前台吧，门口有狗 leaves 前台 and
            // 门口 tied here, and refusing both let the assistant keep confirming 门口 -- the one
            // place the caller had ruled out. Take what they said first after correcting; the
            // explanation trails it.
            if (!corrected) {
                rejected += "slot:location:conflict"
                return null
            }
            return maximal.first()
        }
        return maximal.singleOrNull()
    }

    private fun preferCorrectedLocationText(text: String, rejected: MutableList<String>): String {
        val correction = correctionMarkerRegex.findAll(text).lastOrNull() ?: return text
        val before = text.substring(0, correction.range.first)
        val after = text.substring(correction.range.last + 1)
            .replace(leadingCorrectionWordsRegex, "")
            .trim()
        if (after.isBlank()) return text

        rejected += "slot:location:corrected"
        if (!after.startsWith("靠")) return after
        val previous = locationCandidateRegexes
            .asSequence()
            .flatMap { (regex, _) -> regex.findAll(before).asSequence() }
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.let(::normalizeLocation) }
            .lastOrNull(::isPlausibleLocation)
            ?: return after
        val root = previous.replace(previousLocationEndingRegex, "").trim()
        return if (root.length >= 2) root + after else after
    }

    private fun enrichRelativeLocation(text: String, value: String, startIndex: Int): String {
        if (!relativeLocationRegex.containsMatchIn(value)) {
            return prependSiteContext(text, value, startIndex)
        }
        val earlierText = text.substring(0, startIndex)
        val previous = currentLocationRegex.findAll(earlierText)
            .mapNotNull { it.groupValues.getOrNull(1)?.let(::normalizeLocation) }
            .lastOrNull()
            ?.replace(trailingDeicticLocationRegex, "")
            ?.trim()
        if (previous != null && namedLocationRootRegex.containsMatchIn(previous)) {
            return previous + value
        }
        return prependSiteContext(text, value, startIndex)
    }

    private fun prependSiteContext(text: String, value: String, startIndex: Int): String {
        val prefix = text.substring(0, startIndex)
        val siteContext = siteContextRegex.findAll(prefix).lastOrNull()
            ?.groupValues?.getOrNull(1)
            ?.trim()
            ?: return value
        return if (value.startsWith(siteContext)) value else siteContext + value
    }

    private fun isKnownLocationEnding(value: String): Boolean {
        if ('的' !in value) return locationEndings.any(value::endsWith)
        return locationEndings.any(value::endsWith)
    }

    private fun extractOrderNumber(text: String): String? {
        val rawValue = orderNumberRegex.find(text)?.groupValues?.getOrNull(1)
            ?: orderTailRegex.find(text)?.groupValues?.getOrNull(1)
            ?: return null
        val compact = rawValue.replace(" ", "")
        return normalizeSpokenDigits(compact).uppercase()
    }

    private fun normalizeSpokenDigits(value: String): String =
        value.replace(" ", "").map { spokenDigitMap[it] ?: it }.joinToString("")

    private fun extractDeliveryIssueType(text: String): String? {
        deliveryIssuePatterns.forEach { (canonicalValue, patterns) ->
            val matched = patterns.any { pattern ->
                pattern.findAll(text).any { match -> !isNegatedAt(text, match.range.first) }
            }
            if (matched) return canonicalValue
        }
        return null
    }

    private fun extractEstimatedTime(text: String, rejected: MutableList<String>): String? {
        val candidates = estimatedTimeRegex.findAll(text)
            .filter { match -> isEstimatedTimeCandidate(text, match.range.first) }
            .toList()
        if (candidates.isEmpty()) return null
        val correction = correctionMarkerRegex.findAll(text).lastOrNull()
        if (correction != null) {
            val corrected = candidates.lastOrNull { it.range.first > correction.range.last }
            if (corrected != null) {
                rejected += "slot:estimatedTime:corrected"
                return corrected.groupValues.getOrNull(1)
            }
        }
        return candidates.first().groupValues.getOrNull(1)
    }

    private fun isEstimatedTimeCandidate(text: String, startIndex: Int): Boolean {
        val prefix = text.substring(maxOf(0, startIndex - 12), startIndex)
        return !estimatedTimeNoiseRegex.containsMatchIn(prefix) &&
            !estimatedTimeSavingRegex.containsMatchIn(prefix)
    }

    private companion object {
        const val MAX_PURPOSE_LENGTH = 80
        const val MAX_INSURANCE_LOCATION_LENGTH = 15
        const val MAX_RIDE_LOCATION_LENGTH = 48
        val phoneRegex = Regex("(?<!\\d)(?:1[3-9]\\d{9}|0\\d{2,3}[- ]?\\d{7,8})(?!\\d)")
        val timeRegex = Regex(
            "(?:今天|明天|后天|大后天|本周|下周|周[一二三四五六日天]|星期[一二三四五六日天])" +
                "(?:上午|下午|晚上|中午)?(?:(?:\\d{1,2}|[一二三四五六七八九十两]{1,3})[点时](?:半|\\d{1,2}分)?)?" +
                "|(?:上午|下午|晚上|中午)(?:\\d{1,2}|[一二三四五六七八九十两]{1,3})[点时](?:半|\\d{1,2}分)?" +
                "|(?:\\d{1,2}|[一二三四五六七八九十两]{1,3})[点时](?:半|\\d{1,2}分)",
        )
        val departmentIdentityRegex = Regex(
            "(?:我是|我叫|这边是)([^，。,.]{2,16}?(?:部|部门|公司|团队|单位))([^，。,.]{1,8})",
        )
        val organizationRegex = Regex(
            "(?:来自|我们是|这边是|我是)([\\u4e00-\\u9fa5A-Za-z0-9]{2,24}(?:公司|部门|团队|单位|物业|医院|学校|银行))",
        )
        val identityRegex = Regex("(?:我叫|我是)([^，。,.]{2,16})")
        val destinationLocationRegex = Regex(
            "(?:放在|放到|送到|留在|搁在|搁到|绕到|地址是|您填的是|东西在|" +
                "上车地点(?:是|在)|上车点(?:是|在)|取件地点(?:是|在))([^。；;！？!?\\n]{1,72})",
        )
        val locationQuestionRegex = Regex(
            "(?:(?:您|你)(?:实际)?是在|(?:您|你)具体是在|到底在)([^。；;！？!?\\n]{1,48})",
        )
        val currentLocationRegex = Regex(
            "(?:我现在在|现在在|我已经到达|已经到达|我到达|我已经到|已经到|我到|我在|就在|等在|停在|到达|到)([^。；;！？!?\\n]{1,72})",
        )
        val addressBuildingRegex = Regex("((?:[一二三四五六七八九十两0-9]+号楼)(?:[一二三四五六七八九十两0-9]+单元)?)")
        val explicitLandmarkRegex = Regex(
            "([\\u4e00-\\u9fa5A-Za-z0-9]{0,36}(?:访客通道|校车通道|取餐柜|取餐架|保安亭|值班室|" +
                "卸货区|闸机|连廊|消防门|茶水间|电梯厅|等候区|停车带|会议室|访客口|住院部|" +
                "体育馆|门诊|入口|出口|电梯口|门口|前台|驿站|快递柜|保安室|保安处|楼下|大厅|" +
                "号楼|单元|置物架|外卖架|路口|门把手|鞋柜|桌子|地上|[东南西北]侧?门)" +
                "(?:旁边|里面|外面|门外|顶层|底层|第?[一二三四五六七八九十两0-9]+层|上)?)",
        )
        val locationCandidateRegexes = listOf(
            locationQuestionRegex to 4,
            destinationLocationRegex to 3,
            currentLocationRegex to 2,
            addressBuildingRegex to 2,
            explicitLandmarkRegex to 1,
        )
        val locationQuestionSuffixRegex = Regex("(?:是否可以|可以吗|行吗|方便吗|好不好|吗)(?:呢|呀|啊|吧)?$")
        val trailingLocationPunctuationRegex = Regex("[\\s？?！!；;：:，、。,.]+$")
        val trailingLocationActionRegex = Regex(
            "(?:，|,)?(?:但是|不过|可是|订单定位|麻烦|正在这里|等您|等你|取一下|取下|拿一下|出来拿|" +
                "没有写|没写|您从|您到|您带|您是|您到底|您下来|下来取|下来就|接我|接一下|" +
                "才能|估计|再过去).*$",
        )
        // Punctuation has to be strippable between the particles, or one comma stops the whole
        // chain: 您的快递到了，我在楼下 captured 了，我在楼下 and kept 我在楼下 as the place.
        val leadingLocationWordsRegex = Regex("^(?:[，、。,.\u0020]|我已经|我|已经|现在|先|去|到|在|是|了)+")
        // 放门口 is as common as 放在门口 and was not trimmed, so the bare 到 in
        // currentLocationRegex matched 包裹到了 and carried the rest of the sentence into the
        // slot: 您的包裹到了，现在放门口可以吗 filled location with 了现在放门口.
        val embeddedLocationActionRegex = Regex("^.*(?:放在|放到|放|送到|留在|搁在|搁到|绕到)")
        val embeddedSpeakerLocationRegex = Regex("^.*(?:我在|我到|我已经到|我现在在|现在在)")
        val trailingLocationParticlesRegex = Regex("(?:了|啦|呢|啊|呀|吧)+$")
        // A refusal corrects a placement as surely as 说错了 does, and is how people actually do
        // it: 不行，还是放前台吧，门口有狗 kept 门口 as the location and the assistant confirmed
        // the one spot the caller had just ruled out.
        val correctionMarkerRegex = Regex(
            "(?:啊不对|不对|我说错了|说错了|改成|改到|实际是|应该是|不行|不用放|别放|还是放|换成|换到)",
        )
        val leadingCorrectionWordsRegex = Regex("^[，、。,.\\s]*(?:不是|是|应该是|改成|改到)?[，、。,.\\s]*")
        val previousLocationEndingRegex = Regex(
            "(?:[东南西北]侧?门|入口|出口|门口|楼下|前台|大厅|停车带|访客通道|电梯口|这边)$",
        )
        val trailingDeicticLocationRegex = Regex("(?:的)?这边$")
        val relativeLocationRegex = Regex("^(?:靠|[东南西北]|地下|地上)")
        val namedLocationRootRegex = Regex("(?:馆|院|校|中学|大学|中心|广场|园区|小区|公寓|大厦|楼)$")
        val siteContextRegex = Regex(
            "([\\u4e00-\\u9fa5A-Za-z0-9]{0,24}(?:学校|中学|大学|园区|小区|医院|广场|中心|公寓)[东南西北]侧)",
        )
        val directionalGateRegex = Regex("[东南西北]侧?(?:门|入口)")
        val locationQuestionTargetRegex = Regex(
            "(?:哪(?:个|一)?|什么|哪里|哪儿).{0,12}(?:单元|门|入口|楼|位置|地方)?",
        )
        val rideArrivalLocationRegex = Regex("(?:已经到达|我已经到达|我到达|已经到|我在|车在)([^。；;！？!?\\n]{2,48})")
        val pickupCodeRegex = Regex("(?:取件码|提货码|柜码)[是为：: ]*([A-Za-z0-9]{3,10})", RegexOption.IGNORE_CASE)
        val licensePlateRegex = Regex("[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼][A-Z][A-Z0-9]{5}", RegexOption.IGNORE_CASE)
        val licensePlateTailRegex = Regex("(?:车牌|车号).{0,4}(?:尾号|最后四位)[是为：: ]*([0-9一二三四五六七八九零两]{4,8})")
        val phoneTailRegex = Regex("(?:手机号|手机号码|电话).{0,4}(?:尾号|最后四位)[是为：: ]*([0-9一二三四五六七八九零两]{4,8})")
        val orderIdRegex = Regex("(?:订单号|工单号|单号)[是为：: ]*([A-Za-z0-9-]{5,32})", RegexOption.IGNORE_CASE)
        val orderIdTailRegex = Regex("(?:订单)?尾号[是为：: ]*([0-9]{4,8})")
        val destinationRegex = Regex("(?:目的地|终点)(?!是否|是不是|对不对|正确)[是为：: ]+([^，。；;！？!?\\n]{2,32})")
        val orderNumberRegex = Regex("(?:订单号|单号)[是为：: ]*([A-Za-z0-9一二三四五六七八九零两-]{4,32})", RegexOption.IGNORE_CASE)
        val orderTailRegex = Regex("尾号[是为：: ]*([0-9一二三四五六七八九零]{4,8})")
        val estimatedTimeRegex = Regex(
            "([0-9一二三四五六七八九十百两几]+(?:来|多)?(?:分钟|小时)(?:左右)?" +
                "|一刻钟|一会儿)",
        )
        // 万科城 and 天悦府 are as much a listing as 阳光花园 is. The four original suffixes covered
        // none of the names estate agents actually say on the phone.
        val communityRegex = Regex(
            "[\\u4e00-\\u9fa5A-Za-z0-9]{2,16}(?:小区|公寓|花园|家园|城|苑|府|湾|庭|阁|轩|墅|新村|广场|大厦)",
        )
        /**
         * Slots a caller may answer with a bare noun, when they were asked for exactly that.
         *
         * Kept short on purpose. Treating any brief utterance as an answer would turn 好的 and 没有
         * into place names; these two are the questions the follow-up states actually ask.
         */
        val BARE_ANSWER_SLOTS = listOf("pickupLocation", "community")

        /** Particles and fillers a spoken answer trails, leaving the answer itself behind. */
        val bareAnswerNoiseRegex =
            Regex("^(?:是|在|就|那|这|嗯|哦|对|我在|我到|地址是)+|(?:的|了|吧|呢|啊|呀|这边|那边|这里|那里)+$")

        const val BARE_ANSWER_MAX_CHARS = 8

        val roleOnlyPattern = Regex("(?:送外卖的|送快递的|司机|客服|中介|业务员)")
        val rideLocationClauseRegex = Regex("(?:请确认|是否|没有看到|没看到|看不到|找不到|正在这里|等您|等你)")
        val estimatedTimeNoiseRegex = Regex("(?:等|等了|等候|等待)$")
        /**
         * Prefixes after which a duration is not an estimate of when the caller arrives.
         *
         * 节省/省下/减少 name time that will not be spent, so the number is not an ETA. 晚, 晚了,
         * 延误 and 耽搁 were in this list too, and they are the opposite case: 配送可能会晚几分钟
         * is the delay itself, which is the one number the owner needs from a late courier, and it
         * was thrown away. Recorded on device as delivery_013 on 2026-08-08 -- recognition was
         * word-perfect and the slot came back empty.
         */
        val estimatedTimeSavingRegex = Regex("(?:节省|省下|减少|少用|提前)$")
        val invalidLocationCandidates = setOf("以上", "一下", "这里", "那里", "这边", "那边")
        val invalidIdentityCandidates = setOf("送外卖的", "送快递的", "司机", "客服", "保险公司的")
        val knownOrganizations = listOf(
            "顺丰",
            "京东",
            "美团",
            "饿了么",
            "滴滴",
            "高德",
            "中国移动",
            "中国联通",
            "中国电信",
            "中国平安",
            "中国人寿",
            "太平洋保险",
            "招商银行",
            "工商银行",
            "建设银行",
            "农业银行",
            "中国银行",
        )
        val knownPlatforms = listOf("顺丰", "京东", "美团", "饿了么", "滴滴", "高德", "淘宝", "天猫", "拼多多")
        val insuranceTypes = listOf("车险", "寿险", "医疗险", "意外险", "重疾险", "财产险", "基金", "理财产品", "年金保险", "分红保险")
        val propertyTypes = listOf("新房", "二手房", "公寓", "商铺", "写字楼", "租房")
        val serviceTypes = listOf("退款", "售后", "回访", "理赔", "续保", "维修", "投诉")
        val issueTypes = listOf("缺货", "破损", "未到账", "异常消费", "订单取消", "退货", "换货")
        val contactPurposes = listOf(
            "理赔核实",
            "补充材料",
            "理赔进度",
            "保单到期",
            "续保",
            "还款提醒",
            "交易提醒",
            "保险产品介绍",
            "理财产品介绍",
            "产品介绍",
        )
        val vehicleColors = listOf("白色", "黑色", "灰色", "银色", "红色", "蓝色", "绿色", "黄色")
        val vehicleModels = listOf("轿车", "SUV", "商务车", "面包车")
        val locationMarkers = listOf(
            "门口", "前台", "驿站", "快递柜", "保安室", "保安处", "北门", "南门", "东门", "西门", "上车点", "上客区",
            "楼下", "大厅", "号楼", "单元", "置物架", "外卖架", "路口", "门把手", "鞋柜", "桌子", "地上",
            "北侧门", "南侧门", "东侧门", "西侧门", "入口", "出口", "电梯口", "访客通道", "校车通道",
            "取餐柜", "取餐架", "保安亭", "值班室", "卸货区", "闸机", "连廊", "消防门", "茶水间",
            "电梯厅", "等候区", "停车带", "会议室", "访客口", "住院部", "门诊", "体育馆",
        )
        val locationEndings = listOf(
            "门口", "前台", "驿站", "快递柜", "保安室", "保安处", "北门", "南门", "东门", "西门", "上车点", "上客区",
            "楼下", "大厅", "号楼", "单元", "置物架", "外卖架", "路口", "门把手", "鞋柜", "桌子", "地上",
            "北侧门", "南侧门", "东侧门", "西侧门", "入口", "出口", "电梯口", "访客通道", "校车通道",
            "取餐柜", "取餐架", "保安亭", "值班室", "卸货区", "闸机", "连廊", "消防门", "茶水间",
            "电梯厅", "等候区", "停车带", "会议室", "访客口", "住院部", "门诊", "体育馆",
            "旁边", "里面", "外面", "门外", "顶层", "底层", "层", "柜子上", "桌子上", "矮柜上",
        )
        val spokenDigitMap = mapOf(
            '零' to '0', '一' to '1', '二' to '2', '两' to '2', '三' to '3', '四' to '4',
            '五' to '5', '六' to '6', '七' to '7', '八' to '8', '九' to '9',
        )
        val deliveryIssuePatterns = linkedMapOf(
            "缺货" to listOf(Regex("缺货"), Regex("(?:商家|店家|商品|饮料).{0,8}卖完")),
            "破损" to listOf(
                Regex("破损"),
                Regex("(?:袋子|包装|外袋).{0,8}(?:破|坏|裂|湿)"),
                Regex("(?:汤|粥|饮料|可乐|汤盒).{0,12}(?:渗|漏|洒|倒)"),
            ),
            "延迟" to listOf(
                Regex("延迟"),
                // Weather delays a courier as surely as traffic does, and 外面雨太大了配送可能会晚
                // 几分钟 named none of the conditions this list knew.
                Regex("(?:堵车|堵得|路上|路滑|管制|施工车|下雨|雨太大|下雪|大风|天气).{0,24}(?:晚|送到|分钟|一刻钟)"),
                Regex("(?:还得|大概|再有|估计|差不多).{0,8}(?:[0-9一二三四五六七八九十百两几]+(?:来)?分钟|一刻钟)"),
                // The delay stated directly, with no reason offered.
                Regex("(?:会|要|得|可能会)?晚[0-9一二三四五六七八九十百两几]+(?:来|多)?(?:分钟|小时)"),
                Regex("晚一会儿"),
            ),
            "餐具缺失" to listOf(Regex("(?:忘记|少|没放|漏).{0,6}(?:餐具|筷子|吸管)")),
        )

        data class LocationCandidate(
            val value: String,
            val priority: Int,
            val startIndex: Int,
        )
    }

    private fun normalizeLocation(rawLocation: String): String {
        val withoutPunctuation = rawLocation.trim()
            .replace("上课区", "上客区")
            .replace(trailingLocationPunctuationRegex, "")
        val cleaned = withoutPunctuation
            .replace(trailingLocationActionRegex, "")
            .replace(embeddedLocationActionRegex, "")
            // 不对，导航把我带到南门了，我在南门 corrects to 南门了我在南门: the speaker
            // restating where they are is the end of the value, not part of it.
            .replace(embeddedSpeakerLocationRegex, "")
            .replace(leadingLocationWordsRegex, "")
            .replace(locationQuestionSuffixRegex, "")
            .replace(trailingLocationParticlesRegex, "")
            .replace(trailingDeicticLocationRegex, "")
            .replace(Regex("(置物架)上$"), "$1")
            .trim()
            .replace(trailingLocationPunctuationRegex, "")
            .replace("，", "")
            .replace(",", "")
        return trimAfterLastLocationEnding(cleaned)
    }

    private fun normalizeDestination(rawDestination: String): String? {
        val cleaned = rawDestination
            .trim()
            .replace(Regex("(?:请确认|请核对|确认一下|核对一下).*$"), "")
            .replace(trailingLocationPunctuationRegex, "")
            .trim()
        return cleaned.takeIf { it.length in 2..24 && it !in setOf("是否正确", "是不是正确", "对不对") }
    }

    private fun trimAfterLastLocationEnding(value: String): String {
        var lastEndIndex = -1
        locationEndings.forEach { ending ->
            val startIndex = value.lastIndexOf(ending)
            if (startIndex >= 0) {
                lastEndIndex = maxOf(lastEndIndex, startIndex + ending.length)
            }
        }
        if (lastEndIndex <= 0 || lastEndIndex >= value.length) return value
        return value.substring(0, lastEndIndex)
    }
}
