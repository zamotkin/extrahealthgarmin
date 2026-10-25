package com.zamotkin.extrahealthgarmin.devices

import android.content.Context
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.devices.api.BleBpSourceType
import com.zamotkin.extrahealthgarmin.devices.api.BpSource
import com.zamotkin.extrahealthgarmin.devices.api.BpSourceType
import com.zamotkin.extrahealthgarmin.devices.api.DiscoveredBleDevice
import com.zamotkin.extrahealthgarmin.devices.api.SourceCapabilities
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadRequest
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadResult
import com.zamotkin.extrahealthgarmin.devices.api.SyncMode
import com.zamotkin.extrahealthgarmin.devices.api.UserAttribution
import com.zamotkin.extrahealthgarmin.devices.healthconnect.HealthConnectBpSourceType
import com.zamotkin.extrahealthgarmin.devices.healthconnect.HcBpSource
import com.zamotkin.extrahealthgarmin.devices.omron.OmronBleSourceType
import com.zamotkin.extrahealthgarmin.devices.omron.OmronBleSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SourceRegistry] tests (task tefal2garmin-app-redesign-v3-xlg.2): the two
 * shipped types resolve with their capabilities, and a fake THIRD type
 * registers and resolves with no core changes - the extensibility proof of
 * epic requirement R2. Plain JUnit, no Android: the registry and the real
 * source types are plain-JVM objects (the Context is a create-time
 * parameter nobody calls here).
 */
class SourceRegistryTest {

    private fun registry(vararg extra: BpSourceType) =
        SourceRegistry(listOf(OmronBleSourceType, HealthConnectBpSourceType) + extra)

    @Test
    fun `resolves the omron-ble type with slot capabilities`() {
        val type = registry().type(OmronBleSource.TYPE_ID)

        assertSame(OmronBleSourceType, type)
        assertEquals("omron-ble", type?.typeId)
        assertEquals(R.string.bp_source_type_omron_ble, type?.displayNameRes)
        assertEquals(
            SourceCapabilities(
                userAttribution = UserAttribution.SLOTS,
                supportedSyncModes = setOf(SyncMode.HISTORY, SyncMode.CURRENT),
                requiresPairingKey = true,
                pairingKeyBytes = 16
            ),
            type?.capabilities
        )
    }

    @Test
    fun `resolves the health-connect type with current-only capabilities`() {
        val type = registry().type(HcBpSource.TYPE_ID)

        assertSame(HealthConnectBpSourceType, type)
        assertEquals("health-connect", type?.typeId)
        assertEquals(R.string.bp_source_type_health_connect, type?.displayNameRes)
        assertEquals(
            SourceCapabilities(
                userAttribution = UserAttribution.NONE,
                supportedSyncModes = setOf(SyncMode.CURRENT),
                requiresPairingKey = false
            ),
            type?.capabilities
        )
    }

    @Test
    fun `unknown type id resolves to null`() {
        assertNull(registry().type("no-such-type"))
    }

    @Test
    fun `types lists every registered type in registration order`() {
        val types = registry().types

        assertEquals(
            listOf(OmronBleSourceType, HealthConnectBpSourceType),
            types
        )
    }

    /**
     * The SC2 proof: a brand-new source type plugs into the registry with
     * nothing but a registration entry - no core file changes. The fake
     * implements the same interface the standard 0x1810 driver (task xlg.9)
     * will.
     */
    @Test
    fun `a fake third type registers and resolves with no core changes`() {
        val registry = registry(FakeBleSourceType)

        assertSame(FakeBleSourceType, registry.type("fake-ble"))
        assertEquals(3, registry.types.size)
        assertTrue(registry.types.contains(FakeBleSourceType))
    }

    /** A stand-in future BLE driver: same seam, different type id. */
    private object FakeBleSourceType : BleBpSourceType {
        override val typeId: String = "fake-ble"
        override val displayNameRes: Int = 0
        override val capabilities: SourceCapabilities = SourceCapabilities(
            userAttribution = UserAttribution.NONE,
            supportedSyncModes = setOf(SyncMode.CURRENT),
            requiresPairingKey = false
        )

        override fun createFromDevice(context: Context, device: DiscoveredBleDevice): BpSource =
            FakeBpSource
    }

    private object FakeBpSource : BpSource {
        override val sourceTypeId: String = FakeBleSourceType.typeId
        override val displayNameRes: Int = FakeBleSourceType.displayNameRes
        override val capabilities: SourceCapabilities = FakeBleSourceType.capabilities

        override suspend fun read(request: SourceReadRequest): SourceReadResult =
            SourceReadResult.Failed("fake source never reads")
    }
}
