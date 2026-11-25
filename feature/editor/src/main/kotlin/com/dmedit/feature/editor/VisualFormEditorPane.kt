package com.dmedit.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dmedit.core.model.InventoryValueType
import com.dmedit.core.model.ItemCatalog
import com.dmedit.core.model.SchemaField
import com.dmedit.core.model.SchemaSection
import com.dmedit.core.model.VisualSchema
import com.dmedit.core.nativebridge.JsonTreeReader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

@Composable
fun VisualFormEditorPane(
    jsonRoot: JsonElement?,
    schema: VisualSchema?,
    catalog: ItemCatalog?,
    isModernFormat: Boolean,
    availablePresetKeys: Set<String>,
    onPatchField: (jsonPath: String, newValue: JsonElement) -> Unit,
    onPatchMapEntry: (mapPath: String, key: String, newValue: JsonElement) -> Unit,
    onBatchMap: (mapPath: String, newValue: JsonElement) -> Unit,
    onBatchNestedPairs: (mapPath: String, groupKey: String?, valueField: String, newValue: JsonElement) -> Unit,
    onApplyPresetMerge: (
        targetPath: String,
        presetKey: String,
        legacyPresetKey: String?,
        dedupeKey: String?,
        overwriteExisting: Boolean,
        label: String
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    if (jsonRoot == null) {
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Nội dung hiện tại chưa phải JSON hợp lệ — hãy kiểm tra lỗi cú pháp ở chế độ Raw.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        return
    }

    val firstMatchingSection = remember(jsonRoot, schema) {
        schema?.sections?.firstOrNull { sec ->
            sec.fields.any { f ->
                f is SchemaField.PresetAction || JsonTreeReader.read(jsonRoot, f.jsonPath) != null
            }
        } ?: schema?.sections?.firstOrNull()
    }

    var selectedSectionId by remember(schema?.id, firstMatchingSection?.id) {
        mutableStateOf(firstMatchingSection?.id ?: "__auto_all__")
    }

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (schema != null) {
                items(schema.sections, key = { it.id }) { section ->
                    FilterChip(
                        selected = section.id == selectedSectionId,
                        onClick = { selectedSectionId = section.id },
                        label = { Text(section.title) }
                    )
                }
            }
            item {
                FilterChip(
                    selected = selectedSectionId == "__auto_all__",
                    onClick = { selectedSectionId = "__auto_all__" },
                    label = { Text("Tất cả trường (Auto)") }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (selectedSectionId == "__auto_all__" || schema == null) {
            AutoGeneratedJsonForm(
                jsonRoot = jsonRoot,
                catalog = catalog,
                onPatchField = onPatchField,
                onPatchMapEntry = onPatchMapEntry,
                onBatchMap = onBatchMap,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val activeSection = schema.sections.firstOrNull { it.id == selectedSectionId } ?: schema.sections.first()
            SchemaSectionFieldsList(
                jsonRoot = jsonRoot,
                activeSection = activeSection,
                catalog = catalog,
                isModernFormat = isModernFormat,
                availablePresetKeys = availablePresetKeys,
                onPatchField = onPatchField,
                onPatchMapEntry = onPatchMapEntry,
                onBatchMap = onBatchMap,
                onBatchNestedPairs = onBatchNestedPairs,
                onApplyPresetMerge = onApplyPresetMerge
            )
        }
    }
}

@Composable
private fun SchemaSectionFieldsList(
    jsonRoot: JsonElement,
    activeSection: SchemaSection,
    catalog: ItemCatalog?,
    isModernFormat: Boolean,
    availablePresetKeys: Set<String>,
    onPatchField: (String, JsonElement) -> Unit,
    onPatchMapEntry: (String, String, JsonElement) -> Unit,
    onBatchMap: (String, JsonElement) -> Unit,
    onBatchNestedPairs: (String, String?, String, JsonElement) -> Unit,
    onApplyPresetMerge: (String, String, String?, String?, Boolean, String) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(activeSection.fields, key = { it.id }) { field ->
            when (field) {
                is SchemaField.NumberStepper -> NumberStepperCard(
                    field = field,
                    existsInDoc = JsonTreeReader.read(jsonRoot, field.jsonPath) != null,
                    current = JsonTreeReader.asLong(JsonTreeReader.read(jsonRoot, field.jsonPath)) ?: 0L,
                    onValueChange = { v -> onPatchField(field.jsonPath, JsonPrimitive(v)) }
                )
                is SchemaField.BooleanSwitch -> BooleanSwitchCard(
                    field = field,
                    checked = JsonTreeReader.asBoolean(
                        JsonTreeReader.read(jsonRoot, field.jsonPath),
                        field.trueIntValue
                    ) ?: false,
                    onCheckedChange = { checked ->
                        val value = if (field.trueIntValue != null && field.falseIntValue != null) {
                            JsonPrimitive(if (checked) field.trueIntValue!! else field.falseIntValue!!)
                        } else {
                            JsonPrimitive(checked)
                        }
                        onPatchField(field.jsonPath, value)
                    }
                )
                is SchemaField.StringInput -> StringInputCard(
                    field = field,
                    value = (JsonTreeReader.read(jsonRoot, field.jsonPath) as? JsonPrimitive)?.content.orEmpty(),
                    onValueChange = { onPatchField(field.jsonPath, JsonPrimitive(it)) }
                )
                is SchemaField.EnumSelect -> EnumSelectCard(
                    field = field,
                    currentRaw = (JsonTreeReader.read(jsonRoot, field.jsonPath) as? JsonPrimitive)?.content.orEmpty(),
                    onSelect = { opt ->
                        val prim = if (opt.isNumeric) {
                            opt.value.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(opt.value)
                        } else {
                            JsonPrimitive(opt.value)
                        }
                        onPatchField(field.jsonPath, prim)
                    }
                )
                is SchemaField.InventoryGrid -> InventoryGridCard(
                    field = field,
                    entries = JsonTreeReader.readObjectMap(jsonRoot, field.jsonPath),
                    catalog = catalog,
                    isModernFormat = isModernFormat,
                    hasPreset = field.presetKey != null && field.presetKey in availablePresetKeys,
                    onUpdateEntry = { k, v -> onPatchMapEntry(field.jsonPath, k, v) },
                    onBatchSet = { batchJson -> onBatchMap(field.jsonPath, batchJson) },
                    onApplyPreset = {
                        val pk = field.presetKey ?: return@InventoryGridCard
                        val overwrite = field.valueType == InventoryValueType.AUTO_ENUM_UNLOCK ||
                            field.valueType == InventoryValueType.STRING_UNLOCK ||
                            field.valueType == InventoryValueType.BOOL_UNLOCK ||
                            pk == "room_objects_max"
                        onApplyPresetMerge(
                            field.jsonPath,
                            pk,
                            field.legacyPresetKey,
                            null,
                            overwrite,
                            field.presetButtonLabel
                        )
                    }
                )
                is SchemaField.PresetAction -> PresetActionCard(
                    field = field,
                    currentNode = JsonTreeReader.read(jsonRoot, field.jsonPath),
                    enabled = field.presetKey in availablePresetKeys,
                    onApply = {
                        onApplyPresetMerge(
                            field.jsonPath,
                            field.presetKey,
                            field.legacyPresetKey,
                            field.dedupeKey,
                            field.overwriteExisting,
                            field.buttonLabel
                        )
                    }
                )
                is SchemaField.NestedPairList -> NestedPairListCard(
                    field = field,
                    groupsMap = JsonTreeReader.readObjectMap(jsonRoot, field.jsonPath),
                    catalog = catalog,
                    onToggleSingle = { groupKey, index, unlocked ->
                        val escapedGroup = if (groupKey.contains('.') || groupKey.contains(' ')) {
                            "[\"$groupKey\"]"
                        } else {
                            ".$groupKey"
                        }
                        val fullPath = "${field.jsonPath}$escapedGroup[$index].${field.valueField}"
                        val newVal = if (field.isIntUnlock) {
                            JsonPrimitive(if (unlocked) field.unlockedIntValue else field.lockedIntValue)
                        } else {
                            JsonPrimitive(unlocked)
                        }
                        onPatchField(fullPath, newVal)
                    },
                    onUnlockGroup = { groupKey ->
                        val newVal = if (field.isIntUnlock) JsonPrimitive(field.unlockedIntValue) else JsonPrimitive(true)
                        onBatchNestedPairs(field.jsonPath, groupKey, field.valueField, newVal)
                    },
                    onUnlockAll = {
                        val newVal = if (field.isIntUnlock) JsonPrimitive(field.unlockedIntValue) else JsonPrimitive(true)
                        onBatchNestedPairs(field.jsonPath, null, field.valueField, newVal)
                    }
                )
            }
        }
    }
}

@Composable
private fun NestedPairListCard(
    field: SchemaField.NestedPairList,
    groupsMap: Map<String, JsonElement>,
    catalog: ItemCatalog?,
    onToggleSingle: (groupKey: String, index: Int, unlocked: Boolean) -> Unit,
    onUnlockGroup: (groupKey: String) -> Unit,
    onUnlockAll: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var expandedGroups by remember { mutableStateOf(setOf<String>()) }

    val parsedGroups = remember(groupsMap, field) {
        groupsMap.mapNotNull { (heroKey, el) ->
            val arr = el as? JsonArray ?: return@mapNotNull null
            val items = arr.mapIndexedNotNull { idx, itemEl ->
                val obj = itemEl as? JsonObject ?: return@mapIndexedNotNull null
                val keyText = (obj[field.keyField] as? JsonPrimitive)?.content
                    ?: (obj["Name"] as? JsonPrimitive)?.content
                    ?: idx.toString()
                val rawVal = obj[field.valueField] as? JsonPrimitive
                val isUnlocked = if (field.isIntUnlock) {
                    rawVal?.longOrNull == field.unlockedIntValue.toLong()
                } else {
                    rawVal?.booleanOrNull == true
                }
                Triple(idx, keyText, isUnlocked to (rawVal?.content ?: ""))
            }
            heroKey to items
        }
    }

    val totalCount = parsedGroups.sumOf { it.second.size }
    val unlockedCount = parsedGroups.sumOf { (_, items) -> items.count { it.third.first } }

    val filteredGroups = remember(parsedGroups, query, catalog) {
        parsedGroups.filter { (heroKey, _) ->
            val cat = catalog?.items?.get(heroKey)
            query.isBlank() ||
                heroKey.contains(query, ignoreCase = true) ||
                (cat?.nameVi?.contains(query, ignoreCase = true) == true) ||
                (cat?.nameEn?.contains(query, ignoreCase = true) == true)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${field.label} (${parsedGroups.size} tướng • Đã mở $unlockedCount/$totalCount)",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (field.description.isNotBlank()) {
                        Text(
                            text = field.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Button(
                    onClick = onUnlockAll,
                    enabled = totalCount > 0 && unlockedCount < totalCount
                ) {
                    Icon(Icons.Default.LockOpen, contentDescription = null)
                    Text(" Mở khóa tất cả")
                }
            }

            if (totalCount == 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Không tìm thấy trường '${field.jsonPath}' trong tệp save hiện tại.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Tìm tướng (Knight, Rogue, Paladin, Hiệp sĩ...)...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            filteredGroups.forEach { (heroKey, items) ->
                val heroUnlocked = items.count { it.third.first }
                val catItem = catalog?.items?.get(heroKey)
                val isExpanded = heroKey in expandedGroups || query.isNotBlank()

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(catItem?.nameVi ?: heroKey, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "$heroKey • Đã mở $heroUnlocked/${items.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (heroUnlocked == items.size) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    if (heroUnlocked < items.size) {
                        AssistChip(
                            onClick = { onUnlockGroup(heroKey) },
                            label = { Text("Mở hết") }
                        )
                    }
                    OutlinedButton(onClick = {
                        expandedGroups = if (heroKey in expandedGroups) {
                            expandedGroups - heroKey
                        } else {
                            expandedGroups + heroKey
                        }
                    }) {
                        Text(if (isExpanded) "Thu gọn" else "Chi tiết")
                    }
                }

                if (isExpanded) {
                    Column(modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 6.dp)) {
                        items.forEach { (idx, keyName, statusPair) ->
                            val (unlocked, rawStr) = statusPair
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${field.itemPrefix}$keyName (Value: $rawStr)",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = unlocked,
                                    onCheckedChange = { checked -> onToggleSingle(heroKey, idx, checked) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NumberStepperCard(
    field: SchemaField.NumberStepper,
    existsInDoc: Boolean,
    current: Long,
    onValueChange: (Long) -> Unit
) {
    var text by remember(current) { mutableStateOf(current.toString()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = field.label + if (!existsInDoc) " (chưa có trong file — sửa sẽ tạo mới)" else "",
                style = MaterialTheme.typography.titleSmall
            )
            if (field.description.isNotBlank()) {
                Text(
                    field.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onValueChange((current - field.step).coerceIn(field.min, field.max)) }) {
                    Icon(Icons.Default.Remove, contentDescription = "Giảm")
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { raw ->
                        text = raw
                        raw.toLongOrNull()?.let { parsed ->
                            onValueChange(parsed.coerceIn(field.min, field.max))
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onValueChange((current + field.step).coerceIn(field.min, field.max)) }) {
                    Icon(Icons.Default.Add, contentDescription = "Tăng")
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                field.quickAddPresets.forEach { preset ->
                    AssistChip(
                        onClick = { onValueChange((current + preset).coerceIn(field.min, field.max)) },
                        label = { Text("+$preset") }
                    )
                }
                AssistChip(
                    onClick = { onValueChange(field.safeMax) },
                    label = { Text("Max an toàn (${field.safeMax})") }
                )
            }
        }
    }
}

@Composable
private fun BooleanSwitchCard(
    field: SchemaField.BooleanSwitch,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(field.label, style = MaterialTheme.typography.titleSmall)
                if (field.description.isNotBlank()) {
                    Text(
                        field.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun StringInputCard(
    field: SchemaField.StringInput,
    value: String,
    onValueChange: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(field.label, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                readOnly = field.readOnly,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun EnumSelectCard(
    field: SchemaField.EnumSelect,
    currentRaw: String,
    onSelect: (com.dmedit.core.model.EnumOption) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = field.options.firstOrNull { it.value == currentRaw }?.label ?: currentRaw

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(field.label, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedLabel.ifBlank { "Chọn giá trị..." })
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                field.options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text("${option.label} (${option.value})") },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetActionCard(
    field: SchemaField.PresetAction,
    currentNode: JsonElement?,
    enabled: Boolean,
    onApply: () -> Unit
) {
    val countText = when (currentNode) {
        is JsonObject -> "Hiện có: ${currentNode.size} mục"
        is JsonArray -> "Hiện có: ${currentNode.size} mục"
        null -> "Hiện chưa có trong tệp (nạp sẽ tạo tự động)"
        else -> "Đã có dữ liệu"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(field.label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = countText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (field.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = field.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onApply, enabled = enabled) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Text(" ${field.buttonLabel}")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InventoryGridCard(
    field: SchemaField.InventoryGrid,
    entries: Map<String, JsonElement>,
    catalog: ItemCatalog?,
    isModernFormat: Boolean,
    hasPreset: Boolean,
    onUpdateEntry: (String, JsonElement) -> Unit,
    onBatchSet: (JsonElement) -> Unit,
    onApplyPreset: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var visibleLimit by remember(field.id, query) { mutableStateOf(120) }

    val usesIntEnum = remember(entries, isModernFormat) {
        val sample = entries.values.firstOrNull() as? JsonPrimitive
        if (sample != null) {
            !sample.isString && sample.longOrNull != null
        } else {
            isModernFormat
        }
    }

    val filtered = remember(entries, query, catalog) {
        entries.entries.filter { (key, _) ->
            val cat = catalog?.items?.get(key)
            query.isBlank() ||
                key.contains(query, ignoreCase = true) ||
                (cat?.nameVi?.contains(query, ignoreCase = true) == true) ||
                (cat?.nameEn?.contains(query, ignoreCase = true) == true)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("${field.label} (${entries.size} mục)", style = MaterialTheme.typography.titleSmall)

            if (hasPreset) {
                Spacer(modifier = Modifier.height(6.dp))
                Button(onClick = onApplyPreset) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Text(" ${field.presetButtonLabel}")
                }
            }

            if (entries.isEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Không có mục nào tại '${field.jsonPath}' trong tệp save hiện tại.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Tìm theo tên hoặc mã...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(6.dp))

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (field.valueType) {
                    InventoryValueType.BOOL_UNLOCK -> {
                        Button(onClick = { onBatchSet(JsonPrimitive(true)) }) {
                            Icon(Icons.Default.LockOpen, contentDescription = null)
                            Text(" Mở khóa tất cả")
                        }
                    }
                    InventoryValueType.STRING_UNLOCK -> {
                        Button(onClick = { onBatchSet(JsonPrimitive(field.unlockedStringValue)) }) {
                            Icon(Icons.Default.LockOpen, contentDescription = null)
                            Text(" Mở khóa tất cả (${field.unlockedStringValue})")
                        }
                    }
                    InventoryValueType.AUTO_ENUM_UNLOCK -> {
                        Button(onClick = {
                            val target = if (usesIntEnum) JsonPrimitive(2) else JsonPrimitive(field.unlockedStringValue)
                            onBatchSet(target)
                        }) {
                            Icon(Icons.Default.LockOpen, contentDescription = null)
                            Text(if (usesIntEnum) " Mở khóa tất cả (2)" else " Mở khóa tất cả (${field.unlockedStringValue})")
                        }
                    }
                    else -> {
                        field.batchSetPresets.forEach { preset ->
                            AssistChip(
                                onClick = { onBatchSet(JsonPrimitive(preset)) },
                                label = { Text("Đặt tất cả = $preset") }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()

            filtered.take(visibleLimit).forEach { (key, value) ->
                val catItem = catalog?.items?.get(key)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(catItem?.nameVi ?: key, style = MaterialTheme.typography.bodyMedium)
                        if (catItem != null) {
                            Text(
                                text = "$key • ${catItem.category}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    when (field.valueType) {
                        InventoryValueType.BOOL_UNLOCK -> {
                            val unlocked = JsonTreeReader.asBoolean(value) ?: false
                            Switch(
                                checked = unlocked,
                                onCheckedChange = { onUpdateEntry(key, JsonPrimitive(it)) }
                            )
                        }
                        InventoryValueType.STRING_UNLOCK -> {
                            val curStr = (value as? JsonPrimitive)?.content.orEmpty()
                            val unlocked = curStr == field.unlockedStringValue
                            Switch(
                                checked = unlocked,
                                onCheckedChange = { checked ->
                                    onUpdateEntry(
                                        key,
                                        JsonPrimitive(if (checked) field.unlockedStringValue else field.lockedStringValue)
                                    )
                                }
                            )
                        }
                        InventoryValueType.AUTO_ENUM_UNLOCK -> {
                            val prim = value as? JsonPrimitive
                            val unlocked = if (prim != null && !prim.isString && prim.longOrNull != null) {
                                prim.longOrNull == 2L
                            } else {
                                prim?.content == field.unlockedStringValue
                            }
                            Switch(
                                checked = unlocked,
                                onCheckedChange = { checked ->
                                    val newVal = if (usesIntEnum) {
                                        JsonPrimitive(if (checked) 2 else 1)
                                    } else {
                                        JsonPrimitive(if (checked) field.unlockedStringValue else field.lockedStringValue)
                                    }
                                    onUpdateEntry(key, newVal)
                                }
                            )
                        }
                        else -> {
                            val num = JsonTreeReader.asLong(value) ?: 0L
                            var itemText by remember(num) { mutableStateOf(num.toString()) }
                            OutlinedTextField(
                                value = itemText,
                                onValueChange = { raw ->
                                    itemText = raw
                                    raw.toLongOrNull()?.let { v ->
                                        onUpdateEntry(key, JsonPrimitive(v.coerceIn(field.min, field.max)))
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(120.dp)
                            )
                        }
                    }
                }
                HorizontalDivider()
            }

            if (filtered.size > visibleLimit) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { visibleLimit += 150 },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Hiện thêm (+150) — đang hiện $visibleLimit/${filtered.size} mục")
                }
            }
        }
    }
}

@Composable
private fun AutoGeneratedJsonForm(
    jsonRoot: JsonElement,
    catalog: ItemCatalog?,
    onPatchField: (String, JsonElement) -> Unit,
    onPatchMapEntry: (String, String, JsonElement) -> Unit,
    onBatchMap: (String, JsonElement) -> Unit,
    modifier: Modifier = Modifier
) {
    val obj = jsonRoot as? JsonObject
    var filter by remember { mutableStateOf("") }
    var expandedObjectKeys by remember { mutableStateOf(setOf<String>()) }

    if (obj == null) {
        Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
            Text("JSON gốc là mảng/giá trị đơn — hãy chỉnh sửa ở chế độ Raw.")
        }
        return
    }

    val primitiveEntries = remember(obj, filter) {
        obj.entries.filter { (k, v) ->
            v is JsonPrimitive && (filter.isBlank() || k.contains(filter, ignoreCase = true))
        }
    }

    val nestedObjectEntries = remember(obj, filter) {
        obj.entries.filter { (k, v) ->
            v is JsonObject && (
                filter.isBlank() ||
                    k.contains(filter, ignoreCase = true) ||
                    v.keys.any { childKey -> childKey.contains(filter, ignoreCase = true) }
                )
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Tự động phân tích JSON: ${primitiveEntries.size} trường đơn • ${nestedObjectEntries.size} nhóm lồng nhau.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(10.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            singleLine = true,
            placeholder = { Text("Tìm khóa bất kỳ trong file...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(primitiveEntries, key = { "prim_${it.key}" }) { (key, element) ->
                val prim = element as JsonPrimitive
                val path = if (key.contains('.') || key.contains(' ')) "$[\"$key\"]" else "$.$key"
                val catItem = catalog?.items?.get(key)

                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(catItem?.nameVi ?: key, style = MaterialTheme.typography.bodyMedium)
                            if (catItem != null) {
                                Text(key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        val boolVal = prim.booleanOrNull
                        val longVal = prim.longOrNull
                        when {
                            !prim.isString && boolVal != null -> {
                                Switch(
                                    checked = boolVal,
                                    onCheckedChange = { onPatchField(path, JsonPrimitive(it)) }
                                )
                            }
                            !prim.isString && longVal != null -> {
                                var numText by remember(longVal) { mutableStateOf(longVal.toString()) }
                                OutlinedTextField(
                                    value = numText,
                                    onValueChange = { raw ->
                                        numText = raw
                                        raw.toLongOrNull()?.let { onPatchField(path, JsonPrimitive(it)) }
                                    },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(150.dp)
                                )
                            }
                            else -> {
                                var strText by remember(prim.content) { mutableStateOf(prim.content) }
                                OutlinedTextField(
                                    value = strText,
                                    onValueChange = {
                                        strText = it
                                        onPatchField(path, JsonPrimitive(it))
                                    },
                                    singleLine = true,
                                    modifier = Modifier.width(180.dp)
                                )
                            }
                        }
                    }
                }
            }

            items(nestedObjectEntries, key = { "obj_${it.key}" }) { (groupKey, groupEl) ->
                val childObj = groupEl as JsonObject
                val groupPath = if (groupKey.contains('.') || groupKey.contains(' ')) "$[\"$groupKey\"]" else "$.$groupKey"
                val isExpanded = groupKey in expandedObjectKeys || filter.isNotBlank()

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "$groupKey (${childObj.size} mục)",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(onClick = {
                                expandedObjectKeys = if (groupKey in expandedObjectKeys) {
                                    expandedObjectKeys - groupKey
                                } else {
                                    expandedObjectKeys + groupKey
                                }
                            }) {
                                Text(if (isExpanded) "Thu gọn" else "Mở rộng")
                            }
                        }

                        if (isExpanded) {
                            Spacer(modifier = Modifier.height(6.dp))
                            val matchingChildren = childObj.entries.filter { (ck, cv) ->
                                cv is JsonPrimitive && (filter.isBlank() || ck.contains(filter, ignoreCase = true) || groupKey.contains(filter, ignoreCase = true))
                            }
                            if (matchingChildren.isNotEmpty() && matchingChildren.all { (it.value as JsonPrimitive).longOrNull != null }) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    AssistChip(onClick = { onBatchMap(groupPath, JsonPrimitive(999)) }, label = { Text("Tất cả = 999") })
                                    AssistChip(onClick = { onBatchMap(groupPath, JsonPrimitive(9999)) }, label = { Text("Tất cả = 9999") })
                                }
                            } else if (matchingChildren.isNotEmpty() && matchingChildren.all { (it.value as JsonPrimitive).booleanOrNull != null }) {
                                AssistChip(onClick = { onBatchMap(groupPath, JsonPrimitive(true)) }, label = { Text("Bật tất cả") })
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            matchingChildren.take(80).forEach { (childKey, childVal) ->
                                val prim = childVal as JsonPrimitive
                                val catItem = catalog?.items?.get(childKey)
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(catItem?.nameVi ?: childKey, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    val b = prim.booleanOrNull
                                    val n = prim.longOrNull
                                    when {
                                        !prim.isString && b != null -> Switch(
                                            checked = b,
                                            onCheckedChange = { onPatchMapEntry(groupPath, childKey, JsonPrimitive(it)) }
                                        )
                                        !prim.isString && n != null -> {
                                            var t by remember(n) { mutableStateOf(n.toString()) }
                                            OutlinedTextField(
                                                value = t,
                                                onValueChange = { raw ->
                                                    t = raw
                                                    raw.toLongOrNull()?.let { onPatchMapEntry(groupPath, childKey, JsonPrimitive(it)) }
                                                },
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                modifier = Modifier.width(120.dp)
                                            )
                                        }
                                        else -> {
                                            var t by remember(prim.content) { mutableStateOf(prim.content) }
                                            OutlinedTextField(
                                                value = t,
                                                onValueChange = {
                                                    t = it
                                                    onPatchMapEntry(groupPath, childKey, JsonPrimitive(it))
                                                },
                                                singleLine = true,
                                                modifier = Modifier.width(150.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
