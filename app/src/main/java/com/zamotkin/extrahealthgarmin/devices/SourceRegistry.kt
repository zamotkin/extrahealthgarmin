package com.zamotkin.extrahealthgarmin.devices

import com.zamotkin.extrahealthgarmin.devices.api.BpSourceType

/**
 * Resolves a source type id to its [BpSourceType] factory (task
 * tefal2garmin-app-redesign-v3-xlg.2). Deliberately a plain map - no
 * reflection, no plugin loading: adding a source type is one more entry in
 * the list handed to the constructor (see
 * [com.zamotkin.extrahealthgarmin.health.HealthSyncWiring.sourceRegistry]),
 * and the types themselves are plain-JVM objects, so the registry is
 * unit-testable with no Android at all.
 *
 * Never throws: an unknown id resolves to null and the caller decides what
 * that means.
 */
class SourceRegistry(registered: List<BpSourceType>) {

    private val byTypeId: Map<String, BpSourceType> = registered.associateBy { it.typeId }

    /** Every registered type, in registration order. */
    val types: List<BpSourceType> = byTypeId.values.toList()

    /** The type registered under [typeId], or null when no such type exists. */
    fun type(typeId: String): BpSourceType? = byTypeId[typeId]
}
