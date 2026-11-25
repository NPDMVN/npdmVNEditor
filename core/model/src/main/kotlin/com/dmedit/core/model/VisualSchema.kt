package com.dmedit.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VisualSchema(
    val schemaVersion: Int = 1,
    val id: String,
    val title: String,
    val description: String = "",
    val catalogRef: String? = null,
    val presetRef: String? = null,
    val sections: List<SchemaSection> = emptyList()
)

@Serializable
data class SchemaSection(
    val id: String,
    val title: String,
    val description: String = "",
    val icon: String = "tune",
    val fields: List<SchemaField> = emptyList()
)

@Serializable
sealed class SchemaField {
    abstract val id: String
    abstract val label: String
    abstract val jsonPath: String
    abstract val description: String

    @Serializable
    @SerialName("number_stepper")
    data class NumberStepper(
        override val id: String,
        override val label: String,
        override val jsonPath: String,
        override val description: String = "",
        val min: Long = 0L,
        val max: Long = 999_999_999L,
        val safeMax: Long = 99_999_999L,
        val step: Long = 100L,
        val quickAddPresets: List<Long> = listOf(1_000L, 10_000L, 100_000L)
    ) : SchemaField()

    @Serializable
    @SerialName("boolean_switch")
    data class BooleanSwitch(
        override val id: String,
        override val label: String,
        override val jsonPath: String,
        override val description: String = "",
        val trueIntValue: Int? = null,
        val falseIntValue: Int? = null
    ) : SchemaField()

    @Serializable
    @SerialName("string_input")
    data class StringInput(
        override val id: String,
        override val label: String,
        override val jsonPath: String,
        override val description: String = "",
        val readOnly: Boolean = false
    ) : SchemaField()

    @Serializable
    @SerialName("enum_select")
    data class EnumSelect(
        override val id: String,
        override val label: String,
        override val jsonPath: String,
        override val description: String = "",
        val options: List<EnumOption> = emptyList()
    ) : SchemaField()

    @Serializable
    @SerialName("inventory_grid")
    data class InventoryGrid(
        override val id: String,
        override val label: String,
        override val jsonPath: String,
        override val description: String = "",
        val valueType: InventoryValueType = InventoryValueType.INT_COUNT,
        val min: Long = 0L,
        val max: Long = 999_999L,
        val batchSetPresets: List<Long> = listOf(99L, 999L, 9_999L),
        val unlockedStringValue: String = "Researched",
        val lockedStringValue: String = "Got",
        val categoryFilter: String? = null,
        val presetKey: String? = null,
        val legacyPresetKey: String? = null,
        val presetButtonLabel: String = "+ Nạp full tài nguyên"
    ) : SchemaField()

    /**
     * One-tap sanitized full-resource preset merge action for arrays or complex objects
     * (e.g., `itemUnlock`, `mythicWeapons`, `jewelryData`, `weapons`, `chips`).
     */
    @Serializable
    @SerialName("preset_action")
    data class PresetAction(
        override val id: String,
        override val label: String,
        override val jsonPath: String,
        override val description: String = "",
        val presetKey: String,
        val legacyPresetKey: String? = null,
        val dedupeKey: String? = null,
        val overwriteExisting: Boolean = true,
        val buttonLabel: String = "Nạp Preset"
    ) : SchemaField()

    /**
     * Object mapping GroupName -> Array<{ keyField, valueField }>,
     * matching Soul Knight's `skinLock` (Value == 1 unlocked) and `heroSkillUnlock` (Value == true unlocked).
     */
    @Serializable
    @SerialName("nested_pair_list")
    data class NestedPairList(
        override val id: String,
        override val label: String,
        override val jsonPath: String,
        override val description: String = "",
        val keyField: String = "Key",
        val valueField: String = "Value",
        val isIntUnlock: Boolean = true,
        val unlockedIntValue: Int = 1,
        val lockedIntValue: Int = 0,
        val itemPrefix: String = "Skin #"
    ) : SchemaField()
}

@Serializable
enum class InventoryValueType {
    @SerialName("int_count") INT_COUNT,
    @SerialName("bool_unlock") BOOL_UNLOCK,
    @SerialName("string_unlock") STRING_UNLOCK,
    @SerialName("auto_enum_unlock") AUTO_ENUM_UNLOCK,
    @SerialName("string_list_unlock") STRING_LIST_UNLOCK
}

@Serializable
data class EnumOption(
    val value: String,
    val label: String,
    val isNumeric: Boolean = false
)

@Serializable
data class ItemCatalog(
    val id: String,
    val items: Map<String, CatalogItem> = emptyMap()
)

@Serializable
data class CatalogItem(
    val nameVi: String,
    val nameEn: String = nameVi,
    val category: String = "general",
    val description: String = ""
)
