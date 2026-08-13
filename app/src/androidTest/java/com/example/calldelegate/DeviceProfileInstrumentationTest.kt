package com.example.calldelegate

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.calldelegate.di.DebugTestEntryPoint
import com.example.calldelegate.domain.model.InferenceBackend
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceProfileInstrumentationTest {
    @Test fun detectsDeviceAndPublishesAppliedCpuPolicy() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dependencies = EntryPointAccessors.fromApplication(context, DebugTestEntryPoint::class.java)
        val profiles = dependencies.deviceProfileProvider()

        profiles.refresh()
        val profile = profiles.profile.value

        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("device_tier", profile.tier.name)
                putString("base_device_tier", profile.baseTier.name)
                putInt("total_ram_mb", profile.totalRamMb)
                putInt("nominal_ram_gb", profile.nominalRamGb)
                putInt("memory_class_mb", profile.memoryClassMb)
                putInt("cpu_core_count", profile.cpuCoreCount)
                putString("soc_family", profile.socFamily.name)
                putString("soc_model", profile.socModel)
                putString("primary_abi", profile.primaryAbi)
                putInt("current_pss_mb", profile.currentPssMb)
                putString("thermal_severity", profile.thermalSeverity.name)
                putString("low_memory", profile.lowMemory.toString())
                putInt("tts_threads", profile.policy.ttsThreadCount)
                putString("benchmark_state", profile.benchmark.state.name)
            },
        )

        assertThat(profile.totalRamMb).isGreaterThan(0)
        assertThat(profile.memoryClassMb).isGreaterThan(0)
        assertThat(profile.cpuCoreCount).isGreaterThan(0)
        assertThat(profile.currentPssMb).isGreaterThan(0)
        assertThat(profile.thermalSeverity.name).isNotEqualTo("UNKNOWN")
        assertThat(profile.arm64Supported).isTrue()
        assertThat(profile.policy.backend).isEqualTo(InferenceBackend.CPU)
        assertThat(profile.policy.ttsThreadCount).isIn(1..4)
    }
}
