# 动态手持光源诊断指南

## 📅 测试时间：编译完成后

---

## 🔍 **诊断步骤**

### **步骤 1：启动游戏并监控日志**

**终端 1 - 启动游戏**：
```fish
prismlauncher
```

**终端 2 - 实时监控日志**：
```bash
tail -f "/run/media/darren/1TB/Windows data/Apps/PCL/.minecraft/versions/26.2-Fabric_0.19.3/logs/latest.log" | grep -i "dynamiclight"
```

---

### **步骤 2：进入游戏测试**

1. **加载存档**
2. **手持火把** 在主手
3. **等待 5 秒**
4. **查看日志输出**

---

## 📊 **可能的日志场景**

### **场景 A：完全没有日志输出**

```
(没有任何 [DynamicLight] 日志)
```

**原因**：
- `dynamic-lights.enabled = false` 配置被禁用
- 或者实体列表为空
- 或者玩家不在实体列表中

**修复方向**：
1. 检查配置文件
2. 修改实体获取方式（从 `level.entitiesForRendering()` 改为直接获取玩家）

---

### **场景 B：检测到玩家但光照等级为 0**

```
[INFO] [DynamicLight] Player YourName mainHand=torch (light=0) offHand=empty (light=0)
```

**原因**：
- `Block.byItem(stack.getItem())` 返回了错误的方块
- 或者 `state.getLightEmission()` 在这个 Minecraft 版本返回 0

**修复方向**：
```java
// 硬编码火把的光照等级
if (stack.getItem().toString().contains("torch")) {
    return 14; // 火把光照等级
}
if (stack.getItem().toString().contains("lantern")) {
    return 15; // 灯笼光照等级
}
```

---

### **场景 C：检测到光照但没有 "Detected held light"**

```
[INFO] [DynamicLight] Player YourName mainHand=torch (light=14) offHand=empty (light=0)
(但没有 "Detected held light" 日志)
```

**原因**：
- `heldLight > 0` 条件失败
- 或者 `intensityScale` 导致光照被缩放到 0

**修复方向**：
检查 `intensity-scale` 配置值

---

### **场景 D：检测到光源但不发光**

```
[INFO] [DynamicLight] Player YourName mainHand=torch (light=14) offHand=empty (light=0)
[INFO] [DynamicLight] Detected held light: entity=123 type=Player light=14 pos=(100, 64, 200)
[INFO] Dynamic lights frame 120: 1 lights detected
[INFO] ReSTIR DI Frame 60: 15 lights (14 static + 1 dynamic)
```

**原因**：
- 光源被检测并上传到 GPU
- 但 shader 没有正确渲染
- 可能是坐标转换问题（terrain origin rebasing）

**修复方向**：
1. 检查 `DynamicLight.rebased()` 是否正确
2. 验证动态光源的 `dynamic` 标志位
3. 检查 shader 中的 `RESTIR_DYNAMIC_M_SCALE`

---

## 🎯 **成功的标志**

完整的成功日志应该是：

```
[INFO] [DynamicLight] Player YourName mainHand=minecraft:torch (light=14) offHand=empty (light=0)
[INFO] [DynamicLight] Item minecraft:torch has light level 14
[INFO] [DynamicLight] Detected held light: entity=123 type=Player light=14 pos=(100, 64, 200)
[INFO] Dynamic lights frame 120: 1 lights detected, config: held=true dropped=true entities=true
[INFO] ReSTIR DI Frame 60: 15 lights (14 static + 1 dynamic), buffer=0x12345678, bufferChanged=true
```

**并且**：
- 手持火把时周围**确实被照亮**

---

## 🔧 **快速修复方案（如果场景 B）**

如果日志显示 `light=0`，我会添加硬编码的物品光照映射：

```java
private static final Map<String, Integer> ITEM_LIGHT_LEVELS = Map.of(
    "torch", 14,
    "soul_torch", 10,
    "lantern", 15,
    "soul_lantern", 10,
    "sea_lantern", 15,
    "glowstone", 15,
    "shroomlight", 15,
    "jack_o_lantern", 15,
    "redstone_torch", 7,
    "glow_berries", 14
);

private int getItemLightLevel(ItemStack stack) {
    if (stack.isEmpty()) return 0;
    
    String itemName = stack.getItem().toString().toLowerCase();
    
    // 先尝试硬编码映射
    for (Map.Entry<String, Integer> entry : ITEM_LIGHT_LEVELS.entrySet()) {
        if (itemName.contains(entry.getKey())) {
            return entry.getValue();
        }
    }
    
    // 回退到方块光照检测
    Block block = Block.byItem(stack.getItem());
    if (block != null && block != Blocks.AIR) {
        return block.defaultBlockState().getLightEmission();
    }
    
    return 0;
}
```

---

## 📝 **测试后报告**

完成测试后，请复制粘贴：

1. **所有 [DynamicLight] 开头的日志**
2. **手持火把是否发光**（是/否）
3. **游戏版本确认**（真的是 1.26.2 吗？还是 1.21.2？）

这样我可以快速定位问题并修复！
