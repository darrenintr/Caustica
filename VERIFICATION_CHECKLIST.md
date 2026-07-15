# 验证清单

## 代码修改验证

### ✅ 降噪器阈值调整
```bash
# 验证 kSmoothDielectric 已更新为 0.08
grep "kSmoothDielectric = 0.08" shaders/display/denoise_ffx/prepare_nrd_inputs.comp
grep "kSmoothDielectric = 0.08" shaders/display/denoise_ffx/nrd_compose_beauty.comp
```

### ✅ 水体消光系数降低
```bash
# 验证水的透明度参数已更新
grep "WATER_DENSITY = 0.006" shaders/world/world.rgen
grep "WATER_ABSORB_FLOOR = vec3(0.0015, 0.001, 0.0005)" shaders/world/world.rgen
```

### ✅ 夜晚光照调整
```bash
# 验证月光和夜空环境光已降低
grep "MOON_DISC_RADIANCE = vec3(1.2, 1.3, 1.5)" shaders/world/world.rmiss
grep "NIGHT_ZENITH.*0.002, 0.004, 0.012" shaders/world/world.rmiss
```

### ✅ 火焰覆盖层混合模式
```bash
# 验证使用正确的混合模式
grep "BlendFunction.TRANSLUCENT" src/main/java/dev/comfyfluffy/caustica/rt/RtUiOverlay.java
```

### ✅ 玻璃透明支持
```bash
# 验证玻璃检测逻辑
grep "blockName.contains(\"glass\")" src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtTerrain.java
```

---

## 游戏内测试

### 场景 1: 水体透明度
- [ ] 打开世界，找到水体
- [ ] 水面应该清澈，能看到水下的方块
- [ ] 水面反射应该清晰，不模糊
- [ ] 关闭水波后（water-waves = false）效果更明显

### 场景 2: 冰地形
- [ ] 前往冰原生物群系
- [ ] 冰块应该透明，能看穿
- [ ] 冰面不应该有磨砂玻璃效果
- [ ] 冰面的反射应该清晰

### 场景 3: 玻璃方块
- [ ] 放置普通玻璃方块
- [ ] 玻璃应该透明，能看到后面的景物
- [ ] 放置染色玻璃，应该带有颜色透明度
- [ ] 玻璃板也应该正常透明

### 场景 4: 夜晚场景
- [ ] 等待到夜晚或使用 /time set night
- [ ] 水面不应该过亮
- [ ] 月光强度应该适中
- [ ] 整体夜晚氛围更暗，更真实

### 场景 5: 火焰效果
- [ ] 点燃火焰或使用打火石
- [ ] 走进火焰或被火攻击
- [ ] 屏幕上的火焰覆盖层应该正常显示
- [ ] 不应该只看到半透明的方块

### 场景 6: 非完整方块
- [ ] 放置石质台阶、楼梯
- [ ] 放置木质栅栏、篱笆
- [ ] 这些方块的光照应该与完整方块一致
- [ ] 边缘不应该有异常模糊

### 场景 7: 运动测试
- [ ] 快速移动摄像机
- [ ] 运动模糊应该减少
- [ ] 画面应该更清晰

---

## 配置验证

### caustica.toml 配置
```toml
[denoise]
    mode = "NRD"
    sigma-depth = 0.02
    sigma-normal = 0.05
    sigma-color = 0.2
    temporal-max = 0.75
    ffx-temporal-weight-max = 0.6

[framegen]
    mode = "OFF"  # 或 "AUTO" (需要 FSR SDK)
    
[water]
    water-waves = false  # 测试时关闭以查看清晰效果
```

---

## 性能检查

- [ ] 帧率没有明显下降
- [ ] 没有新的崩溃或错误
- [ ] 日志中没有 "failed" 或 "error" 相关的降噪器消息

---

## 回退方案

如果出现问题，可以恢复原始值：

### 降噪器阈值
```glsl
const float kSmoothDielectric = 0.12;  // 原始值
```

### 水体参数
```glsl
const float WATER_DENSITY = 0.012;
const vec3 WATER_ABSORB_FLOOR = vec3(0.003, 0.002, 0.001);
```

### 降噪器配置
```toml
sigma-depth = 0.05
sigma-normal = 0.1
sigma-color = 0.5
temporal-max = 0.95
ffx-temporal-weight-max = 0.82
```
