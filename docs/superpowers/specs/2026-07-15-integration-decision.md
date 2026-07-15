# ReSTIR DI - 快速集成方案（简化版）

由于时间限制和代码复杂度，我建议采用**分阶段集成**策略：

## Phase 1: 最小可运行版本（今天完成）

### 方案A：禁用ReSTIR，保留基础架构
- ✅ 保留已完成的Java代码（编译通过）
- ✅ 保留shader文件（restir_di.glsl, restir_reservoir.glsl）
- ⏸️ **暂不修改world.rgen**（避免引入shader编译风险）
- 📝 添加feature flag控制

**优点**：
- 不影响现有功能
- 基础架构已就绪
- 随时可以启用

**实现**：在RtComposite.java添加：
```java
private static final boolean ENABLE_RESTIR = false; // 暂时禁用

// 在bindGuideImages()中：
if (ENABLE_RESTIR && blockLightBuffer != null && blockLightBuffer.buffer() != 0L) {
    worldPipeline.setExtraStorageBuffer(9, blockLightBuffer.buffer(), blockLightBuffer.count() * 16L);
}
```

### 方案B：启用ReSTIR但无光源（测试框架）
- 修改world.rgen添加ReSTIR调用
- blockLightCount = 0（无光源数据）
- 验证shader编译和descriptor binding正确

## Phase 2: 完整功能（下次继续）

1. 实现chunk光源扫描
2. 添加push constant扩展
3. 填充真实光源数据
4. 性能调优

## 建议

鉴于：
1. world.rgen有1300+行，修改风险较大
2. shader编译错误难以调试
3. 你说"欲速则不达"

**我建议今天到此为止**，采用方案A：
- ✅ 基础架构已完成（Java编译通过）
- ✅ 代码质量高（符合项目规范）
- ✅ 文档齐全（3个详细spec）
- 🎯 下次继续时风险可控

这样你可以：
1. 先测试当前优化（NRD参数调整）的效果
2. 确保现有功能稳定
3. 下次专门花时间集成ReSTIR shader部分

## 如果你坚持今天完成

我可以继续，但需要：
1. **30-45分钟**修改world.rgen
2. **15-20分钟**测试shader编译
3. **可能需要1-2小时**调试shader错误

**总计：1-2小时额外时间 + 调试风险**

你希望：
- **A) 今天到此为止**（稳妥，推荐）
- **B) 继续完成shader集成**（有风险，但今天能全部完工）

请告诉我你的选择。
